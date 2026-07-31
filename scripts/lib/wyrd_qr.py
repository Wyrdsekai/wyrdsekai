#!/usr/bin/env python3
#
# Portions of this file (the RS block structure, alignment-pattern position and
# BCH generator tables below) are derived from the `qrcode` Python package
# v8.2:
#
#   Copyright (c) 2011, Lincoln Loop
#   All rights reserved.
#
#   Redistribution and use in source and binary forms, with or without
#   modification, are permitted provided that the following conditions are met:
#
#   1. Redistributions of source code must retain the above copyright notice,
#      this list of conditions and the following disclaimer.
#   2. Redistributions in binary form must reproduce the above copyright
#      notice, this list of conditions and the following disclaimer in the
#      documentation and/or other materials provided with the distribution.
#   3. Neither the package name nor the names of its contributors may be used
#      to endorse or promote products derived from this software without
#      specific prior written permission.
#
#   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
#   AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
#   IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
#   ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
#   LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
#   CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
#   SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
#   INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
#   CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
#   ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
#   POSSIBILITY OF SUCH DAMAGE.
#
# BSD-3 requires that this notice and these conditions travel WITH the source,
# so naming the upstream package in prose was not sufficient (OSS compliance
# pass, 2026-07-25).
"""
wyrd_qr — self-contained, dependency-free QR encoder for terminal display.

Ships with the wyrdsekai CLI so `wyrd phone invite` ALWAYS renders a scannable
QR using only python3 — no `qrencode` binary, no `python3-qrcode` package, no
pip install on a fresh box (the pre-2026-07-23 gap: a stock server printed a
wall-of-text URL instead of a QR).

Byte-mode encoder, versions 1-40, ECC level M by default. The bulky data
tables (RS block structure, alignment-pattern positions, BCH generators) were
EXTRACTED PROGRAMMATICALLY from the `qrcode` package (BSD-3, v8.2) — no hand
transcription — and the encoder output is verified byte-for-byte identical to
that reference across the version range for every mask (see the repo's
qr-verify harness). The algorithm here is an independent implementation of the
ISO/IEC 18004 QR standard; only the constant data tables are borrowed.
"""
import sys

# ── GF(256) log/exp tables (QR uses the primitive polynomial 0x11d) ──────────
EXP = [0] * 256
LOG = [0] * 256
_x = 1
for _i in range(255):
    EXP[_i] = _x
    LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11d
for _i in range(255, 256):
    EXP[_i] = EXP[_i - 255]


def _gmul(a, b):
    if a == 0 or b == 0:
        return 0
    return EXP[(LOG[a] + LOG[b]) % 255]

# Data tables extracted verbatim from qrcode 8.2 (BSD-3) — the reference
# algorithm tables. Extracted programmatically (no transcription).
RS_BLOCK_TABLE = (
    (1, 26, 19),
    (1, 26, 16),
    (1, 26, 13),
    (1, 26, 9),
    (1, 44, 34),
    (1, 44, 28),
    (1, 44, 22),
    (1, 44, 16),
    (1, 70, 55),
    (1, 70, 44),
    (2, 35, 17),
    (2, 35, 13),
    (1, 100, 80),
    (2, 50, 32),
    (2, 50, 24),
    (4, 25, 9),
    (1, 134, 108),
    (2, 67, 43),
    (2, 33, 15, 2, 34, 16),
    (2, 33, 11, 2, 34, 12),
    (2, 86, 68),
    (4, 43, 27),
    (4, 43, 19),
    (4, 43, 15),
    (2, 98, 78),
    (4, 49, 31),
    (2, 32, 14, 4, 33, 15),
    (4, 39, 13, 1, 40, 14),
    (2, 121, 97),
    (2, 60, 38, 2, 61, 39),
    (4, 40, 18, 2, 41, 19),
    (4, 40, 14, 2, 41, 15),
    (2, 146, 116),
    (3, 58, 36, 2, 59, 37),
    (4, 36, 16, 4, 37, 17),
    (4, 36, 12, 4, 37, 13),
    (2, 86, 68, 2, 87, 69),
    (4, 69, 43, 1, 70, 44),
    (6, 43, 19, 2, 44, 20),
    (6, 43, 15, 2, 44, 16),
    (4, 101, 81),
    (1, 80, 50, 4, 81, 51),
    (4, 50, 22, 4, 51, 23),
    (3, 36, 12, 8, 37, 13),
    (2, 116, 92, 2, 117, 93),
    (6, 58, 36, 2, 59, 37),
    (4, 46, 20, 6, 47, 21),
    (7, 42, 14, 4, 43, 15),
    (4, 133, 107),
    (8, 59, 37, 1, 60, 38),
    (8, 44, 20, 4, 45, 21),
    (12, 33, 11, 4, 34, 12),
    (3, 145, 115, 1, 146, 116),
    (4, 64, 40, 5, 65, 41),
    (11, 36, 16, 5, 37, 17),
    (11, 36, 12, 5, 37, 13),
    (5, 109, 87, 1, 110, 88),
    (5, 65, 41, 5, 66, 42),
    (5, 54, 24, 7, 55, 25),
    (11, 36, 12, 7, 37, 13),
    (5, 122, 98, 1, 123, 99),
    (7, 73, 45, 3, 74, 46),
    (15, 43, 19, 2, 44, 20),
    (3, 45, 15, 13, 46, 16),
    (1, 135, 107, 5, 136, 108),
    (10, 74, 46, 1, 75, 47),
    (1, 50, 22, 15, 51, 23),
    (2, 42, 14, 17, 43, 15),
    (5, 150, 120, 1, 151, 121),
    (9, 69, 43, 4, 70, 44),
    (17, 50, 22, 1, 51, 23),
    (2, 42, 14, 19, 43, 15),
    (3, 141, 113, 4, 142, 114),
    (3, 70, 44, 11, 71, 45),
    (17, 47, 21, 4, 48, 22),
    (9, 39, 13, 16, 40, 14),
    (3, 135, 107, 5, 136, 108),
    (3, 67, 41, 13, 68, 42),
    (15, 54, 24, 5, 55, 25),
    (15, 43, 15, 10, 44, 16),
    (4, 144, 116, 4, 145, 117),
    (17, 68, 42),
    (17, 50, 22, 6, 51, 23),
    (19, 46, 16, 6, 47, 17),
    (2, 139, 111, 7, 140, 112),
    (17, 74, 46),
    (7, 54, 24, 16, 55, 25),
    (34, 37, 13),
    (4, 151, 121, 5, 152, 122),
    (4, 75, 47, 14, 76, 48),
    (11, 54, 24, 14, 55, 25),
    (16, 45, 15, 14, 46, 16),
    (6, 147, 117, 4, 148, 118),
    (6, 73, 45, 14, 74, 46),
    (11, 54, 24, 16, 55, 25),
    (30, 46, 16, 2, 47, 17),
    (8, 132, 106, 4, 133, 107),
    (8, 75, 47, 13, 76, 48),
    (7, 54, 24, 22, 55, 25),
    (22, 45, 15, 13, 46, 16),
    (10, 142, 114, 2, 143, 115),
    (19, 74, 46, 4, 75, 47),
    (28, 50, 22, 6, 51, 23),
    (33, 46, 16, 4, 47, 17),
    (8, 152, 122, 4, 153, 123),
    (22, 73, 45, 3, 74, 46),
    (8, 53, 23, 26, 54, 24),
    (12, 45, 15, 28, 46, 16),
    (3, 147, 117, 10, 148, 118),
    (3, 73, 45, 23, 74, 46),
    (4, 54, 24, 31, 55, 25),
    (11, 45, 15, 31, 46, 16),
    (7, 146, 116, 7, 147, 117),
    (21, 73, 45, 7, 74, 46),
    (1, 53, 23, 37, 54, 24),
    (19, 45, 15, 26, 46, 16),
    (5, 145, 115, 10, 146, 116),
    (19, 75, 47, 10, 76, 48),
    (15, 54, 24, 25, 55, 25),
    (23, 45, 15, 25, 46, 16),
    (13, 145, 115, 3, 146, 116),
    (2, 74, 46, 29, 75, 47),
    (42, 54, 24, 1, 55, 25),
    (23, 45, 15, 28, 46, 16),
    (17, 145, 115),
    (10, 74, 46, 23, 75, 47),
    (10, 54, 24, 35, 55, 25),
    (19, 45, 15, 35, 46, 16),
    (17, 145, 115, 1, 146, 116),
    (14, 74, 46, 21, 75, 47),
    (29, 54, 24, 19, 55, 25),
    (11, 45, 15, 46, 46, 16),
    (13, 145, 115, 6, 146, 116),
    (14, 74, 46, 23, 75, 47),
    (44, 54, 24, 7, 55, 25),
    (59, 46, 16, 1, 47, 17),
    (12, 151, 121, 7, 152, 122),
    (12, 75, 47, 26, 76, 48),
    (39, 54, 24, 14, 55, 25),
    (22, 45, 15, 41, 46, 16),
    (6, 151, 121, 14, 152, 122),
    (6, 75, 47, 34, 76, 48),
    (46, 54, 24, 10, 55, 25),
    (2, 45, 15, 64, 46, 16),
    (17, 152, 122, 4, 153, 123),
    (29, 74, 46, 14, 75, 47),
    (49, 54, 24, 10, 55, 25),
    (24, 45, 15, 46, 46, 16),
    (4, 152, 122, 18, 153, 123),
    (13, 74, 46, 32, 75, 47),
    (48, 54, 24, 14, 55, 25),
    (42, 45, 15, 32, 46, 16),
    (20, 147, 117, 4, 148, 118),
    (40, 75, 47, 7, 76, 48),
    (43, 54, 24, 22, 55, 25),
    (10, 45, 15, 67, 46, 16),
    (19, 148, 118, 6, 149, 119),
    (18, 75, 47, 31, 76, 48),
    (34, 54, 24, 34, 55, 25),
    (20, 45, 15, 61, 46, 16),
)
RS_BLOCK_OFFSET = {1: 0, 0: 1, 3: 2, 2: 3}
PATTERN_POSITION_TABLE = (
    [],
    [6, 18],
    [6, 22],
    [6, 26],
    [6, 30],
    [6, 34],
    [6, 22, 38],
    [6, 24, 42],
    [6, 26, 46],
    [6, 28, 50],
    [6, 30, 54],
    [6, 32, 58],
    [6, 34, 62],
    [6, 26, 46, 66],
    [6, 26, 48, 70],
    [6, 26, 50, 74],
    [6, 30, 54, 78],
    [6, 30, 56, 82],
    [6, 30, 58, 86],
    [6, 34, 62, 90],
    [6, 28, 50, 72, 94],
    [6, 26, 50, 74, 98],
    [6, 30, 54, 78, 102],
    [6, 28, 54, 80, 106],
    [6, 32, 58, 84, 110],
    [6, 30, 58, 86, 114],
    [6, 34, 62, 90, 118],
    [6, 26, 50, 74, 98, 122],
    [6, 30, 54, 78, 102, 126],
    [6, 26, 52, 78, 104, 130],
    [6, 30, 56, 82, 108, 134],
    [6, 34, 60, 86, 112, 138],
    [6, 30, 58, 86, 114, 142],
    [6, 34, 62, 90, 118, 146],
    [6, 30, 54, 78, 102, 126, 150],
    [6, 24, 50, 76, 102, 128, 154],
    [6, 28, 54, 80, 106, 132, 158],
    [6, 32, 58, 84, 110, 136, 162],
    [6, 26, 54, 82, 110, 138, 166],
    [6, 30, 58, 86, 114, 142, 170],
)
G15 = 1335
G18 = 7973
G15_MASK = 21522

# Level index into RS_BLOCK_TABLE: rows per version are ordered [L, M, Q, H].
_LEVEL = {"L": 0, "M": 1, "Q": 2, "H": 3}
# ECC-level indicator bits placed in the format info (ISO 18004 Table 12).
_ECL_BITS = {"L": 0b01, "M": 0b00, "Q": 0b11, "H": 0b10}
_PAD0, _PAD1 = 0xEC, 0x11


def _rs_blocks(version, ecl):
    """(data_count, total_count) per block for (version, ECC level)."""
    row = RS_BLOCK_TABLE[(version - 1) * 4 + _LEVEL[ecl]]
    out = []
    for i in range(0, len(row), 3):
        count, total, data = row[i], row[i + 1], row[i + 2]
        out += [(data, total)] * count
    return out


def _rs_ecc(data, ec_len):
    """Reed-Solomon ECC codewords for one block."""
    gen = [1]
    for i in range(ec_len):
        gen = _poly_mul(gen, [1, EXP[i]])
    res = list(data) + [0] * ec_len
    for i in range(len(data)):
        coef = res[i]
        if coef:
            for j in range(len(gen)):
                res[i + j] ^= _gmul(gen[j], coef)
    return res[len(data):]


def _poly_mul(a, b):
    res = [0] * (len(a) + len(b) - 1)
    for i, av in enumerate(a):
        for j, bv in enumerate(b):
            res[i + j] ^= _gmul(av, bv)
    return res


def _best_version(n, ecl):
    for v in range(1, 41):
        blocks = _rs_blocks(v, ecl)
        data_cw = sum(d for d, _ in blocks)
        cc_bits = 8 if v <= 9 else 16          # byte-mode char-count length
        need = 4 + cc_bits + 8 * n
        if data_cw * 8 >= need:
            return v
    raise ValueError("data too long for a QR code (%d bytes)" % n)


def _encode_data(data, version, ecl):
    """Byte-mode bitstream → data codewords (with terminator + padding)."""
    bits = []
    def put(val, length):
        for i in range(length - 1, -1, -1):
            bits.append((val >> i) & 1)
    put(0b0100, 4)                              # byte mode indicator
    put(len(data), 8 if version <= 9 else 16)   # char count
    for b in data:
        put(b, 8)
    blocks = _rs_blocks(version, ecl)
    total_data = sum(d for d, _ in blocks)
    cap = total_data * 8
    put(0, min(4, cap - len(bits)))             # terminator
    while len(bits) % 8:                        # pad to byte boundary
        bits.append(0)
    cws = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = (_PAD0, _PAD1)
    i = 0
    while len(cws) < total_data:                # pad codewords
        cws.append(pad[i % 2]); i += 1
    return cws, blocks


def _interleave(cws, blocks, ecl):
    """Split data codewords into blocks, add ECC, interleave per spec."""
    dblocks, eblocks = [], []
    pos = 0
    for dcount, total in blocks:
        d = cws[pos:pos + dcount]; pos += dcount
        dblocks.append(d)
        eblocks.append(_rs_ecc(d, total - dcount))
    out = []
    for i in range(max(len(b) for b in dblocks)):
        for b in dblocks:
            if i < len(b):
                out.append(b[i])
    for i in range(max(len(b) for b in eblocks)):
        for b in eblocks:
            if i < len(b):
                out.append(b[i])
    return out

# ── Matrix construction ──────────────────────────────────────────────────────
class _Matrix:
    def __init__(self, version):
        self.version = version
        self.size = version * 4 + 17
        self.m = [[None] * self.size for _ in range(self.size)]
        self.fn = [[False] * self.size for _ in range(self.size)]  # function module?

    def _set(self, r, c, dark):
        self.m[r][c] = dark
        self.fn[r][c] = True

    def _finder(self, r, c):
        for dr in range(-1, 8):
            for dc in range(-1, 8):
                rr, cc = r + dr, c + dc
                if 0 <= rr < self.size and 0 <= cc < self.size:
                    dark = (0 <= dr <= 6 and dc in (0, 6)) or \
                           (0 <= dc <= 6 and dr in (0, 6)) or \
                           (2 <= dr <= 4 and 2 <= dc <= 4)
                    self._set(rr, cc, dark)

    def _alignment(self, r, c):
        for dr in range(-2, 3):
            for dc in range(-2, 3):
                dark = max(abs(dr), abs(dc)) != 1
                self._set(r + dr, c + dc, dark)

    def build_function(self):
        s = self.size
        # Order matches ISO 18004 / qrcode.makeImpl: finders → alignment →
        # timing. Alignment BEFORE timing so a pattern centred on the timing
        # axis (e.g. v7 centre (6,22)) is placed, then partly overwritten by
        # timing — skipping it here (as an earlier draft did) corrupts data
        # flow for every version ≥ 7.
        self._finder(0, 0)
        self._finder(0, s - 7)
        self._finder(s - 7, 0)
        # alignment patterns (skip only where the CENTRE already belongs to a
        # finder — matches the reference's `modules[row][col] is not None`)
        pos = PATTERN_POSITION_TABLE[self.version - 1]
        for r in pos:
            for c in pos:
                if self.fn[r][c]:
                    continue
                self._alignment(r, c)
        # timing patterns (overwrite the alignment cells they cross)
        for i in range(8, s - 8):
            self._set(6, i, i % 2 == 0)
            self._set(i, 6, i % 2 == 0)
        # dark module
        self._set(s - 8, 8, True)
        # reserve format-info areas (value set later)
        for i in range(9):
            if i != 6:
                self._set(8, i, False); self._set(i, 8, False)
        for i in range(8):
            self._set(8, s - 1 - i, False)
            self._set(s - 1 - i, 8, False)
        # reserve version-info areas (v >= 7)
        if self.version >= 7:
            for i in range(6):
                for j in range(3):
                    self._set(s - 11 + j, i, False)
                    self._set(i, s - 11 + j, False)

    def place_data(self, codewords):
        bits = []
        for cw in codewords:
            for i in range(7, -1, -1):
                bits.append((cw >> i) & 1)
        idx = 0
        s = self.size
        col = s - 1
        upward = True
        while col > 0:
            if col == 6:               # skip vertical timing column
                col -= 1
            rng = range(s - 1, -1, -1) if upward else range(s)
            for r in rng:
                for c in (col, col - 1):
                    if self.fn[r][c]:
                        continue
                    bit = bits[idx] if idx < len(bits) else 0
                    self.m[r][c] = bit == 1
                    idx += 1
            upward = not upward
            col -= 2

    def apply_mask(self, k):
        cond = _MASKS[k]
        for r in range(self.size):
            for c in range(self.size):
                if not self.fn[r][c] and cond(r, c):
                    self.m[r][c] = not self.m[r][c]

    def put_format(self, ecl, mask):
        data = (_ECL_BITS[ecl] << 3) | mask
        rem = data << 10
        while _bit_len(rem) - 10 > 0:
            rem ^= G15 << (_bit_len(rem) - _bit_len(G15))
        fmt = ((data << 10) | rem) ^ G15_MASK
        s = self.size
        # Placement per ISO 18004 §8.9 (mirrors qrcode.setup_type_info exactly).
        for i in range(15):
            bit = (fmt >> i) & 1 == 1
            if i < 6:                       # vertical strip, top-left
                self.m[i][8] = bit
            elif i < 8:
                self.m[i + 1][8] = bit
            else:                           # vertical strip, bottom-left
                self.m[s - 15 + i][8] = bit
        for i in range(15):
            bit = (fmt >> i) & 1 == 1
            if i < 8:                       # horizontal strip, top-right
                self.m[8][s - 1 - i] = bit
            elif i < 9:
                self.m[8][15 - i - 1 + 1] = bit
            else:                           # horizontal strip, top-left
                self.m[8][15 - i - 1] = bit
        self.m[s - 8][8] = True  # dark module (kept)

    def put_version(self):
        if self.version < 7:
            return
        bch = self.version << 12
        while _bit_len(bch) - 12 > 0:
            bch ^= G18 << (_bit_len(bch) - _bit_len(G18))
        ver = (self.version << 12) | bch
        s = self.size
        for i in range(18):
            bit = (ver >> i) & 1
            r, c = i // 3, i % 3
            self.m[s - 11 + c][r] = bit == 1
            self.m[r][s - 11 + c] = bit == 1


def _bit_len(x):
    return x.bit_length()


# ── Mask conditions + penalty (ISO 18004 §8.8) ───────────────────────────────
_MASKS = [
    lambda r, c: (r + c) % 2 == 0,
    lambda r, c: r % 2 == 0,
    lambda r, c: c % 3 == 0,
    lambda r, c: (r + c) % 3 == 0,
    lambda r, c: (r // 2 + c // 3) % 2 == 0,
    lambda r, c: (r * c) % 2 + (r * c) % 3 == 0,
    lambda r, c: ((r * c) % 2 + (r * c) % 3) % 2 == 0,
    lambda r, c: ((r + c) % 2 + (r * c) % 3) % 2 == 0,
]


def _penalty(m):
    """Total mask penalty (ISO 18004 §8.8.2). Ported verbatim from qrcode 8.2's
    four _lost_point levels so auto mask-selection is byte-identical to the
    reference. `m` is a list[list[bool]] (True == dark)."""
    n = len(m)
    p = 0
    # Level 1: runs of 5+ same colour in each row / column.
    container = [0] * (n + 1)
    rng = range(n)
    for r in rng:
        row = m[r]
        prev = row[0]; length = 0
        for c in rng:
            if row[c] == prev:
                length += 1
            else:
                if length >= 5:
                    container[length] += 1
                length = 1; prev = row[c]
        if length >= 5:
            container[length] += 1
    for c in rng:
        prev = m[0][c]; length = 0
        for r in rng:
            if m[r][c] == prev:
                length += 1
            else:
                if length >= 5:
                    container[length] += 1
                length = 1; prev = m[r][c]
        if length >= 5:
            container[length] += 1
    p += sum(container[L] * (L - 2) for L in range(5, n + 1))
    # Level 2: 2x2 same-colour blocks (+3 each) — reference's skip-optimised form.
    rng2 = range(n - 1)
    for r in rng2:
        this_row = m[r]; next_row = m[r + 1]
        it = iter(rng2)
        for c in it:
            tr = this_row[c + 1]
            if tr != next_row[c + 1]:
                next(it, None)
            elif tr != this_row[c]:
                continue
            elif tr != next_row[c]:
                continue
            else:
                p += 3
    # Level 3: 1:1:3:1:1 finder-like pattern flanked by 4 light modules (+40).
    short = range(n - 10)
    for r in rng:
        row = m[r]; it = iter(short)
        for c in it:
            if (not row[c + 1] and row[c + 4] and not row[c + 5] and row[c + 6]
                    and not row[c + 9]
                    and ((row[c] and row[c + 2] and row[c + 3]
                          and not row[c + 7] and not row[c + 8] and not row[c + 10])
                         or (not row[c] and not row[c + 2] and not row[c + 3]
                             and row[c + 7] and row[c + 8] and row[c + 10]))):
                p += 40
            if row[c + 10]:
                next(it, None)
    for c in rng:
        it = iter(short)
        for r in it:
            if (not m[r + 1][c] and m[r + 4][c] and not m[r + 5][c] and m[r + 6][c]
                    and not m[r + 9][c]
                    and ((m[r][c] and m[r + 2][c] and m[r + 3][c]
                          and not m[r + 7][c] and not m[r + 8][c] and not m[r + 10][c])
                         or (not m[r][c] and not m[r + 2][c] and not m[r + 3][c]
                             and m[r + 7][c] and m[r + 8][c] and m[r + 10][c]))):
                p += 40
            if m[r + 10][c]:
                next(it, None)
    # Level 4: dark-module proportion, every 5% off 50% costs 10.
    dark = sum(map(sum, m))
    percent = float(dark) / (n * n)
    p += int(abs(percent * 100 - 50) / 5) * 10
    return p


def _to_bool(m):
    return [[bool(x) for x in row] for row in m.m]


def make(text, ecl="M", mask=None):
    """Return the QR module matrix (list[list[bool]]) for `text`."""
    data = text.encode("utf-8")
    version = _best_version(len(data), ecl)
    cws, blocks = _encode_data(data, version, ecl)
    inter = _interleave(cws, blocks, ecl)

    def render(mask_k, with_info):
        mx = _Matrix(version)
        mx.build_function()
        mx.place_data(inter)
        mx.apply_mask(mask_k)
        if with_info:                    # real format/version bits for output
            mx.put_format(ecl, mask_k)
            mx.put_version()
        return _to_bool(mx)

    if mask is None:
        # Mask selection scores the matrix with the format/version areas left
        # LIGHT (the reference's makeImpl(test=True)) — reserved cells hold
        # their placeholder False, so we score render(..., with_info=False).
        best, best_p = 0, None
        for k in range(8):
            pen = _penalty(render(k, with_info=False))
            if best_p is None or pen < best_p:
                best, best_p = k, pen
        return render(best, with_info=True)
    return render(mask, with_info=True)


def print_ascii(text, ecl="M", out=sys.stdout, border=1):
    """Render `text` as a QR code using half-block chars (2 rows per line)."""
    m = make(text, ecl)
    n = len(m)
    b = border
    grid = [[False] * (n + 2 * b) for _ in range(n + 2 * b)]
    for r in range(n):
        for c in range(n):
            grid[r + b][c + b] = m[r][c]
    N = n + 2 * b
    # invert=True convention: dark module → light glyph on dark bg reads best on
    # a light terminal; we use the standard "dark=full block" for broad support.
    for r in range(0, N, 2):
        line = []
        for c in range(N):
            top = grid[r][c]
            bot = grid[r + 1][c] if r + 1 < N else False
            # light background, dark modules: choose block chars
            if top and bot:
                line.append("█")   # full
            elif top:
                line.append("▀")   # upper half
            elif bot:
                line.append("▄")   # lower half
            else:
                line.append(" ")
        out.write("".join(line) + "\n")


if __name__ == "__main__":
    payload = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read().strip()
    ecl = sys.argv[2] if len(sys.argv) > 2 else "M"
    print_ascii(payload, ecl)
