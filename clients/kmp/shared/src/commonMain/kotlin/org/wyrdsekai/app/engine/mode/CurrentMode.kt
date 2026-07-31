package org.wyrdsekai.app.engine.mode

import org.wyrdsekai.app.state.TokenStore

/**
 * Resolving the phone's mode from real app state.
 *
 * [decideMode] in PhoneMode.kt is pure and knows nothing about where facts
 * live. This file is the seam: it reads the five inputs out of [TokenStore].
 *
 * Kotlin twin of the RN client's engine/mode/currentMode.ts. Keeping the
 * decision separate from the gathering is what lets the decision table itself
 * be shared via clients/parity/parity.json.
 *
 */

/**
 * Read the mode inputs from persisted state.
 *
 * [hasOnDeviceModel] is passed in rather than probed: at boot the model file
 * may exist while the inference provider is not yet running, and those are
 * different questions. The caller knows which one it means.
 */
fun collectModeInputs(
    store: TokenStore,
    hasOnDeviceModel: Boolean,
): ModeInputs =
    modeInputsFrom(
        onDeviceModelOptIn = store.loadOnDeviceModelOptIn(),
        mode = store.loadMode(),
        zoneId = store.loadZoneId(),
        relayUrl = store.loadRelayUrl(),
        householdId = store.loadHouseholdId(),
        apiKey = store.loadApiKey(),
        apiProvider = store.loadApiProvider(),
        preferredBacking = store.loadPreferredBacking(),
        hasOnDeviceModel = hasOnDeviceModel,
    )

/**
 * The mapping itself, over raw persisted values.
 *
 * Split out from [collectModeInputs] so it is testable: [TokenStore] is a
 * concrete `expect class` backed by real platform preferences, so a test that
 * went through it would write to the developer's actual prefs store. The
 * interesting logic — what counts as a home zone, what counts as a usable key
 * — lives here where a test can reach it.
 */
fun modeInputsFrom(
    /**
     * The user's EXPERIMENTAL opt-in to running a model on this device.
     *
     * Today the only signal feeding viability (see [onDeviceModelViable]). A
     * separate field from the tree's input on purpose: when a measurement can
     * answer the question, it joins here and the tree is untouched.
     */
    onDeviceModelOptIn: Boolean = false,
    mode: String?,
    zoneId: String?,
    relayUrl: String?,
    householdId: String?,
    apiKey: String?,
    apiProvider: String?,
    preferredBacking: String?,
    hasOnDeviceModel: Boolean,
): ModeInputs = ModeInputs(
    onDeviceModelViable = onDeviceModelViable(optIn = onDeviceModelOptIn),
    // "local" means the user asked for their own node; "remote" means
    // terminal. Absent is first run.
    wantsOwnNode = mode == "local",
    // Any of these means a household is known or reachable.
    hasHomeZone = !zoneId.isNullOrBlank() ||
        !relayUrl.isNullOrBlank() ||
        !householdId.isNullOrBlank(),
    // A key alone is not enough — without a provider there is no URL to send
    // it to, and the request would go out unaddressed.
    hasCloudKey = !apiKey.isNullOrBlank() && !apiProvider.isNullOrBlank(),
    hasOnDeviceModel = hasOnDeviceModel,
    // Anything unrecognised (or absent) means the user never chose, and HOME
    // is the right default for someone who paired with a household.
    preferredBacking = if (preferredBacking == "cloud") Backing.CLOUD else Backing.HOME,
)

/** Gather inputs and decide. */
fun resolvePhoneMode(
    store: TokenStore,
    hasOnDeviceModel: Boolean,
): ModeDecision = decideMode(collectModeInputs(store, hasOnDeviceModel))

/**
 * Is running the companion's model on THIS device viable?
 *
 * The single place that policy lives. Deliberately a function over a bag of
 * signals rather than a bare boolean read, because the set of signals is
 * expected to grow and the tree must not care:
 *
 *   - today → the user's explicit EXPERIMENTAL opt-in, and nothing else.
 *             Measured phone throughput does not clear a usable bar (see the
 *             evidence in PhoneMode.kt), so no device is auto-promoted.
 *   - later → add [measuredTokensPerSecond] and return true when it beats a
 *             floor. A loaded tablet then qualifies on its own merits, the
 *             opt-in becomes a manual override rather than the only door, and
 *             decideMode does not change by one line.
 *
 * Keeping this OUT of decideMode is what makes that future edit a one-function
 * change instead of a re-litigation of the mode tree.
 */
fun onDeviceModelViable(
    optIn: Boolean,
    /** Reserved: measured decode throughput, once we measure it. */
    @Suppress("UNUSED_PARAMETER") measuredTokensPerSecond: Int? = null,
): Boolean = optIn

/**
 * Throughput at which an on-device model stops being an annoyance.
 *
 * People read at roughly 7-10 tokens/s, so below this the companion is visibly
 * behind the reader. Unused until something measures throughput; named now so
 * the future check has a number to point at rather than inventing one.
 */
const val USABLE_TOKENS_PER_SECOND = 10

/** Persist the user's 4-vs-5 choice. */
fun savePreferredBacking(store: TokenStore, backing: Backing) {
    store.savePreferredBacking(if (backing == Backing.CLOUD) "cloud" else "home")
}

/** Human-readable name for a mode, for settings and diagnostics. */
fun modeLabel(mode: PhoneMode?): String = when (mode) {
    PhoneMode.REMOTE_TERMINAL -> "Remote terminal"
    PhoneMode.LOCAL_CLOUD_NO_ZONE -> "On this phone, cloud API"
    PhoneMode.LOCAL_ON_DEVICE -> "On this phone, on-device model"
    PhoneMode.LOCAL_HOME_GPU -> "On this phone, home zone behind it"
    PhoneMode.LOCAL_CLOUD -> "On this phone, cloud API behind it"
    null -> "Setup incomplete"
}

/**
 * Which backings the user can actually choose between right now.
 *
 * Offering CLOUD with no API key configured would be offering a mode that
 * cannot answer, so the settings surface asks this first.
 */
fun availableBackings(i: ModeInputs): List<Backing> = buildList {
    if (i.hasHomeZone) add(Backing.HOME)
    if (i.hasCloudKey) add(Backing.CLOUD)
}
