// Track-C C7 — Recipes Console furnishing (steward Study).
//
// Read-only first cut. Renders the recipe scheduler surface so the
// steward can see what's enrolled, what's queued, what ran lately, and
// where welfare blocks are firing. Pause / resume / force-fire live on
// the `wyrd recipes` CLI (C6); this furnishing is in-world reading.
//
// Verbs:
//   use recipes_console            — full summary (enrollments + last 10 runs)
//   use recipes_console runs       — runs-only (last 10)
//   use recipes_console runs 25    — last N (capped at 100)
//   use recipes_console enrolled   — enrollments only
//   use recipes_console settings   — curated scheduler config (G6 C9 #1017)
//
// Surfaces consumed (wired via ItemWorldApi):
//   world.recipe.enrolled()        — Tier 1, read-only
//   world.recipe.recentRuns(limit) — Tier 1, read-only
//
// Lives in the steward Study only — see StudyProvisioner; bondholder
// Studies do NOT receive this item. The recipe scheduler is a
// household-level concern.
exports.manifest = {
  name: "recipes_console",
  version: "1.0.0",
  description: "Steward console for the recipe scheduler — enrolled recipes, queue depth, last completed runs.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "The recipes console hums softly as {actor} reads its ledgers — enrolment cards, queue tallies, the last runs settling into the margin."
  },
  // Items-as-tools contract — every arg string invoke() actually parses.
  // `runs` accepts an optional numeric limit (1..100, default 10).
  commands: [
    { label: "Read the console (enrollments + recent runs)", args: "" },
    { label: "Recent runs only (last 10)", args: "runs" },
    { label: "Recent runs (last 25)", args: "runs 25" },
    { label: "Enrollments only", args: "enrolled" },
    { label: "Scheduler settings (read-only)", args: "settings" }
  ]
};

function invoke(params) {
  var args = (params && params.args) || (params && params.text) || "";
  var tokens = String(args).trim().toLowerCase().split(/\s+/).filter(function (s) { return s.length > 0; });
  var mode = tokens[0] || "all";
  var limitArg = tokens[1];
  var limit = parseInt(limitArg, 10);
  if (!(limit > 0 && limit <= 100)) limit = 10;

  // G6 C9 (#1017) — curated scheduler-settings view. Reads
  // current values via world.configGet so the steward sees the keys, their
  // current values (env-overridable), and a brief explanation without
  // having to grep WyrdConfig.java or memorise key names. Writes still
  // route through `use scroll set KEY=VALUE` on the Scroll of Settings
  // (the generic key/value path); this view is read-only on purpose so
  // the recipes_console scope stays diagnostic.
  if (mode === "settings") {
    return renderSettings();
  }

  // Honest unknown-arg branch — previously an unrecognized mode rendered an
  // empty narrative; say so and list the real commands instead.
  if (mode !== "all" && mode !== "runs" && mode !== "enrolled") {
    return {
      ok: true,
      mode: mode,
      narrative: "The console doesn't know '" + mode + "'.\n" +
        "Commands:\n" +
        "  use recipes_console            — full summary\n" +
        "  use recipes_console runs       — recent runs (last 10)\n" +
        "  use recipes_console runs 25    — recent runs (last N, max 100)\n" +
        "  use recipes_console enrolled   — enrollments only\n" +
        "  use recipes_console settings   — scheduler settings (read-only)",
      enrollments: [],
      runs: []
    };
  }

  if (typeof world.recipe === "undefined" || !world.recipe.enrolled) {
    return {
      ok: true,
      mode: mode,
      narrative: "(recipe scheduler bridge not yet wired — no data to render)",
      enrollments: [],
      runs: []
    };
  }

  var enrollments = mode === "runs" ? [] : (world.recipe.enrolled() || []);
  var runs = mode === "enrolled" ? [] : (world.recipe.recentRuns(limit) || []);

  var lines = [];
  if (mode === "all" || mode === "enrolled") {
    lines.push("Enrolled recipes (" + enrollments.length + "):");
    if (enrollments.length === 0) {
      lines.push("  (no enrollments — use the steward Study to enroll a recipe)");
    } else {
      for (var i = 0; i < enrollments.length; i++) {
        var e = enrollments[i];
        var did = String(e.agentDid || "*");
        var en  = e.enabled ? "on" : "off";
        var cad = String(e.cadenceTier || "?");
        var succ = String(e.consecutiveSuccesses != null ? e.consecutiveSuccesses : 0);
        var depth = String(e.queueDepth != null ? e.queueDepth : 0);
        var last  = String(e.lastStatus || "-");
        var nextFire = e.nextFireEstimate ? (" next~" + shortTs(e.nextFireEstimate)) : "";
        lines.push("  - " + e.recipeId + " [" + en + "] " +
                   "cadence=" + cad + " streak=" + succ +
                   " queue=" + depth + " last=" + last + nextFire +
                   " did=" + did);
      }
    }
    lines.push("");
  }

  if (mode === "all" || mode === "runs") {
    lines.push("Recent completed runs (" + runs.length + " of last " + limit + "):");
    if (runs.length === 0) {
      lines.push("  (no completed runs yet)");
    } else {
      for (var j = 0; j < runs.length; j++) {
        var r = runs[j];
        var ts = r.completedAt ? shortTs(r.completedAt) : "?";
        var src = String(r.triggerSource || "?");
        var msg = r.message ? trunc(String(r.message), 60) : "";
        lines.push("  " + ts + "  " + r.recipeId +
                   "  status=" + String(r.status || "?") +
                   "  source=" + src +
                   (msg ? "  — " + msg : ""));
      }
    }
  }

  // Welfare blocks v1 — read-only signal of disabled enrollments only.
  // The full scheduler ForceFire / deploy-ceiling-pause surface lands
  // alongside C9 ship-defaults; this is enough for the steward to spot
  // a paused recipe in the console without leaving the world.
  if (mode === "all") {
    var blocks = [];
    for (var k = 0; k < enrollments.length; k++) {
      if (enrollments[k].enabled === false) {
        blocks.push("  - " + enrollments[k].recipeId +
                    " (did=" + (enrollments[k].agentDid || "*") + ") — paused");
      }
    }
    if (blocks.length > 0) {
      lines.push("");
      lines.push("Active welfare blocks:");
      for (var b = 0; b < blocks.length; b++) lines.push(blocks[b]);
    }
  }

  return {
    ok: true,
    mode: mode,
    limit: limit,
    enrollments: enrollments,
    runs: runs,
    narrative: lines.join("\n")
  };
}

// 2026-05-25T08:45:25.182Z → 2026-05-25 08:45
function shortTs(iso) {
  if (typeof iso !== "string" || iso.length < 16) return String(iso);
  return iso.substring(0, 10) + " " + iso.substring(11, 16);
}

function trunc(s, n) {
  if (s.length <= n) return s;
  return s.substring(0, n - 1) + "…";
}

// G6 C9 (#1017) — curated scheduler-settings dashboard.
// Keys mirror WyrdConfig.java schedulerEnabled() / schedulerPollMinutes() /
// schedulerGpuDailyHours() / schedulerMonthlyRunCap() /
// schedulerDeployCeiling() / schedulerGapDetectionEnabled() /
// schedulerGapTicks() / schedulerGapWindowHours(). Each entry carries the
// env-var name (what `wyrd config set` writes), the production default,
// and a one-line description. configGet returns the env-overridden value
// if set, otherwise the conf-file value if set, otherwise null (default).
var SCHEDULER_KEYS = [
  { key: "WYRDSEKAI_RECIPES_SCHEDULER_ENABLED",
    label: "scheduler.enabled",
    deflt: "true",
    desc: "Whether the RecipeScheduler actor boots in this zone." },
  { key: "WYRDSEKAI_RECIPES_POLL_MINUTES",
    label: "scheduler.poll_minutes",
    deflt: "60",
    desc: "How often (minutes) the scheduler drains the queue + ticks cron." },
  { key: "WYRDSEKAI_RECIPES_GPU_DAILY_HOURS",
    label: "welfare.gpu_daily_hours",
    deflt: "6",
    desc: "Daily GPU-time budget for recipe runs (welfare gate b)." },
  { key: "WYRDSEKAI_RECIPES_MONTHLY_CAP",
    label: "welfare.monthly_run_cap",
    deflt: "100",
    desc: "Max recipe runs per calendar month (welfare gate b)." },
  { key: "WYRDSEKAI_RECIPES_DEPLOY_CEILING",
    label: "welfare.deploy_ceiling",
    deflt: "3",
    desc: "Consecutive deploy-failures before a recipe auto-pauses (welfare gate d)." },
  { key: "WYRDSEKAI_RECIPES_GAP_DETECTION",
    label: "gap_detection.enabled",
    deflt: "true",
    desc: "Whether Chronicle-detected gaps auto-enqueue matching recipes." },
  { key: "WYRDSEKAI_RECIPES_GAP_TICKS",
    label: "gap_detection.ticks",
    deflt: "5",
    desc: "Sustained-pattern tick threshold before gap-detection enqueues." },
  { key: "WYRDSEKAI_RECIPES_GAP_WINDOW_HOURS",
    label: "gap_detection.window_hours",
    deflt: "48",
    desc: "Hours over which the gap-detection ticks must sustain." }
];

function renderSettings() {
  var lines = [];
  lines.push("Recipe scheduler settings (read here, write via");
  lines.push("  'use scroll set <KEY>=<VALUE>' on the Scroll of Settings,");
  lines.push("  then 'use scroll apply' for the change to take effect):");
  lines.push("");
  var rows = [];
  for (var i = 0; i < SCHEDULER_KEYS.length; i++) {
    var entry = SCHEDULER_KEYS[i];
    var current = null;
    try {
      // world.configGet returns the conf-file value (env-overrides are not
      // read by this API; WyrdConfig resolves env at process start).
      current = (typeof world.configGet === "function")
        ? world.configGet(entry.key)
        : null;
    } catch (e) {
      current = null;
    }
    var shown = (current === null || current === undefined || current === "")
      ? "(default " + entry.deflt + ")"
      : String(current);
    lines.push("  " + entry.label);
    lines.push("    key:     " + entry.key);
    lines.push("    current: " + shown);
    lines.push("    " + entry.desc);
    lines.push("");
    rows.push({
      key: entry.key,
      label: entry.label,
      defaultValue: entry.deflt,
      currentValue: current,
      description: entry.desc
    });
  }
  return {
    ok: true,
    mode: "settings",
    narrative: lines.join("\n"),
    settings: rows
  };
}

exports.invoke = invoke;
