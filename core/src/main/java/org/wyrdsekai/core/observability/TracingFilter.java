package org.wyrdsekai.core.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trace context propagation and span creation (§105.3).
 * OpenTelemetry-inspired tracing for user message → response pipeline.
 */
public class TracingFilter {

    /** A trace span. */
    public record Span(
        String traceId,
        String spanId,
        String parentSpanId,
        String operation,
        Instant startTime,
        Instant endTime,
        SpanStatus status,
        Map<String, String> attributes
    ) {
        /** Duration of this span. */
        public Duration duration() {
            if (endTime == null) return Duration.ZERO;
            return Duration.between(startTime, endTime);
        }
    }

    public enum SpanStatus {
        OK, ERROR, CANCELLED
    }

    /** Active trace context. */
    public record TraceContext(
        String traceId,
        String currentSpanId,
        Instant traceStart,
        int spanCount
    ) {}

    private final Map<String, List<Span>> traces = new ConcurrentHashMap<>();
    private final Map<String, Span> activeSpans = new ConcurrentHashMap<>();
    private int nextId = 1;

    /** Start a new trace (root span). */
    public TraceContext startTrace(String operation, Map<String, String> attributes) {
        var traceId = "trace-" + nextId++;
        var spanId = "span-" + nextId++;
        var span = new Span(traceId, spanId, null, operation,
            Instant.now(), null, null, attributes != null ? Map.copyOf(attributes) : Map.of());
        traces.computeIfAbsent(traceId, k -> Collections.synchronizedList(new ArrayList<>())).add(span);
        activeSpans.put(spanId, span);
        return new TraceContext(traceId, spanId, span.startTime(), 1);
    }

    /** Start a child span within an existing trace. */
    public Span startSpan(String traceId, String parentSpanId, String operation,
                           Map<String, String> attributes) {
        var spanId = "span-" + nextId++;
        var span = new Span(traceId, spanId, parentSpanId, operation,
            Instant.now(), null, null, attributes != null ? Map.copyOf(attributes) : Map.of());
        traces.computeIfAbsent(traceId, k -> Collections.synchronizedList(new ArrayList<>())).add(span);
        activeSpans.put(spanId, span);
        return span;
    }

    /** End a span. */
    public Span endSpan(String spanId, SpanStatus status) {
        var span = activeSpans.remove(spanId);
        if (span == null) return null;
        var ended = new Span(span.traceId(), span.spanId(), span.parentSpanId(),
            span.operation(), span.startTime(), Instant.now(), status, span.attributes());

        // Replace in trace list
        var traceSpans = traces.get(span.traceId());
        if (traceSpans != null) {
            synchronized (traceSpans) {
                for (int i = 0; i < traceSpans.size(); i++) {
                    if (traceSpans.get(i).spanId().equals(spanId)) {
                        traceSpans.set(i, ended);
                        break;
                    }
                }
            }
        }
        return ended;
    }

    /** Get all spans for a trace. */
    public List<Span> traceSpans(String traceId) {
        var spans = traces.get(traceId);
        return spans != null ? List.copyOf(spans) : List.of();
    }

    /** Get the root span for a trace. */
    public Optional<Span> rootSpan(String traceId) {
        return traceSpans(traceId).stream()
            .filter(s -> s.parentSpanId() == null)
            .findFirst();
    }

    /** Check if any spans have errors in a trace. */
    public boolean hasErrors(String traceId) {
        return traceSpans(traceId).stream()
            .anyMatch(s -> s.status() == SpanStatus.ERROR);
    }

    /** Get total trace duration (root span start to last span end). */
    public Optional<Duration> traceDuration(String traceId) {
        var spans = traceSpans(traceId);
        if (spans.isEmpty()) return Optional.empty();
        var start = spans.stream().map(Span::startTime).min(Comparator.naturalOrder());
        var end = spans.stream()
            .map(s -> s.endTime() != null ? s.endTime() : Instant.now())
            .max(Comparator.naturalOrder());
        if (start.isEmpty() || end.isEmpty()) return Optional.empty();
        return Optional.of(Duration.between(start.get(), end.get()));
    }

    /** Human-readable trace tree. */
    public String describeTrace(String traceId) {
        var spans = traceSpans(traceId);
        if (spans.isEmpty()) return "No trace found: " + traceId;

        var sb = new StringBuilder("=== Trace: ").append(traceId).append(" ===\n");
        for (var span : spans) {
            var indent = span.parentSpanId() == null ? "" : "  ";
            // Simple indent — not recursive tree
            if (span.parentSpanId() != null) {
                // Check if grandchild
                var parent = spans.stream()
                    .filter(s -> s.spanId().equals(span.parentSpanId()))
                    .findFirst();
                if (parent.isPresent() && parent.get().parentSpanId() != null) {
                    indent = "    ";
                }
            }
            sb.append(indent).append(span.operation());
            if (span.endTime() != null) {
                sb.append(" [").append(span.duration().toMillis()).append("ms]");
            }
            if (span.status() != null) {
                sb.append(" ").append(span.status());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public int activeSpanCount() { return activeSpans.size(); }
    public int traceCount() { return traces.size(); }
}
