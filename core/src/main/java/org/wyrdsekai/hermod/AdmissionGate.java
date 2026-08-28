package org.wyrdsekai.hermod;

/**
 * Owned by the EXECUTING device — the serialization point of the whole
 * mesh. A routing decision is only ever a proposal; this door decides.
 * Refusal is a normal outcome and must be cheap.
 */
public interface AdmissionGate {

    enum Verdict { ADMIT, QUEUE, REFUSE }

    record Decision(Verdict verdict, String reason) {
        public static Decision admit() { return new Decision(Verdict.ADMIT, ""); }
        public static Decision queue(String why) { return new Decision(Verdict.QUEUE, why); }
        public static Decision refuse(String why) { return new Decision(Verdict.REFUSE, why); }
    }

    /**
     * Must verify: envelope signature, expiry, token budget vs local
     * policy, and — for any dataDomain != "none" — the SignedGrant
     * against the household authority key. Grant verification happens
     * HERE, at the data, never at the router.
     */
    Decision consider(TaskEnvelope envelope);
}
