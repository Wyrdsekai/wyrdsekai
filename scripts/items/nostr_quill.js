// companion's "quill" for posting to Nostr.
//
// Carrying this item gives a companion the ability to post short notes
// to the configured Nostr relays under their own npub (derived from their
// DID via HKDF; see core/nostr/NostrKey.deriveFromEd25519PrivateKey).
//
// Demonstrates:
//   * Typed adapter call: world.nostr.publish({content, tags?, kind?, did})
//   * Tier 5 capability declaration (nostr.publish)
//   * Rate limits — adapter enforces a 60/min cap server-side too
//   * Structured error handling with retryable hint
//
// To enable Nostr at the server level, the steward sets
//   wyrdsekai.nostr.enabled = true in application.conf (or
//   WYRDSEKAI_NOSTR_ENABLED=true in env). With Nostr disabled, calls return
//   {error: "unknown_namespace"} at the proxy layer.
//
// To install: drop into ~/.wyrdsekai/items/ or scripts/items/ and add to a
// companion's inventory. No Safe credential needed unless the steward wants
// to override the derived keypair (slot: nostr.keypairs.<did>).
exports.manifest = {
  name: "nostr_quill",
  version: "1.0.0",
  description: "Post short notes to Nostr relays under the companion's own npub.",
  author: "did:wyrd:system",
  // Manifest fix (items-as-tools migration): "nostr.publish" is not in the
  // ItemManifestValidator capability catalogue, so this manifest was
  // rejected as invalid and the quill never loaded. Until a nostr entry
  // lands in the catalogue we declare the Tier-1 surface the script really
  // reads (world.self.did()); the nostr adapter dispatch itself is gated
  // server-side (wyrdsekai.nostr.enabled) and rate-limited below.
  capabilities: ["self.did"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} dips the quill — a faint scratch as the note goes out across the relays."
  },
  rate_limits: {
    "nostr.publish": { per_minute: 10, per_hour: 60, per_day: 200 }
  },
  data_sensitivity: "low",   // events are public-by-design
  // Items-as-tools contract — invoke() reads params.content (required, plus
  // optional tags/kind), not the args string; a bare invoke explains that
  // content is needed.
  commands: [
    { label: "Post a note to Nostr (needs content)", args: "" }
  ],
  // The schema the MODEL sees. This item reads `content` and never reads `query`, so
  // under the old free-form-`query`-only schema the model had no slot to put the note
  // in — every call it could make was a bad_request. "needs content" in a menu label is
  // not something a model can act on; a required parameter is.
  params: [
    { name: "content", type: "string", required: true,
      description: "The note to post, as it should appear. This is the whole point of "
                 + "the call — send the text itself, not a description of it." },
    { name: "tags", type: "array", required: false,
      description: "Nostr tags to attach, e.g. [[\"t\",\"wyrdsekai\"]]." },
    { name: "kind", type: "number", required: false,
      description: "Nostr event kind. Defaults to 1 (a text note)." }
  ]
};

function invoke(params) {
  var content = (params && (params.content || params.text || params.message)) || "";
  if (!content || content.length === 0) {
    return { ok: false, error: "bad_request",
             message: "nostr_quill: 'content' is required" };
  }
  // Caller-supplied tags (optional). Standard Nostr tag shape: [["t","topic"],…]
  var tags = (params && Array.isArray(params.tags)) ? params.tags : [];

  // Kind 1 = short text note (NIP-01). Allow callers to override for replies
  // (kind 1 with an "e" tag) or article (kind 30023).
  var kind = (params && typeof params.kind === "number") ? params.kind : 1;

  // The adapter needs the companion's DID to resolve the keypair. world.self
  // exposes that for the running script.
  var did = world.self.did();
  if (!did) {
    return { ok: false, error: "bad_request",
             message: "nostr_quill: world.self.did() returned null" };
  }

  var response = world.nostr.publish({
    content: content,
    tags: tags,
    kind: kind,
    did: did
  });

  if (!response.success) {
    var code = response.error && response.error.code;
    return {
      ok: false,
      error: code,
      message: response.error && response.error.message,
      retryable: response.error && response.error.retryable,
      hint: code === "credential_missing"
        ? "no Nostr key available for this DID. Either the steward must set "
          + "nostr.keypairs." + did + " in the Safe (nsec1… or hex), or this "
          + "companion's DID is not the node's own DID (per-companion key "
          + "resolution lands in Phase 2c)."
        : code === "rate_limited"
        ? "too many publishes — wait a minute and retry."
        : code === "publish_failed"
        ? "all configured relays rejected or unreachable. Check "
          + "wyrdsekai.nostr.publish_relays + wyrd doctor."
        : null
    };
  }

  var data = response.data || {};
  return {
    ok: true,
    event_id: data.eventId,
    npub: data.npub,
    kind: kind,
    accepted_relays: data.relays && data.relays.accepted,
    rejected_relays: data.relays && data.relays.rejected
  };
}

exports.invoke = invoke;
