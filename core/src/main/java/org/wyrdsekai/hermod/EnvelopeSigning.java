package org.wyrdsekai.hermod;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The origin signature on a task envelope: the sending device's Ed25519 key over the envelope's
 * stable fields. Verified at the receiving door before anything else.
 *
 * <p>Every envelope in production carried the constant byte {@code 1}, and no door looked, though
 * the gate's own contract said it must (audit of 2026-10-03). The carrier had no proof of who sent
 * a task; the day a data domain opened, any device on the household bus could have asked another
 * for its data under a forged origin, and the grant check could not have told whose request it was.
 */
public final class EnvelopeSigning {

    private EnvelopeSigning() {}

    /** The bytes a sender signs and a door verifies: everything but the signature, in one fixed order. */
    public static byte[] signingBytes(TaskEnvelope e) {
        var buf = ByteBuffer.allocate(64 * 1024);
        for (var s : new String[]{e.envelopeId(), e.householdId(), e.originDeviceId(), e.taskType(),
                                  e.dataDomain(), e.capabilityClass()}) {
            put(buf, s);
        }
        // Params in key order, so the same envelope always gives the same bytes.
        var params = new TreeMap<String, String>();
        if (e.params() != null) e.params().forEach((k, v) -> params.put(k == null ? "" : k, v == null ? "" : v));
        buf.putInt(params.size());
        for (var en : params.entrySet()) { put(buf, en.getKey()); put(buf, en.getValue()); }
        buf.putLong(e.tokenBudget());
        buf.putLong(e.issuedAt() == null ? 0L : e.issuedAt().toEpochMilli());
        buf.putLong(e.expiresAt() == null ? 0L : e.expiresAt().toEpochMilli());
        put(buf, e.grant().map(SignedGrant::grantId).orElse(""));
        var out = new byte[buf.position()];
        buf.rewind();
        buf.get(out);
        return out;
    }

    private static void put(ByteBuffer buf, String s) {
        var b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        buf.putInt(b.length).put(b);
    }

    /** The same envelope, signed by the sender ({@code signer} is the device's identity: bytes in, Ed25519 signature out). */
    public static TaskEnvelope sign(TaskEnvelope e, Function<byte[], byte[]> signer) {
        var sig = signer.apply(signingBytes(e));
        return new TaskEnvelope(e.envelopeId(), e.householdId(), e.originDeviceId(), e.taskType(), e.dataDomain(),
            e.capabilityClass(), e.params(), e.tokenBudget(), e.issuedAt(), e.expiresAt(), e.grant(), sig);
    }

    /** Whether the envelope's signature is the given device key's signature over its fields. */
    public static boolean verify(TaskEnvelope e, byte[] originSpki) {
        if (e == null || e.originSignature() == null || e.originSignature().length < 32
                || originSpki == null || originSpki.length == 0) return false;
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(GrantAuthority.publicKeyFromSpki(originSpki));
            sig.update(signingBytes(e));
            return sig.verify(e.originSignature());
        } catch (Exception ex) {
            return false;
        }
    }
}
