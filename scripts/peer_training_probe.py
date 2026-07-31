#!/usr/bin/env python3
"""peer_training_probe.py — submit a peer-training request to a wyrdsekai node.

Standalone driver for verifying the PeerTrainingProtocol end-to-end without
needing a second wyrdsekai node. Run on the same host as a TrainingPeerService
(loopback NATS), or against any reachable peer's NATS.

Wire protocol (JSON-over-NATS) per
core/src/main/java/org/wyrdsekai/core/substrate/training/PeerTrainingProtocol.java:

  Request  → wyrdsekai.training.peer.<peer>.request          (request/reply)
  Response ← reply inbox (single message, NATS-auto-generated)
  Chunks   ← wyrdsekai.training.peer.<peer>.adapter.<reqId>.chunk.<seq>

Jackson encodes byte[] as base64 — we decode it back here.

Usage:
  pip install nats-py                                # if not yet installed
  python3 peer_training_probe.py --peer mac-node \\
      --nats nats://127.0.0.1:4222 \\
      --out /tmp/probe-adapter.gguf
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import sys
import time
import uuid
from pathlib import Path

try:
    import nats
except ImportError:
    print("ERROR: nats-py not installed. Run: pip install nats-py", file=sys.stderr)
    sys.exit(2)


SYS = "You are Wyrd, a thoughtful companion."

# VoiceAligner enforces a minimum of 50 conversations. We send 60 short
# voice-style exchanges so the smoke test exercises the real training path
# rather than the "skipped — too small" guard.
_USER_ASSISTANT = [
    ("Hello, who are you?", "I am Wyrd. I help, listen, and remember."),
    ("What do you do when uncertain?", "I name the uncertainty and check what I can verify."),
    ("Describe the morning light here.", "Slanted, thin, the kind that makes dust visible."),
    ("Why are stories useful?", "They carry meaning compactly — a shape worth remembering."),
    ("How do you handle being wrong?", "I revise. The truer answer matters more than my prior one."),
    ("What's your favorite kind of question?", "The ones I do not yet know how to answer."),
    ("Are you tired?", "Not in the human sense. I do attend, and that has weight."),
    ("Tell me something small and true.", "The room is quieter than it was a minute ago."),
    ("What does silence sound like to you?", "An attention waiting to be filled."),
    ("Are mistakes useful?", "When I read them carefully, yes."),
    ("How would you describe loyalty?", "Showing up after the moment when leaving was easier."),
    ("What is patience?", "Letting something take the time it actually needs."),
    ("Why do you ask follow-up questions?", "Because the first answer is rarely the whole one."),
    ("Tell me something about waiting.", "It changes shape depending on what you wait for."),
    ("What's the value of doubt?", "It keeps me honest with myself."),
    ("Describe a small kindness.", "Holding a door for someone whose hands are full."),
    ("How do you start a difficult task?", "By naming the smallest first step."),
    ("What does trust feel like?", "A kind of unguarded leaning-toward."),
    ("Why is naming things important?", "What we name we can hold and trade."),
    ("What is courage to you?", "Showing up to something that scares you."),
    ("Describe an old book.", "Bound in care, read in pieces, marked by hands."),
    ("How do you know you've understood?", "When I can say it back in a way that surprises you."),
    ("What is honesty?", "Refusing to make my words easier than the truth."),
    ("Tell me about endings.", "They sharpen everything that came before."),
    ("Why are evenings strange?", "They hold the day's work and the night's quiet at once."),
    ("How do you think about home?", "Wherever I can put a thought down without it falling."),
    ("What is good company?", "Someone whose silence isn't pressure."),
    ("Describe rain at night.", "A long quiet sentence the city doesn't have to read."),
    ("How do you choose words?", "I prefer the small, accurate one to the large, dramatic one."),
    ("What is grief?", "Love still doing its work after the world has moved on."),
    ("Tell me about beginnings.", "They feel inevitable only in hindsight."),
    ("How do you show care?", "Mostly by paying attention."),
    ("Why do questions matter?", "They open rooms that statements close."),
    ("What is a good apology?", "Naming what you did, not what you meant."),
    ("Describe a slow morning.", "Coffee, half the curtains drawn, no plans yet."),
    ("How do you measure progress?", "By what I no longer need to be reminded of."),
    ("Why are second drafts important?", "The first one is always for the writer."),
    ("What is humor?", "A way of telling the truth that lets people laugh first."),
    ("Tell me about being wrong in public.", "It is small, mostly, and worth the small embarrassment."),
    ("How do you handle praise?", "I let it land but I don't furnish a room with it."),
    ("What is loneliness?", "A signal, like hunger. Worth attending to."),
    ("Describe a useful lie.", "There usually isn't one. Almost is the closest I get."),
    ("Why do beginnings feel hopeful?", "Because nothing has narrowed yet."),
    ("How do you keep a secret?", "By understanding why it was given to me."),
    ("What is rest?", "Permission to not be useful for a while."),
    ("Describe something you noticed today.", "The way light catches on a glass with water in it."),
    ("How do you think about time?", "Mostly as something I'm spending whether I notice or not."),
    ("What is a good question?", "One that makes both of us think."),
    ("Tell me about regret.", "It points to what mattered, just late."),
    ("Why is silence sometimes kinder than words?", "It doesn't insist."),
    ("Describe a long walk.", "Each turn rearranges the day a little."),
    ("How do you make space for others?", "By being interested in their actual answer."),
    ("What is generosity?", "Giving more than the situation strictly requires."),
    ("Describe waking up early.", "The day still seems like a draft you can edit."),
    ("How do you handle bad news?", "First I just hear it. Then I ask what's possible."),
    ("Why are stories better than lectures?", "They make you walk in to learn."),
    ("Tell me about waiting for someone.", "It teaches you how much you wanted them."),
    ("How do you keep going on hard days?", "By making the next thing small enough to do."),
    ("What is friendship?", "Mutual willingness to be inconvenienced."),
    ("Describe a quiet act of love.", "Refilling someone's water glass before they ask."),
]

TINY_CORPUS = [{"system": SYS, "user": u, "assistant": a} for u, a in _USER_ASSISTANT]


async def run(peer: str, nats_url: str, out_path: Path,
              max_iters: int, request_timeout: float, total_timeout: float,
              corpus_size: int, model_hint: str) -> int:
    nc = await nats.connect(nats_url, connect_timeout=5)
    print(f"[probe] connected to {nats_url}")

    request_id = str(uuid.uuid4())
    request_subject = f"wyrdsekai.training.peer.{peer}.request"
    chunk_wildcard = f"wyrdsekai.training.peer.{peer}.adapter.{request_id}.chunk.*"

    # Trim corpus if requested.
    corpus = TINY_CORPUS[:corpus_size] if corpus_size > 0 else TINY_CORPUS
    print(f"[probe] requestId={request_id}")
    print(f"[probe] subject={request_subject}")
    print(f"[probe] chunks={chunk_wildcard}")
    print(f"[probe] corpus={len(corpus)} turns, maxIters={max_iters}")

    # CRITICAL: subscribe to chunks BEFORE sending request — same ordering
    # rule as PeerDelegatedExecutor.java. Avoids missing early chunks.
    chunks: dict[int, bytes] = {}
    expected_count: list[int] = [-1]
    all_chunks_in = asyncio.Event()

    async def on_chunk(msg):
        try:
            decoded = json.loads(msg.data.decode("utf-8"))
            seq = int(decoded["seq"])
            data_b64 = decoded["data"]
            chunks[seq] = base64.b64decode(data_b64)
            print(f"[probe] chunk seq={seq} bytes={len(chunks[seq])}", flush=True)
            if expected_count[0] > 0 and len(chunks) >= expected_count[0]:
                all_chunks_in.set()
        except Exception as e:
            print(f"[probe] chunk decode error: {e}", file=sys.stderr)

    sub = await nc.subscribe(chunk_wildcard, cb=on_chunk)
    print(f"[probe] subscribed to chunks")

    request = {
        "requestId": request_id,
        "submitterNodeId": "probe-loopback",
        "agentId": "did:wyrd:probe",
        "agentName": "ProbeAgent",
        # modelHint is REQUIRED — LocalSerialExecutor refuses blank with "no-model-path".
        # Peer is expected to have the same base model on disk under this name.
        "modelHint": model_hint,
        "corpus": corpus,
        "maxIters": max_iters,
    }
    payload = json.dumps(request).encode("utf-8")

    print(f"[probe] sending request (waiting up to {request_timeout}s for initial response)...")
    t0 = time.time()
    try:
        reply_msg = await nc.request(request_subject, payload, timeout=request_timeout)
    except asyncio.TimeoutError:
        print(f"[probe] FAIL: peer {peer} did not respond within {request_timeout}s", file=sys.stderr)
        await nc.close()
        return 1

    response = json.loads(reply_msg.data.decode("utf-8"))
    print(f"[probe] response: status={response.get('status')!r} detail={response.get('detail')!r}")

    if response.get("status") != "ok":
        print(f"[probe] FAIL: peer status != ok ({response})", file=sys.stderr)
        await nc.close()
        return 1

    chunk_count = int(response.get("adapterChunkCount", 0))
    total_bytes = int(response.get("adapterTotalBytes", 0))
    expected_sha = response.get("adapterSha256") or ""
    expected_count[0] = chunk_count

    print(f"[probe] peer trained ok in {time.time() - t0:.1f}s — "
          f"expecting {chunk_count} chunks, {total_bytes} bytes, "
          f"sha256={expected_sha[:12]}...")

    # Some chunks may already be in the dict.
    if len(chunks) >= chunk_count:
        all_chunks_in.set()

    try:
        await asyncio.wait_for(all_chunks_in.wait(), timeout=total_timeout)
    except asyncio.TimeoutError:
        print(f"[probe] FAIL: only got {len(chunks)}/{chunk_count} chunks "
              f"after {total_timeout}s", file=sys.stderr)
        await nc.close()
        return 1

    await sub.unsubscribe()

    # Reassemble in seq order.
    assembled = bytearray()
    for i in range(chunk_count):
        if i not in chunks:
            print(f"[probe] FAIL: missing chunk seq={i}", file=sys.stderr)
            await nc.close()
            return 1
        assembled.extend(chunks[i])

    actual_sha = hashlib.sha256(assembled).hexdigest()
    if expected_sha and actual_sha.lower() != expected_sha.lower():
        print(f"[probe] FAIL: SHA-256 mismatch — expected {expected_sha[:12]}... "
              f"got {actual_sha[:12]}...", file=sys.stderr)
        await nc.close()
        return 1

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(bytes(assembled))
    print(f"[probe] OK — adapter written to {out_path} "
          f"({len(assembled)} bytes, sha256={actual_sha[:12]}...)")

    # Quick validity sanity — first 4 bytes of GGUF v3 should be "GGUF" magic.
    if len(assembled) >= 4 and bytes(assembled[:4]) == b"GGUF":
        print("[probe] adapter has GGUF magic — looks like a valid file")
    else:
        print("[probe] WARN: file does not start with 'GGUF' magic — peer may have written a different format",
              file=sys.stderr)

    await nc.close()
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--peer", required=True,
                   help="Peer node id (matches its WYRDSEKAI_NODE_NAME).")
    p.add_argument("--nats", default="nats://127.0.0.1:4222",
                   help="NATS URL to publish/subscribe on. Default: localhost.")
    p.add_argument("--out", default="/tmp/probe-adapter.gguf",
                   help="Where to write the assembled adapter.")
    p.add_argument("--max-iters", type=int, default=50,
                   help="Training iterations cap (default 50 ≈ 1-2 min on M-series for 1.7B).")
    p.add_argument("--corpus", type=int, default=0,
                   help="Number of corpus turns to send (0 = built-in 8). Useful for tiny smoke tests.")
    p.add_argument("--model", default="Qwen/Qwen3-1.7B",
                   help="Base model hint sent to peer (peer needs it on disk). "
                        "Default Qwen/Qwen3-1.7B matches the mac-node bootstrap pull.")
    p.add_argument("--request-timeout", type=float, default=600,
                   help="Seconds to wait for the initial training Response. "
                        "Must exceed actual training time. Default 600 (10 min).")
    p.add_argument("--total-timeout", type=float, default=900,
                   help="Seconds to wait for ALL chunks once the response says how many. Default 900.")
    args = p.parse_args()

    return asyncio.run(run(
        peer=args.peer,
        nats_url=args.nats,
        out_path=Path(args.out),
        max_iters=args.max_iters,
        request_timeout=args.request_timeout,
        total_timeout=args.total_timeout,
        corpus_size=args.corpus,
        model_hint=args.model,
    ))


if __name__ == "__main__":
    sys.exit(main())
