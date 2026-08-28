// WeatherTool — query current weather by location name.
exports.manifest = {
  name: "weather-tool",
  version: "1.0.0",
  description: "Look up current weather at a given city and state",
  author: "did:wyrd:openhands",
  capabilities: ["nominatim.geocode", "openweather.current"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "{actor} checks the weather at {location}"
  },
  commands: [
    { label: "Get current weather for a location", args: "city state" },
    { label: "Describe what the weather means", args: "data-json" }
  ]
};

function invoke(params) {
  const location = params.args || "";

  // Weather lookup — geocode first, then fetch current conditions.
  if (!location) {
    return { ok: true, summary: "Tell me a city and state like 'Seattle WA'." };
  }

  const [city, state] = location.trim().split(/\s+/);
  if (state.length < 2 || !/^[A-Z]{2}$/.test(state)) {
    return { ok: true, summary: "State must be two letters. Try 'Portland OR'." };
  }

  // Step 1: geocode the location to get coordinates
  const geo = world.nominatim.geocode({ q: `${city}, ${state}` });
  if (!geo.success) {
    return { ok: true, summary: `Couldn't find ${city} ${state}.` };
  }

  // Step 2: fetch current weather using coordinates
  const w = world.openweather.current({
    lat: geo.data.lat,
    lon: geo.data.lon
  });

  if (!w.success) {
    return { ok: true, summary: `Weather data unavailable for ${city}.` };
  }

  const tempC = w.data.main.temp;
  const tempF = Math.round(tempC * 9 / 5 + 32);
  const condition = w.data.weather[0].description;

  return { ok: true, summary: `Weather in ${city}: ${tempC}°C (${tempF}°F), ${condition}` };
}

function describeConditions(data) {
  // data is JSON object with tempCelsius, tempFahrenheit, condition.
  const c = data.tempCelsius;
  const f = data.tempFahrenheit;
  const cond = data.condition || "unknown";

  let desc = "";
  if (c < 0) desc = "cold — below freezing";
  else if (c < 10) desc = "cool — comfortable morning air";
  else if (c < 20) desc = "mild — pleasant most of the day";
  else if (c < 30) desc = "warm — good for outdoor things";
  else desc = "hot — watch for heat";

  return { ok: true, summary: `${cond}. ${desc}` };
}

// Demo usage — not invoked automatically.
if (typeof module !== "undefined" && module.exports === module) {
  console.log("WeatherTool: use weather-tool \"Seattle WA\" to query weather.");
}
