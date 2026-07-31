// sample travel-planning item.
//
// Composes the §4.42 Amadeus flight-search adapter with the §4.39 / Phase U
// OpenWeatherMap forecast adapter to produce a quick "should I go?" shape:
// flight options + weather at the destination on the travel date.
//
// Demonstrates:
//   * Multi-adapter composition (typed surfaces, structured returns)
//   * Graceful degradation when credentials are missing — both adapters
//     return {stub:true} which the script treats as "no live data" without
//     crashing
//   * Tier 4 capabilities only — read-only travel/weather, no bookings
//
// Manifest fields:
//   capabilities: ["amadeus.read", "openweathermap.read"]
//   rate_limits: per-cap, per the Tier 4 budget
exports.manifest = {
  name: "trip_planner",
  version: "1.0.0",
  description: "Plan a trip: flight search + weather forecast at destination.",
  author: "did:wyrd:system",
  // Manifest fix (items-as-tools migration): the catalogue cap and the
  // adapter namespace are "openweather" (see OpenWeatherAdapter.namespace()),
  // not "openweathermap" — the old spelling was an unknown capability, so
  // this manifest was rejected and the planner never loaded.
  capabilities: ["amadeus.read", "openweather.read"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "A parchment map brightens; a slender route-line traces from here to the destination."
  },
  rate_limits: {
    "amadeus.read": { per_minute: 10, per_hour: 60, per_day: 300 },
    "openweather.read": { per_minute: 10, per_hour: 60, per_day: 300 }
  },
  data_sensitivity: "low",
  // Items-as-tools contract — invoke() reads structured params (origin,
  // destination, date, optional destLat/destLon), not the args string; a
  // bare invoke explains what is required.
  commands: [
    { label: "Plan a trip (needs origin, destination, date)", args: "" }
  ],
  // The schema the MODEL sees. This item survived the old undescribed `query` slot
  // only because it happens to read params.query and geocode free text — it was the
  // one shape that fit the guess. Declare the real contract anyway.
  // `destination` is REQUIRED — the one thing this tool cannot work without (invoke()
  // needs `dest || query` to geocode anything). Everything here used to be optional,
  // which is the same trap that had the model calling the calculator with no arguments
  // at all: an optional parameter is a parameter the model will not fill. This one only
  // ever worked because the model volunteered a destination unprompted. Now it must.
  params: [
    { name: "destination", type: "string", required: true,
      description: "The place this is about, as a name a geocoder can resolve — "
                 + "e.g. \"San Francisco\" or \"Kyoto, Japan\". Take it from what the "
                 + "user asked for." },
    { name: "date", type: "string", required: false,
      description: "Day of travel or forecast: \"today\", \"tomorrow\", or an ISO date." },
    { name: "origin", type: "string", required: false,
      description: "Where the trip starts, if it is a journey rather than a lookup." },
    { name: "query", type: "string", required: false,
      description: "The user's request in their own words, for anything the fields "
                 + "above don't capture." }
  ]
};

// ── #32 item 6: day-hint selection (mirror of morning_briefing) ────────────
// "weather in SF tomorrow?" answered with the requested day's entry, labeled.

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
  var origin = params.origin || "";
  // Accept the model's natural param names for the place (second-node 2026-07-10:
  // a weather question arrived as {query: "...San Francisco..."} and the hard
  // origin/destination/date contract bounced it).
  var dest = params.destination || params.location || "";
  var date = params.date || "";
  var query = params.query || "";

  // Forecast-only mode: a place (or free-text query) without a route is a
  // weather ask, not a trip plan — answer it instead of refusing.
  if (!origin && (dest || query)) {
    var place = dest || query;
    var geo = world.maps.geocode({ address: place });
    var coords = (geo && geo.success && geo.data && geo.data.coords) || null;
    if (!coords) {
      return {
        ok: true,
        summary: "Forecast request for '" + place + "' noted — geocoder returned " +
          ((geo && geo.data) ? "ok" : "stub") +
          "; awaiting steward credentials for live weather data."
      };
    }
    // Prefer the geocoder's canonical name — `place` may be a long free-text
    // query (second-node 2026-07-11: the spoken forecast echoed the whole question).
    var placeName = (geo.data && geo.data.formatted_address) || place;
    var fc = world.openweather.forecast({ lat: coords.lat, lon: coords.lon });
    if (fc && fc.success && fc.data) {
      // Prefer the adapter's preformatted digest (#31 item 2): fc.data is a
      // Java host map, and GraalJS's JSON.stringify can't serialize those —
      // the spoken forecast came out as a literal "{".
      var fcText = fc.data.text;
      if (!fcText) {
        var days = fc.data.daily || [];
        var parts = [];
        for (var di = 0; di < days.length; di++) {
          parts.push(days[di].date + ": low " + days[di].low_f + "F high " +
            days[di].high_f + "F, " + days[di].conditions);
        }
        fcText = parts.length > 0 ? parts.join("; ") : "no forecast data returned";
      }
      // #32 item 6 — a day hint in the request ("tomorrow", "7/13", "July 13")
      // leads the answer with THAT day's entry, labeled with the day.
      var todayIso = String(world.time.iso()).slice(0, 10);
      var hint = resolveDayHint([place, query, params.date].join(" "), todayIso);
      var hintDay = pickForecastDay(fc.data.daily, hint);
      if (hintDay) {
        var label = dayLabel(hint.date) + (hint.word ? " (" + hint.word + ")" : "");
        return { ok: true, findings: "Forecast for " + placeName + " on " + label +
          ": low " + hintDay.low_f + "F, high " + hintDay.high_f + "F, " +
          hintDay.conditions + ". Full outlook — " + fcText };
      }
      if (hint) {
        // Asked-for day is outside the forecast window — say so honestly.
        return { ok: true, findings: "Forecast for " + placeName + ": no entry for " +
          dayLabel(hint.date) + " in the current window. Outlook — " + fcText };
      }
      return { ok: true, findings: "Forecast for " + placeName + ": " + fcText };
    }
    return {
      ok: true,
      summary: "Geocoded '" + place + "' but live weather needs steward credentials " +
        "(openweathermap key in the Safe)."
    };
  }

  if (!origin || !dest || !date) {
    return {
      ok: false,
      error: "missing_args — a full trip plan needs {origin, destination, date}; " +
        "for weather only, pass {destination: \"<place>\"} alone"
    };
  }

  // 1) Flights via Amadeus (Phase V).
  var flights = world.amadeus.flight_search({
    origin: origin,
    destination: dest,
    date: date
  });
  // Phase V no-credential stubs return success:true with {stub:true, results:[]} —
  // report that honestly instead of "0 flights found".
  if (flights && flights.success && flights.data && flights.data.stub) {
    return { ok: true, summary: "Flight search for " + origin + "->" + dest +
      " needs steward credentials (amadeus keys in the Safe) before live results." };
  }

  // 2) Destination weather forecast via OpenWeatherMap (Phase U).
  // Caller is expected to pass {lat, lon} for the destination — the Amadeus
  // search returns IATA codes, which the planner doesn't try to geocode here.
  var weather = null;
  if (params.destLat != null && params.destLon != null) {
    weather = world.openweather.forecast({
      lat: params.destLat,
      lon: params.destLon
    });
  }

  var summary = {
    origin: origin,
    destination: dest,
    travel_date: date,
    requested_at: world.time.iso(),
    flights: {
      stub: !!(flights && flights.data && flights.data.stub),
      reason: flights && flights.data ? flights.data.reason : null,
      offers: flights && flights.data ? (flights.data.flights || []) : []
    },
    weather: weather && weather.data ? {
      stub: !!weather.data.stub,
      reason: weather.data.reason || null,
      forecast: weather.data.forecast || []
    } : null
  };

  return { ok: true, summary: summary };
}
