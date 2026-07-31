#!/usr/bin/env bash
#
# build-evolved-artifact.sh Track-B B1
#
# Release-time evolution bake. For each enrolled classifier head (or the
# default set), run `retrain-classifier-head` through the real production
# code path (RecipeService + CodingBackendDispatcher + GooseBackend) and
# emit two evidence artifacts under data/release-evidence/:
#
#   <head>-recipe-run-<timestamp>.json    — full RecipeRunLog
#   <head>-soul-fragment-seed.json        — DEXTERITY fragment seed
#                                           (ingested at first boot under
#                                           did:wyrd:release-bake)
#
# Plus a baseline copy of the prior <head>.onnx for sha256 provenance.
#
# Failures abort the entire release build with non-zero exit so the
# bundle never ships with a broken loop. Skip emergency builds with:
#
#   BAKE_SKIP_HEADS=task_present,cleanliness ./build-evolved-artifact.sh
#   BAKE_HEADS_ONLY=task_present ./build-evolved-artifact.sh   # tighter subset
#
# Requirements on the build host:
#   * goose CLI on PATH (or installed via `wyrd coding install goose` into
#     the bundle dir — script PATH-prepends both locations).
#   * Local llama-server reachable (Linux: :8200 native; macOS: :8200 via
#     /opt/homebrew/bin/llama-server installed by `wyrd setup`).
#   * Python venv with sklearn + onnx + skl2onnx + transformers, located at:
#       Linux source mode: scripts/training/.venv-home-server
#       macOS .pkg install: $WYRDSEKAI_DATA_DIR/.venv-recipes (created by
#         `wyrd setup` since #1089).
#     Script auto-detects and PATH-prepends — no manual activation needed.
#   * No ANTHROPIC_API_KEY needed — recipe-local-first invariant (#1004).
#
# Mirror: mac-node-deploy-mechanism (.pkg path), goose-live-e2e-passed-2026-05-24
# (live-verify shape), mac-node-recipe-stack-verified-2026-05-27 (#1089 port).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
EVIDENCE_DIR="$PROJECT_DIR/data/release-evidence"
PRETRAINED_DIR="$PROJECT_DIR/core/src/main/resources/classifier/pretrained"
PLATFORM="$(uname -s)"

# ── Venv detection (#1089) ───────────────────────────────────────────────
# Pick the first venv that exists; PATH-prepend its bin/ so all `python3`
# invocations inside the recipe run pick up sklearn/onnx/skl2onnx.
RECIPE_VENV=""
for candidate in \
    "${WYRDSEKAI_DATA_DIR:-$HOME/.wyrdsekai}/.venv-recipes" \
    "$PROJECT_DIR/scripts/training/.venv-home-server" \
    "$PROJECT_DIR/scripts/training/.venv-mac-node" ; do
    if [[ -x "$candidate/bin/python3" ]]; then
        RECIPE_VENV="$candidate"
        export PATH="$candidate/bin:$PATH"
        break
    fi
done
# Also PATH-prepend the goose bundle install dir (where `wyrd coding install
# goose` lands the binary). Idempotent if already on PATH.
GOOSE_BUNDLE="${WYRDSEKAI_DATA_DIR:-$HOME/.wyrdsekai}/coding-cli-bundle/goose"
[[ -d "$GOOSE_BUNDLE" ]] && export PATH="$GOOSE_BUNDLE:$PATH"

info()  { echo -e "\033[36m[bake]\033[0m $*"; }
ok()    { echo -e "\033[32m[bake]\033[0m $*"; }
warn()  { echo -e "\033[33m[bake]\033[0m $*"; }
err()   { echo -e "\033[31m[bake]\033[0m $*" >&2; }

# Default bake set (2026-07-21). ONLY the heads whose evolution genuinely
# improves them in the RUNTIME ClassifierArm path — proven end-to-end:
#   task_present  — LR retrain, runtime 0/90 (was ~2)
#   cleanliness   — LR retrain, runtime 5/90 (was ~13)
# DELIBERATELY EXCLUDED (baking them regresses or can't clear the runtime gate):
#   substrate_present — baseline is already 0/90 (optimal); BOTH LR (6/90) and
#     MLP (12/90) retrains REGRESS it. Nothing to gain, only to lose. Keep the
#     shipped baseline; the runtime gate would abort a release if it were baked.
#   request_type — 8-way head at its capability ceiling (val_accuracy ~0.58 <
#     0.60); needs the affect/dispatch label split before it can close. Until
#     then it only fails the accuracy gate. (Follow-up, not in this set.)
# Override with BAKE_HEADS_ONLY=… to bake a specific head anyway.
DEFAULT_HEADS=(task_present cleanliness)

if [[ -n "${BAKE_HEADS_ONLY:-}" ]]; then
    IFS=',' read -r -a HEADS <<< "$BAKE_HEADS_ONLY"
else
    HEADS=("${DEFAULT_HEADS[@]}")
    if [[ -n "${BAKE_SKIP_HEADS:-}" ]]; then
        IFS=',' read -r -a SKIP <<< "$BAKE_SKIP_HEADS"
        filtered=()
        for h in "${HEADS[@]}"; do
            skip=false
            for s in "${SKIP[@]}"; do
                [[ "$h" == "$s" ]] && skip=true && break
            done
            $skip || filtered+=("$h")
        done
        HEADS=("${filtered[@]+"${filtered[@]}"}")
        warn "Skipping heads: ${BAKE_SKIP_HEADS}"
    fi
fi

if [[ ${#HEADS[@]} -eq 0 ]]; then
    warn "No heads to bake (all skipped or empty BAKE_HEADS_ONLY)"
    exit 0
fi

# ── Preflight ───────────────────────────────────────────────────────────
if ! command -v goose &>/dev/null && [[ -z "${WYRDSEKAI_BAKE_ALLOW_PI:-}" ]]; then
    err "goose CLI not on PATH and WYRDSEKAI_BAKE_ALLOW_PI not set"
    err "Install goose with: wyrd coding install goose"
    err "(or set WYRDSEKAI_BAKE_ALLOW_PI=1 to allow pi-only fallback)"
    exit 2
fi

if [[ -z "$RECIPE_VENV" ]]; then
    warn "No recipe Python venv found at any candidate path."
    warn "  Linux: $PROJECT_DIR/scripts/training/.venv-home-server"
    warn "  macOS: \${WYRDSEKAI_DATA_DIR:-~/.wyrdsekai}/.venv-recipes"
    warn "  Run 'wyrd setup' (which auto-creates the recipe venv since #1089),"
    warn "  or build one manually with sklearn + onnx + skl2onnx + transformers."
    warn "  Falling back to system python3 — pip-installed deps may be missing."
fi

# Local llama-server reachability — recipe's expand-corpus / regression-probe
# / smoke BACKEND steps need it. The check is best-effort; the real failure
# mode (gate trip) gives a louder error inside the recipe run.
if command -v curl &>/dev/null; then
    if ! curl -fsS http://127.0.0.1:8200/v1/models >/dev/null 2>&1; then
        if [[ "$PLATFORM" == "Darwin" ]]; then
            warn "Local llama-server :8200 not reachable — run 'wyrd inference start' first."
        else
            warn "Local llama-server :8200 not reachable — BACKEND steps may fail"
        fi
    fi
fi

mkdir -p "$EVIDENCE_DIR"

info "Evolved-artifact bake starting"
info "  heads:     ${HEADS[*]}"
info "  pretrained: $PRETRAINED_DIR"
info "  evidence:  $EVIDENCE_DIR"

# ── Build CLI classpath once (avoids 4× gradle warm starts) ─────────────
info "Compiling :cli for bakeRecipe..."
( cd "$PROJECT_DIR" && ./gradlew :cli:compileJava -q )
# Gate-runtime parity (2026-07-22): the recipe's regression-probe step runs
# ProbeHeadMain via plain `java -cp $(cat cli/build/probe-classpath.txt)` —
# a nested gradlew inside the bakeRecipe JVM would contend for the project
# lock mid-recipe. Write the classpath file now (also builds dependency jars).
info "Writing probe classpath for the runtime-space regression gate..."
( cd "$PROJECT_DIR" && ./gradlew :cli:writeProbeClasspath -q )

# ── Per-head bake ───────────────────────────────────────────────────────
# THREE-OUTCOME SEMANTICS (2026-07-22):
#   evolved       — candidate beat every gate, deployed. Loop improved a head.
#   kept baseline — loop ran end to end, candidate honestly rejected by a
#                   QUALITY gate (not better than the proven head), baseline
#                   retained. Valid release outcome — on a fixed seed corpus
#                   with an already-evolved baseline this is the EXPECTED
#                   steady state; improvement needs new experience data.
#   failed        — infrastructure broke (expand timeout, train crash, …).
#                   The loop could not run; release aborts.
# RecipeBakeMain exits 0 for both evolved and kept-baseline (JavaExec would
# swallow a distinct code) and records which in the evidence `outcome` field.
failed_heads=()
evolved_heads=()
kept_heads=()
for head in "${HEADS[@]}"; do
    info "── bake head=$head ──"
    if ( cd "$PROJECT_DIR" && \
         ./gradlew :cli:bakeRecipe -Phead="$head" -q --console=plain --no-daemon ); then
        ev="$(ls -t "$EVIDENCE_DIR/${head}-recipe-run-"*.json 2>/dev/null | grep -v failed | head -1)"
        outcome="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('outcome','evolved'))" "$ev" 2>/dev/null || echo evolved)"
        if [[ "$outcome" == "kept_baseline" ]]; then
            ok "head=$head KEPT BASELINE — loop ran honestly; candidate did not beat the proven head"
            kept_heads+=("$head")
        else
            ok "head=$head EVOLVED — candidate beat the gates and was deployed"
            evolved_heads+=("$head")
        fi
    else
        rc=$?
        err "head=$head bake FAILED (rc=$rc) — infrastructure/step failure, see $EVIDENCE_DIR/${head}-recipe-run-*-failed.json"
        failed_heads+=("$head")
    fi
done
succeeded_heads=("${evolved_heads[@]+"${evolved_heads[@]}"}" "${kept_heads[@]+"${kept_heads[@]}"}")

# ── Summary ─────────────────────────────────────────────────────────────
echo
ok  "evolved:       ${evolved_heads[*]:-<none>}"
ok  "kept baseline: ${kept_heads[*]:-<none>}"
if [[ ${#failed_heads[@]} -gt 0 ]]; then
    err "failed:        ${failed_heads[*]}"
    err ""
    err "Release build aborted: the evolution loop could NOT RUN for at least"
    err "one head (infrastructure/step failure — not an honest gate rejection;"
    err "those ship as 'kept baseline'). Inspect data/release-evidence/"
    err "<head>-recipe-run-*-failed.json for the step-level outcomes. Fix the"
    err "infra, skip the head with BAKE_SKIP_HEADS=$(IFS=,; echo "${failed_heads[*]}")"
    err "for an emergency build, or run the recipe manually to investigate."
    exit 1
fi

# Evidence sanity check — every succeeded head should have BOTH evidence
# files. If one is missing the bake silently produced a half-deliverable.
for head in "${succeeded_heads[@]}"; do
    seed="$EVIDENCE_DIR/${head}-soul-fragment-seed.json"
    if [[ ! -f "$seed" ]]; then
        err "post-bake check: $seed missing — bake recorded SUCCESS but produced no seed"
        exit 1
    fi
    # At least one run-log file for this head exists.
    if ! ls "$EVIDENCE_DIR/${head}-recipe-run-"*.json >/dev/null 2>&1; then
        err "post-bake check: no recipe-run-*.json for head=$head"
        exit 1
    fi
done

# ── RUNTIME floor gate (2026-07-21; direct-probe form 2026-07-22) ────────
# Belt-and-suspenders behind the recipe's runtime-space deciding gate: every
# head that SHIPS (baked or not) must clear its absolute miss floor in the
# ClassifierArm path. Originally ran ProbeAnchorRuntimeFloorTest via gradle,
# but a JUnit assumption-skip (encoder init flake on installed-daemon hosts)
# reads as gradle SUCCESS — a silently VACUOUS gate. ProbeHeadMain never
# skips: it probes the deployed artifacts in a fresh JVM and throws if the
# encoder/head is unavailable, so a pass here is always a REAL measurement.
# Floors mirror ProbeAnchorRuntimeFloorTest.MAX_MISSES — keep in sync.
# Skip with WYRDSEKAI_SKIP_RUNTIME_GATE=1.
if [[ -z "${WYRDSEKAI_SKIP_RUNTIME_GATE:-}" ]]; then
    info "Runtime floor gate (ClassifierArm path, real tokenizer, direct probe)..."
    declare -A RUNTIME_FLOORS=(
        [task_present]=4
        [cleanliness]=6
        [substrate_present]=3
        [request_type]=14
    )
    runtime_gate_failed=0
    for gate_head in "${!RUNTIME_FLOORS[@]}"; do
        floor="${RUNTIME_FLOORS[$gate_head]}"
        onnx="$PRETRAINED_DIR/$gate_head.onnx"
        labels="$PRETRAINED_DIR/$gate_head.labels.json"
        [[ -f "$onnx" && -f "$labels" ]] || { warn "runtime gate: $gate_head artifacts missing — skipping head"; continue; }
        result="$(cd "$PROJECT_DIR" && java --enable-native-access=ALL-UNNAMED \
            -cp "$(cat cli/build/probe-classpath.txt)" \
            org.wyrdsekai.cli.ProbeHeadMain \
            --head "$gate_head" --classifier "$onnx" --labels "$labels" \
            --max-misses "$floor" 2>/dev/null)" || { err "runtime gate: probe errored for $gate_head"; runtime_gate_failed=1; continue; }
        passes="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['overrouting_probe_passes'])" "$result" 2>/dev/null)"
        misses="$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['misclassified'])" "$result" 2>/dev/null)"
        if [[ "$passes" == "True" ]]; then
            ok "  $gate_head: $misses misses (floor $floor) ✓"
        else
            err "  $gate_head: $misses misses EXCEEDS floor $floor ✗"
            runtime_gate_failed=1
        fi
    done
    if [[ $runtime_gate_failed -ne 0 ]]; then
        err "RUNTIME floor gate FAILED — a shipping head exceeds its miss floor in"
        err "the ClassifierArm path. Revert the offending head (git checkout"
        err "core/src/main/resources/classifier/pretrained/<head>.*) and re-bake,"
        err "or investigate. Release aborted."
        exit 1
    fi
    ok "Runtime floor gate PASSED — every shipping head measured (no vacuous skips)."
fi

ok "All ${#succeeded_heads[@]} head(s) baked clean + cleared the runtime gate. Evidence in $EVIDENCE_DIR/"
exit 0
