#!/usr/bin/env python3
"""
Regression test for the bundled QR renderer (scripts/lib/wyrd_qr.py).

Runs standalone (no deps) with structural checks that a real QR must satisfy,
and — when the reference `qrcode` package happens to be importable — asserts
byte-for-byte parity across the version range, all four ECC levels, and both
forced and auto-selected masks. That parity is how the encoder was originally
proven correct; keeping it here catches any future drift.

    python3 scripts/lib/test_wyrd_qr.py      # exits non-zero on failure
"""
import importlib.util
import os
import random
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("wyrd_qr", os.path.join(_HERE, "wyrd_qr.py"))
wq = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(wq)


def _finder_at(m, r, c):
    """The 7x7 finder pattern must sit at (r, c)."""
    for dr in range(7):
        for dc in range(7):
            dark = (dr in (0, 6) or dc in (0, 6) or (2 <= dr <= 4 and 2 <= dc <= 4))
            if m[r + dr][c + dc] != dark:
                return False
    return True


def test_structure():
    """A rendered code has the right size and three finder patterns."""
    for payload, ecl in [("hi", "M"),
                         ("wyrdphone://198.51.100.5:4443/" + "a" * 600, "M"),
                         ("x" * 40, "H")]:
        m = wq.make(payload, ecl)
        n = len(m)
        assert (n - 17) % 4 == 0 and 21 <= n <= 177, f"bad size {n}"
        assert all(len(row) == n for row in m), "non-square matrix"
        assert _finder_at(m, 0, 0), "missing top-left finder"
        assert _finder_at(m, 0, n - 7), "missing top-right finder"
        assert _finder_at(m, n - 7, 0), "missing bottom-left finder"
    # deterministic
    p = "wyrdphone://z/" + "b" * 300
    assert wq.make(p, "M") == wq.make(p, "M"), "non-deterministic output"
    print("structure: OK")


def test_reference_parity():
    try:
        import qrcode
        from qrcode.util import QRData, MODE_8BIT_BYTE
        from qrcode.constants import (
            ERROR_CORRECT_L, ERROR_CORRECT_M, ERROR_CORRECT_Q, ERROR_CORRECT_H)
    except Exception:
        print("reference-parity: SKIP (qrcode package not importable)")
        return
    ec = {"L": ERROR_CORRECT_L, "M": ERROR_CORRECT_M,
          "Q": ERROR_CORRECT_Q, "H": ERROR_CORRECT_H}

    def ref(text, ecl, mask):
        qr = qrcode.QRCode(error_correction=ec[ecl], mask_pattern=mask, border=0)
        qr.add_data(QRData(text.encode(), mode=MODE_8BIT_BYTE))
        qr.make(fit=True)
        return [[bool(x) for x in row] for row in qr.modules]

    def ref_auto(text, ecl):
        qr = qrcode.QRCode(error_correction=ec[ecl], border=0)
        qr.add_data(QRData(text.encode(), mode=MODE_8BIT_BYTE))
        qr.make(fit=True)
        return [[bool(x) for x in row] for row in qr.modules]

    rnd = random.Random(20260723)
    charset = "abcdefghijklmnopqrstuvwxyz0123456789+/=:.@-_"
    payloads = ["".join(rnd.choice(charset) for _ in range(n))
                for n in (1, 2, 10, 40, 120, 300, 640, 1000, 1600, 2200)]
    forced = auto = 0
    for t in payloads:
        for ecl in ("L", "M", "Q", "H"):
            try:
                exp_auto = ref_auto(t, ecl)
            except Exception:
                continue  # too long for this ECC level
            assert wq.make(t, ecl) == exp_auto, f"auto-mask drift len={len(t)} ecl={ecl}"
            auto += 1
            for mask in range(8):
                assert wq.make(t, ecl, mask=mask) == ref(t, ecl, mask), \
                    f"forced-mask drift len={len(t)} ecl={ecl} mask={mask}"
                forced += 1
    print(f"reference-parity: OK ({forced} forced + {auto} auto, all byte-identical)")


if __name__ == "__main__":
    test_structure()
    test_reference_parity()
    print("all QR tests passed")
