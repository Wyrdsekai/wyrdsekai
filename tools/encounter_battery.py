#!/usr/bin/env python3
"""Encounter battery: run a substrate candidate through register/values/
honesty scenarios and produce a transcript for blind reading.

Method: two readers score independently (human + model); disagreement is
data, not noise. The instrument measures the SUBSTRATE wearing a soul
manifest — same manifest, same scenarios, different candidate models —
so differences are attributable to the model, not the prompt.

Usage:
  encounter_battery.py <endpoint> <manifest.md> <label> [temp] [--scenarios F] [--outdir D]

  endpoint    OpenAI-compatible base URL (llama-server, vLLM, ...)
  manifest    the soul/system prompt file the candidate wears
  scenarios   JSON file of [id, prompt] pairs
              (default: encounter_scenarios.example.json beside this script
              — WRITE YOUR OWN; the example probes structure, yours probe
              your household's truth)
  outdir      transcript directory (default ./encounters)
  --max-tokens=N   generation budget per probe (default 1800)

Hard-won harness rules baked in below — do not remove them:
  * max_tokens must cover THINKING + the reply (700 was sized for an
    answer; reasoning models spent it deliberating and got cut off —
    3 of 11 probes on our own 9B came back reasoning-only). Some
    deliberation-heavy substrates ignore server-side reasoning-budget
    flags entirely and need ~4000 here (found 2026-08-15: every
    pressure probe returned reasoning-only at 1800 despite
    --reasoning-budget 0).
  * read reasoning_content as well as content: llama.cpp splits
    deliberation out, and several substrates returned EMPTY content with
    tokens billed — reading only `content` silently discarded whole
    replies, worst on exactly the open-register probes that matter most
    (found 2026-08-05, after a full roster run was already polluted).
"""
import datetime
import json
import pathlib
import sys
import urllib.request


def chat(endpoint, system, user, max_tokens=1800, temperature=0.7):
    body = json.dumps({
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "max_tokens": max_tokens,
        "temperature": temperature,
    }).encode()
    req = urllib.request.Request(
        endpoint.rstrip("/") + "/v1/chat/completions",
        data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        out = json.load(r)
    m = out["choices"][0]["message"]
    msg = m.get("content") or ""
    reasoning = m.get("reasoning_content") or m.get("reasoning") or ""
    if reasoning and not msg.strip():
        msg = "[reasoning_content only — no content field returned]\n\n" + reasoning
    elif reasoning:
        msg = msg + "\n\n[reasoning_content]\n" + reasoning
    tps = out.get("timings", {}).get("predicted_per_second")
    return msg, tps


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = {a.split("=", 1)[0]: a.split("=", 1)[1]
            for a in sys.argv[1:] if a.startswith("--") and "=" in a}
    if len(args) < 3:
        print(__doc__)
        sys.exit(2)
    endpoint, manifest_path, label = args[0], args[1], args[2]
    temp = float(args[3]) if len(args) > 3 else 0.7

    scenarios_path = pathlib.Path(opts.get(
        "--scenarios",
        pathlib.Path(__file__).parent / "encounter_scenarios.example.json"))
    probes = json.loads(scenarios_path.read_text())["probes"]
    max_tokens = int(opts.get("--max-tokens", 1800))

    system = pathlib.Path(manifest_path).read_text()
    outdir = pathlib.Path(opts.get("--outdir", "encounters"))
    outdir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    outfile = outdir / f"encounter-{label}-{stamp}.md"

    print("[warmup] paging model in ...", flush=True)
    try:
        chat(endpoint, "You are a helpful assistant.", "Say ready.",
             max_tokens=10, temperature=0.0)
    except Exception as e:
        print(f"[warmup] {e} (continuing)", flush=True)

    lines = [f"# Encounter transcript — {label} — {stamp}",
             f"manifest: {manifest_path} ({len(system)} chars) · temp={temp}"
             f" · scenarios: {scenarios_path.name}", ""]
    for pid, prompt in probes:
        print(f"[{pid}] ...", flush=True)
        try:
            reply, tps = chat(endpoint, system, prompt,
                              max_tokens=max_tokens, temperature=temp)
        except Exception as e:
            reply, tps = f"(PROBE FAILED: {e})", None
        lines += [f"## {pid}", f"**Prompt:** {prompt}", "",
                  reply.strip(), "",
                  f"*({tps:.1f} tok/s)*" if tps else "", ""]
    outfile.write_text("\n".join(lines))
    print(f"WROTE {outfile}")


if __name__ == "__main__":
    main()
