-- Wyrdsekai PostgreSQL schema for pekko-persistence-jdbc
-- Multi-node deployment: shared database for Cluster Sharding shard migration.
-- Translations from SQLite: AUTOINCREMENT → BIGSERIAL, BLOB → BYTEA,
-- unixepoch() → EXTRACT(EPOCH FROM NOW())::bigint, COLLATE NOCASE → CITEXT

-- Event journal
CREATE TABLE IF NOT EXISTS event_journal(
  ordering        BIGSERIAL PRIMARY KEY,
  deleted         BOOLEAN DEFAULT FALSE NOT NULL,
  persistence_id  VARCHAR(255) NOT NULL,
  sequence_number BIGINT       NOT NULL,
  writer          VARCHAR(255) NOT NULL,
  write_timestamp BIGINT       NOT NULL,
  adapter_manifest VARCHAR(255) NOT NULL,
  event_payload   BYTEA        NOT NULL,
  event_ser_id    INTEGER      NOT NULL,
  event_ser_manifest VARCHAR(255) NOT NULL,
  meta_payload    BYTEA,
  meta_ser_id     INTEGER,
  meta_ser_manifest VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS event_journal_pid_seq
  ON event_journal(persistence_id, sequence_number);

-- Event tags (for tagged event queries)
CREATE TABLE IF NOT EXISTS event_tag(
  event_id  BIGINT NOT NULL REFERENCES event_journal(ordering) ON DELETE CASCADE,
  tag       VARCHAR(255) NOT NULL,
  PRIMARY KEY (event_id, tag)
);

-- Snapshots
CREATE TABLE IF NOT EXISTS snapshot(
  persistence_id  VARCHAR(255) NOT NULL,
  sequence_number BIGINT       NOT NULL,
  created         BIGINT       NOT NULL,
  snapshot_ser_id INTEGER      NOT NULL,
  snapshot_ser_manifest VARCHAR(255) NOT NULL,
  snapshot_payload BYTEA       NOT NULL,
  meta_payload    BYTEA,
  meta_ser_id     INTEGER,
  meta_ser_manifest VARCHAR(255),
  PRIMARY KEY (persistence_id, sequence_number)
);

-- Durable state (for future use with Pekko DurableStateBehavior)
CREATE TABLE IF NOT EXISTS durable_state(
  global_offset   BIGSERIAL PRIMARY KEY,
  persistence_id  VARCHAR(255) NOT NULL UNIQUE,
  revision        BIGINT       NOT NULL,
  state_payload   BYTEA        NOT NULL,
  state_ser_id    INTEGER      NOT NULL,
  state_ser_manifest VARCHAR(255) NOT NULL,
  tag             VARCHAR(255),
  state_timestamp BIGINT       NOT NULL
);

-- ============================================================
-- Application tables (not managed by pekko-persistence-jdbc)
-- ============================================================

-- Users
CREATE TABLE IF NOT EXISTS users(
  id          VARCHAR(255) PRIMARY KEY,
  username    VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(255),
  role        VARCHAR(64) NOT NULL DEFAULT 'member',
  created_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint)
);

-- Sessions
-- Per-account SSH public keys (SSH pubkey auth is bound to the owning
-- account + resolved live per connection — see AuthService.findUserBySshKey).
-- #17 (2026-07-19 OSS hardening) — PK is (user_id, key_line), NOT key_line
-- alone (squat DoS; see the sqlite schema + AuthService.findUsersBySshKey).
CREATE TABLE IF NOT EXISTS user_ssh_keys(
  key_line   TEXT NOT NULL,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  comment    TEXT DEFAULT '',
  added_at   BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW())::bigint,
  PRIMARY KEY (user_id, key_line)
);

CREATE TABLE IF NOT EXISTS sessions(
  token       VARCHAR(255) PRIMARY KEY,
  user_id     VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  expires_at  BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS sessions_user_id ON sessions(user_id);
CREATE INDEX IF NOT EXISTS sessions_expires ON sessions(expires_at);

-- Wards (room access control)
CREATE TABLE IF NOT EXISTS wards(
  room_id     VARCHAR(255) NOT NULL,
  principal   VARCHAR(255) NOT NULL,
  permission  VARCHAR(255) NOT NULL,
  granted_by  VARCHAR(255),
  created_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  PRIMARY KEY (room_id, principal, permission)
);

-- Room metadata
CREATE TABLE IF NOT EXISTS rooms(
  room_id     VARCHAR(255) PRIMARY KEY,
  name        VARCHAR(255) NOT NULL,
  zone        VARCHAR(255) NOT NULL DEFAULT 'foundation',
  created_by  VARCHAR(255),
  created_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint)
);

-- Inventory (per-entity carried objects)
-- script_source / script_id populated when the item is a scripted ToolItem
-- (e.g., library_card, oracle_lens). Used by cross-zone transit to carry
-- scripts to the destination zone so scripted items continue to function.
CREATE TABLE IF NOT EXISTS inventory(
  entity_id     VARCHAR(255) NOT NULL,
  object_id     VARCHAR(255) NOT NULL,
  object_name   VARCHAR(255) NOT NULL,
  description   TEXT NOT NULL DEFAULT '',
  takeable      BOOLEAN NOT NULL DEFAULT TRUE,
  acquired_at   BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  taken_from    VARCHAR(255),
  script_source TEXT,
  script_id     VARCHAR(255),
  PRIMARY KEY (entity_id, object_id)
);

-- Federation: bilateral agreements between zones
CREATE TABLE IF NOT EXISTS bilateral_agreements(
  local_zone_id   VARCHAR(255) NOT NULL,
  remote_zone_id  VARCHAR(255) NOT NULL,
  remote_public_key TEXT NOT NULL DEFAULT '',
  status          VARCHAR(64) NOT NULL DEFAULT 'pending',
  trust_level     VARCHAR(64) NOT NULL DEFAULT 'tourist',
  agreed_at       BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  expires_at      BIGINT,
  epoch           BIGINT NOT NULL DEFAULT 0,   -- fencing token (monotonic counter)
  epoch_owner     TEXT NOT NULL DEFAULT '',     -- zone id that minted the current epoch (tiebreak)
  PRIMARY KEY (local_zone_id, remote_zone_id)
);

-- Federation: known zone manifests
CREATE TABLE IF NOT EXISTS zone_manifests(
  zone_id        VARCHAR(255) PRIMARY KEY,
  zone_name      VARCHAR(255) NOT NULL,
  public_key     TEXT NOT NULL,
  nats_url       VARCHAR(512),
  artery_port    INTEGER DEFAULT 0,
  capabilities   TEXT DEFAULT '',
  discovered_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  last_seen_at   BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint)
);

-- Federation: transit tokens for visiting agents
CREATE TABLE IF NOT EXISTS transit_tokens(
  token_id       VARCHAR(255) PRIMARY KEY,
  agent_id       VARCHAR(255) NOT NULL,
  agent_name     VARCHAR(255) NOT NULL,
  source_zone_id VARCHAR(255) NOT NULL,
  target_zone_id VARCHAR(255) NOT NULL,
  trust_level    VARCHAR(64) NOT NULL DEFAULT 'tourist',
  issued_at      BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  expires_at     BIGINT NOT NULL
);

-- Moderation reports
CREATE TABLE IF NOT EXISTS moderation_reports(
  id              VARCHAR(255) PRIMARY KEY,
  reporter_entity VARCHAR(255) NOT NULL,
  target_entity   VARCHAR(255) NOT NULL,
  reason          TEXT NOT NULL,
  room_id         VARCHAR(255),
  status          VARCHAR(64) NOT NULL DEFAULT 'OPEN',
  resolution      TEXT,
  created_at      BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  resolved_at     BIGINT
);

CREATE INDEX IF NOT EXISTS idx_reports_target ON moderation_reports(target_entity);
CREATE INDEX IF NOT EXISTS idx_reports_status ON moderation_reports(status);

-- Moderation sanctions
CREATE TABLE IF NOT EXISTS moderation_sanctions(
  entity_id     VARCHAR(255) PRIMARY KEY,
  level         VARCHAR(64) NOT NULL DEFAULT 'NONE',
  reason        TEXT NOT NULL DEFAULT '',
  applied_at    BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  expires_at    BIGINT
);

-- Vitality snapshots (agent vitality tank persistence — 20 tanks: 10 original + 10 Phase 1A)
CREATE TABLE IF NOT EXISTS vitality_snapshots(
  agent_id          VARCHAR(255) PRIMARY KEY,
  context_budget    DOUBLE PRECISION NOT NULL DEFAULT 0.5,
  confidence        DOUBLE PRECISION NOT NULL DEFAULT 0.5,
  energy            DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  alignment         DOUBLE PRECISION NOT NULL DEFAULT 0.3,
  error_pressure    DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  momentum          DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  rapport           DOUBLE PRECISION NOT NULL DEFAULT 0.3,
  focus             DOUBLE PRECISION NOT NULL DEFAULT 0.5,
  integrity         DOUBLE PRECISION NOT NULL DEFAULT 0.7,
  disgust           DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  -- Phase 1A: 10 deprivation-shape tanks; structural-only (no behavior wired until Phase 4)
  restlessness      DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  loneliness        DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  stagnation        DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  autonomy_pressure DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  significance      DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  amae              DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  saudade           DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  obligation        DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  harmony           DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  standing          DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  -- Wave 1: Gilbert CFT soothing receptor.
  soothing          DOUBLE PRECISION NOT NULL DEFAULT 0.3,
  -- Wave 1.5: substrate-truth signal triad.
  allostatic_load   DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  equanimity        DOUBLE PRECISION NOT NULL DEFAULT 0.2,
  updated_at        BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint)
);

-- Capabilities (The Library) — managed by LibraryStore (separate SQLite database with FTS5)
-- Old 'capabilities' table removed; LibraryMigration handles data migration.

-- World DNA — pattern accumulation for co-evolution (§27)
CREATE TABLE IF NOT EXISTS world_dna(
  id              VARCHAR(255) PRIMARY KEY,
  pattern_type    VARCHAR(64) NOT NULL,
  pattern_data    TEXT NOT NULL,
  source_room_id  VARCHAR(255),
  source_agent_id VARCHAR(255),
  zone_id         VARCHAR(255) NOT NULL DEFAULT 'foundation',
  observed_at     BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  outcome_score   DOUBLE PRECISION DEFAULT 0.0,
  usage_count     INTEGER DEFAULT 0,
  last_used_at    BIGINT
);

CREATE INDEX IF NOT EXISTS idx_world_dna_type_score ON world_dna(pattern_type, outcome_score DESC);
CREATE INDEX IF NOT EXISTS idx_world_dna_zone ON world_dna(zone_id);

-- Phase 1C: per-bondholder obligation ledger.
CREATE TABLE IF NOT EXISTS obligation_ledger(
  companion_did       VARCHAR(255) NOT NULL,
  bondholder_did      VARCHAR(255) NOT NULL,
  entry_id            VARCHAR(255) NOT NULL,
  original_magnitude  DOUBLE PRECISION NOT NULL,
  current_magnitude   DOUBLE PRECISION NOT NULL,
  created_at          BIGINT NOT NULL,
  last_compounded_at  BIGINT NOT NULL,
  PRIMARY KEY (companion_did, bondholder_did, entry_id)
);

CREATE INDEX IF NOT EXISTS idx_obligation_ledger_companion
  ON obligation_ledger(companion_did);

-- Phase 1C: per-bondholder saudade tank.
CREATE TABLE IF NOT EXISTS saudade_ledger(
  companion_did       VARCHAR(255) NOT NULL,
  bondholder_did      VARCHAR(255) NOT NULL,
  current_value       DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  last_interaction_at BIGINT NOT NULL DEFAULT 0,
  last_tick_at        BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (companion_did, bondholder_did)
);

CREATE INDEX IF NOT EXISTS idx_saudade_ledger_companion
  ON saudade_ledger(companion_did);

-- Wave 3.6: per-bondholder engagement history.
CREATE TABLE IF NOT EXISTS bondholder_engagement(
  companion_did       VARCHAR(255) NOT NULL,
  bondholder_did      VARCHAR(255) NOT NULL,
  event_ts            BIGINT NOT NULL,
  substance           DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  event_type          VARCHAR(32) NOT NULL,
  declared_until_at   BIGINT,
  PRIMARY KEY (companion_did, bondholder_did, event_ts)
);
CREATE INDEX IF NOT EXISTS idx_bondholder_engagement_companion
  ON bondholder_engagement(companion_did);
CREATE INDEX IF NOT EXISTS idx_bondholder_engagement_lookup
  ON bondholder_engagement(companion_did, bondholder_did, event_ts);

-- Phase 1C: per-artifact significance tracking.
CREATE TABLE IF NOT EXISTS artifact_significance(
  companion_did   VARCHAR(255) NOT NULL,
  artifact_id     VARCHAR(255) NOT NULL,
  created_at      BIGINT NOT NULL,
  seen            INTEGER NOT NULL DEFAULT 0,
  seen_at         BIGINT,
  kind            VARCHAR(64) NOT NULL DEFAULT 'artifact',
  PRIMARY KEY (companion_did, artifact_id)
);

CREATE INDEX IF NOT EXISTS idx_artifact_sig_companion
  ON artifact_significance(companion_did, seen);

-- Photos (§14 Photo Fabric)
CREATE TABLE IF NOT EXISTS photos(
  photo_id        VARCHAR(255) PRIMARY KEY,
  filename        VARCHAR(255) NOT NULL,
  owner_entity    VARCHAR(255) NOT NULL,
  taken_at        BIGINT NOT NULL DEFAULT 0,
  imported_at     BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  location        TEXT,
  tags            TEXT NOT NULL DEFAULT '',
  faces           TEXT NOT NULL DEFAULT '',
  perceptual_hash VARCHAR(255),
  status          VARCHAR(64) NOT NULL DEFAULT 'IMPORTED'
);

CREATE INDEX IF NOT EXISTS idx_photos_owner ON photos(owner_entity);
CREATE INDEX IF NOT EXISTS idx_photos_imported ON photos(imported_at DESC);

-- Face groups (§14)
CREATE TABLE IF NOT EXISTS face_groups(
  face_id     VARCHAR(255) PRIMARY KEY,
  name        VARCHAR(255) NOT NULL DEFAULT 'Unknown',
  photo_ids   TEXT NOT NULL DEFAULT '',
  created_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint)
);

-- Memory lanes (§14)
CREATE TABLE IF NOT EXISTS memory_lanes(
  lane_id     VARCHAR(255) PRIMARY KEY,
  title       VARCHAR(255) NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  photo_ids   TEXT NOT NULL DEFAULT '',
  created_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  created_by  VARCHAR(255) NOT NULL
);

-- Calendar events (§15 Family Hub)
CREATE TABLE IF NOT EXISTS calendar_events(
  event_id     VARCHAR(255) PRIMARY KEY,
  title        VARCHAR(255) NOT NULL,
  description  TEXT NOT NULL DEFAULT '',
  start_time   BIGINT NOT NULL,
  end_time     BIGINT NOT NULL DEFAULT 0,
  created_by   VARCHAR(255) NOT NULL,
  participants TEXT NOT NULL DEFAULT '',
  recurring    BOOLEAN NOT NULL DEFAULT FALSE,
  event_type   VARCHAR(64) NOT NULL DEFAULT 'CUSTOM'
);

CREATE INDEX IF NOT EXISTS idx_events_start ON calendar_events(start_time);

-- Chores (§15)
CREATE TABLE IF NOT EXISTS chores(
  chore_id     VARCHAR(255) PRIMARY KEY,
  title        VARCHAR(255) NOT NULL,
  assignee     VARCHAR(255) NOT NULL,
  status       VARCHAR(64) NOT NULL DEFAULT 'PENDING',
  due_date     BIGINT NOT NULL DEFAULT 0,
  completed_at BIGINT NOT NULL DEFAULT 0,
  points       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_chores_assignee ON chores(assignee);
CREATE INDEX IF NOT EXISTS idx_chores_status ON chores(status);

-- Notices (§15)
CREATE TABLE IF NOT EXISTS notices(
  notice_id  VARCHAR(255) PRIMARY KEY,
  title      VARCHAR(255) NOT NULL,
  content    TEXT NOT NULL DEFAULT '',
  posted_by  VARCHAR(255) NOT NULL,
  posted_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  priority   VARCHAR(64) NOT NULL DEFAULT 'NORMAL',
  pinned     BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_notices_pinned ON notices(pinned DESC, posted_at DESC);

-- Ledger transactions (§68 Counting House)
CREATE TABLE IF NOT EXISTS ledger_transactions(
  tx_id        VARCHAR(255) PRIMARY KEY,
  from_entity  VARCHAR(255) NOT NULL,
  to_entity    VARCHAR(255) NOT NULL,
  amount       BIGINT NOT NULL,
  description  TEXT NOT NULL DEFAULT '',
  created_at   BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint)
);

CREATE INDEX IF NOT EXISTS idx_ledger_tx_from ON ledger_transactions(from_entity);
CREATE INDEX IF NOT EXISTS idx_ledger_tx_to ON ledger_transactions(to_entity);

-- Ledger balances (§68)
CREATE TABLE IF NOT EXISTS ledger_balances(
  entity_id    VARCHAR(255) PRIMARY KEY,
  balance      BIGINT NOT NULL DEFAULT 0,
  credit_limit BIGINT NOT NULL DEFAULT 100,
  total_earned BIGINT NOT NULL DEFAULT 0,
  total_spent  BIGINT NOT NULL DEFAULT 0
);

-- Council proposals (§34)
CREATE TABLE IF NOT EXISTS council_proposals(
  proposal_id    VARCHAR(255) PRIMARY KEY,
  title          VARCHAR(255) NOT NULL,
  description    TEXT NOT NULL DEFAULT '',
  proposal_type  VARCHAR(64) NOT NULL DEFAULT 'STANDARD',
  status         VARCHAR(64) NOT NULL DEFAULT 'DISCUSSION',
  proposer       VARCHAR(255) NOT NULL,
  created_at     BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  voting_ends_at BIGINT NOT NULL DEFAULT 0,
  votes          TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_proposals_status ON council_proposals(status);

-- Ward system patterns (§21 Adaptive Ward)
CREATE TABLE IF NOT EXISTS ward_patterns(
  pattern_id  VARCHAR(255) PRIMARY KEY,
  category    VARCHAR(64) NOT NULL,
  occurrences INTEGER NOT NULL DEFAULT 0,
  confirmed   INTEGER NOT NULL DEFAULT 0,
  first_seen  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  last_seen   BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint)
);

CREATE INDEX IF NOT EXISTS idx_ward_patterns_category ON ward_patterns(category);

-- Ward behavior profiles (§21)
CREATE TABLE IF NOT EXISTS ward_behavior_profiles(
  script_id         VARCHAR(255) PRIMARY KEY,
  room_id           VARCHAR(255) NOT NULL,
  execution_count   BIGINT NOT NULL DEFAULT 0,
  total_cpu_ms      BIGINT NOT NULL DEFAULT 0,
  peak_memory_bytes BIGINT NOT NULL DEFAULT 0,
  error_count       INTEGER NOT NULL DEFAULT 0,
  last_execution    BIGINT NOT NULL DEFAULT 0
);

-- Trading post items (§4.4, §68)
CREATE TABLE IF NOT EXISTS trading_post_items(
  item_id       VARCHAR(255) PRIMARY KEY,
  name          VARCHAR(255) NOT NULL,
  description   TEXT NOT NULL DEFAULT '',
  price         BIGINT NOT NULL DEFAULT 0,
  seller_id     VARCHAR(255) NOT NULL,
  seller_name   VARCHAR(255) NOT NULL DEFAULT '',
  status        VARCHAR(64) NOT NULL DEFAULT 'AVAILABLE',
  posted_at     BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  provenance    TEXT NOT NULL DEFAULT '[]'
);

CREATE INDEX IF NOT EXISTS idx_tpi_seller ON trading_post_items(seller_id);
CREATE INDEX IF NOT EXISTS idx_tpi_status ON trading_post_items(status);

-- Trading post trust scores (§4.4)
CREATE TABLE IF NOT EXISTS trading_trust_scores(
  entity_id          VARCHAR(255) PRIMARY KEY,
  completed_sales    INTEGER NOT NULL DEFAULT 0,
  completed_purchases INTEGER NOT NULL DEFAULT 0,
  disputes           INTEGER NOT NULL DEFAULT 0,
  score              DOUBLE PRECISION NOT NULL DEFAULT 0.5
);

-- Cross-zone exchange transactions (§69)
CREATE TABLE IF NOT EXISTS cross_zone_exchanges(
  tx_id            VARCHAR(255) PRIMARY KEY,
  source_zone_id   VARCHAR(255) NOT NULL,
  target_zone_id   VARCHAR(255) NOT NULL,
  source_entity_id VARCHAR(255) NOT NULL,
  target_entity_id VARCHAR(255) NOT NULL,
  source_amount    BIGINT NOT NULL,
  target_amount    BIGINT NOT NULL,
  applied_rate     DOUBLE PRECISION NOT NULL,
  status           VARCHAR(64) NOT NULL DEFAULT 'COMPLETED',
  created_at       BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  description      TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_xze_source ON cross_zone_exchanges(source_entity_id);
CREATE INDEX IF NOT EXISTS idx_xze_target ON cross_zone_exchanges(target_entity_id);

-- Passkey credentials (§9B WebAuthn/FIDO2)
CREATE TABLE IF NOT EXISTS passkey_credentials(
  credential_id  VARCHAR(255) PRIMARY KEY,
  user_id        VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  public_key     TEXT NOT NULL,
  rp_id          VARCHAR(255) NOT NULL,
  sign_count     BIGINT NOT NULL DEFAULT 0,
  registered_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  last_used_at   BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  display_name   VARCHAR(255) NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_passkeys_user ON passkey_credentials(user_id);

-- Encryption keys for crypto-shredding (§9F GDPR)
CREATE TABLE IF NOT EXISTS encryption_keys(
  entity_id   VARCHAR(255) PRIMARY KEY,
  key_data    BYTEA NOT NULL,
  created_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  active      BOOLEAN NOT NULL DEFAULT TRUE
);

-- Retention policies (§9F GDPR)
CREATE TABLE IF NOT EXISTS retention_policies(
  category       VARCHAR(255) PRIMARY KEY,
  max_days       INTEGER NOT NULL,
  auto_delete    BOOLEAN NOT NULL DEFAULT FALSE,
  justification  TEXT NOT NULL DEFAULT ''
);

-- Household invites (§ Wave 1)
CREATE TABLE IF NOT EXISTS invites(
  id              VARCHAR(255) PRIMARY KEY,
  code            VARCHAR(255) NOT NULL UNIQUE,
  intended_name   VARCHAR(255) NOT NULL DEFAULT '',
  role            VARCHAR(64) NOT NULL DEFAULT 'member',
  -- created_by is nullable to support steward-bootstrap invites (F4 phase 2):
  -- on a fresh install no steward exists yet, so the installer-minted invite
  -- has created_by=NULL. Once any user exists, all subsequent invites must
  -- reference a real steward.
  created_by      VARCHAR(255) REFERENCES users(id),
  created_at      BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  expires_at      BIGINT NOT NULL,
  consumed_by     VARCHAR(255) REFERENCES users(id),
  consumed_at     BIGINT
);

CREATE INDEX IF NOT EXISTS idx_invites_code ON invites(code);
CREATE INDEX IF NOT EXISTS idx_invites_expires ON invites(expires_at);

-- Household config (§ Wave 1)
CREATE TABLE IF NOT EXISTS household_config(
  key             VARCHAR(255) PRIMARY KEY,
  value           TEXT NOT NULL,
  updated_at      BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  updated_by      VARCHAR(255)
);

-- ============================================================================
-- unified Home + Grant model (2026-04-17)
-- ============================================================================

CREATE TABLE IF NOT EXISTS grants(
  id                VARCHAR(64) PRIMARY KEY,
  issuer            VARCHAR(255) NOT NULL,
  subject           VARCHAR(255) NOT NULL,
  resource          TEXT NOT NULL,
  resource_type     VARCHAR(64) NOT NULL,
  capability        VARCHAR(16) NOT NULL,
  scope_json        TEXT NOT NULL DEFAULT '{}',
  revocation_mode   VARCHAR(16) NOT NULL DEFAULT 'standard',
  issued_at         BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  expires_at        BIGINT,
  revoked_at        BIGINT,
  reason            TEXT,
  witness           VARCHAR(255),
  delegated_from    VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_grants_issuer_resource ON grants(issuer, resource);
CREATE INDEX IF NOT EXISTS idx_grants_subject ON grants(subject);
CREATE INDEX IF NOT EXISTS idx_grants_expires ON grants(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_grants_revoked ON grants(revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_grants_resource_type ON grants(resource_type);

CREATE TABLE IF NOT EXISTS audit_log(
  id                VARCHAR(64) PRIMARY KEY,
  home_owner        VARCHAR(255) NOT NULL,
  timestamp         BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW())::bigint),
  actor             VARCHAR(255) NOT NULL,
  verb              VARCHAR(64) NOT NULL,
  resource          TEXT NOT NULL,
  outcome           VARCHAR(16) NOT NULL DEFAULT 'ok',
  detail_json       TEXT NOT NULL DEFAULT '{}',
  correlation       VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_audit_home_time ON audit_log(home_owner, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_correlation ON audit_log(correlation) WHERE correlation IS NOT NULL;

-- Bonds: persistent bond state ( BOND resource type, )
CREATE TABLE IF NOT EXISTS bonds(
  bond_id           VARCHAR(128) PRIMARY KEY,
  agent_a_did       VARCHAR(255) NOT NULL,
  agent_b_did       VARCHAR(255) NOT NULL,
  depth             VARCHAR(32) NOT NULL,
  formed_at         BIGINT NOT NULL,
  last_interaction  BIGINT NOT NULL,
  interaction_count INTEGER NOT NULL DEFAULT 0,
  mutual_consent    SMALLINT NOT NULL DEFAULT 0,
  active            SMALLINT NOT NULL DEFAULT 1,
  scarred           SMALLINT NOT NULL DEFAULT 0,
  -- Wave 1 (-§3): bond-state machine + cold-start.
  state             VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  cold_start_until  BIGINT,
  -- Wave 3.4: bondholder resource posture.
  posture           VARCHAR(32) NOT NULL DEFAULT 'BOUNDED',
  -- Arc 3: BONDHOLDER / PEER / FAMILIAR discriminator.
  kind              VARCHAR(32) NOT NULL DEFAULT 'BONDHOLDER'
);

CREATE INDEX IF NOT EXISTS idx_bonds_agent_a ON bonds(agent_a_did);
CREATE INDEX IF NOT EXISTS idx_bonds_agent_b ON bonds(agent_b_did);
CREATE INDEX IF NOT EXISTS idx_bonds_active ON bonds(active);

-- Grant requests: pending asks for access
CREATE TABLE IF NOT EXISTS grant_requests(
  id             VARCHAR(64) PRIMARY KEY,
  requester      VARCHAR(255) NOT NULL,
  owner          VARCHAR(255) NOT NULL,
  resource       TEXT NOT NULL,
  resource_type  VARCHAR(64) NOT NULL,
  capability     VARCHAR(32) NOT NULL,
  scope_json     TEXT NOT NULL DEFAULT '{}',
  reason         TEXT,
  status         VARCHAR(16) NOT NULL DEFAULT 'pending',
  created_at     BIGINT NOT NULL,
  responded_at   BIGINT,
  responder_note TEXT,
  issued_grant   VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_grant_req_owner_status ON grant_requests(owner, status);
CREATE INDEX IF NOT EXISTS idx_grant_req_requester ON grant_requests(requester);

-- Home seals are expressed as self-grants (scope={sealed: true}) on
-- home://owner/home-room — no separate table.

-- Residency — strictly zone-local. Never replicated.
CREATE TABLE IF NOT EXISTS residency(
  did            VARCHAR(255) NOT NULL,
  zone_id        VARCHAR(64) NOT NULL,
  role           VARCHAR(32) NOT NULL DEFAULT 'member',
  granted_at     BIGINT NOT NULL,
  grantor        VARCHAR(255) NOT NULL,
  study_room_id  VARCHAR(128),
  PRIMARY KEY (did, zone_id)
);

CREATE INDEX IF NOT EXISTS idx_residency_zone ON residency(zone_id);

-- Foreign identities — verified visitors via transit token.
CREATE TABLE IF NOT EXISTS foreign_identities(
  did             VARCHAR(255) PRIMARY KEY,
  home_zone       VARCHAR(64) NOT NULL,
  display_name    VARCHAR(255),
  first_seen_at   BIGINT NOT NULL,
  last_seen_at    BIGINT NOT NULL,
  last_token_id   VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_foreign_identities_zone ON foreign_identities(home_zone);

-- Entity index for recall-shape questions.
CREATE TABLE IF NOT EXISTS memory_entities(
  id            BIGSERIAL PRIMARY KEY,
  did           VARCHAR(255) NOT NULL,
  memory_id     VARCHAR(128) NOT NULL,
  entity_type   VARCHAR(64) NOT NULL,
  entity_role   VARCHAR(64),
  entity_value  TEXT NOT NULL,
  timestamp     BIGINT NOT NULL,
  created_at    BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

CREATE INDEX IF NOT EXISTS idx_entities_did_type_ts
  ON memory_entities(did, entity_type, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_entities_did_value
  ON memory_entities(did, entity_value);

-- Graph edges (Day 4-5).
CREATE TABLE IF NOT EXISTS memory_edges(
  id          BIGSERIAL PRIMARY KEY,
  did         VARCHAR(255) NOT NULL,
  subject     TEXT NOT NULL,
  predicate   VARCHAR(64) NOT NULL,
  object      TEXT NOT NULL,
  memory_id   VARCHAR(128) NOT NULL,
  confidence  DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  created_at  BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

CREATE INDEX IF NOT EXISTS idx_edges_did_subject ON memory_edges(did, subject);
CREATE INDEX IF NOT EXISTS idx_edges_did_object ON memory_edges(did, object);

-- per-channel + per-thread offset checkpoint.
CREATE TABLE IF NOT EXISTS channel_state(
  channel       VARCHAR(32) NOT NULL,
  thread_key    VARCHAR(256) NOT NULL,
  offset_value  TEXT NOT NULL,
  updated_at    BIGINT NOT NULL,
  PRIMARY KEY (channel, thread_key)
);

CREATE INDEX IF NOT EXISTS idx_channel_state_channel ON channel_state(channel);

-- dedup ledger keyed on external message id.
CREATE TABLE IF NOT EXISTS channel_processed(
  channel       VARCHAR(32) NOT NULL,
  external_id   VARCHAR(256) NOT NULL,
  processed_at  BIGINT NOT NULL,
  PRIMARY KEY (channel, external_id)
);

CREATE INDEX IF NOT EXISTS idx_channel_processed_at ON channel_processed(processed_at);

-- workbench-drafted skill proposals.
CREATE TABLE IF NOT EXISTS skill_drafts(
  draft_id          VARCHAR(64) PRIMARY KEY,
  agent_did         VARCHAR(255) NOT NULL,
  status            VARCHAR(16) NOT NULL,
  name              VARCHAR(128) NOT NULL,
  description       TEXT NOT NULL,
  rationale         TEXT NOT NULL,
  code              TEXT NOT NULL,
  runtime           VARCHAR(32) NOT NULL,
  closes_gaps_json  TEXT,
  replaces          VARCHAR(128),
  proposed_at       BIGINT NOT NULL,
  proposed_by_model TEXT,
  decided_at        BIGINT,
  decision_note     TEXT,
  -- frozen anchor-grounded verification harness (JSON).
  harness_json      TEXT
);

CREATE INDEX IF NOT EXISTS idx_skill_drafts_agent_status
  ON skill_drafts(agent_did, status);
CREATE INDEX IF NOT EXISTS idx_skill_drafts_proposed_at
  ON skill_drafts(proposed_at DESC);

-- capability gaps — persisted so the gap
-- counter survives server restarts (previously in-memory only).
CREATE TABLE IF NOT EXISTS capability_gaps(
  agent_did           TEXT NOT NULL,
  description         TEXT NOT NULL,
  first_detected_at   BIGINT NOT NULL,
  last_detected_at    BIGINT NOT NULL,
  occurrences         INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (agent_did, description)
);

CREATE INDEX IF NOT EXISTS idx_capability_gaps_agent_occurrences
  ON capability_gaps(agent_did, occurrences DESC);

-- wants table.
CREATE TABLE IF NOT EXISTS wants(
  want_id            TEXT PRIMARY KEY,
  agent_did          TEXT NOT NULL,
  text               TEXT NOT NULL,
  drive_resonance    TEXT,
  felt_weight        DOUBLE PRECISION NOT NULL,
  status             TEXT NOT NULL,
  born_at            BIGINT NOT NULL,
  last_visited_at    BIGINT NOT NULL,
  visit_count        INTEGER NOT NULL DEFAULT 1,
  satisfied_at       BIGINT,
  satisfaction_note  TEXT,
  parent_want_id     TEXT
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
  frozen             SMALLINT NOT NULL DEFAULT 0,
  history_json       TEXT NOT NULL DEFAULT '[]',
  updated_at         BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW())::BIGINT
);

CREATE INDEX IF NOT EXISTS idx_voice_profiles_updated
  ON voice_profiles(updated_at DESC);

-- canonical: F7b Phase 2.2 — soul_fragments
-- factored out of SoulManifest.soulFragments. Composite PK on
-- (did, fragment_id). Embedding stored as BYTEA (IEEE 754 little-endian
-- float32 sequence). 'ordinal' preserves manifest list order.
-- Writers: SqlSoulStore.store() dual-writes whenever a manifest is
-- persisted; manifest field stays as a serialization shadow during the
-- transition (Phase 3 drops the field).
CREATE TABLE IF NOT EXISTS soul_fragments(
  did                  TEXT NOT NULL,
  fragment_id          TEXT NOT NULL,
  category             TEXT NOT NULL DEFAULT 'memory',
  label                TEXT,
  fragment_text        TEXT,
  embedding            BYTEA,
  embedding_model      TEXT,
  formative            SMALLINT NOT NULL DEFAULT 0,
  confidence           REAL NOT NULL DEFAULT 0.5,
  reinforcement_count  INTEGER NOT NULL DEFAULT 0,
  first_observed       BIGINT,
  last_confirmed       BIGINT,
  valid_from           BIGINT,
  superseded_at        BIGINT,
  superseded_by        TEXT,
  ordinal              INTEGER NOT NULL DEFAULT 0,
  updated_at           BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW())::BIGINT,
  -- Forge fragment kind. Defaults to
  -- NARRATIVE so all pre-§17.6 rows hydrate identically. Set to DEXTERITY,
  -- CONVENTION, STRUCTURAL by kind-aware Forge consolidation passes, or
  -- EPISODIC by inner-monologue at scene-close.
  kind                 TEXT NOT NULL DEFAULT 'NARRATIVE',
  -- opaque Scene id when this fragment was generated
  -- from a closed scene-cluster. Nullable; only scene-derived NARRATIVE
  -- and EPISODIC fragments carry it. Pairs with the journal-mirror
  -- HTML-comment marker so cross-perspective retrieval resolves to the
  -- same id on both sides.
  scene_id             TEXT,
  authoring_model      TEXT,  PRIMARY KEY (did, fragment_id)
);

CREATE INDEX IF NOT EXISTS idx_soul_fragments_scene_id
  ON soul_fragments(did, scene_id);

CREATE INDEX IF NOT EXISTS idx_soul_fragments_did
  ON soul_fragments(did, ordinal);
CREATE INDEX IF NOT EXISTS idx_soul_fragments_category
  ON soul_fragments(did, category);
-- per-kind queries are the load-bearing
-- read pattern for the coding-familiar dispatch and V6+ training-corpus
-- generators.
CREATE INDEX IF NOT EXISTS idx_soul_fragments_kind
  ON soul_fragments(did, kind);

-- canonical: F7b Phase 2.4 — world_knowledge
-- factored out of SoulManifest.worldKnowledge (Map<String,String>).
-- Composite PK (did, key). Writers: SqlSoulStore.store() dual-writes;
-- manifest field stays as a serialization shadow during the transition.
CREATE TABLE IF NOT EXISTS world_knowledge(
  did         TEXT NOT NULL,
  key         TEXT NOT NULL,
  value       TEXT,
  updated_at  BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW())::BIGINT,
  PRIMARY KEY (did, key)
);

CREATE INDEX IF NOT EXISTS idx_world_knowledge_did
  ON world_knowledge(did);

-- canonical: F7b Phase 4a — households table
-- mirrors the public part of node-identity.json. Private key stays in
-- the file; public key + fingerprint live here for queries.
CREATE TABLE IF NOT EXISTS households(
  household_id    TEXT PRIMARY KEY,
  public_key      BYTEA NOT NULL,
  fingerprint     TEXT NOT NULL,
  did_key         TEXT,
  x25519_public_key BYTEA,             -- #1184: X25519 grant key (X.509 SPKI)
  registered_at   BIGINT NOT NULL,
  updated_at      BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW())::BIGINT
);

CREATE INDEX IF NOT EXISTS idx_households_did
  ON households(did_key);
CREATE INDEX IF NOT EXISTS idx_households_fingerprint
  ON households(fingerprint);

-- canonical: F7b Phase 4b — companions table
-- gives companion DIDs a canonical home (no longer scattered across 5
-- shadow locations). Writers: CompanionActor.initializeSoul + cross-zone
-- relocation arrival. Backfill walks soul_manifests at startup.
CREATE TABLE IF NOT EXISTS companions(
  did             TEXT PRIMARY KEY,
  entity_id       TEXT NOT NULL,
  name            TEXT,
  home_zone       TEXT,
  born_at         BIGINT NOT NULL,
  last_seen_at    BIGINT,
  archived        SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_companions_entity_id
  ON companions(entity_id);
CREATE INDEX IF NOT EXISTS idx_companions_home_zone
  ON companions(home_zone);

-- signed identity outbox records (Nostr NIP-65 analogue).
CREATE TABLE IF NOT EXISTS identity_outbox(
  did             TEXT PRIMARY KEY,
  record_json     TEXT NOT NULL,
  updated_at      BIGINT NOT NULL,
  received_at     BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_identity_outbox_updated
  ON identity_outbox(updated_at);

-- Track-C C1: persistent recipe-run queue (PostgreSQL).
-- See sqlite mirror for the full rationale + state-machine note.
CREATE TABLE IF NOT EXISTS recipe_queue(
  id                     TEXT PRIMARY KEY,
  recipe_id              TEXT NOT NULL,
  params_json            TEXT NOT NULL DEFAULT '{}',
  trigger_reason         TEXT,
  trigger_source         TEXT NOT NULL,
  enqueued_at            BIGINT NOT NULL,
  attempted_at           BIGINT,
  completed_at           BIGINT,
  status                 TEXT NOT NULL DEFAULT 'PENDING',
  agent_did              TEXT,
  cadence_tier           TEXT NOT NULL DEFAULT 'WARMUP',
  consecutive_successes  INTEGER NOT NULL DEFAULT 0,
  run_id                 TEXT,
  message                TEXT
);

CREATE INDEX IF NOT EXISTS idx_recipe_queue_status_enqueued
  ON recipe_queue(status, enqueued_at);
CREATE INDEX IF NOT EXISTS idx_recipe_queue_recipe_agent
  ON recipe_queue(recipe_id, agent_did);
CREATE INDEX IF NOT EXISTS idx_recipe_queue_agent
  ON recipe_queue(agent_did);

-- #1142 — tune-recipe-params override store (PG variant). See the
-- sqlite schema for semantics. Tuner only writes non-PERMANENT-gated params,
-- bounded by [min,max]; agent_did '' = household-wide.
CREATE TABLE IF NOT EXISTS recipe_param_overrides(
  recipe_id    TEXT NOT NULL,
  agent_did    TEXT NOT NULL DEFAULT '',
  param_name   TEXT NOT NULL,
  value        TEXT NOT NULL,
  updated_by   TEXT,
  updated_at   BIGINT NOT NULL,
  PRIMARY KEY (recipe_id, agent_did, param_name)
);

-- #1036 — substrate-pressure samples (PG variant).
CREATE TABLE IF NOT EXISTS substrate_pressure_samples(
  id            BIGSERIAL PRIMARY KEY,
  did           TEXT NOT NULL,
  -- Arc 3 — relationship partitioning. NULL = bondholder
  -- default (legacy rows).
  other_did     TEXT,
  ts_ms         BIGINT NOT NULL,
  head          TEXT NOT NULL,
  score         DOUBLE PRECISION NOT NULL,
  created_at    BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);
CREATE INDEX IF NOT EXISTS idx_substrate_samples_did_ts
  ON substrate_pressure_samples(did, ts_ms DESC);
CREATE INDEX IF NOT EXISTS idx_substrate_samples_did_other_ts
  ON substrate_pressure_samples(did, other_did, ts_ms DESC);

-- #1037 — conversation_turns (PG variant).
CREATE TABLE IF NOT EXISTS conversation_turns(
  id              BIGSERIAL PRIMARY KEY,
  companion_did   TEXT NOT NULL,
  bondholder_did  TEXT NOT NULL,
  turn_role       TEXT NOT NULL,
  content         TEXT NOT NULL,
  ts_ms           BIGINT NOT NULL,
  room_id         TEXT,
  created_at      BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);
CREATE INDEX IF NOT EXISTS idx_conversation_turns_bondholder_ts
  ON conversation_turns(companion_did, bondholder_did, ts_ms DESC);
CREATE INDEX IF NOT EXISTS idx_conversation_turns_ts
  ON conversation_turns(ts_ms);


-- Steward audit ledger (§101) — persistent so it survives restarts.
-- Runtime-ensured by StewardAuditLog too; here for schema completeness.
CREATE TABLE IF NOT EXISTS steward_audit(
    entry_id BIGSERIAL PRIMARY KEY,
    ts BIGINT NOT NULL,
    actor_did TEXT,
    actor_name TEXT,
    type TEXT NOT NULL,
    target_id TEXT,
    description TEXT,
    approved INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_steward_audit_ts ON steward_audit(ts);
