package org.wyrdsekai.core.inference;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Core-side seam to the hermod mesh, installed by the server at boot
 * (BunshinScheduler.install pattern). Core code — BunshinActor — asks
 * the mesh to carry a full chat turn; absent installation, callers fall
 * back to the local InferenceRouter. The payload is the serialized
 * ChatRequest and the return the serialized ChatResponse, so tool calls
 * survive the ride intact.
 */
public final class MeshDispatch {

    public interface Carrier {
        /** @return serialized ChatResponse JSON, or null if no device would take it. */
        String carryChat(String chatRequestJson, long tokenBudget);
    }

    private static final AtomicReference<Carrier> INSTALLED = new AtomicReference<>();

    private MeshDispatch() {}

    public static void install(Carrier carrier) {
        INSTALLED.set(carrier);
    }

    public static Carrier installed() {
        return INSTALLED.get();
    }
}
