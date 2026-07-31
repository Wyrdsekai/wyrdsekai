package org.wyrdsekai.core.oracle;

/**
 * A prediction from the oracle-core engine.
 * Parsed from the JSON response of /v1/analyze/anticipate.
 */
public record OraclePrediction(
    String id,
    String text,
    String category,    // "pattern", "anomaly", "forecast", "correlation", "topic", "sequence", "recommendation", "anticipation"
    double confidence,
    String textKey,     // i18n key for re-translation
    String evidence,
    boolean actionable
) {}
