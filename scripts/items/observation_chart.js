// Phase B+ visualization demo.
//
// Demonstrates that an item can render a Vega-Lite bar chart from a hardcoded
// dataset. The returned chart is auto-registered as an artifact when an
// ArtifactService is wired (so consumers can reference it by id later, or
// embed it into a scroll). For terminal sessions the script also produces a
// matching ASCII fallback under params.terminal=true.
exports.manifest = {
  name: "observation_chart",
  version: "1.0.0",
  description: "Render a sample observation bar chart (Vega-Lite + ASCII).",
  author: "did:wyrd:system",
  capabilities: ["chart.render", "artifact.write"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The wall-chart blinks as fresh bars rise — soft phosphor-glow across the observation grid."
  },
  // Items-as-tools contract — invoke() reads structured params
  // (params.title, params.terminal), not the args string; the bare entry
  // renders the sample Vega-Lite chart.
  commands: [
    { label: "Render the sample observation chart", args: "" }
  ],
  // Optional: the no-arg default renders the sample chart.
  params: [
    { name: "title", type: "string", required: false,
      description: "Title to render above the chart." },
    { name: "terminal", type: "boolean", required: false,
      description: "Render for a terminal (plain text) rather than rich output." }
  ]
};

function invoke(params) {
  // Sample dataset — daily-step counts across a week.
  var data = [
    { category: "Mon", value: 6132 },
    { category: "Tue", value: 8421 },
    { category: "Wed", value: 5198 },
    { category: "Thu", value: 9027 },
    { category: "Fri", value: 7384 },
    { category: "Sat", value: 4216 },
    { category: "Sun", value: 3124 }
  ];

  var opts = {
    title: params.title || "Daily Steps",
    xLabel: "Day",
    yLabel: "Steps"
  };

  // Render Vega-Lite bar chart (rich clients).
  var chart = world.chart.bar(data, opts);

  if (params.terminal) {
    // Terminal-friendly fallback — implicit Tier 1, no cap consumed.
    var ascii = world.chart.ascii(data, opts);
    return {
      ok: true,
      chartId: chart.id,
      artifactId: chart.artifactId || null,
      ascii: ascii.payload,
      summary: "Rendered " + opts.title + " (" + data.length + " bars)"
    };
  }

  return {
    ok: true,
    chartId: chart.id,
    artifactId: chart.artifactId || null,
    mime: chart.mime,
    title: chart.title,
    summary: "Rendered " + opts.title + " (" + data.length + " bars)"
  };
}
