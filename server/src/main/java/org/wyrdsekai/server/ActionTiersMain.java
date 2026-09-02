package org.wyrdsekai.server;

import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.agent.ActionPolicy.AutonomyTier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeSet;

/**
 * {@code wyrd grants tiers} — the autonomy ladder, verb by verb.
 *
 * <p>Until 2026-09-01 the only way to learn which verbs a companion may use on
 * her own time — and which rung a steward grant would lift — was to read
 * {@code ActionPolicy.java}. The steward asked, in so many words, "how does one
 * know what things are upgradeable". This prints it: every verb the policy
 * knows, its rung, its domain and maturity tier, and the exact grant resource
 * that promotes it. Verbs the policy never classified fall to CONSENT by
 * default (conservative), and are listed as such.
 *
 * <p>Pure: reads the policy tables only, opens no database, needs no running
 * server. Which grants are currently HELD is the server's business —
 * {@code wyrd grants held --subject <did>}.
 */
public final class ActionTiersMain {

    private ActionTiersMain() {}

    public static void main(String[] args) {
        boolean json = false;
        for (var a : args) if ("--json".equals(a)) json = true;

        var verbs = new TreeSet<String>();
        verbs.addAll(ActionPolicy.AUTONOMY_TIERS.keySet());
        verbs.addAll(ActionPolicy.REGISTRY.keySet());

        var byTier = new EnumMap<AutonomyTier, List<String>>(AutonomyTier.class);
        for (var t : AutonomyTier.values()) byTier.put(t, new ArrayList<>());
        for (var v : verbs) byTier.get(ActionPolicy.autonomyTierFor(v)).add(v);

        if (json) {
            var sb = new StringBuilder("[\n");
            boolean first = true;
            for (var v : verbs) {
                var pol = ActionPolicy.forAction(v);
                if (!first) sb.append(",\n");
                first = false;
                sb.append("  {\"verb\":\"").append(v)
                  .append("\",\"autonomy\":\"").append(ActionPolicy.autonomyTierFor(v))
                  .append("\",\"classified\":").append(ActionPolicy.AUTONOMY_TIERS.containsKey(v))
                  .append(",\"domain\":\"").append(pol.domain() == null ? "" : pol.domain())
                  .append("\",\"maturity_tier\":").append(pol.requiredTier())
                  .append(",\"grant_resource\":\"home://<owner-did>/action/").append(v).append("\"}");
            }
            System.out.println(sb.append("\n]"));
            return;
        }

        System.out.println("The autonomy ladder — what a companion may do on her OWN time.");
        System.out.println("  AMBIENT   freely            VISIBLE   freely, lands on the steward feed");
        System.out.println("  CONSENT   asks first        FORBIDDEN never unprompted");
        System.out.println("A steward grant lifts a verb for one companion:");
        System.out.println("  wyrd grants issue --subject <companion-did> \\");
        System.out.println("      --resource home://<owner-did>/action/<verb> --capability use");
        System.out.println("(the gate honours it for FORBIDDEN always, for CONSENT when consent-strict is on;");
        System.out.println(" 'wyrd grants revoke' is the downgrade; 'wyrd grants held --subject <did>' lists what is lifted now)");
        for (var t : AutonomyTier.values()) {
            var list = byTier.get(t);
            System.out.println();
            System.out.println("── " + t + " (" + list.size() + ") " + "─".repeat(Math.max(1, 52 - t.name().length())));
            for (var v : list) {
                var pol = ActionPolicy.forAction(v);
                var note = ActionPolicy.AUTONOMY_TIERS.containsKey(v) ? "" : "   (unclassified → default)";
                System.out.printf("  %-30s %-14s maturity %d%s%n", v,
                    pol.domain() == null ? "-" : pol.domain(), pol.requiredTier(), note);
            }
        }
    }
}
