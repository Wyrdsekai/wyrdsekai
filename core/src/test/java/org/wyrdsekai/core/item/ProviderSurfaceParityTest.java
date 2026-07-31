package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The two item-provider hierarchies — {@link HomeOwnerItemProvider} (player route)
 * and {@link ItemWorldApiProviderImpl} (companion route) — must BOTH override the
 * {@code world.*} surfaces that shipped Study furnishings depend on. When only one
 * overrides, the furnishing renders real data on one route and a silent empty on
 * the other (2026-07-18: the bond crystal, journal, recipes console, treasury, and
 * council board were all instances of exactly this). These are the surfaces we
 * wired on both routes; this test fails if either regresses to the interface
 * default.
 *
 * <p>Not every surface belongs here — some are legitimately one-route (a companion's
 * own {@code driveSnapshot}, player-only {@code pairedDevices}). This pins only the
 * ones a furnishing reads from either surface.</p>
 */
class ProviderSurfaceParityTest {

    /** Surfaces a Study furnishing invokes that must resolve on BOTH routes. */
    private static final String[][] DUAL_ROUTE_SURFACES = {
        {"journalWrite", "java.lang.String", "java.util.Map"},
        {"journalRecent", "int"},
        {"journalSearch", "java.lang.String", "int"},
        {"treasurySummary"},
        {"treasuryPerMember"},
        {"budgetSummary"},
        {"councilProposals"},
        {"councilHistory", "int"},
        {"recipeEnrolled"},
        {"recipeRecentRuns", "int"},
        {"codingBackendsStatus"},
        {"bondsList"},
        {"companionsList"},
    };

    @Test
    @DisplayName("both provider hierarchies override every dual-route furnishing surface")
    void bothHierarchiesOverrideDualRouteSurfaces() {
        var missing = new StringBuilder();
        for (var sig : DUAL_ROUTE_SURFACES) {
            checkDeclares(HomeOwnerItemProvider.class, sig, missing);
            checkDeclares(ItemWorldApiProviderImpl.class, sig, missing);
        }
        if (missing.length() > 0) {
            fail("Provider surfaces missing an override (would silently return the "
                + "empty interface default on that route):\n" + missing);
        }
    }

    private static void checkDeclares(Class<?> provider, String[] sig, StringBuilder missing) {
        var name = sig[0];
        Class<?>[] params = new Class<?>[sig.length - 1];
        try {
            for (int i = 1; i < sig.length; i++) params[i - 1] = classFor(sig[i]);
        } catch (ClassNotFoundException e) {
            missing.append("  (bad test param ").append(e.getMessage()).append(")\n");
            return;
        }
        try {
            provider.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            missing.append("  ").append(provider.getSimpleName())
                .append(" does not override ").append(name).append("\n");
        }
    }

    private static Class<?> classFor(String s) throws ClassNotFoundException {
        return switch (s) {
            case "int" -> int.class;
            default -> Class.forName(s);
        };
    }

    @Test
    @DisplayName("the journal.write default carries ok:false so a not-wired write can't read as success")
    @SuppressWarnings("unchecked")
    void journalWriteDefaultIsNotSuccessShaped() throws Throwable {
        // The default must be ok:false — the journal item guards on written.ok===false,
        // and a bare {error} default read as success and silently discarded the entry.
        // The interface has abstract methods, so drive the DEFAULT via a proxy that
        // invokes default methods and throws on anything abstract (we only call one).
        var iface = org.wyrdsekai.scripting.api.ItemWorldApiProvider.class;
        var proxy = java.lang.reflect.Proxy.newProxyInstance(
            iface.getClassLoader(), new Class<?>[]{iface},
            (p, method, args) -> {
                if (method.isDefault()) {
                    return java.lang.reflect.InvocationHandler.invokeDefault(p, method, args);
                }
                throw new UnsupportedOperationException(method.getName());
            });
        var result = (java.util.Map<String, Object>)
            ((org.wyrdsekai.scripting.api.ItemWorldApiProvider) proxy).journalWrite("x", null);
        assertTrue(Boolean.FALSE.equals(result.get("ok")),
            "journalWrite default must return ok:false, was: " + result);
    }
}
