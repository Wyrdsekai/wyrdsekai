// Tier 5 web.post sample.
//
// A scrapbook clipper that posts the title + URL of the current page to a
// configured webhook (e.g. an Inbox automation). Demonstrates:
//   * Declared external_domains allowlist gating (only the configured webhook
//     domain may be reached)
//   * Tier 5 web.post capability + steward consent
//   * Posting JSON with custom headers
//
// Manifest fields used:
//   capabilities: ["web.post"]
//   external_domains: ["hooks.example.com", "*.zapier.com"]
//   rate_limits.web.post: 10/min, 60/hour, 200/day
exports.manifest = {
  name: "web_clipper",
  version: "1.0.0",
  description: "Post titled web links to a configured webhook receiver.",
  author: "did:wyrd:system",
  capabilities: ["web.post"],
  embodiment: {
    silent: true,
    reason: "outbound webhook poster, no in-room body"
  },
  external_domains: ["hooks.example.com", "*.zapier.com"],
  rate_limits: {
    "web.post": { per_minute: 10, per_hour: 60, per_day: 200 }
  },
  data_sensitivity: "medium",
  // Items-as-tools contract — invoke() reads structured params (url
  // required, plus title/note/endpoint), not the args string; a bare
  // invoke explains that a url is needed.
  commands: [
    { label: "Clip a link to the webhook (needs a url)", args: "" }
  ],
  // The schema the MODEL sees. This item reads `url` and never reads `query`, so
  // under the old one-size-fits-all `query` slot the model had no way to hand it a
  // url — every call failed with "url is required". Same defect as morning_briefing.
  params: [
    { name: "url", type: "string", required: true,
      description: "The link to clip, e.g. \"https://example.com/article\"." },
    { name: "title", type: "string", required: false,
      description: "Title for the clipped link." },
    { name: "note", type: "string", required: false,
      description: "A short note to store alongside the link." }
  ]
};

function invoke(params) {
  var endpoint = params.endpoint || "https://hooks.example.com/webhook";
  var title = params.title || "Untitled";
  var url = params.url || params.link || "";
  var note = params.note || "";

  if (!url) {
    return { ok: false, error: "url is required" };
  }

  // Optional: introspect what domains we can reach. Useful for diagnostics.
  var allowed = world.web.allowed_domains();

  var payload = {
    title: title,
    url: url,
    note: note,
    clipped_at: world.time.iso(),
    via: "web_clipper:" + world.self.did()
  };

  var response = world.web.post(endpoint, world.json.stringify(payload), {
    contentType: "application/json",
    headers: { "X-Clipped-By": "wyrd-web-clipper/1" }
  });

  if (response.error) {
    return {
      ok: false,
      error: response.error,
      message: response.message || "post failed",
      allowed_domains: allowed
    };
  }

  return {
    ok: true,
    status: response.status,
    summary: "Clipped: " + title
  };
}
