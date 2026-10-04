package org.wyrdsekai.scripting.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One run of an item script, and what it has read in it.
 *
 * <p>The shape of a leak through an item is exact: read something private, then send it out, in
 * one breath. So that is what is refused. Once an item that is not the house's own has read a
 * private thing (her memory, her journal, the people's grants, a join secret, the safe), outbound
 * web and network calls are refused for the rest of that run, with the reason, and the refusal is
 * audited. An item that fetches first and reads nothing private, the weather tool a person asked
 * for, is untouched. No list of allowed sites, nothing for the steward to keep (operator, 2026-10-03:
 * "i dont want the steward to have to be hassled about this ... lets do what you now recommend").
 */
public final class Breath {

    private volatile String privateRead;

    /** The item read something private; the first such read is remembered. */
    public void privateRead(String what) {
        if (what != null && privateRead == null) privateRead = what;
    }

    /** What was read, or null when nothing private was. */
    public String privateReadWas() { return privateRead; }

    /**
     * Whether an outbound call may go now: null when it may; else the reason, after the refusal has
     * been counted against the item's capability set and audited. The house's own items (an
     * unrestricted set) are not held.
     */
    public static String held(Breath breath, ItemCapabilitySet caps, String capability) {
        if (breath == null || caps == null || caps.isUnrestricted()) return null;
        var read = breath.privateReadWas();
        if (read == null) return null;
        caps.refuse(capability);
        return "private_read_before_send: this item read private things of the household (" + read
            + ") in this run, so it may not reach the web or the network in the same breath";
    }

    /** The answer a refused outbound call returns in place of its result. */
    public static Map<String, Object> refused(String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("status", 0);
        out.put("error", "private_read_before_send");
        out.put("message", reason);
        return out;
    }
}
