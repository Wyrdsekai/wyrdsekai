// health/gov/maps/weather adapters sample.
//
// A morning-briefing scroll that uses the Phase U external-adapter surface to
// produce a daily forecast for the bondholder's location:
//   1. world.maps.geocode — turn the bondholder's home address into {lat, lon}
//   2. world.openweather.current + world.openweather.forecast — daily outlook
//
// Both adapters dispatch through ExternalAdapterRegistry, so missing
// credentials surface as a structured `{error: {code: "credential_missing"}}`
// envelope rather than throwing — the scroll degrades gracefully and asks
// the steward to populate the relevant Safe slot.
//
// Manifest fields used:
//   capabilities: ["maps.read", "openweather.read"]
//   external_domains: ["maps.googleapis.com", "api.openweathermap.org"]

exports.manifest = {
  name: "morning_briefing",
  version: "1.0.0",
  description: "Daily forecast scroll for the bondholder's home location.",
  author: "did:wyrd:system",
  capabilities: ["maps.read", "openweather.read"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "A morning scroll unfurls — sky, temperature, the day's weight, written in dawn-light hand."
  },
  external_domains: [
    "maps.googleapis.com",
    "api.openweathermap.org"
  ],
  data_sensitivity: "low",
  // Items-as-tools contract — invoke() reads params.address (required),
  // not the args string; a bare invoke explains that an address is needed.
  commands: [
    { label: "Read the morning forecast (needs an address)", args: "" }
  ],
  // The schema the MODEL sees. Without this the item was advertised as a single
  // optional free-form `query`, so the drive model called it with {query: ""} and
  // the forecast failed on every attempt ("address is required"). "needs an address"
  // in a menu label is not something the model can act on; this is.
  params: [
    { name: "address", type: "string", required: true,
      description: "The place to forecast, as a location the geocoder can resolve — "
                 + "e.g. \"San Francisco\", \"Kyoto, Japan\", or a street address. "
                 + "Take it from what the user asked for." },
    { name: "day", type: "string", required: false,
      description: "Which day to report: \"today\" (default) or \"tomorrow\"." }
  ]
};

// ── #32 item 6: day-hint selection ─────────────────────────────────────────
// "what's the weather TOMORROW?" used to be answered with the "now" line.
// A day word (tomorrow/today/tonight), an ISO date (2026-07-13), a M/D
// (7/13), or a month-name date (July 13) in the request selects the matching
// forecast daily entry, and the answer leads with that day, labeled.

var DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
var MONTH_NUM = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
                  jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };

function pad2(n) { var s = String(n); return s.length < 2 ? "0" + s : s; }

function isoAddDays(isoDate, n) {
  var t = Date.parse(isoDate + "T00:00:00Z") + n * 86400000;
  return new Date(t).toISOString().slice(0, 10);
}

/** "2026-07-13" → "Mon 7/13" (UTC-anchored; forecast dates are calendar days). */
function dayLabel(isoDate) {
  var d = new Date(Date.parse(isoDate + "T00:00:00Z"));
  if (isNaN(d.getTime())) return isoDate;
  return DAY_NAMES[d.getUTCDay()] + " " + (d.getUTCMonth() + 1) + "/" + d.getUTCDate();
}

/** Extract a target date from free text. Returns {date, word} or null. */
function resolveDayHint(text, todayIso) {
  if (!text) return null;
  var lower = String(text).toLowerCase();
  if (/\btomorrow\b/.test(lower)) return { date: isoAddDays(todayIso, 1), word: "tomorrow" };
  if (/\btoday\b|\btonight\b/.test(lower)) return { date: todayIso, word: "today" };
  var iso = lower.match(/\b(\d{4})-(\d{2})-(\d{2})\b/);
  if (iso) return { date: iso[1] + "-" + iso[2] + "-" + iso[3], word: null };
  var md = lower.match(/\b(\d{1,2})\/(\d{1,2})\b/);
  if (md) return { date: todayIso.slice(0, 4) + "-" + pad2(md[1]) + "-" + pad2(md[2]), word: null };
  var mn = lower.match(/\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(st|nd|rd|th)?\b/);
  if (mn) return { date: todayIso.slice(0, 4) + "-" + pad2(MONTH_NUM[mn[1]]) + "-" + pad2(mn[2]), word: null };
  return null;
}

/** Find the daily entry matching the hint date, or null. */
function pickForecastDay(daily, hint) {
  if (!hint || !daily) return null;
  for (var i = 0; i < daily.length; i++) {
    if (daily[i] && String(daily[i].date) === hint.date) return daily[i];
  }
  return null;
}

function invoke(params) {
  // Accept the model's natural param names too (second-node 2026-07-10: the companion
  // filled `query` with a perfectly good location and the hard `address`
  // requirement bounced it).
  var address = params.address || params.query || params.location || "";
  if (!address) {
    return { ok: false, error: "address is required — pass the location to forecast, e.g. {address: \"San Francisco\"}" };
  }

  // Step 1 — geocode the address through Google Maps adapter
  var geo = world.maps.geocode({ address: address });
  if (!geo.success) {
    return {
      ok: false,
      step: "geocode",
      error: geo.error
    };
  }

  // Phase U adapters return live data once stewards populate keys; until
  // then the structured `not_yet_wired` envelope is the contract surface.
  var coords = (geo.data && geo.data.coords) || params.coords;
  // Prefer the geocoder's canonical name for spoken labels — `address` may be a
  // long free-text query (second-node final-verify 032eca34: the spoken forecast echoed
  // the whole question, "Weather for weather forecast for San Francisco …").
  // Same fix already lives in trip_planner.js; never label with the raw input.
  var placeName = (geo.data && geo.data.formatted_address) || address;
  if (!coords) {
    return {
      ok: true,
      summary: "Geocoder returned " + (geo.data ? "ok" : "stub") +
        "; awaiting steward credentials for live data."
    };
  }

  // Step 2 — current conditions
  var current = world.openweather.current({
    lat: coords.lat,
    lon: coords.lon
  });

  // Step 3 — multi-day forecast
  var forecast = world.openweather.forecast({
    lat: coords.lat,
    lon: coords.lon,
    days: 3
  });

  // Voice-ready digest (#31 item 2): current.data / forecast.data are Java
  // host maps — GraalJS JSON.stringify can't serialize them, so anything that
  // stringifies the envelope spoke "{". Prefer the adapter's preformatted
  // data.text; keep the structured fields for programmatic consumers.
  var currentText = (current && current.success && current.data && current.data.text) || null;
  var forecastText = (forecast && forecast.success && forecast.data && forecast.data.text) || null;

  // #32 item 6 — honor a day hint: lead with the requested day's forecast
  // entry (labeled with the day) instead of the "now" line.
  var hintText = [params.address, params.query, params.location,
                  params.date, params.day].join(" ");
  var todayIso = String(world.time.iso()).slice(0, 10);
  var hint = resolveDayHint(hintText, todayIso);
  var daily = (forecast && forecast.success && forecast.data && forecast.data.daily) || null;
  var hintDay = pickForecastDay(daily, hint);

  var findings;
  if (hintDay) {
    var label = dayLabel(hint.date) + (hint.word ? " (" + hint.word + ")" : "");
    findings = "Weather for " + placeName + " on " + label + ": low " + hintDay.low_f +
      "F, high " + hintDay.high_f + "F, " + hintDay.conditions + "." +
      (currentText ? " Right now — " + currentText + "." : "") +
      (forecastText ? " Outlook — " + forecastText + "." : "");
  } else if (hint) {
    // A day was asked for but the forecast window doesn't cover it — say so
    // honestly instead of silently answering about "now".
    findings = "Morning briefing for " + placeName + ": I have no forecast entry for " +
      dayLabel(hint.date) + "." +
      (forecastText ? " Outlook — " + forecastText + "." : "") +
      (currentText ? " Right now — " + currentText + "." : "");
  } else {
    findings = "Morning briefing for " + placeName + ": " +
      (currentText ? "now — " + currentText : "current conditions unavailable") +
      (forecastText ? ". Outlook — " + forecastText : "") + ".";
  }

  return {
    ok: true,
    address: address,
    findings: findings,
    current: current.data,
    forecast: forecast.data,
    generated_at: world.time.iso()
  };
}
