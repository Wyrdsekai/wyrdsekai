package org.wyrdsekai.core.crypto;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Shamir-based threshold signer.
 * Splits an Ed25519 private key using Shamir's SS, reconstructs for signing.
 * This is a simplified implementation — not a true DKG/FROST scheme.
 * FROST integration deferred to M2+ when Java libraries are available.
 */
class ShamirThresholdSigner implements ThresholdSigner {

    private KeyPair keyPair;
    private int threshold;

    ShamirThresholdSigner() {
        try {
            var keyGen = KeyPairGenerator.getInstance("Ed25519");
            this.keyPair = keyGen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 not available", e);
        }
    }

    @Override
    public List<byte[]> generateKeyShares(int n, int k) {
        this.threshold = k;
        var privateKeyBytes = keyPair.getPrivate().getEncoded();
        var shares = ShamirSecretSharing.split(privateKeyBytes, n, k);
        var result = new ArrayList<byte[]>();
        for (var share : shares) {
            // Encode: x (1 byte) + y (variable)
            var encoded = new byte[1 + share.y().length];
            encoded[0] = (byte) share.x();
            System.arraycopy(share.y(), 0, encoded, 1, share.y().length);
            result.add(encoded);
        }
        return result;
    }

    @Override
    public PartialSignature partialSign(int participantId, byte[] keyShare, byte[] message) {
        // Decode share
        int x = keyShare[0] & 0xFF;
        var y = new byte[keyShare.length - 1];
        System.arraycopy(keyShare, 1, y, 0, y.length);
        return new PartialSignature(participantId, keyShare, keyPair.getPublic().getEncoded());
    }

    @Override
    public CombinedSignature combine(List<PartialSignature> partials, byte[] message) {
        if (partials.size() < threshold) {
            return new CombinedSignature(new byte[0], false);
        }

        // Reconstruct key from shares
        var shares = new ArrayList<ShamirSecretSharing.Share>();
        for (var partial : partials) {
            var keyShare = partial.signatureBytes();
            int x = keyShare[0] & 0xFF;
            var y = new byte[keyShare.length - 1];
            System.arraycopy(keyShare, 1, y, 0, y.length);
            shares.add(new ShamirSecretSharing.Share(x, y));
        }

        try {
            var reconstructedKeyBytes = ShamirSecretSharing.reconstruct(
                shares.subList(0, Math.min(shares.size(), threshold)));

            // Sign with reconstructed key
            var keyFactory = KeyFactory.getInstance("Ed25519");
            var privateKey = keyFactory.generatePrivate(
                new PKCS8EncodedKeySpec(reconstructedKeyBytes));

            var sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(message);
            var signature = sig.sign();

            return new CombinedSignature(signature, true);
        } catch (Exception e) {
            return new CombinedSignature(new byte[0], false);
        }
    }

    @Override
    public boolean verify(byte[] signature, byte[] message, byte[] publicKeyBytes) {
        try {
            var keyFactory = KeyFactory.getInstance("Ed25519");
            var publicKey = keyFactory.generatePublic(
                new X509EncodedKeySpec(publicKeyBytes));

            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
