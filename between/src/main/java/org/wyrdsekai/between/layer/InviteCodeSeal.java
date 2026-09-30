package org.wyrdsekai.between.layer;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Seals an invite code to one household machine's X25519 key, so a replicated invite never crosses the
 * bus in the clear (audit 2026-09-28). Ephemeral-static X25519, HKDF-SHA256, AES-256-GCM; the invite id
 * and the recipient node id are bound in as associated data, so a sealed code cannot be moved to another
 * invite or another machine.
 */
final class InviteCodeSeal {

    private static final byte[] INFO = "wyrd-invite-code-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG = new SecureRandom();

    private InviteCodeSeal() {}

    static String seal(String code, byte[] recipientX25519Spki, String inviteId, String recipientNodeId)
            throws GeneralSecurityException {
        var kpg = KeyPairGenerator.getInstance("XDH");
        kpg.initialize(new NamedParameterSpec("X25519"));
        var eph = kpg.generateKeyPair();
        var recipient = KeyFactory.getInstance("XDH").generatePublic(new X509EncodedKeySpec(recipientX25519Spki));
        var key = derive(eph.getPrivate(), recipient);
        var iv = new byte[12];
        RNG.nextBytes(iv);
        var gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        gcm.updateAAD(aad(inviteId, recipientNodeId));
        var ct = gcm.doFinal(code.getBytes(StandardCharsets.UTF_8));
        var ephPub = eph.getPublic().getEncoded();
        var out = ByteBuffer.allocate(2 + ephPub.length + iv.length + ct.length);
        out.putShort((short) ephPub.length).put(ephPub).put(iv).put(ct);
        return Base64.getEncoder().encodeToString(out.array());
    }

    static String open(String sealed, byte[] myX25519Pkcs8, String inviteId, String myNodeId)
            throws GeneralSecurityException {
        var in = ByteBuffer.wrap(Base64.getDecoder().decode(sealed));
        var ephPub = new byte[in.getShort() & 0xFFFF];
        in.get(ephPub);
        var iv = new byte[12];
        in.get(iv);
        var ct = new byte[in.remaining()];
        in.get(ct);
        var xdh = KeyFactory.getInstance("XDH");
        var key = derive(xdh.generatePrivate(new PKCS8EncodedKeySpec(myX25519Pkcs8)),
            xdh.generatePublic(new X509EncodedKeySpec(ephPub)));
        var gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        gcm.updateAAD(aad(inviteId, myNodeId));
        return new String(gcm.doFinal(ct), StandardCharsets.UTF_8);
    }

    private static byte[] derive(PrivateKey priv, PublicKey pub) throws GeneralSecurityException {
        var ka = KeyAgreement.getInstance("XDH");
        ka.init(priv);
        ka.doPhase(pub, true);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        var prk = mac.doFinal(ka.generateSecret());              // HKDF extract
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(INFO);
        mac.update((byte) 1);
        return mac.doFinal();                                   // HKDF expand, one block
    }

    private static byte[] aad(String inviteId, String nodeId) {
        return (inviteId + "|" + nodeId).getBytes(StandardCharsets.UTF_8);
    }
}
