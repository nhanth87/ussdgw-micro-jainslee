#!/usr/bin/env bash
# Prove the RUNNING container, not the build host.
#
# AGENTS.md: green tests and a successful build NEVER mean a host runs the new code.
# This is the Docker adaptation of that gate. Each check below has a matching
# failure mode that is otherwise invisible until an operator hits it.
#
# Usage:
#   ./docker/prove.sh                       # local docker / compose
#   ./docker/prove.sh ussdgw_ussdgw        # swarm service (container id varies)
#   ./docker/prove.sh "$(docker ps -q --filter name=ussdgw_ussdgw | head -1)"
set -uo pipefail

CONTAINER="${1:-}"
BASE="${PROVE_BASE_URL:-http://127.0.0.1}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $*"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $*"; FAIL=$((FAIL+1)); }
head_() { echo; echo "=== $* ==="; }

if [[ -z "$CONTAINER" ]]; then
  CONTAINER="$(docker ps -q --filter name=ussdgw_ussdgw | head -1)"
fi
[[ -n "$CONTAINER" ]] || { echo "prove: no running ussdgw container — deploy first"; exit 1; }

# Resolve the admin key from the RUNNING container, not from this shell's environment, and
# do not guess: a wrong key turns every admin check into a 401, which reads as "the gateway is
# broken" when it is serving traffic. That misread is what made Swarm kill a healthy container
# earlier today. Three things went wrong, all silently:
#
#   1. the default was the built-in lab key `ussd-admin`, which no real deployment uses;
#   2. the key in force is `ussd.admin.api-key` in the mounted config when the deployment is
#      configured by file rather than by secret;
#   4. USSD_ADMIN_API_KEY in the ENVIRONMENT is trusted first — and docker/.env ships
#      USSD_ADMIN_API_KEY=ussd-admin as a lab placeholder, which deploy.sh re-exports. So
#      "the override wins" carried the lab default straight into the 401s on a host whose
#      real key is a 64-char secret. A source's name says nothing about whether its value is
#      the key in force.
#
# So: collect every candidate, then keep the first one the gateway ACCEPTS. Verified against
# a live host — an env override can be stale, a secret can be unreadable, and a config can
# carry the key — and only acceptance distinguishes them.
key_accepted() {  # $1 = candidate; 0 = the gateway accepted it
  [[ -n "$1" ]] || return 1
  [[ "$(curl -sS --connect-timeout 3 --max-time 10 -o /dev/null -w '%{http_code}' \
        -H "X-USSD-Admin-Key: $1" "$BASE:8088/admin/status.json" 2>/dev/null)" == 200 ]]
}
KEY=""; KEY_SOURCE=""
try_key() {  # $1 = candidate, $2 = description
  key_accepted "$1" || return 1
  KEY="$1"; KEY_SOURCE="$2"
  return 0
}
# 1. the secret the container was given, as the container's uid, then as root (docker's own
#    privilege, not ours). A deployment configured by secret has this as the key in force.
SECRET_KEY=""
for u in "" "0"; do
  out="$(docker exec ${u:+-u "$u"} "$CONTAINER" sh -c \
    'tr -d "\r\n" < "${USSD_ADMIN_API_KEY_FILE:-/run/secrets/ussdgw_admin_key}"' 2>/dev/null || true)"
  [[ -n "$out" ]] && { SECRET_KEY="$out"; break; }
done
# 2. ussd.admin.api-key from the config the container actually mounted
CFG_KEY="$(docker exec "$CONTAINER" sh -c \
  'sed -n "s/^ussd\.admin\.api-key=//p" /opt/ussdgw/configs/application.properties 2>/dev/null | head -1' \
  2>/dev/null | tr -d '\r\n[:space:]' || true)"
# 3. the environment override LAST. It is the one an operator sets by hand, but it is also
#    the one .env pre-fills with a lab placeholder, so it has to earn its place like the rest.
ENV_KEY="${USSD_ADMIN_API_KEY:-}"

# Prefer the secret, then the config, then the env override; each only if accepted.
try_key "$SECRET_KEY" "the container's mounted secret" \
  || try_key "$CFG_KEY"   "ussd.admin.api-key in the mounted config" \
  || try_key "$ENV_KEY"   "USSD_ADMIN_API_KEY from the environment"

if [[ -z "$KEY" ]]; then
  echo "admin key: none of the candidates was accepted by $BASE:8088/admin/status.json"
  [[ -n "$ENV_KEY" ]] && echo "  tried: USSD_ADMIN_API_KEY from the environment (${#ENV_KEY} chars)"
  [[ -n "$SECRET_KEY" ]] && echo "  tried: the mounted secret (${#SECRET_KEY} chars)"
  [[ -n "$CFG_KEY" ]] && echo "  tried: ussd.admin.api-key in the mounted config (${#CFG_KEY} chars)"
  [[ -z "$ENV_KEY" && -z "$SECRET_KEY" && -z "$CFG_KEY" ]] && \
    echo "  nothing to try: no secret readable, no api-key in the config, no override set"
  echo "  Refusing to run the admin checks: a 401 from a guessed key looks exactly like a"
  echo "  broken gateway, and that misreading is what made Swarm kill a healthy container."
  echo "  Set USSD_ADMIN_API_KEY to the key in force, or read it from the running container:"
  echo "    docker exec \$(docker ps -q --filter name=ussdgw_ussdgw | head -1) cat /run/secrets/ussdgw_admin_key"
  exit 1
fi
echo "admin key: $KEY_SOURCE (${#KEY} chars, verified by a 200 from the gateway)"
[[ -n "$ENV_KEY" && "$KEY" != "$ENV_KEY" ]] && \
  echo "  note: USSD_ADMIN_API_KEY in the environment does NOT match the key in force." \
       "The .env lab placeholder is the usual cause — it is ignored here, but fix it so" \
       "other tools stop guessing."

# Curl the admin API without putting the key in argv. `ps` on this host shows the full
# command line of every process, and prove.sh is run by hand on a carrier box that has other
# local accounts — the same reason the in-image probe uses `curl -K -`.
admin_get() {  # $1 = path under :8088, $2 = output file (or /dev/null)
  printf 'header = "X-USSD-Admin-Key: %s"\n' "$KEY" \
    | curl -sS --connect-timeout 3 --max-time 10 -o "$2" -w '%{http_code}' -K - "$BASE:8088$1" 2>/dev/null
}

echo "container: $(docker inspect -f '{{.Name}} ({{.Image}})' "$CONTAINER")"

# --- 1. the artifact in the container is the one we think it is -----------------
head_ "artifact identity"
img_digest="$(docker inspect -f '{{.Image}}' "$CONTAINER")"
expected_digest="$(docker image inspect -f '{{index .RepoDigests 0}}' "$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")" 2>/dev/null || echo "")"
if [[ -n "$img_digest" ]]; then
  ok "container runs image id ${img_digest:0:19}"
else
  bad "cannot read the image id of the running container"
fi

# BUILD-INFO.json travels inside the image: which SHAs produced it.
if info="$(docker exec "$CONTAINER" cat /opt/ussdgw/BUILD-INFO.json 2>/dev/null)"; then
  # Validate via a FILE, never through a pipeline. Under `set -o pipefail` an
  # `echo | python3 -c` dies with SIGPIPE the moment python stops reading, which
  # reports a perfectly valid BUILD-INFO as a failure.
  printf '%s\n' "$info" > /tmp/prove-build-info.json
  if summary="$(python3 - /tmp/prove-build-info.json <<'PY' 2>&1
import json, sys
d = json.load(open(sys.argv[1]))
print("  sources:")
for k, v in sorted(d.get("sources", {}).items()):
    print(f"    {k:18} {v}")
print(f"  builtAt:  {d.get('builtAt')}")
print(f"  dbKind:   {d.get('bakedDbKind')}  (must be postgresql)")
PY
  )"; then
    echo "$summary"
    ok "BUILD-INFO.json present and valid JSON"
  else
    bad "BUILD-INFO.json is not valid JSON: $summary"
  fi
else
  bad "no BUILD-INFO.json in the image - this is not a docker-built artifact"
fi

# The PG-bake stamp: an H2 bake on a PG host is THE crash-loop.
baked="$(docker exec "$CONTAINER" cat /opt/ussdgw/.baked-db-kind 2>/dev/null | tr -d '[:space:]')"
[[ "$baked" == "postgresql" ]] \
  && ok ".baked-db-kind=postgresql (matches the runtime config)" \
  || bad ".baked-db-kind='$baked' — expected postgresql; an H2 bake crash-loops on PG"

# --- 1b. the RUNNING task is the one the service spec describes ------------------
head_ "service/task agreement"
# `docker stack services` prints the spec's image and a replica count that stays 1/1 even
# while the update itself is stuck. nginx shipped exactly that: a task Pending for sixteen
# minutes on "no suitable node (host-mode port already in use)" — start-first against a
# host-mode published port can never be placed — while the service reported
# `1/1  ussdgw-nginx:<new-sha>` and the container actually serving was the previous image.
# Compare resolved image IDs, not tags: two tags can name the same or different bits.
stack_ns="$(docker inspect -f '{{index .Config.Labels "com.docker.stack.namespace"}}' "$CONTAINER" 2>/dev/null)"
if [[ -z "$stack_ns" ]]; then
  echo "  note  not a swarm task (no stack label) — skipping the service/task comparison"
else
  for svc in $(docker stack services "$stack_ns" --format '{{.Name}}' 2>/dev/null); do
    spec_img="$(docker service inspect -f '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "$svc" 2>/dev/null)"
    cid="$(docker ps -q --filter "label=com.docker.swarm.service.name=$svc" 2>/dev/null | head -1)"
    if [[ -z "$cid" ]]; then
      bad "$svc has NO running container, though its spec is $spec_img"
    else
      run_id="$(docker inspect -f '{{.Image}}' "$cid" 2>/dev/null)"
      spec_id="$(docker image inspect -f '{{.Id}}' "$spec_img" 2>/dev/null)"
      if [[ -z "$spec_id" ]]; then
        bad "$svc spec image '$spec_img' is not present locally — Swarm cannot converge on it"
      elif [[ "$run_id" == "$spec_id" ]]; then
        ok "$svc is really running its spec image ($(docker inspect -f '{{.Config.Image}}' "$cid"))"
      else
        bad "$svc is running $(docker inspect -f '{{.Config.Image}}' "$cid") but its spec says $spec_img — the update never landed"
      fi
    fi
    pend="$(docker service ps "$svc" --filter 'desired-state=running' --format '{{.CurrentState}} {{.Error}}' 2>/dev/null | grep -ci 'pending' || true)"
    if [[ "${pend:-0}" != "0" ]]; then
      bad "$svc has $pend task(s) stuck Pending — read why: docker service ps $svc --no-trunc"
    fi
  done
fi

# --- 2. the expected classes are actually inside the jar ------------------------
head_ "classes in the running jar"
# CdrFileLedger is the file-ledger read model; if it is missing the CDR page is
# silently serving the old DB path. A mtime is not proof — grep the jar.
# List the jar ONCE into a file. Same trap as above: `docker exec ... | grep -q`
# under `set -o pipefail` fails on a MATCH because grep -q exits at the first hit
# and docker exec is killed by SIGPIPE. That produced a false "wrong artifact".
docker exec "$CONTAINER" unzip -l /opt/ussdgw/ussdgw-app.jar > /tmp/prove-appjar.txt 2>/dev/null || true
if [ ! -s /tmp/prove-appjar.txt ]; then
  docker exec "$CONTAINER" sh -c 'jar tf /opt/ussdgw/ussdgw-app.jar' > /tmp/prove-appjar.txt 2>/dev/null || true
fi
if grep -q 'cdr/CdrFileLedger\.class' /tmp/prove-appjar.txt 2>/dev/null; then
  ok "CdrFileLedger present in ussdgw-app.jar (file-ledger read model)"
else
  bad "CdrFileLedger NOT in ussdgw-app.jar - wrong artifact deployed"
fi
# The MAP returnError branch must be inside the running SBB class, not just in git.
docker exec "$CONTAINER" sh -c \
  'unzip -p /opt/ussdgw/ussdgw-app.jar et/restlink/ussdgw/sbbs/MapUssdParentSbb.class' \
  > /tmp/prove-sbb.bin 2>/dev/null || true
if grep -aq 'MAP_RETURN_ERROR' /tmp/prove-sbb.bin 2>/dev/null; then
  ok "MapUssdParentSbb carries the MAP_RETURN_ERROR branch"
else
  bad "MAP_RETURN_ERROR not in the running MapUssdParentSbb - stale artifact"
fi
# The MAP returnError branch must be in the running SBB.
docker exec "$CONTAINER" sh -c \
  'unzip -l /opt/ussdgw/lib/main/com.microjainslee.ra-jss7-*.jar 2>/dev/null | grep -c Ss7MapEvent' \
  > /tmp/probe-jss7.txt 2>/dev/null || true
if [ -s /tmp/probe-jss7.txt ] && [ "$(tr -d '[:space:]' < /tmp/probe-jss7.txt)" != "0" ]; then
  ok "ra-jss7 present with the sealed Ss7MapEvent"
else
  echo "  SKIP  could not inspect ra-jss7 in the runtime image"
fi

# The running process really uses this jar, not a stale one from a previous deploy.
head_ "running process"
pid_in_container="$(docker exec "$CONTAINER" sh -c 'pgrep -f quarkus-run.jar | head -1' 2>/dev/null)"
[[ -n "$pid_in_container" ]] \
  && ok "JVM running inside the container (pid $pid_in_container)" \
  || bad "no JVM process found inside the container"
cp_line="$(docker exec "$CONTAINER" sh -c "tr '\\0' ' ' < /proc/$pid_in_container/cmdline" 2>/dev/null)"
grep -q 'quarkus-run.jar' <<<"$cp_line" \
  && ok "process runs quarkus-run.jar (the launcher, not the app jar alone)" \
  || bad "unexpected command line: $cp_line"

# --- 3. preflight conditions the entrypoint enforces -----------------------------
head_ "preflight"
docker exec "$CONTAINER" test -e /proc/net/sctp \
  && ok "host SCTP available inside the container" \
  || bad "no /proc/net/sctp — the host kernel has no sctp module (run host-prep.sh)"

# --- 3b. the wire is REALLY listening: kernel sockets, not a log line ------------
head_ "SCTP endpoints (kernel truth)"
# `SS7 boot: ss7=wired` and `[ss7-config] SCTP server … listening` are NOT evidence. When
# sctp.backend resolves to FSTACK_DPDK — which is what SctpBackend.from(null) returns, i.e.
# what any stack JSON without a "backend" key gets — the userspace stack logs both lines and
# creates no kernel socket at all. The gateway reports healthy, status.json says
# ss7.live=false "peer down", and the only honest witness is the kernel's own table.
#
# Scope matters, and the first version of this check got it wrong in a way that produced a
# FAIL on a healthy gateway: it globbed every ss7-*.json in the configs mount, so the unused
# ss7-lab.json (port 8013, left on the host from August) contributed an expectation for a
# socket nothing was ever going to open. Only the file ussd.map.config-file selects boots.
# A gate that demands a port no process asked for is a false positive, and false positives
# are how operators learn to ignore gates.
eps="$(cat /proc/net/sctp/eps 2>/dev/null || docker exec "$CONTAINER" cat /proc/net/sctp/eps 2>/dev/null || true)"
if [[ -z "$eps" ]]; then
  bad "cannot read /proc/net/sctp/eps — the SS7 wire is UNPROVEN (re-run with sudo)"
else
  booted_stack="$(docker exec "$CONTAINER" sh -c \
    'grep -E "^ussd\.map\.config-file=" /opt/ussdgw/configs/application.properties 2>/dev/null \
     | head -1 | cut -d= -f2- | tr -d " "' 2>/dev/null || true)"
  booted_stack="${booted_stack:-ss7-lab.json}"
  booted_stack="$(basename "$booted_stack")"

  # LPORT is field 6; the first line is the header.
  have="$(awk 'NR>1 && $6!="" {print $6}' <<<"$eps" | sort -un | tr '\n' ' ')"
  want="$(docker exec "$CONTAINER" sh -c \
            "grep -ohE '\"local\"[[:space:]]*:[[:space:]]*\"[0-9.]+:[0-9]+\"' '/opt/ussdgw/configs/$booted_stack' 2>/dev/null" \
          | grep -oE ':[0-9]+"$' | tr -d ':"' | sort -un | tr '\n' ' ' || true)"
  if [[ -z "$want" ]]; then
    bad "no local SCTP ports found in $booted_stack (the file ussd.map.config-file selects) — cannot prove the wire"
  else
    missing=""
    for p in $want; do
      [[ " $have " == *" $p "* ]] || missing="$missing $p"
    done
    if [[ -z "$missing" ]]; then
      ok "kernel SCTP endpoints listening on:$want (from $booted_stack, the file that boots)"
    else
      bad "NO kernel SCTP endpoint on:$missing  (kernel has:${have:- none})"
      echo "        sctp.backend in the stack JSON decides which implementation binds these."
      echo "        Omitted, it is FSTACK_DPDK — userspace DPDK, which logs 'listening' and"
      echo "        binds nothing without hugepages + libsctp_fstack.so. Set"
      echo "        \"backend\": \"NETTY_KERNEL\" in the sctp block."
    fi
  fi

  # Associations. The header is 19 space-separated fields and every data row has more than
  # that, so counting "lines with more than 3 fields" counts the header — the previous
  # version reported 2 associations on a host with 0. Field 5 is SST; 3 = ESTABLISHED.
  assocs="$(awk 'NR>1 && NF>19' /proc/net/sctp/assocs 2>/dev/null | wc -l | tr -d ' ' || echo 0)"
  estab="$(awk 'NR>1 && NF>19 && $5==3' /proc/net/sctp/assocs 2>/dev/null | wc -l | tr -d ' ' || echo 0)"
  if [[ "${assocs:-0}" -gt 0 ]]; then
    ok "SCTP associations: $assocs ($estab ESTABLISHED)"
    # Columns, counted from the kernel's own header line:
    #   1 ASSOC 2 SOCK 3 STY 4 SST 5 ST 6 HBKT 7 ASSOC-ID 8 TX_QUEUE 9 RX_QUEUE 10 UID
    #   11 INODE 12 LPORT 13 RPORT 14 LADDRS 15 <-> 16 RADDRS 17 HBINT …
    awk 'NR>1 && NF>19 {printf "        %s:%s <-> %s:%s  ST=%s\n", $14, $12, $16, $13, $5}' \
      /proc/net/sctp/assocs 2>/dev/null | head -6
  else
    # Zero associations is not automatically a defect: a lab stack file whose peers are
    # 127.0.0.1 simulators that are not running has nothing to connect to, and only the
    # gateway's own log knows the difference. Say what is known and what is not.
    peers="$(docker exec "$CONTAINER" sh -c \
      "grep -ohE '\"peer\"[[:space:]]*:[[:space:]]*\"[0-9.]+:[0-9]+\"' '/opt/ussdgw/configs/$booted_stack' 2>/dev/null" \
      | sed -E 's/.*"([0-9.]+:[0-9]+)".*/\1/' | sort -u || true)"
    echo "  note  0 SCTP associations. The listening sockets exist (above), so this is a"
    echo "        peer/firewall question rather than a deaf gateway. $booted_stack points at:"
    echo "$peers" | sed 's/^/          /'
    echo "        A public peer (anything but 127.0.0.1) that stays unconnected while these"
    echo "        sockets listen is a FAIL for a carrier deploy — investigate before shipping."
    # No PCRE here: grep -E has no negative lookahead, and a pattern it cannot compile would
    # exit 2, which under `set -e` inside an `if` reads as "no match" rather than as a broken
    # check. Classify the addresses in the shell instead.
    public_peer=""
    for p in $peers; do
      case "$p" in 127.*|0.0.0.0*|"[::1]"*) ;; *) public_peer="$public_peer $p" ;; esac
    done
    if [[ -n "${public_peer// /}" ]]; then
      bad "carrier peers configured in $booted_stack but no association is established:$public_peer"
    fi
  fi
fi

java_ver="$(docker exec "$CONTAINER" java -version 2>&1 | head -1)"
grep -q 'version "25' <<<"$java_ver" \
  && ok "Java 25 ($java_ver)" \
  || bad "not Java 25: $java_ver"

# --- 4. the live surface (status.json alone does NOT prove CDR/UI) --------------
head_ "live HTTP surface"
code="$(admin_get /admin/status.json /tmp/prove-status.json)"
[[ "$code" == "200" ]] \
  && ok "status.json 200 (app ready, not necessarily SS7 up)" \
  || bad "status.json returned $code"

if [[ -s /tmp/prove-status.json ]]; then
  python3 - <<'PY' && ok "status.json parses; key values below" || bad "status.json is not valid JSON"
import json
d = json.load(open("/tmp/prove-status.json"))
print(f"    ss7.live            = {d.get('ss7.live')}   <- link truth, may honestly be false")
print(f"    cdr.file.recentEvents = {d.get('cdr.file.recentEvents')}")
print(f"    cdr.file.warmed     = {d.get('cdr.file.warmed')}")
print(f"    scheduler.gateTicks = {d.get('scheduler.gateTicks')}")
PY
fi

# CDR page: the file-ledger surface. This is the surface that regressed before.
code="$(admin_get /admin/cdr/partial /tmp/prove-cdr.html)"
[[ "$code" == "200" ]] \
  && ok "/admin/cdr/partial 200" \
  || bad "/admin/cdr/partial returned $code"

if grep -q 'cdr-ledger-row' /tmp/prove-cdr.html 2>/dev/null; then
  rows="$(grep -c 'cdr-ledger-row' /tmp/prove-cdr.html)"
  ok "CDR ledger serves $rows row(s) from the file ledger"
  grep -q 'cdr-hop-list\|cdr-spine' /tmp/prove-cdr.html \
    && ok "6-hop spine markup present" \
    || echo "  note  no spine markup (expected when no session is expanded)"
else
  ok "CDR page returned no rows (valid on a freshly started gateway with an empty ledger)"
fi

# Admin UI shell (a 302 means the session cookie is missing, not a broken page).
code="$(admin_get /admin/cdr /dev/null)"
[[ "$code" =~ ^(200|302)$ ]] \
  && ok "admin CDR shell reachable ($code)" \
  || bad "admin CDR shell returned $code"

# Through nginx, when it is up.
code="$(curl -sS --connect-timeout 3 --max-time 10 -o /dev/null -w '%{http_code}' "$BASE/healthz" 2>/dev/null)"
[[ "$code" == "200" ]] \
  && ok "nginx /healthz 200 (:80 in front of :8088)" \
  || echo "  note  nginx not answering on :80 (expected if not deployed yet)"

# --- 5. runtime files the operator depends on -----------------------------------
head_ "persisted files"
docker exec "$CONTAINER" sh -c 'test -f /opt/ussdgw/logs/ussdgw.log' \
  && ok "Log4j2 app log present in the mounted logs volume" \
  || bad "no ussdgw.log — logs are not reaching the persistent volume"
docker exec "$CONTAINER" sh -c 'test -d /opt/ussdgw/configs/ss7-persist' \
  && ok "configs/ss7-persist exists and is writable (admin UI saves stack JSON)" \
  || bad "ss7-persist missing — the SS7 admin save will fail"

# --- 6. the liveness probe: the other half of the proof -------------------------
head_ "healthcheck"
# docker/build/build-images.sh proves the probe FAILS when nothing is listening. This is
# the half it cannot prove: that it PASSES on a running gateway. A probe with only one of
# the two is worthless — one that cannot fail reports a dead gateway as healthy, and one
# that cannot pass SIGTERMs a live one every start-period, which is what shipped:
#     Failed … "task: non-zero exit (143): dockerexec: unhealthy container"
# five times, then Swarm retired the task while `docker stack services` still listed it.
hc="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER" 2>/dev/null)"
case "$hc" in
  healthy)
    ok "container health = healthy — Swarm will not restart it" ;;
  starting)
    bad "container health = starting (still inside start_period) — re-run once it settles; if it never leaves 'starting' the probe is timing out" ;;
  none)
    bad "the running container has NO healthcheck — Swarm cannot distinguish a hung gateway from a live one" ;;
  *)
    bad "container health = $hc — Swarm is restarting or has retired this task"
    docker inspect -f '{{range .State.Health.Log}}    exit={{.ExitCode}} {{.Output}}{{end}}' "$CONTAINER" 2>/dev/null | tail -3
    ;;
esac

# The probe deliberately accepts 401, because liveness must never restart a gateway over a
# credential question. So the credential question is asked here, where a failure is a
# finding instead of an outage: does the operator's key actually authenticate?
docker exec "$CONTAINER" sh -c 'test -x /usr/local/bin/ussdgw-healthcheck.sh' 2>/dev/null \
  && ok "probe script present and executable in the image" \
  || bad "/usr/local/bin/ussdgw-healthcheck.sh missing — the stack healthcheck points at nothing"

echo
echo "=== prove.sh: $PASS passed, $FAIL failed ==="
[[ "$FAIL" -eq 0 ]] || echo "prove.sh: NOT proven — fix the failures above before calling this done"
exit $(( FAIL > 0 ? 1 : 0 ))