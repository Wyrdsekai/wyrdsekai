package org.wyrdsekai.app.protocol

enum class PriorityLevel {
    CRITICAL,
    NORMAL,
    AMBIENT;

    companion object {
        fun fromWire(value: String?): PriorityLevel = when (value?.lowercase()) {
            "critical" -> CRITICAL
            "ambient" -> AMBIENT
            else -> NORMAL
        }
    }
}
