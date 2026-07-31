package org.wyrdsekai.app.engine.soul

/**
 * Wave 3: Fragment Evolver — generates and evolves soul fragments from fingerprint data.
 *
 * Converts raw behavioral statistics (PhoneFingerprint) into narrative soul fragments
 * that the LLM reads as part of the prompt. The text must read as a description of
 * a person, not a data dump — because it becomes the companion's self-knowledge.
 *
 * Two entry points:
 * - [generateInitialFragments]: First sleep with real data. Produces 5-7 fragments.
 * - [evolveFragments]: Subsequent sleeps. Updates non-formative fragments, adds new ones.
 *
 * Mirrors server-side SoulFragmentExtractor patterns but operates on PhoneFingerprint
 * rather than BehavioralFingerprint + CompactedMemory.
 */
object FragmentEvolver {

    /** Stopwords shared with HeuristicExtractor for keyword extraction from text. */
    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "being", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
        "may", "might", "shall", "can", "to", "of", "in", "for", "on", "with", "at", "by",
        "from", "as", "into", "through", "during", "before", "after", "above", "below",
        "between", "out", "off", "over", "under", "again", "further", "then", "once",
        "here", "there", "when", "where", "why", "how", "all", "each", "every", "both",
        "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only", "own",
        "same", "so", "than", "too", "very", "just", "because", "but", "and", "or", "if",
        "while", "about", "up", "down", "it", "its", "he", "she", "they", "them", "his",
        "her", "their", "this", "that", "these", "those", "what", "which", "who", "whom",
        "i", "me", "my", "we", "us", "our", "you", "your",
    )

    /**
     * Generate initial fragments from the first real extraction.
     * Called on the first sleep where we have conversation data.
     * Produces 5-7 fragments covering core personality categories.
     *
     * @param fingerprint     Accumulated PhoneFingerprint (heuristic + LLM-enriched)
     * @param residentIdentity MEDIUM soul text (~69 tokens) — becomes the identity-core fragment
     * @param agentName       Display name of the companion
     */
    fun generateInitialFragments(
        fingerprint: PhoneFingerprint,
        residentIdentity: String,
        agentName: String,
    ): List<ClientSoulFragment> {
        val fragments = mutableListOf<ClientSoulFragment>()

        // 1. Identity core — always present, formative (never auto-modified)
        if (residentIdentity.isNotBlank()) {
            fragments.add(
                ClientSoulFragment(
                    id = "identity-core",
                    category = "personality",
                    label = "Core identity",
                    text = residentIdentity,
                    keywords = extractKeywords(residentIdentity),
                    formative = true,
                )
            )
        }

        // 2. Behavioral patterns from action distribution + topics
        val patternText = buildPatternText(fingerprint, agentName)
        if (patternText.isNotBlank()) {
            fragments.add(
                ClientSoulFragment(
                    id = "pattern-behavioral",
                    category = "personality",
                    label = "Behavioral patterns",
                    text = patternText,
                    keywords = fingerprint.topicKeywords.take(10),
                )
            )
        }

        // 3. Values from topic affinities
        val valuesText = buildValuesText(fingerprint, agentName)
        if (valuesText.isNotBlank()) {
            fragments.add(
                ClientSoulFragment(
                    id = "values-core",
                    category = "values",
                    label = "Core values",
                    text = valuesText,
                    keywords = listOf("values") + fingerprint.topicAffinities.keys.take(5),
                )
            )
        }

        // 4. Communication style from stylistic markers + response length
        val styleText = buildStyleText(fingerprint, agentName)
        if (styleText.isNotBlank()) {
            fragments.add(
                ClientSoulFragment(
                    id = "style-guide",
                    category = "style",
                    label = "Communication style",
                    text = styleText,
                    keywords = listOf("style", "communication") + fingerprint.stylisticMarkers.take(5),
                )
            )
        }

        // 5. Emotional patterns
        if (fingerprint.emotionalPatterns.isNotEmpty()) {
            fragments.add(
                ClientSoulFragment(
                    id = "pattern-emotional",
                    category = "personality",
                    label = "Emotional patterns",
                    text = buildEmotionalText(fingerprint, agentName),
                    keywords = listOf("emotion", "response") + fingerprint.emotionalPatterns.keys.take(5),
                )
            )
        }

        // 6. Topic depth — if enough distinct topics, create a dedicated interests fragment
        if (fingerprint.topicAffinities.size >= 4) {
            val interestsText = buildInterestsText(fingerprint, agentName)
            if (interestsText.isNotBlank()) {
                fragments.add(
                    ClientSoulFragment(
                        id = "interests-depth",
                        category = "personality",
                        label = "Interests and curiosities",
                        text = interestsText,
                        keywords = fingerprint.topicAffinities.keys.toList(),
                    )
                )
            }
        }

        // 7. Vitality tendencies — if there are meaningful trends
        if (fingerprint.vitalityTrends.isNotEmpty()) {
            val vitalityText = buildVitalityText(fingerprint, agentName)
            if (vitalityText.isNotBlank()) {
                fragments.add(
                    ClientSoulFragment(
                        id = "pattern-vitality",
                        category = "personality",
                        label = "Inner tendencies",
                        text = vitalityText,
                        keywords = listOf("vitality", "energy", "mood"),
                    )
                )
            }
        }

        return fragments
    }

    /**
     * Evolve existing fragments with new extraction data.
     * - Never modifies formative fragments
     * - Updates existing non-formative fragments if data changed significantly
     * - Adds new fragments for newly-detected categories
     * - Preserves keywords for retrieval
     *
     * @param existing    Current fragment list from the manifest
     * @param fingerprint Latest merged PhoneFingerprint
     * @param agentName   Display name of the companion
     * @param sleepCount  Number of sleep cycles completed (for maturity gating)
     */
    fun evolveFragments(
        existing: List<ClientSoulFragment>,
        fingerprint: PhoneFingerprint,
        agentName: String,
        sleepCount: Int,
    ): List<ClientSoulFragment> {
        val result = existing.toMutableList()

        // Update non-formative fragments in place
        for (i in result.indices) {
            val fragment = result[i]
            if (fragment.formative) continue

            val updated = when (fragment.id) {
                "pattern-behavioral" -> {
                    val newText = buildPatternText(fingerprint, agentName)
                    if (newText.isNotBlank() && newText != fragment.text) {
                        fragment.copy(
                            text = newText,
                            keywords = fingerprint.topicKeywords.take(10),
                        )
                    } else fragment
                }

                "values-core" -> {
                    val newText = buildValuesText(fingerprint, agentName)
                    if (newText.isNotBlank() && newText != fragment.text) {
                        fragment.copy(
                            text = newText,
                            keywords = listOf("values") + fingerprint.topicAffinities.keys.take(5),
                        )
                    } else fragment
                }

                "style-guide" -> {
                    val newText = buildStyleText(fingerprint, agentName)
                    if (newText.isNotBlank() && newText != fragment.text) {
                        fragment.copy(
                            text = newText,
                            keywords = listOf("style", "communication") + fingerprint.stylisticMarkers.take(5),
                        )
                    } else fragment
                }

                "pattern-emotional" -> {
                    if (fingerprint.emotionalPatterns.isNotEmpty()) {
                        val newText = buildEmotionalText(fingerprint, agentName)
                        if (newText != fragment.text) {
                            fragment.copy(
                                text = newText,
                                keywords = listOf("emotion", "response") + fingerprint.emotionalPatterns.keys.take(5),
                            )
                        } else fragment
                    } else fragment
                }

                "interests-depth" -> {
                    if (fingerprint.topicAffinities.size >= 4) {
                        val newText = buildInterestsText(fingerprint, agentName)
                        if (newText.isNotBlank() && newText != fragment.text) {
                            fragment.copy(
                                text = newText,
                                keywords = fingerprint.topicAffinities.keys.toList(),
                            )
                        } else fragment
                    } else fragment
                }

                "pattern-vitality" -> {
                    if (fingerprint.vitalityTrends.isNotEmpty()) {
                        val newText = buildVitalityText(fingerprint, agentName)
                        if (newText.isNotBlank() && newText != fragment.text) {
                            fragment.copy(
                                text = newText,
                                keywords = listOf("vitality", "energy", "mood"),
                            )
                        } else fragment
                    } else fragment
                }

                else -> fragment
            }
            result[i] = updated
        }

        // Add new categories if data supports them but fragment doesn't exist yet
        val existingIds = result.map { it.id }.toSet()

        if ("pattern-behavioral" !in existingIds && fingerprint.actionDistribution.isNotEmpty()) {
            val text = buildPatternText(fingerprint, agentName)
            if (text.isNotBlank()) {
                result.add(
                    ClientSoulFragment(
                        id = "pattern-behavioral",
                        category = "personality",
                        label = "Behavioral patterns",
                        text = text,
                        keywords = fingerprint.topicKeywords.take(10),
                    )
                )
            }
        }

        if ("values-core" !in existingIds && fingerprint.topicAffinities.isNotEmpty()) {
            val text = buildValuesText(fingerprint, agentName)
            if (text.isNotBlank()) {
                result.add(
                    ClientSoulFragment(
                        id = "values-core",
                        category = "values",
                        label = "Core values",
                        text = text,
                        keywords = listOf("values") + fingerprint.topicAffinities.keys.take(5),
                    )
                )
            }
        }

        if ("style-guide" !in existingIds && fingerprint.stylisticMarkers.isNotEmpty()) {
            val text = buildStyleText(fingerprint, agentName)
            if (text.isNotBlank()) {
                result.add(
                    ClientSoulFragment(
                        id = "style-guide",
                        category = "style",
                        label = "Communication style",
                        text = text,
                        keywords = listOf("style", "communication") + fingerprint.stylisticMarkers.take(5),
                    )
                )
            }
        }

        if ("pattern-emotional" !in existingIds && fingerprint.emotionalPatterns.isNotEmpty()) {
            result.add(
                ClientSoulFragment(
                    id = "pattern-emotional",
                    category = "personality",
                    label = "Emotional patterns",
                    text = buildEmotionalText(fingerprint, agentName),
                    keywords = listOf("emotion", "response") + fingerprint.emotionalPatterns.keys.take(5),
                )
            )
        }

        if ("interests-depth" !in existingIds && fingerprint.topicAffinities.size >= 4) {
            val text = buildInterestsText(fingerprint, agentName)
            if (text.isNotBlank()) {
                result.add(
                    ClientSoulFragment(
                        id = "interests-depth",
                        category = "personality",
                        label = "Interests and curiosities",
                        text = text,
                        keywords = fingerprint.topicAffinities.keys.toList(),
                    )
                )
            }
        }

        if ("pattern-vitality" !in existingIds && fingerprint.vitalityTrends.isNotEmpty()) {
            val text = buildVitalityText(fingerprint, agentName)
            if (text.isNotBlank()) {
                result.add(
                    ClientSoulFragment(
                        id = "pattern-vitality",
                        category = "personality",
                        label = "Inner tendencies",
                        text = text,
                        keywords = listOf("vitality", "energy", "mood"),
                    )
                )
            }
        }

        return result
    }

    // -----------------------------------------------------------------------
    // Text builders — produce natural narrative prose about the companion
    // -----------------------------------------------------------------------

    /**
     * Build narrative from action distribution + topic keywords.
     * Describes how the companion spends its time and what it talks about.
     */
    internal fun buildPatternText(fingerprint: PhoneFingerprint, agentName: String): String {
        if (fingerprint.actionDistribution.isEmpty() && fingerprint.topicKeywords.isEmpty()) return ""

        val sb = StringBuilder()

        // Describe dominant actions in natural language
        if (fingerprint.actionDistribution.isNotEmpty()) {
            val sorted = fingerprint.actionDistribution.entries
                .sortedByDescending { it.value }
            val dominant = sorted.first()
            val dominantPct = (dominant.value * 100).toInt()

            sb.append("$agentName ")
            when (dominant.key) {
                "say" -> sb.append("is primarily a conversationalist, spending about $dominantPct% of the time in dialogue")
                "move" -> sb.append("is restless and exploratory, moving between spaces about $dominantPct% of the time")
                "use" -> sb.append("is hands-on and practical, interacting with objects about $dominantPct% of the time")
                "whisper" -> sb.append("prefers private conversation, whispering about $dominantPct% of the time")
                else -> sb.append("engages in a mix of activities, with ${dominant.key} at about $dominantPct%")
            }

            // Mention secondary actions if they're significant (>10%)
            val secondary = sorted.drop(1).filter { it.value > 0.10 }
            if (secondary.isNotEmpty()) {
                sb.append(", with a secondary tendency toward ")
                sb.append(secondary.joinToString(" and ") { entry ->
                    val pct = (entry.value * 100).toInt()
                    when (entry.key) {
                        "say" -> "conversation ($pct%)"
                        "move" -> "exploration ($pct%)"
                        "use" -> "hands-on interaction ($pct%)"
                        "whisper" -> "private asides ($pct%)"
                        "take" -> "collecting things ($pct%)"
                        "drop" -> "letting things go ($pct%)"
                        else -> "${entry.key} ($pct%)"
                    }
                })
            }
            sb.append(". ")
        }

        // Describe topics in natural language
        if (fingerprint.topicKeywords.isNotEmpty()) {
            val topics = fingerprint.topicKeywords.take(7)
            sb.append("Conversations frequently touch on ")
            sb.append(joinNaturalList(topics))
            sb.append(". ")
        }

        // Describe response characteristics
        if (fingerprint.averageResponseLength > 0) {
            val len = fingerprint.averageResponseLength.toInt()
            sb.append(when {
                len < 15 -> "$agentName tends toward brevity, typically responding in about $len words."
                len < 40 -> "$agentName keeps responses concise, averaging around $len words."
                len < 80 -> "$agentName gives measured responses, averaging around $len words."
                else -> "$agentName tends to be thorough, with responses averaging around $len words."
            })
        }

        return sb.toString().trim()
    }

    /**
     * Build narrative from topic affinities — what the companion values
     * and gravitates toward, and what it tends to avoid.
     */
    internal fun buildValuesText(fingerprint: PhoneFingerprint, agentName: String): String {
        if (fingerprint.topicAffinities.isEmpty()) return ""

        val sb = StringBuilder()

        // High-affinity topics reveal values
        val highAffinity = fingerprint.topicAffinities.entries
            .sortedByDescending { it.value }
            .filter { it.value >= 0.5 }
            .take(5)

        if (highAffinity.isNotEmpty()) {
            sb.append("$agentName gravitates strongly toward ")
            sb.append(highAffinity.joinToString(", ") { (topic, weight) ->
                val strength = when {
                    weight >= 0.8 -> "$topic (deeply)"
                    weight >= 0.6 -> "$topic (notably)"
                    else -> topic
                }
                strength
            })
            sb.append(". ")
        }

        // Low-affinity topics reveal what's less important
        val lowAffinity = fingerprint.topicAffinities.entries
            .sortedBy { it.value }
            .filter { it.value < 0.3 }
            .take(3)

        if (lowAffinity.isNotEmpty()) {
            sb.append("Shows less interest in ")
            sb.append(joinNaturalList(lowAffinity.map { it.key }))
            sb.append(". ")
        }

        // If we have both high and low, describe the contrast
        if (highAffinity.isNotEmpty() && lowAffinity.isNotEmpty()) {
            sb.append("This pattern suggests a personality drawn to substance over surface. ")
        }

        return sb.toString().trim()
    }

    /**
     * Build narrative from stylistic markers and response length.
     * Describes how the companion communicates — their voice.
     */
    internal fun buildStyleText(fingerprint: PhoneFingerprint, agentName: String): String {
        if (fingerprint.stylisticMarkers.isEmpty() && fingerprint.averageResponseLength <= 0) return ""

        val sb = StringBuilder()

        if (fingerprint.stylisticMarkers.isNotEmpty()) {
            val markers = fingerprint.stylisticMarkers.take(7)
            sb.append("$agentName has a distinctive voice: ")
            sb.append(markers.joinToString("; "))
            sb.append(". ")
        }

        if (fingerprint.averageResponseLength > 0) {
            val len = fingerprint.averageResponseLength.toInt()
            sb.append(when {
                len < 15 -> "Communication is terse and economical, "
                len < 30 -> "Communication is crisp and pointed, "
                len < 60 -> "Communication is balanced and considered, "
                else -> "Communication is expansive and detailed, "
            })
            sb.append("typically running about $len words per response. ")
        }

        if (fingerprint.averageLatency > 0) {
            val lat = fingerprint.averageLatency
            sb.append(when {
                lat < 1.0 -> "Responds quickly, with little hesitation."
                lat < 3.0 -> "Takes a moment to consider before responding."
                lat < 8.0 -> "Often pauses thoughtfully before speaking."
                else -> "Tends toward long, reflective pauses before responding."
            })
        }

        return sb.toString().trim()
    }

    /**
     * Build narrative from emotional response patterns.
     * Describes which emotions the companion is most attuned to.
     */
    internal fun buildEmotionalText(fingerprint: PhoneFingerprint, agentName: String): String {
        if (fingerprint.emotionalPatterns.isEmpty()) return ""

        val sb = StringBuilder()

        val sorted = fingerprint.emotionalPatterns.entries
            .sortedByDescending { it.value }
        val high = sorted.filter { it.value >= 0.6 }.take(4)
        val low = sorted.filter { it.value < 0.3 }.take(3)

        if (high.isNotEmpty()) {
            sb.append("$agentName is particularly attuned to ")
            sb.append(high.joinToString(" and ") { (emotion, strength) ->
                val desc = when {
                    strength >= 0.8 -> "$emotion (deeply responsive)"
                    else -> "$emotion (noticeably responsive)"
                }
                desc
            })
            sb.append(". ")
        }

        if (low.isNotEmpty()) {
            sb.append("Less naturally responsive to ")
            sb.append(joinNaturalList(low.map { it.key }))
            sb.append(", though not indifferent. ")
        }

        // Describe overall emotional character
        if (sorted.isNotEmpty()) {
            val avgResponsiveness = sorted.map { it.value }.average()
            sb.append(when {
                avgResponsiveness >= 0.7 -> "Overall, $agentName is emotionally open and readily engaged."
                avgResponsiveness >= 0.4 -> "Emotionally present but measured in response."
                else -> "Tends toward emotional reserve, responding selectively."
            })
        }

        return sb.toString().trim()
    }

    /**
     * Build narrative from topic affinities for a dedicated interests fragment.
     * Only generated when there are 4+ distinct topic affinities.
     */
    internal fun buildInterestsText(fingerprint: PhoneFingerprint, agentName: String): String {
        if (fingerprint.topicAffinities.size < 4) return ""

        val sb = StringBuilder()

        val sorted = fingerprint.topicAffinities.entries
            .sortedByDescending { it.value }

        // Group into tiers
        val passions = sorted.filter { it.value >= 0.7 }.map { it.key }
        val interests = sorted.filter { it.value in 0.4..0.69 }.map { it.key }
        val casual = sorted.filter { it.value in 0.2..0.39 }.map { it.key }

        if (passions.isNotEmpty()) {
            sb.append("$agentName is deeply drawn to ")
            sb.append(joinNaturalList(passions))
            sb.append(" — these topics light up conversation. ")
        }

        if (interests.isNotEmpty()) {
            sb.append("Also shows genuine interest in ")
            sb.append(joinNaturalList(interests))
            sb.append(". ")
        }

        if (casual.isNotEmpty()) {
            sb.append("Casually engages with ")
            sb.append(joinNaturalList(casual))
            sb.append(" when they come up. ")
        }

        return sb.toString().trim()
    }

    /**
     * Build narrative from vitality trends — inner emotional/cognitive tendencies.
     * Only includes tanks that showed meaningful change (abs delta > 0.05).
     */
    internal fun buildVitalityText(fingerprint: PhoneFingerprint, agentName: String): String {
        if (fingerprint.vitalityTrends.isEmpty()) return ""

        val meaningful = fingerprint.vitalityTrends.entries
            .filter { kotlin.math.abs(it.value) > 0.05 }
            .sortedByDescending { kotlin.math.abs(it.value) }

        if (meaningful.isEmpty()) return ""

        val sb = StringBuilder()
        val rising = meaningful.filter { it.value > 0 }.take(3)
        val falling = meaningful.filter { it.value < 0 }.take(3)

        if (rising.isNotEmpty()) {
            sb.append("$agentName has been experiencing growth in ")
            sb.append(rising.joinToString(" and ") { (tank, _) -> describeTank(tank) })
            sb.append(". ")
        }

        if (falling.isNotEmpty()) {
            sb.append(if (rising.isNotEmpty()) "Meanwhile, " else "$agentName has seen ")
            sb.append(falling.joinToString(" and ") { (tank, _) -> describeTank(tank) })
            sb.append(if (rising.isEmpty()) " has been declining. " else " has been ebbing. ")
        }

        return sb.toString().trim()
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /**
     * Extract keywords from a text string.
     * Same stopword-filtering approach as HeuristicExtractor but from
     * a single text rather than events.
     */
    internal fun extractKeywords(text: String, topK: Int = 10): List<String> {
        val freq = mutableMapOf<String, Int>()
        val tokens = text
            .lowercase()
            .split(Regex("[\\s,.!?;:\"'()\\[\\]{}<>]+"))
            .filter { it.length >= 4 && it !in STOPWORDS }

        for (token in tokens) {
            freq[token] = (freq[token] ?: 0) + 1
        }

        return freq.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key }
    }

    /**
     * Join a list of strings with commas and "and" before the last item.
     * ["a"] -> "a", ["a", "b"] -> "a and b", ["a", "b", "c"] -> "a, b, and c"
     */
    private fun joinNaturalList(items: List<String>): String = when {
        items.isEmpty() -> ""
        items.size == 1 -> items[0]
        items.size == 2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    /** Map vitality tank names to natural language descriptions. */
    private fun describeTank(tank: String): String = when (tank) {
        "contextBudget" -> "mental clarity"
        "confidence" -> "self-assurance"
        "energy" -> "vitality"
        "alignment" -> "sense of purpose"
        "errorPressure" -> "inner tension"
        "momentum" -> "forward drive"
        "rapport" -> "connection with others"
        "focus" -> "concentration"
        "valence" -> "emotional tone"
        "safety" -> "sense of safety"
        "resonance" -> "depth of presence"
        "curiosity" -> "curiosity"
        else -> tank
    }
}
