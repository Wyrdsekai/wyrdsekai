#!/usr/bin/env bash
# `wyrd researcher gpu`: where the library's model runs (the companions' brain by default, its own
# card, another machine), how to change it, and the suggestion in setup and doctor. Every change goes
# through ResearchZosho's own commands as the user the library belongs to; a fake ResearchZosho records
# them. The changes are tested against a 0.5.0 (its setup offers the address, then the service is
# restarted) and a 0.5.1 (`model use` switches the running service; no setup, no restart). Lifts the functions from bin/wyrd with fakes for nvidia-smi, docker, curl, systemctl, hostname
# and uname, and runs each command under bin/wyrd's own shell options. Run as a normal user.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
for fn in _version_newer _sibling_version _sibling_as _rz_brain_url _rz_lan_ip _rz_ask _rz_yesno \
          _rz_model_status _rz_drive_of _rz_has_own_model _rz_host_is_local _rz_url_key _rz_where \
          _rz_brain_pids _rz_library_pids _rz_gpu_cards _rz_free_card _rz_gpu_suggest _rz_gpu_show \
          _rz_restart_service _rz_point_drive _rz_host_answers _rz_host_url _rz_gpu_done _rz_gpu_card \
          _rz_gpu_host _rz_gpu_shared _rz_gpu _rz_chat_model _rz_current_model _rz_model_use _rz_plan_after; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$HERE/bin/wyrd")"
    declare -F "$fn" >/dev/null || { echo "FAIL could not lift $fn from bin/wyrd"; exit 1; }
done
eval "$(grep -E '^RZ_OWN_MODEL_URL=|^RZ_LIVE_SWITCH=' "$HERE/bin/wyrd")"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
CALLS="$T/calls"; : > "$CALLS"
export RESEARCHZOSHO_HOME="$T/rzhome"; mkdir -p "$RESEARCHZOSHO_HOME"   # the library's own settings file
log() { echo "LOG $*"; }; warn() { echo "WARN $*"; }; err() { echo "ERR $*"; }; info() { echo "INFO $*"; }
_t() { echo "$*"; }
_drain_stdin() { :; }

# Who runs ResearchZosho's commands: the real _sibling_as, with every call recorded under the user it names.
eval "$(sed -n '/^_sibling_as() {/,/^}/p' "$HERE/bin/wyrd" | sed 's/^_sibling_as() {/_sibling_as_real() {/')"
_sibling_as() { echo "AS $*" >> "$CALLS"; _sibling_as_real "$@"; }
_rz_bin_for_update() { echo "librarian $T/rz/bin/researchzosho"; }

# The fake ResearchZosho: its drive, whether it has a model server of its own, its version.
mkdir -p "$T/rz/bin"
cat > "$T/rz/bin/researchzosho" <<SH
#!/bin/sh
T="$T"
case "\$1 \$2" in
  "version "*) echo "researchzosho \$(cat "\$T/rz.version")"; exit 0 ;;
  "model status")
    [ -f "\$T/rz.nomodel" ] && { echo "usage: researchzosho <verb>" >&2; exit 2; }
    echo "  drive: \$(cat "\$T/rz.drive")"
    if [ -f "\$T/rz.own" ]; then echo "  model: Qwen3.8-27B on CUDA, stops after 20 idle minutes"
    else echo "  no model server of this machine's own (\\\`researchzosho model install\\\` sets one up)"; fi
    exit 0 ;;
  "model install")
    echo "rz model install \$3 \$4 \$5" >> "\$T/calls"
    rc=\$(cat "\$T/rz.install_rc" 2>/dev/null || echo 0)
    [ "\$rc" = 0 ] || exit "\$rc"
    [ -f "\$T/rz.prev" ] || cp "\$T/rz.drive" "\$T/rz.prev"
    echo "http://127.0.0.1:8211/v1/models" >> "\$T/answers"
    echo "http://127.0.0.1:8211" > "\$T/rz.drive"; touch "\$T/rz.own"; exit 0 ;;
  "model use")
    [ "\$(cat "\$T/rz.version")" = 0.5.0 ] && { echo "usage: researchzosho model install|status|switch|prune|stop|uninstall|check"; exit 2; }
    echo "rz model use \$3 \$4" >> "\$T/calls"
    grep -qxF "\$3/v1/models" "\$T/answers" 2>/dev/null || { echo "  No model server answers at \$3. Nothing was changed."; exit 1; }
    [ -f "\$T/rz.use_rc" ] && exit "\$(cat "\$T/rz.use_rc")"
    n=\$(grep -o '"id"' "\$T/models.json" | wc -l)
    if [ -z "\$4" ] && [ "\$n" -gt 1 ]; then echo "  The server serves \$n models. Name the one to use. Nothing was changed."; exit 2; fi
    echo "\$3" > "\$T/rz.drive"; echo "  The library now uses \$4 at \$3."; exit 0 ;;
  "model uninstall")
    echo "rz model uninstall" >> "\$T/calls"
    rm -f "\$T/rz.own"
    if [ -f "\$T/rz.prev" ]; then mv "\$T/rz.prev" "\$T/rz.drive"; fi
    exit 0 ;;
  "setup "*)
    shift; echo "rz setup \$* DRIVE=\$RESEARCHZOSHO_DRIVE" >> "\$T/calls"
    [ -n "\$RESEARCHZOSHO_DRIVE" ] && echo "\$RESEARCHZOSHO_DRIVE" > "\$T/rz.drive"
    exit 0 ;;
esac
exit 2
SH
chmod +x "$T/rz/bin/researchzosho"
RZBIN="$T/rz/bin/researchzosho"

# This machine: home-server, 192.0.2.5, two cards; the brain's container holds pid 4242.
hostname() { case "${1:-}" in -I) echo "192.0.2.5" ;; *) echo "home-server" ;; esac; }
WYRDSEKAI_LAN_IP=192.0.2.5
uname() { if [[ "${1:-}" == "-s" ]]; then echo "${FAKE_OS:-Linux}"; else command uname "$@"; fi; }
nvidia-smi() {
    case "$*" in
        *--query-gpu=*) cat "$T/gpus" ;;
        *--query-compute-apps=*) cat "$T/apps" ;;
    esac
}
docker() {
    [[ "$1" == top ]] || return 1
    case "$2" in
        wyrdsekai-llama-brain) [[ -f "$T/brain_up" ]] && printf 'PID\n4242\n' || return 1 ;;
        researchzosho-model) [[ -f "$T/lib_up" ]] && printf 'PID\n5151\n' || return 1 ;;
        *) return 1 ;;
    esac
}
pgrep() { return 1; }
curl() {   # answers the addresses listed in $T/answers, with the model list in $T/models.json
    local u="${*: -1}"
    grep -qxF "$u" "$T/answers" 2>/dev/null || return 22
    cat "$T/models.json"
}
systemctl() {
    echo "systemctl $*" >> "$CALLS"
    case "$*" in *is-active*) [[ -f "$T/service_up" ]] ;; *restart*) return 0 ;; *) return 1 ;; esac
}
unset WYRDSEKAI_LLAMA_URL RESEARCHZOSHO_DRIVE_DEFAULT LLAMA_DRIVE_PORT LLAMA_VOICE_PORT RESEARCHZOSHO_DRIVE

two_cards() {   # card 0 holds the brain, card 1 is empty
    printf '0, GPU-aaa, NVIDIA GeForce RTX 4090, 24564, 20010\n1, GPU-bbb, NVIDIA RTX A4000, 16376, 4\n' > "$T/gpus"
    printf 'GPU-aaa, 4242\n' > "$T/apps"; touch "$T/brain_up"
}
one_card() { printf '0, GPU-aaa, NVIDIA GeForce RTX 5060 Ti, 16311, 9800\n' > "$T/gpus"; printf 'GPU-aaa, 4242\n' > "$T/apps"; touch "$T/brain_up"; }
state() {   # <drive> [own]   (ResearchZosho $VER)
    rm -f "$T/rz.own" "$T/rz.prev" "$T/rz.nomodel" "$T/rz.install_rc" "$T/rz.use_rc" "$T/answers" "$T/lib_up"
    echo "$1" > "$T/rz.drive"; echo "$VER" > "$T/rz.version"; touch "$T/service_up"; : > "$T/answers"
    echo '{"data":[{"id":"embed"},{"id":"qwen3.8-27b"}]}' > "$T/models.json"   # the proxy lists its embeddings server too
    if [[ "${2:-}" == own ]]; then touch "$T/rz.own"; echo "http://127.0.0.1:8200" > "$T/rz.prev"; fi
    return 0
}
VER=0.5.0
fail=0
check() { local label="$1"; shift; if "$@"; then echo "ok   $label"; else echo "FAIL $label"; fail=1; fi; }
has() { grep -qF -- "$1" <<<"$OUT"; }
called() { grep -qF -- "$1" "$CALLS"; }
not_called() { ! grep -qF -- "$1" "$CALLS"; }
# Every command under bin/wyrd's own shell options: a non-zero answer from a fake must be read, not end the script.
run() {   # <function> <args...>
    : > "$CALLS"
    ( set -euo pipefail; rc=0; "$@" || rc=$?; echo "RC=$rc"; echo "RAN TO ITS END" ) > "$T/out" 2>&1
    OUT="$(cat "$T/out")"
    grep -q "RAN TO ITS END" <<<"$OUT" || { echo "FAIL $* stopped inside: $(tail -2 <<<"$OUT")"; fail=1; }
}

# ── comparing model servers ──
check "localhost is this machine"                   test "$(_rz_url_key http://localhost:8200)" = "local:8200"
check "127.0.0.1 is this machine"                   test "$(_rz_url_key http://127.0.0.1:8200/v1)" = "local:8200"
check "the LAN address is this machine"             test "$(_rz_url_key http://192.0.2.5:8200)" = "local:8200"
check "the host name is this machine, any case"     test "$(_rz_url_key http://HOME_SERVER:8211)" = "local:8211"
check "another machine keeps its name"              test "$(_rz_url_key http://Dollhouse:8211/)" = "gpu-host:8211"
check "https without a port is 443"                 test "$(_rz_url_key https://api.example.com/v1)" = "api.example.com:443"
check "IPv6 loopback is this machine"               test "$(_rz_url_key 'http://[::1]:8200')" = "local:8200"
check "a machine name → its proxy on 8211"          test "$(_rz_host_url gpu-host)" = "http://gpu-host:8211"
check "a port named is kept"                        test "$(_rz_host_url gpu-host:9000)" = "http://gpu-host:9000"
check "a full address is kept, /v1 dropped"         test "$(_rz_host_url https://box.lan:8211/v1)" = "https://box.lan:8211"
check "where: the brain's address → shared"         test "$(_rz_where http://localhost:8200 http://127.0.0.1:8200)" = shared
check "where: unset → ResearchZosho's default, the brain" test "$(_rz_where '(unset)' http://127.0.0.1:8200)" = shared
check "where: the voice port counts as the brain"   test "$(_rz_where http://127.0.0.1:8201 http://127.0.0.1:8200)" = shared
check "where: the brain on another machine, same address → shared" test "$(_rz_where http://gpu-host:8200 http://gpu-host:8200)" = shared
check "where: its proxy here → own"                 test "$(_rz_where http://127.0.0.1:8211 http://127.0.0.1:8200)" = own
check "where: another machine → other"              test "$(_rz_where http://gpu-host:8211 http://127.0.0.1:8200)" = other

# ── the cards ──
two_cards
CARDS="$(_rz_gpu_cards http://127.0.0.1:8200)"
check "cards: the brain's card is named"            grep -qx "0|NVIDIA GeForce RTX 4090|24564|20010|brain" <<<"$CARDS"
check "cards: an empty card is free"                grep -qx "1|NVIDIA RTX A4000|16376|4|free" <<<"$CARDS"
check "free card: a second card, not the brain's"   test "$(_rz_free_card http://127.0.0.1:8200)" = "1|NVIDIA RTX A4000"
one_card
check "free card: one card and the brain here → none" eval '! _rz_free_card http://127.0.0.1:8200 >/dev/null'
printf '0, GPU-aaa, RTX 4090, 24564, 3\n1, GPU-bbb, RTX A4000, 16376, 4\n' > "$T/gpus"; : > "$T/apps"; rm -f "$T/brain_up"
check "free card: brain not running → card 0 is left to it" test "$(_rz_free_card http://127.0.0.1:8200)" = "1|RTX A4000"
check "free card: the brain on another machine → card 0 may go" test "$(_rz_free_card http://gpu-host:8200)" = "0|RTX 4090"
printf '0, GPU-aaa, RTX 4090, 24564, 20010\n1, GPU-bbb, RTX A4000, 16376, 12000\n' > "$T/gpus"; printf 'GPU-aaa, 4242\n' > "$T/apps"; touch "$T/brain_up"
check "cards: a card that is mostly in use is not free, even with no compute process" grep -qx "1|RTX A4000|16376|12000|" <<<"$(_rz_gpu_cards http://127.0.0.1:8200)"
check "free card: none when the second card is busy" eval '! _rz_free_card http://127.0.0.1:8200 >/dev/null'
printf '0, GPU-aaa, RTX 4090, 24564, 3\n1, GPU-bbb, RTX A4000, 16376, 4\n' > "$T/gpus"; rm -f "$T/brain_up"
printf 'GPU-bbb, 5151\n' > "$T/apps"; touch "$T/lib_up"
check "cards: the library's model is named"         grep -q "^1|RTX A4000|16376|4|library$" <<<"$(_rz_gpu_cards http://127.0.0.1:8200)"
rm -f "$T/lib_up"

# ── the status screen ──
two_cards; state http://127.0.0.1:8200
run _rz_gpu
check "status shared: says it shares the brain"     has "researcher.gpu.now_shared http://127.0.0.1:8200"
check "status shared: the brain's card"             has "researcher.gpu.card 0 NVIDIA GeForce RTX 4090 24564 20010 researcher.gpu.card_brain"
check "status shared: the free card"                has "researcher.gpu.card 1 NVIDIA RTX A4000 16376 4 researcher.gpu.card_free"
check "status shared: why it matters"               has "researcher.gpu.why"
check "status shared: the one command for the free card" has "researcher.gpu.cmd_card 1"
check "status shared: the other-machine way"        has "researcher.gpu.cmd_host"
check "status: ResearchZosho asked as its user"     called "AS librarian $RZBIN model status"
check "status: changes nothing"                      eval '! grep -qE "rz (model install|model uninstall|setup)|restart" "$CALLS"'
state http://127.0.0.1:8211 own; run _rz_gpu
check "status own card: says so"                    has "researcher.gpu.now_own http://127.0.0.1:8211"
check "status own card: where the brain is"         has "researcher.gpu.brain http://127.0.0.1:8200"
check "status own card: the way back"               has "researcher.gpu.cmd_shared"
check "status own card: no card offered"            eval '! has researcher.gpu.cmd_card'
state http://gpu-host:8211; run _rz_gpu
check "status other machine: says so"               has "researcher.gpu.now_other http://gpu-host:8211"
check "status other machine: the way back"          has "researcher.gpu.cmd_shared"
state http://127.0.0.1:8200; touch "$T/rz.nomodel"; run _rz_gpu
check "status: a release without model status → not known" has "researcher.gpu.now_unknown"
state http://127.0.0.1:8200; FAKE_OS=Darwin run _rz_gpu
check "status on macOS: no card offered, the other-machine way" eval 'has researcher.gpu.linux_only && has researcher.gpu.cmd_host && ! has researcher.gpu.cmd_card'

# ── ResearchZosho 0.5.0: its setup offers the address, then the service is restarted ──
VER=0.5.0
# ── gpu <card> ──
two_cards; state http://127.0.0.1:8200; run _rz_gpu 1 --yes
check "gpu 1: model install --own --gpu 1"          called "rz model install --own --gpu 1"
check "gpu 1: as the library's user"                called "AS librarian $RZBIN model install --own --gpu 1"
check "gpu 1: says what happens first"              has "researcher.gpu.plan_card 1 NVIDIA RTX A4000"
check "gpu 1: the library's service restarts"       called "systemctl --user restart researchzosho"
check "gpu 1: restarted as the library's user"      called "AS librarian systemctl --user restart researchzosho"
check "gpu 1: confirmed with model status"          eval 'has researcher.gpu.done && has "drive: http://127.0.0.1:8211"'
check "gpu 1: exit 0"                               has "RC=0"
check "0.5.0: says the service restarts, never asks model use" eval 'has researcher.gpu.plan_restart && ! has researcher.gpu.plan_use && not_called "model use"'
state http://127.0.0.1:8200; rm -f "$T/service_up"; run _rz_gpu 1 --yes
check "gpu 1 with no service: not restarted, said"  eval 'has researcher.gpu.no_service && not_called "restart researchzosho"'
state http://127.0.0.1:8200; run _rz_gpu 0 --yes
check "gpu 0 (the brain's card): warned"            has "WARN researcher.gpu.card_is_brain 0"
check "gpu 0 (the brain's card): --yes takes the default, no" eval 'has researcher.gpu.nothing && not_called "model install"'
state http://127.0.0.1:8200; run _rz_gpu 5 --yes
check "gpu 5: no such card, nothing installed"      eval 'has "researcher.gpu.no_such_card 5 0, 1" && not_called "model install" && has RC=1'
state http://127.0.0.1:8200; echo 0.4.6 > "$T/rz.version"; run _rz_gpu 1 --yes
check "gpu 1 on ResearchZosho 0.4.6: update first"   eval 'has "researcher.gpu.too_old 0.4.6" && not_called "model install"'
state http://127.0.0.1:8200; FAKE_OS=Darwin run _rz_gpu 1 --yes
check "gpu 1 on macOS: not offered, the other-machine way" eval 'has researcher.gpu.linux_only && has researcher.gpu.cmd_host && not_called "model install"'
state http://127.0.0.1:8211 own; run _rz_gpu 1 --yes
check "gpu 1 with its own server already: moved"    eval 'has "researcher.gpu.plan_move 1" && grep "^rz model" "$CALLS" | tr "\n" " " | grep -q "uninstall rz model install --own --gpu 1"'
state http://127.0.0.1:8200; echo "$RZ_OWN_MODEL_URL/v1/models" > "$T/answers"; run _rz_gpu 1 --yes
check "gpu 1 when another program's proxy holds 8211: refused" eval 'has researcher.gpu.proxy_other && not_called "model install"'
state http://127.0.0.1:8200; echo 1 > "$T/rz.install_rc"; run _rz_gpu 1 --yes
check "gpu 1 when the install fails: said, not restarted" eval 'has "ERR researcher.gpu.install_failed http://127.0.0.1:8200" && not_called "restart researchzosho" && has RC=1'

# ── gpu <machine> ──
state http://127.0.0.1:8200; run _rz_gpu gpu-host --yes
check "gpu gpu-host, nothing answers: refused"     has "ERR researcher.gpu.host_down http://gpu-host:8211"
check "gpu gpu-host, nothing answers: what to run there" has "researcher.gpu.host_prepare"
check "gpu gpu-host, nothing answers: nothing changed" eval 'not_called "rz setup" && not_called restart && has RC=1'
state http://127.0.0.1:8200; echo "http://gpu-host:8211/v1/models" > "$T/answers"; run _rz_gpu gpu-host --yes
check "gpu gpu-host: its own setup, the address offered, as its user" called "AS librarian env RESEARCHZOSHO_DRIVE=http://gpu-host:8211 $RZBIN setup --yes --no-service --no-claude"
check "gpu gpu-host: the drive now points there"   test "$(cat "$T/rz.drive")" = "http://gpu-host:8211"
check "gpu gpu-host: says why its setup runs"      has "researcher.gpu.plan_setup"
check "gpu gpu-host: the service restarts"         called "systemctl --user restart researchzosho"
check "gpu gpu-host: exit 0"                       has "RC=0"
state http://127.0.0.1:8200; echo "http://192.0.2.9:9000/v1/models" > "$T/answers"; run _rz_gpu 192.0.2.9:9000 --yes
check "gpu <ip:port>: that address"                 called "RESEARCHZOSHO_DRIVE=http://192.0.2.9:9000"
state http://127.0.0.1:8200; echo "http://gpu-host:8211/v1/models" > "$T/answers"; run _rz_gpu gpu-host qwen-b --yes
check "gpu gpu-host <model>: its setup is offered that model too" called "env RESEARCHZOSHO_DRIVE=http://gpu-host:8211 RESEARCHZOSHO_MODEL=qwen-b $RZBIN setup"

# ── gpu shared ──
state http://127.0.0.1:8211 own; run _rz_gpu shared --yes
check "gpu shared with its own server: model uninstall" called "AS librarian $RZBIN model uninstall"
check "gpu shared with its own server: the drive came back, no setup needed" eval 'test "$(cat "$T/rz.drive")" = http://127.0.0.1:8200 && not_called "rz setup"'
check "gpu shared: the service restarts"            called "systemctl --user restart researchzosho"
state http://gpu-host:8211; run _rz_gpu shared --yes
check "gpu shared from another machine: setup offered the brain" called "RESEARCHZOSHO_DRIVE=http://127.0.0.1:8200 $RZBIN setup --yes --no-service --no-claude"
check "gpu shared from another machine: no uninstall" not_called "rz model uninstall"
check "gpu shared from another machine: back on the brain" test "$(cat "$T/rz.drive")" = "http://127.0.0.1:8200"
state http://127.0.0.1:8200; run _rz_gpu shared --yes
check "gpu shared when already shared: nothing to do" eval 'has researcher.gpu.already_shared && not_called "rz " && not_called restart'

# ── ResearchZosho 0.5.1: `model use` switches the running service; no setup, no restart ──
VER=0.5.1
two_cards; state http://127.0.0.1:8200; run _rz_gpu 1 --yes
check "0.5.1 gpu 1: model install --own --gpu 1, as its user" called "AS librarian $RZBIN model install --own --gpu 1"
check "0.5.1 gpu 1: then model use on its proxy, the model named" called "AS librarian $RZBIN model use http://127.0.0.1:8211 qwen3.8-27b"
check "0.5.1 gpu 1: install before use"             eval 'grep "^rz model" "$CALLS" | tr "\n" " " | grep -q "install --own --gpu 1 *rz model use"'
check "0.5.1 gpu 1: no restart of ours"             eval 'not_called "systemctl" && ! has researcher.gpu.restarted'
check "0.5.1 gpu 1: says the running service takes it" eval 'has researcher.gpu.plan_use && ! has researcher.gpu.plan_restart'
check "0.5.1 gpu 1: confirmed, exit 0"              eval 'has researcher.gpu.done && has RC=0'
state http://127.0.0.1:8200; echo 1 > "$T/rz.use_rc"; run _rz_gpu 1 --yes
check "0.5.1 gpu 1, the model does not reply yet: said, with the command to switch later" eval 'has "WARN researcher.gpu.use_failed http://127.0.0.1:8211" && has "researcher.gpu.use_retry http://127.0.0.1:8211 qwen3.8-27b" && has RC=1 && not_called systemctl'
state http://127.0.0.1:8200; echo "http://gpu-host:8211/v1/models" > "$T/answers"; run _rz_gpu gpu-host --yes
check "0.5.1 gpu gpu-host: model use, the one chat model named" called "AS librarian $RZBIN model use http://gpu-host:8211 qwen3.8-27b"
check "0.5.1 gpu gpu-host: no setup run, no restart" eval 'not_called "rz setup" && not_called systemctl'
check "0.5.1 gpu gpu-host: no word about setup"    eval '! has researcher.gpu.plan_setup && has researcher.gpu.plan_use'
check "0.5.1 gpu gpu-host: the drive points there, exit 0" eval 'test "$(cat "$T/rz.drive")" = http://gpu-host:8211 && has RC=0'
state http://127.0.0.1:8200; echo "http://gpu-host:8211/v1/models" > "$T/answers"
echo '{"data":[{"id":"qwen-a"},{"id":"qwen-b"},{"id":"embed"}]}' > "$T/models.json"; run _rz_gpu gpu-host --yes
check "0.5.1 gpu gpu-host, two chat models: ResearchZosho asks for the name, we say how" eval 'called "rz model use http://gpu-host:8211 " && has "researcher.gpu.use_name gpu-host" && has RC=1 && test "$(cat "$T/rz.drive")" = http://127.0.0.1:8200'
run _rz_gpu gpu-host qwen-b --yes
check "0.5.1 gpu gpu-host <model>: that model"     eval 'called "rz model use http://gpu-host:8211 qwen-b" && has RC=0'
# A server with several models keeps the one the library uses now; one it does not offer is not guessed.
state http://127.0.0.1:8200; echo "http://gpu-host:8211/v1/models" > "$T/answers"
echo '{"data":[{"id":"qwen-a"},{"id":"qwen-b"},{"id":"embed"}]}' > "$T/models.json"
echo 'RESEARCHZOSHO_MODEL = qwen-b' > "$RESEARCHZOSHO_HOME/config"; run _rz_gpu gpu-host --yes
check "0.5.1 gpu gpu-host, several models: keeps the one it uses now" eval 'called "rz model use http://gpu-host:8211 qwen-b" && has RC=0'
echo 'RESEARCHZOSHO_MODEL = qwen-z' > "$RESEARCHZOSHO_HOME/config"; run _rz_gpu gpu-host --yes
check "0.5.1 gpu gpu-host, several models, its model not offered: asks for the name" eval 'has "researcher.gpu.use_name gpu-host" && has RC=1'
rm -f "$RESEARCHZOSHO_HOME/config"
state http://gpu-host:8211; echo "http://127.0.0.1:8200/v1/models" > "$T/answers"; echo '{"data":[{"id":"brain"}]}' > "$T/models.json"
run _rz_gpu shared --yes
check "0.5.1 gpu shared from another machine: model use on the brain" called "AS librarian $RZBIN model use http://127.0.0.1:8200 brain"
check "0.5.1 gpu shared from another machine: no setup, no restart, back on the brain" eval 'not_called "rz setup" && not_called systemctl && test "$(cat "$T/rz.drive")" = http://127.0.0.1:8200'
state http://127.0.0.1:8211 own; run _rz_gpu shared --yes
check "0.5.1 gpu shared with its own server: uninstall brings the brain back, nothing more" eval 'called "rz model uninstall" && not_called "model use" && not_called "rz setup" && not_called systemctl && test "$(cat "$T/rz.drive")" = http://127.0.0.1:8200'
state http://127.0.0.1:8200; run _rz_gpu 1 qwen-b --yes
check "a model name after a card is a usage error"  eval 'has "researcher.gpu.usage" && has RC=2 && not_called "rz "'

# ── the suggestion (setup and doctor) ──
two_cards; state http://127.0.0.1:8200; run _rz_gpu_suggest librarian "$RZBIN"
check "suggest shared: it shares"                   has "INFO researcher.gpu.suggest_shares http://127.0.0.1:8200"
check "suggest shared: why"                         has "INFO researcher.gpu.suggest_why"
check "suggest shared, second card free: names it"  has "INFO researcher.gpu.suggest_card 1 NVIDIA RTX A4000"
check "suggest: information, never a warning"       eval '! grep -q "^WARN" <<<"$OUT"'
one_card; run _rz_gpu_suggest librarian "$RZBIN"
check "suggest shared, one card: the command"       has "INFO researcher.gpu.suggest_cmd"
state http://127.0.0.1:8211 own; run _rz_gpu_suggest librarian "$RZBIN"
check "suggest own card: nothing"                   eval '! has researcher.gpu'
state http://gpu-host:8211; run _rz_gpu_suggest librarian "$RZBIN"
check "suggest other machine: nothing"              eval '! has researcher.gpu'
check "suggest: changes nothing"                    eval 'not_called "rz " && not_called restart'
DOCTOR="$(sed -n '/^do_doctor() {/,/^}/p' "$HERE/bin/wyrd")"
check "doctor gives the suggestion"                 grep -q '_rz_gpu_suggest "${_rz_found%% \*}" "${_rz_found#\* }"' <<<"$DOCTOR"
SETUP="$(sed -n '/^do_researcher() {/,/^}/p' "$HERE/bin/wyrd" | sed -n '/^        setup)/,/^            ;;/p')"
check "setup ends with the suggestion"              grep -q '_rz_gpu_suggest "$(id -un)" "$rz"' <<<"$SETUP"

# ── every key the launcher uses is in the English catalog ──
MISSING=""
for k in $(grep -oE 'researcher\.gpu\.[a-z_]+' "$HERE/bin/wyrd" | sort -u); do
    python3 -c 'import json,sys; sys.exit(0 if sys.argv[1] in json.load(open(sys.argv[2])) else 1)' "$k" "$HERE/scripts/i18n/wyrd_en.json" || MISSING="$MISSING $k"
done
check "every researcher.gpu key is in wyrd_en.json${MISSING:+ (missing:$MISSING)}" test -z "$MISSING"
exit $fail
