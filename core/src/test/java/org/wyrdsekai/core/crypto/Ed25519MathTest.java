package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic tests for Ed25519 curve arithmetic.
 */
class Ed25519MathTest {

    @Test void base_point_is_on_curve() {
        var x = Ed25519Math.B.x();
        var y = Ed25519Math.B.y();
        var P = Ed25519Math.P;
        var D = Ed25519Math.D;

        // -x^2 + y^2 = 1 + d*x^2*y^2 (mod p)
        var lhs = y.multiply(y).subtract(x.multiply(x)).mod(P);
        var rhs = BigInteger.ONE.add(D.multiply(x).multiply(x).multiply(y).multiply(y)).mod(P);
        assertThat(lhs).isEqualTo(rhs);
    }

    @Test void scalar_mult_identity() {
        var result = Ed25519Math.scalarMult(BigInteger.ONE, Ed25519Math.B);
        assertThat(result.x()).isEqualTo(Ed25519Math.B.x());
        assertThat(result.y()).isEqualTo(Ed25519Math.B.y());
    }

    @Test void order_times_base_is_identity() {
        var result = Ed25519Math.scalarMult(Ed25519Math.L, Ed25519Math.B);
        assertThat(result.isIdentity()).isTrue();
    }

    @Test void encode_decode_roundtrip() {
        var encoded = Ed25519Math.encodePoint(Ed25519Math.B);
        assertThat(encoded).hasSize(32);
        var decoded = Ed25519Math.decodePoint(encoded);
        assertThat(decoded.x()).isEqualTo(Ed25519Math.B.x());
        assertThat(decoded.y()).isEqualTo(Ed25519Math.B.y());
    }

    @Test void scalar_encode_decode_roundtrip() {
        var s = Ed25519Math.randomScalar();
        var encoded = Ed25519Math.encodeScalar(s);
        var decoded = Ed25519Math.decodeScalar(encoded);
        assertThat(decoded).isEqualTo(s);
    }

    @Test void add_point_to_itself_equals_double() {
        var doubled = Ed25519Math.scalarMult(BigInteger.TWO, Ed25519Math.B);
        var added = Ed25519Math.add(Ed25519Math.B, Ed25519Math.B);
        assertThat(added.x()).isEqualTo(doubled.x());
        assertThat(added.y()).isEqualTo(doubled.y());
    }
}
