package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import java.util.HashSet;
import java.util.List;

/**
 * Records, after the fact, that a folded companion became another one.
 *
 * <p><b>Why a reconciliation and not a step inside {@link AgentRebind}.</b> The
 * rebind moves rows and can run offline, against a database copy, from a repair
 * tool — none of which have the household secret. Signing needs the zone master,
 * which only exists correctly inside a booted node. Deriving it anywhere else
 * means calling {@code ZoneSecrets.bootstrapLocalZone} by hand, and getting
 * {@code nodeId} wrong there finds no wrapped master and <b>originates a new
 * one</b> — which would make the person's encrypted private key and every
 * content envelope unreadable. So the rebind moves the data, and this closes the
 * record later, in the one place the key is already installed.</p>
 *
 * <p><b>Why it matters.</b> Rebinding deliberately leaves audit history under the
 * old identity — rewriting {@code audit_log.actor} would assert a different agent
 * acted. Those rows are meant to be read <em>through</em> an attestation. Without
 * one they simply point at an identity that no longer answers: on the live
 * household, 168 rows after the 2026-08-08 merge.</p>
 *
 * <p>The attestation is <b>self-issued when the companion can sign</b> and
 * witnessed by a person when it cannot. Every companion born before
 * {@link AgentIdentityStore} existed falls in the second case — its DID was
 * minted with the private half discarded, so its own declaration is not
 * available and recording the steward's observation is the honest substitute.
 * See {@link RebindAttestation#issueSelf} and
 * {@link RebindAttestation#issueWitnessed}. Idempotent: an existing attestation
 * for a pair is never re-issued, so a companion that gains a key later does not
 * cause the old record to be rewritten.</p>
 */
public final class AgentRebindAttestor {

    private static final Logger log = LoggerFactory.getLogger(AgentRebindAttestor.class);

    private AgentRebindAttestor() {}

    /** What the reconciliation found and did. */
    public record Result(int pending, int attested, int skipped, List<String> notes) {}

    /**
     * Issue a witnessed attestation for every archived companion that has none.
     *
     * <p>An archived companion whose {@code entity_id} still has a live sibling is
     * a fold: the household saw one companion throughout, and the archived DID is
     * what she used to be called. Anything else — an archive with no survivor —
     * is a retirement, not a rebind, and is left alone.</p>
     *
     * @param attesterDid the person vouching; null picks the node's sole person
     */
    public static Result reconcile(String jdbcUrl, Supplier<byte[]> householdSecret,
                                   String attesterDid) {
        var notes = new ArrayList<String>();
        var folds = new LinkedHashMap<String, String>();   // archived DID → surviving DID
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(
                 "SELECT a.did, b.did FROM companions a JOIN companions b"
                     + " ON a.entity_id = b.entity_id"
                     + " WHERE a.archived = 1 AND b.archived = 0 AND a.did <> b.did")) {
            var rs = st.executeQuery();
            while (rs.next()) folds.put(rs.getString(1), rs.getString(2));
        } catch (Exception e) {
            log.warn("[RebindAttest] could not scan for folded companions: {}", e.getMessage());
            return new Result(0, 0, 0, notes);
        }
        if (folds.isEmpty()) return new Result(0, 0, 0, notes);

        var store = new RebindAttestationStore(jdbcUrl);
        var existing = new HashSet<String>();
        for (var a : store.all()) existing.add(a.fromDid() + "->" + a.toDid());

        var identities = new PersonIdentityStore(jdbcUrl);
        var attester = resolveAttester(identities, attesterDid);
        if (attester == null) {
            log.warn("[RebindAttest] {} folded companion(s) have no attestation, and no person "
                + "identity is available to witness one. History under the old DID(s) cannot be "
                + "resolved until a person attests.", folds.size());
            return new Result(folds.size(), 0, folds.size(), notes);
        }
        var secret = householdSecret == null ? null : householdSecret.get();
        if (secret == null) {
            log.warn("[RebindAttest] household secret unavailable — cannot sign; "
                + "{} fold(s) left unattested.", folds.size());
            return new Result(folds.size(), 0, folds.size(), notes);
        }

        int attested = 0;
        int skipped = 0;
        var agentSecret = AgentIdentityProvisioner.secret().orElse(null);
        for (var e : folds.entrySet()) {
            if (existing.contains(e.getKey() + "->" + e.getValue())) { skipped++; continue; }
            try {
                // Prefer the companion's own word. "I declare I became them" can
                // only be said by the one who was there, which is what makes it
                // worth more than an observation — but it needs a key, and every
                // companion born before AgentIdentityStore has none. Fall back to
                // the witness rather than pretend she signed it.
                var self = agentSecret == null ? null
                    : AgentIdentityProvisioner.find(e.getKey())
                        .filter(i -> i.privateKeyEncrypted() != null).orElse(null);
                RebindAttestation att;
                if (self != null) {
                    att = RebindAttestation.issueSelf(self, e.getValue(), agentSecret);
                    notes.add(e.getKey() + " → " + e.getValue() + " (self-issued)");
                } else {
                    att = RebindAttestation.issueWitnessed(
                        attester, e.getKey(), e.getValue(), secret);
                    notes.add(e.getKey() + " → " + e.getValue()
                        + " (witnessed by " + attester.did() + ")");
                }
                store.save(att);
                attested++;
            } catch (Exception ex) {
                log.warn("[RebindAttest] could not attest {} → {}: {}",
                    e.getKey(), e.getValue(), ex.toString());
                skipped++;
            }
        }
        if (attested > 0) {
            log.info("[RebindAttest] recorded {} witnessed rebind(s); history under the old "
                + "identity now resolves forward", attested);
        }
        return new Result(folds.size(), attested, skipped, notes);
    }

    /**
     * The person who vouches. A named one when given; otherwise the node's sole
     * person — and NOT a guess when there are several, because "who attested"
     * is exactly the thing this record exists to state.
     */
    private static PersonIdentity resolveAttester(PersonIdentityStore store, String did) {
        if (did != null && !did.isBlank()) return store.findByDid(did).orElse(null);
        var all = store.listDids();
        if (all.size() == 1) return store.findByDid(all.getFirst()).orElse(null);
        if (all.size() > 1) {
            log.warn("[RebindAttest] {} people on this node — pass an explicit attester rather "
                + "than have the record name an arbitrary one.", all.size());
        }
        return null;
    }
}
