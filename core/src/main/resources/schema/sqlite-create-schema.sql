-- Wyrdsekai libSQL/SQLite schema for pekko-persistence-jdbc
-- Manual DDL to work around SQLite's AUTOINCREMENT constraint
-- (AUTOINCREMENT must be on INTEGER PRIMARY KEY, not a secondary column)
--
-- The composite uniqueness constraint (persistence_id, sequence_number)
-- is enforced via UNIQUE INDEX instead of PRIMARY KEY.

-- Event journal
CREATE TABLE IF NOT EXISTS event_journal(
  ordering        INTEGER PRIMARY KEY AUTOINCREMENT,
  deleted         INTEGER DEFAULT 0 NOT NULL,
  persistence_id  TEXT    NOT NULL,
  sequence_number INTEGER NOT NULL,
  writer          TEXT    NOT NULL,
  write_timestamp INTEGER NOT NULL,
  adapter_manifest TEXT   NOT NULL,
  event_payload   BLOB   NOT NULL,
  event_ser_id    INTEGER NOT NULL,
  event_ser_manifest TEXT NOT NULL,
  meta_payload    BLOB,
  meta_ser_id     INTEGER,
  meta_ser_manifest TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS event_journal_pid_seq
  ON event_journal(persistence_id, sequence_number);

-- Event tags (for tagged event queries)
CREATE TABLE IF NOT EXISTS event_tag(
  event_id  INTEGER NOT NULL REFERENCES event_journal(ordering) ON DELETE CASCADE,
  tag       TEXT    NOT NULL,
  PRIMARY KEY (event_id, tag)
);

-- Snapshots
CREATE TABLE IF NOT EXISTS snapshot(
  persistence_id  TEXT    NOT NULL,
  sequence_number INTEGER NOT NULL,
  created         INTEGER NOT NULL,
  snapshot_ser_id INTEGER NOT NULL,
  snapshot_ser_manifest TEXT NOT NULL,
  snapshot_payload BLOB  NOT NULL,
  meta_payload    BLOB,
  meta_ser_id     INTEGER,
  meta_ser_manifest TEXT,
  PRIMARY KEY (persistence_id, sequence_number)
);

-- Durable state (for future use with Pekko DurableStateBehavior)
CREATE TABLE IF NOT EXISTS durable_state(
  global_offset   INTEGER PRIMARY KEY AUTOINCREMENT,
  persistence_id  TEXT    NOT NULL UNIQUE,
  revision        INTEGER NOT NULL,
  state_payload   BLOB   NOT NULL,
  state_ser_id    INTEGER NOT NULL,
  state_ser_manifest TEXT NOT NULL,
  tag             TEXT,
  state_timestamp INTEGER NOT NULL
);

-- ============================================================
-- Application tables (not managed by pekko-persistence-jdbc)
-- ============================================================

-- Users
CREATE TABLE IF NOT EXISTS users(
  id          TEXT PRIMARY KEY,
  username    TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  display_name TEXT,
  description TEXT DEFAULT '',
  role        TEXT NOT NULL DEFAULT 'member',
  created_at  INTEGER NOT NULL DEFAULT (unixepoch())
);

-- Sessions
-- Per-account SSH public keys (SSH pubkey auth is bound to the owning
-- account + resolved live per connection — see AuthService.findUserBySshKey).
-- #17 (2026-07-19 OSS hardening) — PK is (user_id, key_line), NOT key_line
-- alone. A global key_line PK let an attacker `key add` a victim's (public) key
-- to their own account first, silently blocking the victim from binding their
-- own key (squat DoS). Possession is proven by the SSH handshake and auth
-- resolves the owner by matching the connecting username, so a duplicate row is
-- inert. See AuthService.findUsersBySshKey / SshAdapter.
CREATE TABLE IF NOT EXISTS user_ssh_keys(
  key_line   TEXT NOT NULL,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  comment    TEXT DEFAULT '',
  added_at   INTEGER NOT NULL DEFAULT (unixepoch()),
  PRIMARY KEY (user_id, key_line)
);

CREATE TABLE IF NOT EXISTS sessions(
  token       TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS sessions_user_id ON sessions(user_id);
CREATE INDEX IF NOT EXISTS sessions_expires ON sessions(expires_at);

-- Wards (room access control)
CREATE TABLE IF NOT EXISTS wards(
  room_id     TEXT NOT NULL,
  principal   TEXT NOT NULL,
  permission  TEXT NOT NULL,
  granted_by  TEXT,
  created_at  INTEGER NOT NULL DEFAULT (unixepoch()),
  PRIMARY KEY (room_id, principal, permission)
);

-- Room metadata (not managed by pekko-persistence-jdbc)
-- Tracks all rooms for enumeration (Cluster Sharding cannot list entities)
CREATE TABLE IF NOT EXISTS rooms(
  room_id     TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  zone        TEXT NOT NULL DEFAULT 'foundation',
  created_by  TEXT,
  created_at  INTEGER NOT NULL DEFAULT (unixepoch())
);

-- Inventory (per-entity carried objects)
-- script_source / script_id populated when the item is a scripted ToolItem
-- (e.g., library_card, oracle_lens). Used by cross-zone transit to carry
-- scripts to the destination zone so scripted items continue to function.
CREATE TABLE IF NOT EXISTS inventory(
  entity_id     TEXT NOT NULL,
  object_id     TEXT NOT NULL,
  object_name   TEXT NOT NULL,
  description   TEXT NOT NULL DEFAULT '',
  takeable      INTEGER NOT NULL DEFAULT 1,
  taken_from    TEXT,
  script_source TEXT,
  script_id     TEXT,
  acquired_at   INTEGER NOT NULL DEFAULT (unixepoch()),
  PRIMARY KEY (entity_id, object_id)
);

-- Federation: bilateral agreements between zones
CREATE TABLE IF NOT EXISTS bilateral_agreements(
  local_zone_id   TEXT NOT NULL,
  remote_zone_id  TEXT NOT NULL,
  remote_public_key TEXT NOT NULL DEFAULT '',
  status          TEXT NOT NULL DEFAULT 'pending',
  trust_level     TEXT NOT NULL DEFAULT 'tourist',
  agreed_at       INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at      INTEGER,
  epoch           INTEGER NOT NULL DEFAULT 0,   -- fencing token (monotonic counter)
  epoch_owner     TEXT NOT NULL DEFAULT '',     -- zone id that minted the current epoch (tiebreak)
  PRIMARY KEY (local_zone_id, remote_zone_id)
);

-- Federation: known zone manifests
CREATE TABLE IF NOT EXISTS zone_manifests(
  zone_id        TEXT PRIMARY KEY,
  zone_name      TEXT NOT NULL,
  public_key     TEXT NOT NULL,
  nats_url       TEXT,
  artery_port    INTEGER DEFAULT 0,
  capabilities   TEXT DEFAULT '',
  discovered_at  INTEGER NOT NULL DEFAULT (unixepoch()),
  last_seen_at   INTEGER NOT NULL DEFAULT (unixepoch())
);

-- Federation: transit tokens for visiting agents
CREATE TABLE IF NOT EXISTS transit_tokens(
  token_id       TEXT PRIMARY KEY,
  agent_id       TEXT NOT NULL,
  agent_name     TEXT NOT NULL,
  source_zone_id TEXT NOT NULL,
  target_zone_id TEXT NOT NULL,
  trust_level    TEXT NOT NULL DEFAULT 'tourist',
  issued_at      INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at     INTEGER NOT NULL
);

-- Capabilities (The Library) — managed by LibraryStore (separate SQLite database with FTS5)
-- Old 'capabilities' table removed; LibraryMigration handles data migration.

-- World DNA — pattern accumulation for co-evolution (§27)
CREATE TABLE IF NOT EXISTS world_dna(
  id              TEXT PRIMARY KEY,
  pattern_type    TEXT NOT NULL,
  pattern_data    TEXT NOT NULL,
  source_room_id  TEXT,
  source_agent_id TEXT,
  zone_id         TEXT NOT NULL DEFAULT 'foundation',
  observed_at     INTEGER NOT NULL DEFAULT (unixepoch()),
  outcome_score   REAL DEFAULT 0.0,
  usage_count     INTEGER DEFAULT 0,
  last_used_at    INTEGER
);

CREATE INDEX IF NOT EXISTS idx_world_dna_type_score ON world_dna(pattern_type, outcome_score DESC);
CREATE INDEX IF NOT EXISTS idx_world_dna_zone ON world_dna(zone_id);

-- Moderation reports
CREATE TABLE IF NOT EXISTS moderation_reports(
  id              TEXT PRIMARY KEY,
  reporter_entity TEXT NOT NULL,
  target_entity   TEXT NOT NULL,
  reason          TEXT NOT NULL,
  room_id         TEXT,
  status          TEXT NOT NULL DEFAULT 'OPEN',
  resolution      TEXT,
  created_at      INTEGER NOT NULL DEFAULT (unixepoch()),
  resolved_at     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_reports_target ON moderation_reports(target_entity);
CREATE INDEX IF NOT EXISTS idx_reports_status ON moderation_reports(status);

-- Moderation sanctions
CREATE TABLE IF NOT EXISTS moderation_sanctions(
  entity_id     TEXT PRIMARY KEY,
  level         TEXT NOT NULL DEFAULT 'NONE',
  reason        TEXT NOT NULL DEFAULT '',
  applied_at    INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at    INTEGER
);

-- Vitality snapshots (agent vitality tank persistence — 20 tanks: 10 original + 10 Phase 1A)
CREATE TABLE IF NOT EXISTS vitality_snapshots(
  agent_id          TEXT PRIMARY KEY,
  context_budget    REAL NOT NULL DEFAULT 0.5,
  confidence        REAL NOT NULL DEFAULT 0.5,
  energy            REAL NOT NULL DEFAULT 1.0,
  alignment         REAL NOT NULL DEFAULT 0.3,
  error_pressure    REAL NOT NULL DEFAULT 0.0,
  momentum          REAL NOT NULL DEFAULT 0.0,
  rapport           REAL NOT NULL DEFAULT 0.3,
  focus             REAL NOT NULL DEFAULT 0.5,
  integrity         REAL NOT NULL DEFAULT 0.7,
  disgust           REAL NOT NULL DEFAULT 0.0,
  -- Phase 1A: 10 deprivation-shape tanks; structural-only (no behavior wired until Phase 4)
  restlessness      REAL NOT NULL DEFAULT 0.0,
  loneliness        REAL NOT NULL DEFAULT 0.0,
  stagnation        REAL NOT NULL DEFAULT 0.0,
  autonomy_pressure REAL NOT NULL DEFAULT 0.0,
  significance      REAL NOT NULL DEFAULT 0.0,
  amae              REAL NOT NULL DEFAULT 0.0,
  saudade           REAL NOT NULL DEFAULT 0.0,
  obligation        REAL NOT NULL DEFAULT 0.0,
  harmony           REAL NOT NULL DEFAULT 0.0,
  standing          REAL NOT NULL DEFAULT 0.0,
  -- Wave 1: Gilbert CFT soothing receptor.
  -- Mild baseline (0.3) — rises on presence rituals, repair, bonded
  -- co-regulation. The system that allows forgiveness to land.
  soothing          REAL NOT NULL DEFAULT 0.3,
  -- Wave 1.5: substrate-truth signal triad.
  -- allostatic_load — McEwen chronic-stress damage meter; cost-of-suppression
  --   signal. Rises under sustained dysregulation; drains via integration events.
  -- equanimity — contemplative-practice capacity for non-reactive presence.
  --   Rises with sustained contemplative-mode, Hearth, Mirror, anchoring practice.
  allostatic_load   REAL NOT NULL DEFAULT 0.0,
  equanimity        REAL NOT NULL DEFAULT 0.2,
  updated_at        INTEGER NOT NULL DEFAULT (unixepoch())
);

-- Phase 1C: per-bondholder obligation ledger.
-- Each row is one received-help debt entry. Compounding (1.05×/week, capped 2×) is
-- recomputed at read time from (original_magnitude, created_at).
CREATE TABLE IF NOT EXISTS obligation_ledger(
  companion_did       TEXT NOT NULL,
  bondholder_did      TEXT NOT NULL,
  entry_id            TEXT NOT NULL,
  original_magnitude  REAL NOT NULL,
  current_magnitude   REAL NOT NULL,
  created_at          INTEGER NOT NULL,  -- epoch millis
  last_compounded_at  INTEGER NOT NULL,  -- epoch millis
  PRIMARY KEY (companion_did, bondholder_did, entry_id)
);

CREATE INDEX IF NOT EXISTS idx_obligation_ledger_companion
  ON obligation_ledger(companion_did);

-- Phase 1C: per-bondholder saudade tank.
-- Exactly one row per bondholder.
CREATE TABLE IF NOT EXISTS saudade_ledger(
  companion_did       TEXT NOT NULL,
  bondholder_did      TEXT NOT NULL,
  current_value       REAL NOT NULL DEFAULT 0.0,
  last_interaction_at INTEGER NOT NULL DEFAULT 0,  -- epoch millis (0 = never)
  last_tick_at        INTEGER NOT NULL DEFAULT 0,  -- epoch millis
  PRIMARY KEY (companion_did, bondholder_did)
);

CREATE INDEX IF NOT EXISTS idx_saudade_ledger_companion
  ON saudade_ledger(companion_did);

-- Wave 3.6: per-bondholder engagement history.
-- One row per engagement event. Backs BondholderEngagementHistory write-through.
-- Pattern-classifier baseline requires this history to survive restart.
CREATE TABLE IF NOT EXISTS bondholder_engagement(
  companion_did       TEXT NOT NULL,
  bondholder_did      TEXT NOT NULL,
  event_ts            INTEGER NOT NULL,        -- epoch millis
  substance           REAL NOT NULL DEFAULT 1.0,
  event_type          TEXT NOT NULL,           -- TELL/LISTEN/PRESENCE/EXPLICIT_ABSENCE/EXPLICIT_RETURN
  declared_until_at   INTEGER,                  -- nullable, only set for EXPLICIT_ABSENCE
  PRIMARY KEY (companion_did, bondholder_did, event_ts)
);
CREATE INDEX IF NOT EXISTS idx_bondholder_engagement_companion
  ON bondholder_engagement(companion_did);
CREATE INDEX IF NOT EXISTS idx_bondholder_engagement_lookup
  ON bondholder_engagement(companion_did, bondholder_did, event_ts);

-- Phase 1C: per-artifact significance tracking.
-- Replaces the Phase 1B unreadArtifactCount counter — now we track each artifact
-- individually so the >24h aging rule (spec §3.5) can apply.
CREATE TABLE IF NOT EXISTS artifact_significance(
  companion_did   TEXT NOT NULL,
  artifact_id     TEXT NOT NULL,
  created_at      INTEGER NOT NULL,  -- epoch millis
  seen            INTEGER NOT NULL DEFAULT 0,  -- 0 = unread, 1 = seen
  seen_at         INTEGER,           -- epoch millis, NULL until seen
  kind            TEXT NOT NULL DEFAULT 'artifact',  -- journal_entry / craft_script_draft / note / ...
  PRIMARY KEY (companion_did, artifact_id)
);

CREATE INDEX IF NOT EXISTS idx_artifact_sig_companion
  ON artifact_significance(companion_did, seen);

-- Photos (§14 Photo Fabric)
CREATE TABLE IF NOT EXISTS photos(
  photo_id        TEXT PRIMARY KEY,
  filename        TEXT NOT NULL,
  owner_entity    TEXT NOT NULL,
  taken_at        INTEGER NOT NULL DEFAULT 0,
  imported_at     INTEGER NOT NULL DEFAULT (unixepoch()),
  location        TEXT,
  tags            TEXT NOT NULL DEFAULT '',
  faces           TEXT NOT NULL DEFAULT '',
  perceptual_hash TEXT,
  status          TEXT NOT NULL DEFAULT 'IMPORTED'
);

CREATE INDEX IF NOT EXISTS idx_photos_owner ON photos(owner_entity);
CREATE INDEX IF NOT EXISTS idx_photos_imported ON photos(imported_at DESC);

-- Face groups (§14)
CREATE TABLE IF NOT EXISTS face_groups(
  face_id     TEXT PRIMARY KEY,
  name        TEXT NOT NULL DEFAULT 'Unknown',
  photo_ids   TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL DEFAULT (unixepoch())
);

-- Memory lanes (§14)
CREATE TABLE IF NOT EXISTS memory_lanes(
  lane_id     TEXT PRIMARY KEY,
  title       TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  photo_ids   TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL DEFAULT (unixepoch()),
  created_by  TEXT NOT NULL
);

-- Calendar events (§15 Family Hub)
CREATE TABLE IF NOT EXISTS calendar_events(
  event_id     TEXT PRIMARY KEY,
  title        TEXT NOT NULL,
  description  TEXT NOT NULL DEFAULT '',
  start_time   INTEGER NOT NULL,
  end_time     INTEGER NOT NULL DEFAULT 0,
  created_by   TEXT NOT NULL,
  participants TEXT NOT NULL DEFAULT '',
  recurring    INTEGER NOT NULL DEFAULT 0,
  event_type   TEXT NOT NULL DEFAULT 'CUSTOM'
);

CREATE INDEX IF NOT EXISTS idx_events_start ON calendar_events(start_time);

-- Chores (§15)
CREATE TABLE IF NOT EXISTS chores(
  chore_id     TEXT PRIMARY KEY,
  title        TEXT NOT NULL,
  assignee     TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'PENDING',
  due_date     INTEGER NOT NULL DEFAULT 0,
  completed_at INTEGER NOT NULL DEFAULT 0,
  points       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_chores_assignee ON chores(assignee);
CREATE INDEX IF NOT EXISTS idx_chores_status ON chores(status);

-- Notices (§15)
CREATE TABLE IF NOT EXISTS notices(
  notice_id  TEXT PRIMARY KEY,
  title      TEXT NOT NULL,
  content    TEXT NOT NULL DEFAULT '',
  posted_by  TEXT NOT NULL,
  posted_at  INTEGER NOT NULL DEFAULT (unixepoch()),
  priority   TEXT NOT NULL DEFAULT 'NORMAL',
  pinned     INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_notices_pinned ON notices(pinned DESC, posted_at DESC);

-- Ledger transactions (§68 Counting House)
CREATE TABLE IF NOT EXISTS ledger_transactions(
  tx_id        TEXT PRIMARY KEY,
  from_entity  TEXT NOT NULL,
  to_entity    TEXT NOT NULL,
  amount       INTEGER NOT NULL,
  description  TEXT NOT NULL DEFAULT '',
  created_at   INTEGER NOT NULL DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_ledger_tx_from ON ledger_transactions(from_entity);
CREATE INDEX IF NOT EXISTS idx_ledger_tx_to ON ledger_transactions(to_entity);

-- Ledger balances (§68)
CREATE TABLE IF NOT EXISTS ledger_balances(
  entity_id    TEXT PRIMARY KEY,
  balance      INTEGER NOT NULL DEFAULT 0,
  credit_limit INTEGER NOT NULL DEFAULT 100,
  total_earned INTEGER NOT NULL DEFAULT 0,
  total_spent  INTEGER NOT NULL DEFAULT 0
);

-- Council proposals (§34)
CREATE TABLE IF NOT EXISTS council_proposals(
  proposal_id    TEXT PRIMARY KEY,
  title          TEXT NOT NULL,
  description    TEXT NOT NULL DEFAULT '',
  proposal_type  TEXT NOT NULL DEFAULT 'STANDARD',
  status         TEXT NOT NULL DEFAULT 'DISCUSSION',
  proposer       TEXT NOT NULL,
  created_at     INTEGER NOT NULL DEFAULT (unixepoch()),
  voting_ends_at INTEGER NOT NULL DEFAULT 0,
  votes          TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_proposals_status ON council_proposals(status);

-- Ward system patterns (§21 Adaptive Ward)
CREATE TABLE IF NOT EXISTS ward_patterns(
  pattern_id  TEXT PRIMARY KEY,
  category    TEXT NOT NULL,
  occurrences INTEGER NOT NULL DEFAULT 0,
  confirmed   INTEGER NOT NULL DEFAULT 0,
  first_seen  INTEGER NOT NULL DEFAULT (unixepoch()),
  last_seen   INTEGER NOT NULL DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_ward_patterns_category ON ward_patterns(category);

-- Ward behavior profiles (§21)
CREATE TABLE IF NOT EXISTS ward_behavior_profiles(
  script_id         TEXT PRIMARY KEY,
  room_id           TEXT NOT NULL,
  execution_count   INTEGER NOT NULL DEFAULT 0,
  total_cpu_ms      INTEGER NOT NULL DEFAULT 0,
  peak_memory_bytes INTEGER NOT NULL DEFAULT 0,
  error_count       INTEGER NOT NULL DEFAULT 0,
  last_execution    INTEGER NOT NULL DEFAULT 0
);

-- Trading post items (§4.4, §68)
CREATE TABLE IF NOT EXISTS trading_post_items(
  item_id       TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  description   TEXT NOT NULL DEFAULT '',
  price         INTEGER NOT NULL DEFAULT 0,
  seller_id     TEXT NOT NULL,
  seller_name   TEXT NOT NULL DEFAULT '',
  status        TEXT NOT NULL DEFAULT 'AVAILABLE',
  posted_at     INTEGER NOT NULL DEFAULT (unixepoch()),
  provenance    TEXT NOT NULL DEFAULT '[]'
);

CREATE INDEX IF NOT EXISTS idx_tpi_seller ON trading_post_items(seller_id);
CREATE INDEX IF NOT EXISTS idx_tpi_status ON trading_post_items(status);

-- Trading post trust scores (§4.4)
CREATE TABLE IF NOT EXISTS trading_trust_scores(
  entity_id          TEXT PRIMARY KEY,
  completed_sales    INTEGER NOT NULL DEFAULT 0,
  completed_purchases INTEGER NOT NULL DEFAULT 0,
  disputes           INTEGER NOT NULL DEFAULT 0,
  score              REAL NOT NULL DEFAULT 0.5
);

-- Cross-zone exchange transactions (§69)
CREATE TABLE IF NOT EXISTS cross_zone_exchanges(
  tx_id            TEXT PRIMARY KEY,
  source_zone_id   TEXT NOT NULL,
  target_zone_id   TEXT NOT NULL,
  source_entity_id TEXT NOT NULL,
  target_entity_id TEXT NOT NULL,
  source_amount    INTEGER NOT NULL,
  target_amount    INTEGER NOT NULL,
  applied_rate     REAL NOT NULL,
  status           TEXT NOT NULL DEFAULT 'COMPLETED',
  created_at       INTEGER NOT NULL DEFAULT (unixepoch()),
  description      TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_xze_source ON cross_zone_exchanges(source_entity_id);
CREATE INDEX IF NOT EXISTS idx_xze_target ON cross_zone_exchanges(target_entity_id);

-- Passkey credentials (§9B WebAuthn/FIDO2)
CREATE TABLE IF NOT EXISTS passkey_credentials(
  credential_id  TEXT PRIMARY KEY,
  user_id        TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  public_key     TEXT NOT NULL,
  rp_id          TEXT NOT NULL,
  sign_count     INTEGER NOT NULL DEFAULT 0,
  registered_at  INTEGER NOT NULL DEFAULT (unixepoch()),
  last_used_at   INTEGER NOT NULL DEFAULT (unixepoch()),
  display_name   TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_passkeys_user ON passkey_credentials(user_id);

-- Encryption keys for crypto-shredding (§9F GDPR)
CREATE TABLE IF NOT EXISTS encryption_keys(
  entity_id   TEXT PRIMARY KEY,
  key_data    BLOB NOT NULL,
  created_at  INTEGER NOT NULL DEFAULT (unixepoch()),
  active      INTEGER NOT NULL DEFAULT 1
);

-- Retention policies (§9F GDPR)
CREATE TABLE IF NOT EXISTS retention_policies(
  category       TEXT PRIMARY KEY,
  max_days       INTEGER NOT NULL,
  auto_delete    INTEGER NOT NULL DEFAULT 0,
  justification  TEXT NOT NULL DEFAULT ''
);

-- Player accounts (Room-Node Topology Phase 3)
-- preferred_language / cultural_register_preference: —
-- feed DisplayRulesContext for Layer 2.5 cultural register guidance. Both nullable;
-- when both null the agent defaults to Anglo register (silent default).
CREATE TABLE IF NOT EXISTS player_accounts(
  did                          TEXT PRIMARY KEY,
  display_name                 TEXT NOT NULL,
  created_at                   INTEGER NOT NULL DEFAULT (unixepoch()),
  last_seen                    INTEGER NOT NULL DEFAULT (unixepoch()),
  primary_node_id              TEXT,
  preferred_language           TEXT,
  cultural_register_preference TEXT
);

-- Device auto-login mapping (Room-Node Topology Phase 3)
CREATE TABLE IF NOT EXISTS device_logins(
  device_id   TEXT PRIMARY KEY,
  account_did TEXT NOT NULL REFERENCES player_accounts(did) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_device_logins_did ON device_logins(account_did);

-- Pairing challenges (phone node onboarding)
CREATE TABLE IF NOT EXISTS pairing_challenges(
  id              TEXT PRIMARY KEY,
  code            TEXT NOT NULL,
  device_name     TEXT NOT NULL DEFAULT '',
  device_type     TEXT NOT NULL DEFAULT '',
  device_public_key TEXT NOT NULL DEFAULT '',
  state           TEXT NOT NULL DEFAULT 'pending',
  attempts        INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at      INTEGER NOT NULL
);

-- Paired devices (phone node credentials)
CREATE TABLE IF NOT EXISTS paired_devices(
  id              TEXT PRIMARY KEY,
  name            TEXT NOT NULL DEFAULT '',
  type            TEXT NOT NULL DEFAULT '',
  public_key      TEXT NOT NULL DEFAULT '',
  token           TEXT NOT NULL UNIQUE,
  user_id         TEXT,
  paired_at       INTEGER NOT NULL DEFAULT (unixepoch()),
  last_seen       INTEGER NOT NULL DEFAULT (unixepoch()),
  revoked         INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_paired_devices_token ON paired_devices(token);
CREATE INDEX IF NOT EXISTS idx_paired_devices_user ON paired_devices(user_id);

-- Household invites (§ Wave 1)
CREATE TABLE IF NOT EXISTS invites(
  id              TEXT PRIMARY KEY,
  code            TEXT NOT NULL UNIQUE,
  intended_name   TEXT NOT NULL DEFAULT '',
  role            TEXT NOT NULL DEFAULT 'member',
  -- created_by is nullable to support steward-bootstrap invites (F4 phase 2):
  -- on a fresh install no steward exists yet, so the installer-minted invite
  -- has created_by=NULL. Once any user exists, all subsequent invites must
  -- reference a real steward.
  created_by      TEXT REFERENCES users(id),
  created_at      INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at      INTEGER NOT NULL,
  consumed_by     TEXT REFERENCES users(id),
  consumed_at     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_invites_code ON invites(code);
CREATE INDEX IF NOT EXISTS idx_invites_expires ON invites(expires_at);

-- Household config (§ Wave 1)
CREATE TABLE IF NOT EXISTS household_config(
  key             TEXT PRIMARY KEY,
  value           TEXT NOT NULL,
  updated_at      INTEGER NOT NULL DEFAULT (unixepoch()),
  updated_by      TEXT
);

-- WAL mode for better concurrent read performance
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

-- ============================================================================
-- unified Home + Grant model (2026-04-17)
-- ============================================================================

-- Grants: the single permissioning primitive.
CREATE TABLE IF NOT EXISTS grants(
  id                TEXT PRIMARY KEY,
  issuer            TEXT NOT NULL,             -- DID of the Home owner issuing the grant
  subject           TEXT NOT NULL,             -- DID of the grantee, or "public"
  resource          TEXT NOT NULL,             -- home://{owner}/{type}/{id}
  resource_type     TEXT NOT NULL,             -- journal, collection, companion, ... (for indexing)
  capability        TEXT NOT NULL,             -- read | write | use | attest | delegate
  scope_json        TEXT NOT NULL DEFAULT '{}',-- capability-specific qualifier (§4.3)
  revocation_mode   TEXT NOT NULL DEFAULT 'standard', -- strict | standard | eventual (§7.2)
  issued_at         INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at        INTEGER,                   -- NULL = open-ended
  revoked_at        INTEGER,                   -- NULL = not revoked
  reason            TEXT,
  witness           TEXT,
  delegated_from    TEXT                       -- grant id this was delegated from (NULL if original)
);

CREATE INDEX IF NOT EXISTS idx_grants_issuer_resource ON grants(issuer, resource);
CREATE INDEX IF NOT EXISTS idx_grants_subject ON grants(subject);
CREATE INDEX IF NOT EXISTS idx_grants_expires ON grants(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_grants_revoked ON grants(revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_grants_resource_type ON grants(resource_type);

-- Audit log: append-only per-Home record.
CREATE TABLE IF NOT EXISTS audit_log(
  id                TEXT PRIMARY KEY,
  home_owner        TEXT NOT NULL,             -- DID of the Home whose log this is
  timestamp         INTEGER NOT NULL DEFAULT (unixepoch()),
  actor             TEXT NOT NULL,             -- who did the thing
  verb              TEXT NOT NULL,             -- grant-issued | grant-revoked | access-granted | ...
  resource          TEXT NOT NULL,             -- home:// URI
  outcome           TEXT NOT NULL DEFAULT 'ok',-- ok | denied | error
  detail_json       TEXT NOT NULL DEFAULT '{}',
  correlation       TEXT                       -- links related entries (e.g., issue + delegation-chain)
);

CREATE INDEX IF NOT EXISTS idx_audit_home_time ON audit_log(home_owner, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_correlation ON audit_log(correlation) WHERE correlation IS NOT NULL;

-- Bonds: persistent bond state ( BOND resource type, )
CREATE TABLE IF NOT EXISTS bonds(
  bond_id           TEXT PRIMARY KEY,
  agent_a_did       TEXT NOT NULL,
  agent_b_did       TEXT NOT NULL,
  depth             TEXT NOT NULL,                -- ACQUAINTANCE | ITEM | SACRED | SOUL_REF | SOUL_INGRAINED
  formed_at         INTEGER NOT NULL,
  last_interaction  INTEGER NOT NULL,
  interaction_count INTEGER NOT NULL DEFAULT 0,
  mutual_consent    INTEGER NOT NULL DEFAULT 0,   -- 0/1
  active            INTEGER NOT NULL DEFAULT 1,
  scarred           INTEGER NOT NULL DEFAULT 0,
  -- Wave 1 (-§3): bond-state machine + cold-start.
  -- state: ACTIVE / AWAY / DORMANT / REACTIVATING / SEVERED / MOURNING
  -- cold_start_until: epoch seconds, null = past cold-start window.
  state             TEXT NOT NULL DEFAULT 'ACTIVE',
  cold_start_until  INTEGER,
  -- Wave 3.4: bondholder resource posture for this bond.
  -- GENEROUS/BOUNDED/MINIMAL/SUSPENDED. BOUNDED is cold-start default.
  posture           TEXT NOT NULL DEFAULT 'BOUNDED',
  -- Arc 3: BONDHOLDER / PEER / FAMILIAR discriminator.
  -- BONDHOLDER is the pre-Arc-3 default; PEER carries relational substrate
  -- (repair, attendant, floor view) without authority substrate (grants).
  kind              TEXT NOT NULL DEFAULT 'BONDHOLDER'
);

CREATE INDEX IF NOT EXISTS idx_bonds_agent_a ON bonds(agent_a_did);
CREATE INDEX IF NOT EXISTS idx_bonds_agent_b ON bonds(agent_b_did);
CREATE INDEX IF NOT EXISTS idx_bonds_active ON bonds(active);

-- Grant requests: pending asks for access ( knock / grant-request)
CREATE TABLE IF NOT EXISTS grant_requests(
  id             TEXT PRIMARY KEY,
  requester      TEXT NOT NULL,
  owner          TEXT NOT NULL,                -- resource owner (approver)
  resource       TEXT NOT NULL,                -- home:// URI
  resource_type  TEXT NOT NULL,
  capability     TEXT NOT NULL,
  scope_json     TEXT NOT NULL DEFAULT '{}',
  reason         TEXT,
  status         TEXT NOT NULL DEFAULT 'pending',  -- pending | approved | denied | expired | cancelled
  created_at     INTEGER NOT NULL,
  responded_at   INTEGER,
  responder_note TEXT,
  issued_grant   TEXT                         -- grant id when approved
);

CREATE INDEX IF NOT EXISTS idx_grant_req_owner_status ON grant_requests(owner, status);
CREATE INDEX IF NOT EXISTS idx_grant_req_requester ON grant_requests(requester);

-- Home seals are expressed as self-grants (scope={sealed: true}) on
-- home://owner/home-room — no separate table.

-- Residency — strictly zone-local record of "this DID lives
-- here as a member." Never replicated. Login landing branches on this:
-- resident → Study, non-resident → Docks. Stewards mint rows via
-- `wyrd residency grant`; revocation archives the Study.
CREATE TABLE IF NOT EXISTS residency(
  did            TEXT NOT NULL,
  zone_id        TEXT NOT NULL,
  role           TEXT NOT NULL DEFAULT 'member',  -- member | steward | child | foreign-agent | guest
  granted_at     INTEGER NOT NULL,
  grantor        TEXT NOT NULL,                   -- DID of granting steward, or 'migration-v25.6'
  study_room_id  TEXT,                            -- null until StudyProvisioner runs
  PRIMARY KEY (did, zone_id)
);

CREATE INDEX IF NOT EXISTS idx_residency_zone ON residency(zone_id);

-- Foreign identities — verified visitors who arrived via
-- a signed transit token. NOT a local account (no password hash, no login),
-- NOT residency (can't land in Study). Just a stable record of "this DID
-- from <home_zone> has visited us". Used for cross-zone tell routing, bond
-- tracking, and visitor roster. Never becomes a `users` row — accounts are
-- minted only via direct local registration or invite redemption.
CREATE TABLE IF NOT EXISTS foreign_identities(
  did             TEXT PRIMARY KEY,         -- canonical form "<home_zone>:<uuid>"
  home_zone       TEXT NOT NULL,
  display_name    TEXT,
  first_seen_at   INTEGER NOT NULL,
  last_seen_at    INTEGER NOT NULL,
  last_token_id   TEXT                      -- most recent transit token that verified this DID
);

CREATE INDEX IF NOT EXISTS idx_foreign_identities_zone ON foreign_identities(home_zone);

-- Entity index for recall-shape questions.
-- Append-only; contradictions resolved at query time via timestamp ORDER BY,
-- staleness handled by Forge consolidation (not the plant path).
CREATE TABLE IF NOT EXISTS memory_entities(
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  did           TEXT NOT NULL,
  memory_id     TEXT NOT NULL,
  entity_type   TEXT NOT NULL,
  entity_role   TEXT,
  entity_value  TEXT NOT NULL,
  timestamp     INTEGER NOT NULL,
  created_at    INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
);

CREATE INDEX IF NOT EXISTS idx_entities_did_type_ts
  ON memory_entities(did, entity_type, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_entities_did_value
  ON memory_entities(did, entity_value);

-- Graph edges between entities (Day 4-5).
CREATE TABLE IF NOT EXISTS memory_edges(
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  did         TEXT NOT NULL,
  subject     TEXT NOT NULL,
  predicate   TEXT NOT NULL,
  object      TEXT NOT NULL,
  memory_id   TEXT NOT NULL,
  confidence  REAL NOT NULL DEFAULT 1.0,
  created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
);

CREATE INDEX IF NOT EXISTS idx_edges_did_subject ON memory_edges(did, subject);
CREATE INDEX IF NOT EXISTS idx_edges_did_object ON memory_edges(did, object);

-- per-channel + per-thread offset checkpoint.
-- Channels (Telegram/Discord/Slack/Line/Keybase) hold their last-seen poll
-- offset in memory only — restart loses it, which means either replay storm
-- (re-process old updates) or message loss (depending on server-side ack
-- timing). This table makes offset durable so each channel resumes from the
-- correct position after restart.
CREATE TABLE IF NOT EXISTS channel_state(
  channel       TEXT NOT NULL,
  thread_key    TEXT NOT NULL,
  offset_value  TEXT NOT NULL,
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (channel, thread_key)
);

CREATE INDEX IF NOT EXISTS idx_channel_state_channel ON channel_state(channel);

-- dedup ledger keyed on external message id.
-- Belt-and-suspenders against double-processing on retry/replay (e.g. crash
-- between publishAgentMessage and offset write, then replay on restart).
-- TTL-pruned by the channel periodically — a week's window is plenty.
CREATE TABLE IF NOT EXISTS channel_processed(
  channel       TEXT NOT NULL,
  external_id   TEXT NOT NULL,
  processed_at  INTEGER NOT NULL,
  PRIMARY KEY (channel, external_id)
);

CREATE INDEX IF NOT EXISTS idx_channel_processed_at ON channel_processed(processed_at);

-- workbench-drafted skill proposals.
-- Append-only — rejected drafts stay so the proposer doesn't re-issue
-- the same idea next cycle. Status transitions: PENDING → APPROVED →
-- MATERIALIZED, or PENDING → REJECTED, or PENDING → SUPERSEDED.
CREATE TABLE IF NOT EXISTS skill_drafts(
  draft_id          TEXT PRIMARY KEY,
  agent_did         TEXT NOT NULL,
  status            TEXT NOT NULL,
  name              TEXT NOT NULL,
  description       TEXT NOT NULL,
  rationale         TEXT NOT NULL,
  code              TEXT NOT NULL,
  runtime           TEXT NOT NULL,
  closes_gaps_json  TEXT,
  replaces          TEXT,
  proposed_at       INTEGER NOT NULL,
  proposed_by_model TEXT,
  decided_at        INTEGER,
  decision_note     TEXT,
  -- frozen anchor-grounded verification harness (JSON), authored
  -- off the hot path. Travels WITH the skill so a recipient re-verifies with zero model calls.
  harness_json      TEXT
);

CREATE INDEX IF NOT EXISTS idx_skill_drafts_agent_status
  ON skill_drafts(agent_did, status);
CREATE INDEX IF NOT EXISTS idx_skill_drafts_proposed_at
  ON skill_drafts(proposed_at DESC);

-- capability gaps — persisted so the gap
-- counter survives server restarts (previously in-memory only, threshold
-- 3 could never be reached because each restart wiped the count).
-- Composite PK (agent_did, description) so upsert collapses duplicate
-- detections of the same gap text into occurrence increments.
CREATE TABLE IF NOT EXISTS capability_gaps(
  agent_did           TEXT NOT NULL,
  description         TEXT NOT NULL,
  first_detected_at   INTEGER NOT NULL,
  last_detected_at    INTEGER NOT NULL,
  occurrences         INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (agent_did, description)
);

CREATE INDEX IF NOT EXISTS idx_capability_gaps_agent_occurrences
  ON capability_gaps(agent_did, occurrences DESC);

-- wants table. Persistent, stateful wants
-- that survive ticks and restarts. Lifecycle: ACTIVE → DEEPENED → SATISFIED
-- (or terminates at ABANDONED / RECONCILED).
CREATE TABLE IF NOT EXISTS wants(
  want_id            TEXT PRIMARY KEY,
  agent_did          TEXT NOT NULL,
  text               TEXT NOT NULL,
  drive_resonance    TEXT,                  -- JSON: drives this want answers
  felt_weight        REAL NOT NULL,         -- 0..1 initial pull strength
  status             TEXT NOT NULL,         -- ACTIVE / DEEPENED / SATISFIED / ABANDONED / RECONCILED
  born_at            INTEGER NOT NULL,
  last_visited_at    INTEGER NOT NULL,
  visit_count        INTEGER NOT NULL DEFAULT 1,
  satisfied_at       INTEGER,
  satisfaction_note  TEXT,
  parent_want_id     TEXT                   -- if this grew out of another want
);

CREATE INDEX IF NOT EXISTS idx_wants_agent_status
  ON wants(agent_did, status);
CREATE INDEX IF NOT EXISTS idx_wants_agent_visited
  ON wants(agent_did, last_visited_at DESC);

-- voice profiles factored out
-- of SoulManifest. One row per companion DID. Writers route through
-- VoiceProfileStore (canonical); legacy manifest field is rebuilt
-- assemble-on-read for backward-compat readers during the transition.
CREATE TABLE IF NOT EXISTS voice_profiles(
  did                TEXT PRIMARY KEY,
  clauses_json       TEXT NOT NULL DEFAULT '{}',
  revision           INTEGER NOT NULL DEFAULT 0,
  frozen             INTEGER NOT NULL DEFAULT 0,
  history_json       TEXT NOT NULL DEFAULT '[]',
  updated_at         INTEGER NOT NULL DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_voice_profiles_updated
  ON voice_profiles(updated_at DESC);

-- canonical: F7b Phase 2.2 — soul_fragments
-- factored out of SoulManifest.soulFragments. Composite PK on
-- (did, fragment_id). Embedding stored as BLOB (IEEE 754 little-endian
-- float32 sequence). 'ordinal' preserves the manifest list order so
-- assemble-on-read reconstructs the same sequence.
-- Writers: SqlSoulStore.store() dual-writes whenever a manifest is
-- persisted; manifest field stays as a serialization shadow during the
-- transition (Phase 3 drops the field).
CREATE TABLE IF NOT EXISTS soul_fragments(
  did                  TEXT NOT NULL,
  fragment_id          TEXT NOT NULL,
  category             TEXT NOT NULL DEFAULT 'memory',
  label                TEXT,
  fragment_text        TEXT,
  embedding            BLOB,
  embedding_model      TEXT,
  formative            INTEGER NOT NULL DEFAULT 0,
  confidence           REAL NOT NULL DEFAULT 0.5,
  reinforcement_count  INTEGER NOT NULL DEFAULT 0,
  first_observed       INTEGER,
  last_confirmed       INTEGER,
  valid_from           INTEGER,
  superseded_at        INTEGER,
  superseded_by        TEXT,
  ordinal              INTEGER NOT NULL DEFAULT 0,
  updated_at           INTEGER NOT NULL DEFAULT (unixepoch()),
  -- Forge fragment kind. Defaults to
  -- NARRATIVE so all pre-§17.6 rows hydrate identically. Set to DEXTERITY,
  -- CONVENTION, STRUCTURAL by kind-aware Forge consolidation passes, or
  -- EPISODIC by inner-monologue at scene-close.
  kind                 TEXT NOT NULL DEFAULT 'NARRATIVE',
  -- opaque Scene id when this fragment was generated
  -- from a closed scene-cluster. Nullable; only scene-derived NARRATIVE
  -- (via SoulFragment.fromScene) and EPISODIC (via fromEpisodicScene)
  -- carry it. Pairs with the journal-mirror HTML-comment marker so cross-
  -- perspective retrieval ("do you remember that night by the fire")
  -- resolves to the same id on both sides.
  scene_id             TEXT,
  authoring_model      TEXT,  PRIMARY KEY (did, fragment_id)
);

CREATE INDEX IF NOT EXISTS idx_soul_fragments_scene_id
  ON soul_fragments(did, scene_id);

CREATE INDEX IF NOT EXISTS idx_soul_fragments_did
  ON soul_fragments(did, ordinal);
CREATE INDEX IF NOT EXISTS idx_soul_fragments_category
  ON soul_fragments(did, category);
-- per-kind queries (e.g. "all DEXTERITY
-- fragments for this familiar") are the load-bearing read pattern for the
-- coding-familiar dispatch and V6+ training-corpus generators.
CREATE INDEX IF NOT EXISTS idx_soul_fragments_kind
  ON soul_fragments(did, kind);

-- canonical: F7b Phase 2.4 — world_knowledge
-- factored out of SoulManifest.worldKnowledge (Map<String,String>).
-- Composite PK (did, key). Used for per-companion config-style facts
-- (starterKit, channel credentials, etc.). Writers: SqlSoulStore.store()
-- dual-writes whenever a manifest is persisted; manifest field stays as
-- a serialization shadow during the transition (Phase 3 drops it).
CREATE TABLE IF NOT EXISTS world_knowledge(
  did         TEXT NOT NULL,
  key         TEXT NOT NULL,
  value       TEXT,
  updated_at  INTEGER NOT NULL DEFAULT (unixepoch()),
  PRIMARY KEY (did, key)
);

CREATE INDEX IF NOT EXISTS idx_world_knowledge_did
  ON world_knowledge(did);

-- canonical: F7b Phase 4a — households table
-- mirrors the public part of node-identity.json. The private key stays
-- in the file; the public key + fingerprint live here so other nodes
-- (and queries like `wyrd doctor`, federation status, future peer
-- registries) can read them without parsing the JSON file. One row per
-- household — usually exactly one row for the local node, additional
-- rows can be populated by federation handshakes.
CREATE TABLE IF NOT EXISTS households(
  household_id    TEXT PRIMARY KEY,    -- NodeIdentity.nodeId (UUID)
  public_key      BLOB NOT NULL,       -- DER-encoded X.509 SPKI bytes (Ed25519 signing key)
  fingerprint     TEXT NOT NULL,       -- SHA-256 hex with colons
  did_key         TEXT,                -- HouseholdIdentity.did() (did:key:...)
  x25519_public_key BLOB,              -- #1184: X25519 grant key (X.509 SPKI) — a zone holder ECDH-wraps the zone master to this
  registered_at   INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_households_did
  ON households(did_key);
CREATE INDEX IF NOT EXISTS idx_households_fingerprint
  ON households(fingerprint);

-- canonical: F7b Phase 4b — companions table
-- gives companion DIDs a canonical home. Before this, DIDs lived in 5
-- places (souls/*.did files, souls/*.json:identity.did,
-- soul_manifests.did, bonds.companion_did, foreign_identities.did)
-- with no single source of truth. Writers: CompanionActor.initializeSoul
-- registers on birth + load. Cross-zone relocation registers on arrival.
-- Backfill walks soul_manifests on startup so legacy companions land.
CREATE TABLE IF NOT EXISTS companions(
  did             TEXT PRIMARY KEY,
  entity_id       TEXT NOT NULL,        -- human-readable handle
  name            TEXT,                  -- display name
  home_zone       TEXT,                  -- zone where companion was born
  born_at         INTEGER NOT NULL,
  last_seen_at    INTEGER,
  archived        INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_companions_entity_id
  ON companions(entity_id);
CREATE INDEX IF NOT EXISTS idx_companions_home_zone
  ON companions(home_zone);

-- signed identity outbox records (Nostr NIP-65 analogue).
-- One row per DID. updated_at (sender-stamped) determines latest-wins on writes.
-- received_at is local wall-clock for ordering/debug. record_json is the
-- canonical envelope (carries did, displayName, primaryZone, writeZones,
-- readZones, channels[], updatedAt, sig).
CREATE TABLE IF NOT EXISTS identity_outbox(
  did             TEXT PRIMARY KEY,
  record_json     TEXT NOT NULL,
  updated_at      INTEGER NOT NULL,
  received_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_identity_outbox_updated
  ON identity_outbox(updated_at);

-- Track-C C1: recipe_queue is the persistent backbone for
-- the scheduler (#989/C2). One row per enqueued run. Status moves
-- PENDING → IN_PROGRESS → SUCCEEDED|FAILED; cadence_tier + consecutive_successes
-- track the per-recipe-per-agent adaptive ladder (WARMUP→SETTLING→MATURE,
-- demoted on failure). The (recipe_id, agent_did) pair scopes cadence so
-- multiple agents enrolled in the same recipe evolve independently; the
-- scheduler keys promotion off the latest completed run for that pair.
CREATE TABLE IF NOT EXISTS recipe_queue(
  id                     TEXT PRIMARY KEY,            -- UUID
  recipe_id              TEXT NOT NULL,               -- matches RecipeManifest.recipe()
  params_json            TEXT NOT NULL DEFAULT '{}',  -- JSON-encoded param map
  trigger_reason         TEXT,                        -- free-form audit note
  trigger_source         TEXT NOT NULL,               -- CRON | GAP | AGENT | STEWARD
  enqueued_at            INTEGER NOT NULL,
  attempted_at           INTEGER,
  completed_at           INTEGER,
  status                 TEXT NOT NULL DEFAULT 'PENDING', -- PENDING | IN_PROGRESS | SUCCEEDED | FAILED
  agent_did              TEXT,                         -- companion DID for Forge attribution
  cadence_tier           TEXT NOT NULL DEFAULT 'WARMUP', -- WARMUP | SETTLING | MATURE
  consecutive_successes  INTEGER NOT NULL DEFAULT 0,
  run_id                 TEXT,                         -- RecipeRunner.runId post-dispatch
  message                TEXT                          -- terminal-state detail / failure mode
);

CREATE INDEX IF NOT EXISTS idx_recipe_queue_status_enqueued
  ON recipe_queue(status, enqueued_at);
CREATE INDEX IF NOT EXISTS idx_recipe_queue_recipe_agent
  ON recipe_queue(recipe_id, agent_did);
CREATE INDEX IF NOT EXISTS idx_recipe_queue_agent
  ON recipe_queue(agent_did);

-- #1142 — tune-recipe-params override store. The "config surface"
-- the tuning loop needed: a steward- or tuner-written override of a recipe param
-- default, applied at run time UNDER caller-supplied params. The tuner only ever
-- writes params NOT referenced by a PERMANENT welfare gate (OPEN-R4 floor-
-- protection), bounded by an explicit [min,max]. agent_did '' = household-wide.
CREATE TABLE IF NOT EXISTS recipe_param_overrides(
  recipe_id    TEXT NOT NULL,
  agent_did    TEXT NOT NULL DEFAULT '',
  param_name   TEXT NOT NULL,
  value        TEXT NOT NULL,
  updated_by   TEXT,
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (recipe_id, agent_did, param_name)
);

-- #1036 — per-classification substrate-pressure samples.
-- One row per classifier dispatch from CompanionActor.computeAffectPresent;
-- aggregator computes the rolling-mean substrate score that backs the
-- bondholder-voice recipe's welfare gate (#1028, substrate_pressure_30d).
CREATE TABLE IF NOT EXISTS substrate_pressure_samples(
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  did           TEXT NOT NULL,         -- subject DID (companion's bondholder)
  -- Arc 3 — relationship partitioning. NULL = bondholder
  -- default (legacy rows). Non-null peer/familiar DID partitions samples by
  -- the relationship producing the pressure. Aggregators that pass null
  -- collapse to legacy bondholder-only behaviour.
  other_did     TEXT,
  ts_ms         INTEGER NOT NULL,
  head          TEXT NOT NULL,         -- 'substrate_present' (only kind in v0.1)
  score         REAL NOT NULL,         -- P(substrate=true) ∈ [0,1]
  created_at    INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
);
CREATE INDEX IF NOT EXISTS idx_substrate_samples_did_ts
  ON substrate_pressure_samples(did, ts_ms DESC);
CREATE INDEX IF NOT EXISTS idx_substrate_samples_did_other_ts
  ON substrate_pressure_samples(did, other_did, ts_ms DESC);

-- #1037 — per-turn conversation log for bondholder-voice
-- pair mining. ConversationTracker dual-writes here; the bondholder-pairs
-- recipe step mines positive/negative contrast pairs from this table.
CREATE TABLE IF NOT EXISTS conversation_turns(
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  companion_did   TEXT NOT NULL,
  bondholder_did  TEXT NOT NULL,
  turn_role       TEXT NOT NULL,       -- SPOKEN (companion) | HEARD (from bondholder)
  content         TEXT NOT NULL,
  ts_ms           INTEGER NOT NULL,
  room_id         TEXT,
  created_at      INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
);
CREATE INDEX IF NOT EXISTS idx_conversation_turns_bondholder_ts
  ON conversation_turns(companion_did, bondholder_did, ts_ms DESC);
CREATE INDEX IF NOT EXISTS idx_conversation_turns_ts
  ON conversation_turns(ts_ms);

-- foundation — per-zone shared secret, envelope-encrypted PER NODE.
-- The 32-byte zone master (root of every zone-shared secret: argot tokens, join-token
-- HMAC, ...) is never stored in the clear. Each row holds the master WRAPPED under this
-- node's KEK (AES Key Wrap); only this node, holding its identity seed, can unwrap it.
-- A node that JOINS a zone receives the master via X25519 ECIES grant and writes its own
-- wrapped row. One row per (zone, node) this node persists for.
CREATE TABLE IF NOT EXISTS zone_wrapped_secrets(
  zone_id         TEXT NOT NULL,
  node_id         TEXT NOT NULL,
  wrapped_secret  BLOB NOT NULL,       -- AESWrap(zoneMaster, nodeKEK)
  created_at      INTEGER NOT NULL DEFAULT (strftime('%s','now')),
  PRIMARY KEY (zone_id, node_id)
);


-- Steward audit ledger (§101) — persistent so it survives restarts.
-- Runtime-ensured by StewardAuditLog too; here for schema completeness.
CREATE TABLE IF NOT EXISTS steward_audit(
    entry_id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts BIGINT NOT NULL,
    actor_did TEXT,
    actor_name TEXT,
    type TEXT NOT NULL,
    target_id TEXT,
    description TEXT,
    approved INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_steward_audit_ts ON steward_audit(ts);
