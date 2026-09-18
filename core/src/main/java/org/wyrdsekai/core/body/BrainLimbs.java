package org.wyrdsekai.core.body;

import java.time.Duration;
import java.util.Locale;

/**
 * How an inference backend describes itself as a part of the body. The router knows names,
 * types and URLs; she knows a voice brain and a thinking brain, and what each one's silence
 * costs her.
 */
public final class BrainLimbs {

    private BrainLimbs() {}

    public static String id(String backendName) {
        return "brain:" + backendName;
    }

    /**
     * @param voiceUrl the node's configured voice URL, so the voice brain can be told from
     *                 the drive by where it lives and not only by its name
     */
    public static LimbDescriptor forBackend(String name, String type, String url, int priority,
                                            Duration heartbeatEvery, String voiceUrl) {
        return forBackend(name, type, url, priority, heartbeatEvery, voiceUrl, null);
    }

    /**
     * @param attachedBy the node that offers a borrowed brain when that node is not the
     *                   household's own; null for the household's brains. A stranger's brain
     *                   waits at the door like any other foreign part.
     */
    public static LimbDescriptor forBackend(String name, String type, String url, int priority,
                                            Duration heartbeatEvery, String voiceUrl, String attachedBy) {
        var lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        var t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        boolean remote = url != null && url.startsWith("nats://");
        boolean cloud = "cloud".equals(t) || "openrouter".equals(t) || "anthropic".equals(t) || "openai".equals(t);
        boolean voice = lower.contains("voice") || sameServer(url, voiceUrl);

        String partName; String numb; FeltWeight weight;
        if (remote) {
            partName = "borrowed brain (" + name + ")";
            numb = "the brain I borrow across the mesh is out of reach; I think with what is here";
            weight = FeltWeight.QUIET;
        } else if (cloud) {
            partName = "far brain (" + name + ")";
            numb = "the far brain is out of reach; I think with what is here";
            weight = FeltWeight.QUIET;
        } else if (voice) {
            partName = "voice brain";
            numb = "my words come slower and plainer, from the thinking brain alone";
            weight = FeltWeight.PRESENT;
        } else {
            partName = "thinking brain";
            numb = "I think slower and thinner; the voice brain is carrying my decisions too";
            weight = FeltWeight.LOUD;
        }
        return new LimbDescriptor(id(name), BodyKind.BRAIN, partName, attachedBy == null ? "household" : "peer", url,
            heartbeatEvery, weight, numb, "first", attachedBy,
            attachedBy == null ? null : "an inference backend offered across the mesh");
    }

    static boolean sameServer(String a, String b) {
        if (a == null || b == null) return false;
        return strip(a).equals(strip(b));
    }

    private static String strip(String url) {
        var s = url.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("/v1")) s = s.substring(0, s.length() - 3);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.replace("localhost", "127.0.0.1");
    }
}
