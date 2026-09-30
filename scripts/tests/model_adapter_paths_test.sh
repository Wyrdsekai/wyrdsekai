#!/usr/bin/env bash
# `wyrd model status|verify|update|rollback` on adapter entries: an adapter lives beside the brain
# (adapters/brain/<local_file>), a model under models/; brain/work.gguf follows the honesty
# adapter it was linked from and a work.gguf someone placed themselves is left alone. The launcher
# runs from a temporary tree with its own index; `curl` is a stub that serves local files, so no
# network. Run: bash scripts/tests/model_adapter_paths_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/proj/bin" "$T/src" "$T/stub" "$T/data"
cp "$HERE/bin/wyrd" "$T/proj/bin/wyrd"; ln -s "$HERE/scripts" "$T/proj/scripts"
# the files the stub serves, by the name at the end of the URL
printf 'honesty v1\n' > "$T/src/honesty-v1.gguf"; printf 'honesty v2\n' > "$T/src/honesty-v2.gguf"
printf 'species v1\n' > "$T/src/species-v1.gguf"; printf 'base model\n' > "$T/src/base.gguf"; printf 'styled v1\n' > "$T/src/styled-v1.gguf"
sha() { sha256sum "$1" | cut -d' ' -f1; }
cat > "$T/stub/curl" <<'STUB'
#!/usr/bin/env bash
dest=""; url=""
while [ $# -gt 0 ]; do case "$1" in -o) dest="$2"; shift 2;; -*) shift;; *) url="$1"; shift;; esac; done
case "$url" in */missing/*) exit 22;; esac
src="$STUB_SRC/$(basename "$url")"
[ -f "$src" ] || exit 22
cp "$src" "$dest"
STUB
chmod +x "$T/stub/curl"
index() {  # <honesty version> <honesty sha>
cat > "$T/proj/models-index.json" <<JSON
{"models":[
 {"id":"base-x","kind":"llm-single","version":"b1","local_file":"base.gguf","sha256":"$(sha "$T/src/base.gguf")","url":"https://example.invalid/base.gguf"},
 {"id":"honesty-lora-35b-a3b","kind":"adapter","version":"$1","local_file":"honesty.gguf","sha256":"$2","url":"https://example.invalid/honesty-$1.gguf"},
 {"id":"species-floor-35b-a3b","kind":"adapter","version":"v1","local_file":"species.gguf","sha256":"$(sha "$T/src/species-v1.gguf")","url":"https://example.invalid/species-v1.gguf"},
 {"id":"styled-35b-a3b","kind":"adapter","version":"v1","local_file":"styled.gguf","sha256":"$(sha "$T/src/styled-v1.gguf")","url":"https://example.invalid/missing/styled-v1.gguf","mirror_url":"https://mirror.invalid/models/styled-v1.gguf"}
]}
JSON
}
index v1 "$(sha "$T/src/honesty-v1.gguf")"
W() { PATH="$T/stub:$PATH" STUB_SRC="$T/src" WYRDSEKAI_DATA_DIR="$T/data" WYRDSEKAI_CONFIG_FILE=/nonexistent HOME="$T" \
      bash "$T/proj/bin/wyrd" model "$@" 2>&1; }
fail=0
check() {   # <label> <condition...>
    local label="$1"; shift
    if "$@"; then echo "ok   $label"; else echo "FAIL $label"; fail=1; fi
}
A="$T/data/adapters/brain"; M="$T/data/models"
out=$(W status)
check "status: adapters read as not installed"  grep -q "species-floor-35b-a3b    v1                           NOT INSTALLED" <<<"$out"
W update species-floor-35b-a3b >/dev/null
check "update: the species floor lands beside the brain"  test -f "$A/species.gguf"
check "update: nothing named after it under models/"       test ! -e "$M/species.gguf" -a ! -e "$M/honesty.gguf"
check "update: content is the species file"                cmp -s "$A/species.gguf" "$T/src/species-v1.gguf"
W update base-x >/dev/null
check "update: a model still lands under models/"          test -f "$M/base.gguf"
out=$(W status)
check "status: the installed adapter is up to date"        grep -q "species-floor-35b-a3b    v1                           up to date" <<<"$out"
out=$(W verify)
check "verify: hashes the adapter where it lives"          grep -q "species-floor-35b-a3b    matches index" <<<"$out"
W update honesty-lora-35b-a3b >/dev/null
ln "$A/honesty.gguf" "$A/work.gguf"          # what brain setup does beside the species floor
index v2 "$(sha "$T/src/honesty-v2.gguf")"
out=$(W update honesty-lora-35b-a3b)
check "update honesty: previous kept"                       test -f "$A/honesty.gguf.prev"
check "update honesty: work.gguf follows the new file"      test "$A/work.gguf" -ef "$A/honesty.gguf"
check "update honesty: work.gguf holds v2"                  cmp -s "$A/work.gguf" "$T/src/honesty-v2.gguf"
check "update honesty: says it linked work"                 grep -q "work.gguf" <<<"$out"
W rollback honesty-lora-35b-a3b >/dev/null
check "rollback: honesty is v1 again"                       cmp -s "$A/honesty.gguf" "$T/src/honesty-v1.gguf"
check "rollback: work.gguf follows back"                    test "$A/work.gguf" -ef "$A/honesty.gguf"
rm -f "$A/work.gguf"; printf 'my own work adapter\n' > "$A/work.gguf"
index v2 "$(sha "$T/src/honesty-v2.gguf")"
W update honesty-lora-35b-a3b >/dev/null
check "update: a work.gguf someone placed is left alone"    cmp -s "$A/work.gguf" <(printf 'my own work adapter\n')
out=$(W update styled-35b-a3b)
check "mirror: a dead first address falls through to the entry's mirror"  cmp -s "$A/styled.gguf" "$T/src/styled-v1.gguf"
check "mirror: the manifest names the address that answered"           grep -q '"id":"styled-35b-a3b","version":"v1","sha256":"[0-9a-f]*","source_url":"https://mirror.invalid/models/styled-v1.gguf"' "$M/models-manifest.jsonl"
check "mirror: the dead address was reported"                          grep -q "mirror unavailable: example.invalid/missing/styled-v1.gguf" <<<"$out"
check "history: the update was recorded"                    grep -q '"id":"species-floor-35b-a3b","action":"update"' "$T/data/model-history.jsonl"
check "manifest: the adapter record is under models/"       grep -q '"file":"species.gguf","id":"species-floor-35b-a3b","version":"v1"' "$M/models-manifest.jsonl"
exit $fail
