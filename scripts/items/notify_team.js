// / Phase O — Slack outbound sample.
//
// A team-status item that posts a project update to a configured Slack
// channel. Demonstrates:
//   * Typed adapter call: world.slack.post_message({channel, text, opts})
//   * Tier 5 capability declaration (slack.post)
//   * Credential lookup mediated by the Safe (the script never sees the
//     bot token — it lives in slack.bot_token)
//   * Structured error handling with retryable hint
//
// Manifest fields used:
//   capabilities: ["slack.post"]
//   rate_limits.slack.post: 5/min, 30/hour, 100/day
//
// To install: drop into ~/.wyrdsekai/items/ or scripts/items/ and ensure
// slack.bot_token is in your Safe (a Slack bot OAuth token, xoxb-…).
exports.manifest = {
  name: "notify_team",
  version: "1.0.0",
  description: "Post a project status update to a Slack channel.",
  author: "did:wyrd:system",
  capabilities: ["slack.post"],
  embodiment: {
    silent: true,
    reason: "outbound Slack message, no in-room body"
  },
  rate_limits: {
    "slack.post": { per_minute: 5, per_hour: 30, per_day: 100 }
  },
  data_sensitivity: "medium",
  // Items-as-tools contract — invoke() reads structured params (channel,
  // headline, body, threadTs), not the args string; the bare entry posts
  // the default "Status update" headline to #general.
  commands: [
    { label: "Post a status update to Slack", args: "" }
  ],
  // Optional: the script defaults the channel, and a bare call posts a status line.
  // `headline` is what makes the post worth reading — name it whenever you post.
  params: [
    { name: "headline", type: "string", required: false,
      description: "The one-line message to post. This is the substance of the notification." },
    { name: "body", type: "string", required: false,
      description: "Longer detail beneath the headline." },
    { name: "channel", type: "string", required: false,
      description: "Channel to post to, e.g. \"#general\" (the default)." },
    { name: "threadTs", type: "string", required: false,
      description: "Reply into an existing thread instead of posting a new message." }
  ]
};

function invoke(params) {
  var channel = params.channel || "#general";
  var headline = params.headline || "Status update";
  var body = params.body || "";
  var threadTs = params.threadTs || null;

  var when = world.time.iso();
  var sender = world.self.name() || world.self.did();

  var text = ":memo: *" + headline + "*\n"
    + body
    + "\n_— " + sender + " at " + when + "_";

  var opts = {};
  if (threadTs) {
    opts.threadTs = threadTs;
  }

  var response = world.slack.post_message({
    channel: channel,
    text: text,
    opts: opts
  });

  if (!response.success) {
    return {
      ok: false,
      error: response.error.code,
      message: response.error.message,
      retryable: response.error.retryable,
      hint: response.error.code === "credentials_missing"
        ? "set slack.bot_token in your Safe (xoxb-… bot token)"
        : null
    };
  }

  return {
    ok: true,
    summary: "Posted to " + channel,
    ts: response.data.ts,
    channel: response.data.channel
  };
}
