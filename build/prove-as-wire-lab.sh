#!/usr/bin/env bash
# Digicom-ET USSDGW — AS wire / AdaptiveTimeout prove gate (A∧B then Digicom C).
#
# Ship rule (grilling 2026-08-09):
#   1. A∧B unit tests MUST pass + package-dist OK  → only then rsync Digicom
#   2. Digicom C7 preflight (ss7.live / gateTicks / jar mtime)
#   3. C1–C6 on Digicom lab plane: ss7-simulator networkId=1 + lab short-codes
#   4. Brook / live networkId=0 is OUTSIDE automated C (manual prove only)
#   5. C fail ⇒ not shipped; rollback jars (never configs/)
#
# Usage (worktree root):
#   ./build/prove-as-wire-lab.sh              # A∧B only
#   ./build/prove-as-wire-lab.sh --preflight  # A∧B + Digicom C7 (needs digicom-nb + KEY)
#   ./build/prove-as-wire-lab.sh --help
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.local/share/mise/installs/java/zulu-25* 2>/dev/null | head -1)}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

APP_HOME="${USSD_DIGICOM_APP:-/home/app/ota-push-services/ussdgw-micro-jainslee}"
HOST="${USSD_DIGICOM_HOST:-digicom-nb}"
M2_ARGS=()
if [[ -d "$ROOT/.m2-agent-repo" ]]; then
  M2_ARGS=(-Dmaven.repo.local="$ROOT/.m2-agent-repo")
fi

die() { echo "FATAL: $*" >&2; exit 1; }
info() { echo "== $*"; }

run_ab() {
  info "A∧B: wire contract + begin/continue/end + AdaptiveTimeout gate"
  java -version 2>&1 | head -1 | grep -q 'version "25' || die "JDK 25 required (got: $(java -version 2>&1 | head -1))"
  mvn "${M2_ARGS[@]}" -Dtest=Map2MapAsWireContractExamplesTest,AsPullBeginContinueEndAndGateTest test
  info "A∧B green"
}

print_c_checklist() {
  cat <<'EOF'

---- Digicom C lab checklist (ss7-simulator networkId=1 ONLY) ----
Keep real SS7 / Brook on networkId=0 up, but do NOT dial live *804 / Brook codes
in this automated prove. Lab short-codes + sim plane only.

C7 preflight (before C1–C6):
  ssh digicom-nb 'sleep 25'
  # KEY from Digicom configs/application.properties ussd.admin.api-key — never invent
  ssh digicom-nb "curl -sS --connect-timeout 3 --max-time 10 -o /tmp/ussdgw-status.json -w '%{http_code}\n' -H \"X-USSD-Admin-Key: $KEY\" http://127.0.0.1:8088/admin/status.json"
  # Expect HTTP 200; ss7.live true; scheduler.gateTicks climbing; jar mtime fresh
  ssh digicom-nb 'python3 -c "import json;d=json.load(open(\"/tmp/ussdgw-status.json\"));print(d.get(\"ss7.live\"),d.get(\"scheduler.gateTicks\"),d.get(\"bridge.asyncGateMs\"))"'

C1  MO BEGIN → AS pull → CONTINUE menu (XML tenant, lab SC e.g. *100#) via ss7-sim net 1
C2  Digit → continue pull → menu 2 → END text (must NOT echo hlr none / hop codes)
C3  MAP2MAP lab short-code on sim (NOT live *804 Brook) → AS multimenu → END
C4  AdaptiveTimeout: force short async-gate / overdue deadline → UE wait; late AS → NI once
C5  Repeat C1–C2 (and ideally C3) with tenant HTTP AS wire = JSON
C6  pcap/filter on lab plane: processUnstructured BEGIN; Request menu; final Response; hop op59 if C3

Helpers:
  tools/ss7-simulator/pull-lab.sh   # apply-ss7 → as-node → reseed-pull → sim → dial-pull
  docs/agents/ss7-lab-pair.md       # dual plane net0 live / net1 lab
  docs/as-contract/map2map-as-xml.md § Prove / ship gate

Rollback on C fail (jars only — never configs/):
  # From a prior backup dir on Digicom, e.g. /tmp/ussdgw-jar-bak-<ts>/
  rsync -az /tmp/ussdgw-jar-bak-*/ussdgw-app.jar /tmp/ussdgw-jar-bak-*/quarkus-run.jar \
    digicom-nb:/home/app/ota-push-services/ussdgw-micro-jainslee/
  rsync -az --delete /tmp/ussdgw-jar-bak-*/lib/ digicom-nb:.../lib/
  rsync -az --delete /tmp/ussdgw-jar-bak-*/quarkus/ digicom-nb:.../quarkus/
  ssh digicom-nb 'sudo systemctl restart ussdgw.service'
  # Re-run C7; do not claim shipped until C1–C6 green again

Brook live prove (manual, outside C gate): handset / Balance Plus on networkId=0.
EOF
}

preflight_c7() {
  info "C7 Digicom preflight on $HOST"
  [[ -n "${KEY:-}" ]] || die "export KEY=… from Digicom ussd.admin.api-key (never invent)"
  ssh "$HOST" "curl -sS --connect-timeout 3 --max-time 10 -o /tmp/ussdgw-status.json -w '%{http_code}\n' -H \"X-USSD-Admin-Key: $KEY\" http://127.0.0.1:8088/admin/status.json" | tee /tmp/ussdgw-c7-http.txt
  grep -qx '200' /tmp/ussdgw-c7-http.txt || die "C7: /admin/status.json not HTTP 200"
  ssh "$HOST" 'python3 - <<"PY"
import json,sys
d=json.load(open("/tmp/ussdgw-status.json"))
live=d.get("ss7.live")
ticks=d.get("scheduler.gateTicks")
gate=d.get("bridge.asyncGateMs")
print("ss7.live", live)
print("scheduler.gateTicks", ticks)
print("bridge.asyncGateMs", gate)
ok = live is True and ticks is not None and int(ticks) >= 0 and gate is not None
sys.exit(0 if ok else 1)
PY' || die "C7: ss7.live / gateTicks / asyncGateMs check failed — stop before C1–C6"
  ssh "$HOST" "ls -la '$APP_HOME/ussdgw-app.jar'"
  info "C7 green — proceed with C1–C6 checklist (sim networkId=1)"
  print_c_checklist
}

backup_hint() {
  cat <<EOF
Before rsync Digicom, snapshot jars for rollback:
  ssh $HOST "mkdir -p /tmp/ussdgw-jar-bak-\$(date +%Y%m%d%H%M%S) && cd $APP_HOME && cp -a ussdgw-app.jar quarkus-run.jar /tmp/ussdgw-jar-bak-*/ && cp -a lib quarkus /tmp/ussdgw-jar-bak-*/"
EOF
}

case "${1:-}" in
  --help|-h)
    sed -n '2,20p' "$0"
    print_c_checklist
    ;;
  --preflight)
    run_ab
    backup_hint
    preflight_c7
    ;;
  --checklist)
    print_c_checklist
    ;;
  "")
    run_ab
    backup_hint
    print_c_checklist
    ;;
  *)
    die "unknown arg: $1 (try --help)"
    ;;
esac
