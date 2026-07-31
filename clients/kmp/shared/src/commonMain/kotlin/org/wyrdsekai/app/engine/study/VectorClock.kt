package org.wyrdsekai.app.engine.study

/**
 * Vector clock comparison for Study item sync.
 *
 * Three outcomes:
 * - DOMINATES: a is strictly newer than b (fast-forward)
 * - DOMINATED: b is strictly newer than a (fast-forward)
 * - CONCURRENT: neither dominates (conflict — keep both)
 */
object VectorClock {

    enum class Relation { DOMINATES, DOMINATED, CONCURRENT, EQUAL }

    /**
     * Compare two vector clocks.
     * a dominates b if every slot in a >= corresponding slot in b, and at least one is >.
     */
    fun compare(a: Map<String, Long>, b: Map<String, Long>): Relation {
        val allKeys = a.keys + b.keys
        var aGreater = false
        var bGreater = false

        for (key in allKeys) {
            val va = a[key] ?: 0L
            val vb = b[key] ?: 0L
            if (va > vb) aGreater = true
            if (vb > va) bGreater = true
        }

        return when {
            aGreater && !bGreater -> Relation.DOMINATES
            bGreater && !aGreater -> Relation.DOMINATED
            !aGreater && !bGreater -> Relation.EQUAL
            else -> Relation.CONCURRENT
        }
    }

    /** Merge two clocks, taking the max of each slot. */
    fun merge(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        val result = a.toMutableMap()
        for ((key, value) in b) {
            result[key] = maxOf(result[key] ?: 0L, value)
        }
        return result
    }
}
