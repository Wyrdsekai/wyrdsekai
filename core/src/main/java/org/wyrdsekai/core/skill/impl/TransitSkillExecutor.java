package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GTFS static data skill executor.
 * Reads GTFS CSV files (stops.txt, stop_times.txt, trips.txt, routes.txt)
 * from a configured directory or ZIP file.
 * Provides next departures from a stop, route lookup, and alerts.
 */
public class TransitSkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final Path gtfsPath;

    // Cached GTFS data (loaded lazily)
    private volatile Map<String, String> stopNameToId;  // stop_name -> stop_id
    private volatile Map<String, List<StopTime>> stopTimes; // stop_id -> times
    private volatile Map<String, String> tripToRoute;   // trip_id -> route_short_name

    private record StopTime(String tripId, String departure, String stopId) {}

    /**
     * @param gtfsPath Path to GTFS directory or .zip file
     */
    public TransitSkillExecutor(Path gtfsPath) {
        this.gtfsPath = gtfsPath;

        define(new SkillDefinition("scrying.transit.next", "Transit Next",
            "Find next departures from a stop", "scrying-pool", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("stop", "string", "Stop name or ID"),
                     SkillParam.optional("limit", "number", "Max departures (default: 5)")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("scrying.transit.route", "Transit Route",
            "Look up a transit route", "scrying-pool", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("route", "string", "Route name or number")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("scrying.transit.alerts", "Transit Alerts",
            "Check transit service alerts", "scrying-pool", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0", List.of(),
            SkillAuth.NONE, SkillLocality.LOCAL, true));
    }

    private void define(SkillDefinition skill) { skills.put(skill.id(), skill); }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (gtfsPath == null || !Files.exists(gtfsPath))
            return SkillResult.error(I18n.get("skill.transit.no_data"),
                0, SkillTier.NATIVE, skillId);

        long start = System.currentTimeMillis();
        try {
            ensureLoaded();
        } catch (IOException e) {
            return SkillResult.error(I18n.get("skill.transit.no_data"),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId);
        }

        return switch (skillId) {
            case "scrying.transit.next" -> executeNext(params, start, skillId);
            case "scrying.transit.route" -> executeRoute(params, start, skillId);
            case "scrying.transit.alerts" -> executeAlerts(start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeNext(Map<String, Object> params, long start, String skillId) {
        String stopRef = requireParam(params, "stop");
        if (stopRef == null) return SkillResult.error(
            I18n.get("skill.param_required", "stop"), 0, SkillTier.NATIVE, skillId);

        int limit = intParam(params, "limit", 5);

        // Resolve stop name to ID
        String stopId = stopNameToId.getOrDefault(stopRef.toLowerCase(), stopRef);
        List<StopTime> times = stopTimes.getOrDefault(stopId, List.of());

        if (times.isEmpty()) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.ok(I18n.get("skill.transit.no_data"),
                Map.of("stop", stopRef), elapsed, SkillTier.NATIVE, skillId);
        }

        // Filter to upcoming departures
        String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        var upcoming = times.stream()
            .filter(t -> t.departure().compareTo(now) >= 0)
            .limit(limit)
            .toList();

        if (upcoming.isEmpty()) {
            // Wrap around: show earliest tomorrow
            upcoming = times.stream().limit(limit).toList();
        }

        var sb = new StringBuilder();
        var deps = new ArrayList<Map<String, String>>();
        for (var st : upcoming) {
            String route = tripToRoute.getOrDefault(st.tripId(), st.tripId());
            sb.append(route).append(" at ").append(st.departure()).append("\n");
            deps.add(Map.of("route", route, "departure", st.departure()));
        }

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(
            I18n.get("skill.transit.departures", deps.size(), stopRef),
            Map.of("departures", deps, "stop", stopRef, "count", deps.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeRoute(Map<String, Object> params, long start, String skillId) {
        String routeRef = requireParam(params, "route");
        if (routeRef == null) return SkillResult.error(
            I18n.get("skill.param_required", "route"), 0, SkillTier.NATIVE, skillId);

        // Find all trips for this route
        var matchingTrips = tripToRoute.entrySet().stream()
            .filter(e -> e.getValue().equalsIgnoreCase(routeRef))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());

        long elapsed = System.currentTimeMillis() - start;
        if (matchingTrips.isEmpty()) {
            return SkillResult.ok("Route not found: " + routeRef,
                Map.of("route", routeRef, "found", false),
                elapsed, SkillTier.NATIVE, skillId);
        }

        return SkillResult.ok("Route " + routeRef + ": " + matchingTrips.size() + " trips",
            Map.of("route", routeRef, "trips", matchingTrips.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeAlerts(long start, String skillId) {
        // Static GTFS has no real-time alerts -- return placeholder
        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok("No service alerts (static GTFS data only)",
            Map.of("alerts", List.of()), elapsed, SkillTier.NATIVE, skillId);
    }

    private synchronized void ensureLoaded() throws IOException {
        if (stopNameToId != null) return;

        var names = new HashMap<String, String>();
        var times = new HashMap<String, List<StopTime>>();
        var routes = new HashMap<String, String>();

        if (gtfsPath.toString().endsWith(".zip")) {
            loadFromZip(names, times, routes);
        } else {
            loadFromDirectory(names, times, routes);
        }

        this.stopNameToId = names;
        this.stopTimes = times;
        this.tripToRoute = routes;
    }

    private void loadFromZip(Map<String, String> names,
                              Map<String, List<StopTime>> times,
                              Map<String, String> routes) throws IOException {
        try (var zip = new ZipFile(gtfsPath.toFile())) {
            parseStops(readZipEntry(zip, "stops.txt"), names);
            parseStopTimes(readZipEntry(zip, "stop_times.txt"), times);
            parseRoutes(readZipEntry(zip, "routes.txt"), readZipEntry(zip, "trips.txt"), routes);
        }
    }

    private void loadFromDirectory(Map<String, String> names,
                                    Map<String, List<StopTime>> times,
                                    Map<String, String> routes) throws IOException {
        parseStops(Files.readAllLines(gtfsPath.resolve("stops.txt")), names);
        parseStopTimes(Files.readAllLines(gtfsPath.resolve("stop_times.txt")), times);
        parseRoutes(Files.readAllLines(gtfsPath.resolve("routes.txt")),
            Files.readAllLines(gtfsPath.resolve("trips.txt")), routes);
    }

    private void parseStops(List<String> lines, Map<String, String> names) {
        if (lines.size() < 2) return;
        String[] header = lines.get(0).split(",");
        int idIdx = indexOf(header, "stop_id");
        int nameIdx = indexOf(header, "stop_name");
        if (idIdx < 0 || nameIdx < 0) return;
        for (int i = 1; i < lines.size(); i++) {
            String[] cols = lines.get(i).split(",", -1);
            if (cols.length > Math.max(idIdx, nameIdx)) {
                names.put(cols[nameIdx].toLowerCase().replace("\"", ""), cols[idIdx].replace("\"", ""));
            }
        }
    }

    private void parseStopTimes(List<String> lines, Map<String, List<StopTime>> times) {
        if (lines.size() < 2) return;
        String[] header = lines.get(0).split(",");
        int tripIdx = indexOf(header, "trip_id");
        int depIdx = indexOf(header, "departure_time");
        int stopIdx = indexOf(header, "stop_id");
        if (tripIdx < 0 || depIdx < 0 || stopIdx < 0) return;
        for (int i = 1; i < lines.size(); i++) {
            String[] cols = lines.get(i).split(",", -1);
            if (cols.length > Math.max(tripIdx, Math.max(depIdx, stopIdx))) {
                String stopId = cols[stopIdx].replace("\"", "");
                times.computeIfAbsent(stopId, k -> new ArrayList<>())
                    .add(new StopTime(cols[tripIdx].replace("\"", ""),
                        cols[depIdx].replace("\"", ""), stopId));
            }
        }
    }

    private void parseRoutes(List<String> routeLines, List<String> tripLines,
                              Map<String, String> routes) {
        // First build route_id -> route_short_name
        var routeNames = new HashMap<String, String>();
        if (routeLines.size() >= 2) {
            String[] rh = routeLines.get(0).split(",");
            int ridx = indexOf(rh, "route_id");
            int rnidx = indexOf(rh, "route_short_name");
            if (ridx >= 0 && rnidx >= 0) {
                for (int i = 1; i < routeLines.size(); i++) {
                    String[] cols = routeLines.get(i).split(",", -1);
                    if (cols.length > Math.max(ridx, rnidx))
                        routeNames.put(cols[ridx].replace("\"", ""), cols[rnidx].replace("\"", ""));
                }
            }
        }
        // Then build trip_id -> route_short_name
        if (tripLines.size() >= 2) {
            String[] th = tripLines.get(0).split(",");
            int tidx = indexOf(th, "trip_id");
            int tridx = indexOf(th, "route_id");
            if (tidx >= 0 && tridx >= 0) {
                for (int i = 1; i < tripLines.size(); i++) {
                    String[] cols = tripLines.get(i).split(",", -1);
                    if (cols.length > Math.max(tidx, tridx)) {
                        String routeId = cols[tridx].replace("\"", "");
                        routes.put(cols[tidx].replace("\"", ""),
                            routeNames.getOrDefault(routeId, routeId));
                    }
                }
            }
        }
    }

    private List<String> readZipEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return List.of();
        var lines = new ArrayList<String>();
        try (var br = new BufferedReader(new InputStreamReader(
                zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private int indexOf(String[] arr, String key) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].replace("\"", "").trim().equals(key)) return i;
        }
        return -1;
    }

    private String param(Map<String, Object> p, String k, String d) {
        Object v = p != null ? p.get(k) : null; return v != null ? String.valueOf(v) : d;
    }
    private String requireParam(Map<String, Object> p, String k) {
        Object v = p != null ? p.get(k) : null; return v != null ? String.valueOf(v) : null;
    }
    private int intParam(Map<String, Object> p, String k, int d) {
        Object v = p != null ? p.get(k) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) { try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { /* */ } }
        return d;
    }

    @Override public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }
    @Override public boolean supports(String skillId) { return skills.containsKey(skillId); }
    @Override public SkillTier tier() { return SkillTier.NATIVE; }
}
