#!/usr/bin/env bash
# One command: source chain → build (with tests) → images → config → secrets → deploy → prove.
#
# WHY THIS EXISTS
# ---------------
# The runbook in docker/README.md is six numbered steps, and every one of them was
# originally a thing a human had to remember to run in the right order with the right
# env vars. Two of today's defects were exactly that: `RUN_TESTS` defaults to 0 so a
# "successful" build had never run a test, and the images were tagged by hand so the
# tag could drift from the SHA in BUILD-INFO.json. A deploy that is a sequence of
# manual commands is a deploy where skipping one is invisible.
#
# This script is the whole chain, fail-closed, idempotent (safe to re-run), and it ends
# with prove.sh against the RUNNING container — because a green build proves nothing
# about what the host runs (AGENTS.md, "Prove the artifact").
#
# Usage — no options, one command:
#
#   ./docker/full-build.sh
#
# Every run, in order:
#   latest code   git pull (ff-only) + update-sources.sh: newest head of every pinned branch,
#                 versions in pom.xml / builder ARGs synced from the trees
#   host          missing sctp module / data dirs → sudo -n host-prep.sh; swarm init + node label
#   sources       missing trees under $BUILD_ROOT/src are cloned at the pinned SHAs
#   build         sctp → jss7 → corsac → micro-jainslee → ussdgw, WITH tests (fails on any red test)
#   images        ussdgw, ussdgw-nginx, ussdgw-postgres, ussdgw-clickhouse (each probed)
#   config        operator configs seeded only when absent (never overwritten)
#   secrets       created when missing, never rotated
#   deploy        docker stack deploy, then prove.sh against the running containers
#
# Environment (all optional; docker/.env is loaded first and these override it):
#   TAG                 image tag                default: git short SHA of the working tree
#   DEPLOY_PROFILE      test | 5k                default: test (5k runs preflight-capacity.sh)
#   DATA_ROOT           /srv/ussdgw              bind mounts + configs + logs
#   BUILD_ROOT          /srv/ussdgw-build        src/ out/ m2/
#   CONFIG_SRC          operator config dir      default: $DATA_ROOT/configs
#   STACK_NAME          ussdgw
#   READY_TIMEOUT       seconds to wait for :8088  default: 300
#   SOURCE_MODE         local | git              default: local (verify SHAs, no network)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
cd "$REPO_ROOT"

# A fail-fast script that dies without saying why is worse than one that does not
# fail fast: `set -e` + a non-matching grep inside `$( )` exits before any message
# (that was a real defect in entrypoint.sh step 4b). So the trap prints the line, and a
# second EXIT trap removes the config snapshot — which holds a 0600 datasource password.
trap 'echo "full-build: FAILED at line $LINENO (exit $?)" >&2' ERR
trap '[[ -n "${SNAP:-}" && -d ${SNAP:-} ]] && rm -rf "$SNAP"; :' EXIT

die()  { echo "full-build: ERROR: $*" >&2; exit 1; }
info() { echo "full-build: $*"; }
warn() { echo "full-build: WARN: $*" >&2; }
step() { echo; echo "=== $* ==="; }

DEPLOY_BLOCKED=""
SNAP=""   # read-only snapshot of the live config; empty until one is staged
# No options (owner decision 2026-11-15: "chỉ cần chạy full-build.sh là xong").
# The MODE_* switches stay as constants so every guarded step below keeps its meaning.
MODE_BUILD=1; MODE_TESTS=1; MODE_DEPLOY=1; MODE_CHECK=0; MODE_FORCE_CONFIG=0; MODE_FETCH=0
ALLOW_DIRTY=1   # the latest-code bump below edits sources.lock / pom.xml; the tag then says -dirty
MODE_LATEST=1
if [[ $# -gt 0 ]]; then
  echo "full-build: WARN: full-build.sh takes no options — ignoring: $*" >&2
fi

# --- latest code: move to the newest code BEFORE the tag is computed --------------------------
# The tag must describe what is built, so this runs first. This repo is fast-forwarded when its
# upstream is reachable (a host without GitHub credentials just keeps its checkout and says so);
# the source chain is moved by update-sources.sh, which re-pins SHAs and syncs every version.
if [[ "$MODE_LATEST" == 1 ]]; then
  if git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then
    if timeout 120 git pull -q --ff-only; then
      info "latest code: $(git rev-parse --abbrev-ref HEAD) fast-forwarded to $(git rev-parse --short HEAD)"
    else
      warn "latest code: cannot pull this repo (no credentials / diverged) — building $(git rev-parse --short HEAD)"
    fi
  else
    warn "latest code: $(git rev-parse --abbrev-ref HEAD) has no upstream — building $(git rev-parse --short HEAD)"
  fi
  SOURCE_ROOT="${SOURCE_ROOT:-${BUILD_ROOT:-/srv/ussdgw-build}/src}" ./docker/build/update-sources.sh \
    || die "latest code: update-sources.sh failed"
  git --no-pager diff --stat -- docker/sources.lock docker/build/fetch-sources-digicom.sh pom.xml docker/build/Dockerfile || true
fi

# --- compute TAG before loading .env, so the tag always drives the deploy --
GIT_SHA="$(git rev-parse --short HEAD)"
DIRTY=""
if [[ -n "$(git status --porcelain 2>/dev/null || true)" ]]; then
  DIRTY="-dirty"
  [[ "$ALLOW_DIRTY" == 1 || "$MODE_BUILD" == 0 ]] \
    || die "the working tree is uncommitted, so a tag of $GIT_SHA would not identify what was built.
     (unreachable: full-build.sh always builds the working tree; the tag then becomes ${GIT_SHA}-dirty)."
fi
TAG="${TAG:-${GIT_SHA}${DIRTY}}"

# --- already built? reuse instead of rebuilding ---------------------------------------
# A clean tag names exactly one commit, and sources.lock (committed) names exactly one source
# chain, so images built from HEAD are what a rebuild would produce. The same holds for images of
# an EARLIER commit when everything changed since then stays outside the images (this script,
# prove.sh, stack files, docs). Reusing them makes the run after a cutover (stop the host
# services, run this again) a deploy, not a 30-minute build with the gateway off the air.
# Any change to code, poms, sources.lock, Dockerfiles or packaged files → full build with tests.
# A -dirty tree never reuses: two different dirty trees share the same tag.
outside_images() {   # every changed path is one that no image is built from
  local f
  while read -r f; do
    [[ -z "$f" ]] && continue
    case "$f" in
      docker/full-build.sh|docker/prove.sh|docker/host-prep.sh|docker/stack.yml|docker/stack.*.yml) ;;
      docker/.env.example|docker/README.md|docker/*.md|docs/*|*.md) ;;
      build/push-dual.sh) ;;
      *) return 1 ;;
    esac
  done
  return 0
}
images_built_from() {   # prints the full ussdgw SHA baked into ussdgw:<tag>, if all 4 images exist
  local t="$1" img
  for img in ussdgw ussdgw-nginx ussdgw-postgres ussdgw-clickhouse; do
    docker image inspect "$img:$t" >/dev/null 2>&1 || return 0
  done
  docker run --rm --entrypoint cat "ussdgw:$t" /opt/ussdgw/BUILD-INFO.json 2>/dev/null \
    | grep -oE '"ussdgw": *"[0-9a-f]{40}"' | grep -oE '[0-9a-f]{40}' | head -1 || true
}
if [[ -z "$DIRTY" ]]; then
  HEAD_FULL="$(git rev-parse HEAD)"
  REUSE_TAG=""; best=999999
  for t in $(docker images ussdgw --format '{{.Tag}}' 2>/dev/null | grep -E '^[0-9a-f]{7,40}$' | sort -u); do
    full="$(git rev-parse --verify -q "${t}^{commit}" 2>/dev/null || true)"
    [[ -n "$full" ]] && git merge-base --is-ancestor "$full" HEAD 2>/dev/null || continue
    [[ "$(images_built_from "$t")" == "$full" ]] || continue          # tag really names its build
    git diff --name-only "$full" HEAD | outside_images || continue      # nothing image-relevant since
    n="$(git rev-list --count "$full..HEAD")"
    (( n < best )) && { best="$n"; REUSE_TAG="$t"; }
  done
  if [[ -n "$REUSE_TAG" ]]; then
    MODE_BUILD=0
    if [[ "$REUSE_TAG" == "$TAG" || "$(git rev-parse "$REUSE_TAG")" == "$HEAD_FULL" ]]; then
      echo "full-build: images :$REUSE_TAG were already built and proven from this commit — reusing them" >&2
    else
      echo "full-build: images :$REUSE_TAG reused — only deploy scripts/docs changed since (no app/image input)" >&2
    fi
    TAG="$REUSE_TAG"
  fi
fi

# --- load .env (site-specific), created from the example on first run -------------
ENV_FILE="$HERE/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  [[ -f "$HERE/.env.example" ]] || die "no $HERE/.env and no .env.example to copy"
  if [[ "$MODE_CHECK" == 1 ]]; then
    warn "no $ENV_FILE — a real deploy would create it from .env.example (defaults used for this dry run)"
  else
    cp "$HERE/.env.example" "$ENV_FILE"
    warn "created $ENV_FILE from .env.example — review PUBLIC_BASE_URL / DATA_ROOT before a real deploy"
  fi
fi
[[ -f "$ENV_FILE" ]] && { set -a; . "$ENV_FILE"; set +a; }
: "${STACK_NAME:=ussdgw}"
DATA_ROOT="${DATA_ROOT:-/srv/ussdgw}"
DEST="${DEST:-/srv/ussdgw/configs}"
CONFIG_SRC="${CONFIG_SRC:-$DATA_ROOT/configs}"
DEPLOY_PROFILE="${DEPLOY_PROFILE:-test}"
READY_TIMEOUT="${READY_TIMEOUT:-300}"
BUILD_ROOT="${BUILD_ROOT:-/srv/ussdgw-build}"
SOURCE_ROOT="${SOURCE_ROOT:-$BUILD_ROOT/src}"
OUT="${OUT:-$BUILD_ROOT/out}"
SOURCE_MODE="${SOURCE_MODE:-local}"

# USSD_ADMIN_API_KEY is not part of this deployment's configuration and must not become
# one by accident. It used to ship in .env as `USSD-admin` lab placeholder; `set -a` above
# exported it, and prove.sh — which reads the mounted secret, the mounted config, and then
# this variable — tried it FIRST. It was rejected with a 401, and prove.sh reported a live
# carrier gateway as broken. A placeholder that reaches the verifier is not a placeholder.
# Keep only a value an operator set deliberately in THIS shell.
if [[ -n "${USSD_ADMIN_API_KEY:-}" && "$USSD_ADMIN_API_KEY" == "ussd-admin" ]]; then
  unset USSD_ADMIN_API_KEY
  info "ignoring USSD_ADMIN_API_KEY=ussd-admin from the environment: that is the lab"
  info "  placeholder, not this deployment's key (the key in force is ussd.admin.api-key in application.properties)"
fi

# Pin the three images to the tag just computed, in the shell AND in docker/.env.
# `docker stack deploy` does NOT read any .env file — it interpolates ${VAR} only from
# the calling shell's environment. So the EXPORT below is what pins this deploy, and
# the .env rewrite only helps a later manual deploy if the operator first loads it:
#     set -a; . docker/.env; set +a; docker stack deploy -c docker/stack.yml … ussdgw
# A bare manual `docker stack deploy` without that falls back to the :latest defaults.
USSDGW_IMAGE="ussdgw:$TAG"; NGINX_IMAGE="ussdgw-nginx:$TAG"; POSTGRES_IMAGE="ussdgw-postgres:$TAG"
CLICKHOUSE_IMAGE="ussdgw-clickhouse:$TAG"
export USSDGW_IMAGE NGINX_IMAGE POSTGRES_IMAGE CLICKHOUSE_IMAGE
if [[ -w "$ENV_FILE" && "$MODE_CHECK" == 0 ]]; then
  tmp="$(mktemp)"
  sed -e "s|^USSDGW_IMAGE=.*|USSDGW_IMAGE=$USSDGW_IMAGE|" \
      -e "s|^NGINX_IMAGE=.*|NGINX_IMAGE=$NGINX_IMAGE|" \
      -e "s|^POSTGRES_IMAGE=.*|POSTGRES_IMAGE=$POSTGRES_IMAGE|" \
      -e "s|^CLICKHOUSE_IMAGE=.*|CLICKHOUSE_IMAGE=$CLICKHOUSE_IMAGE|" "$ENV_FILE" > "$tmp"
  cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
  info "$ENV_FILE pinned to :$TAG"
fi

# --- gateway role: GMLC primary ⇒ USSDGW standby ------------------------------------
# GMLC and USSDGW bind the same :8088 and SCTP 2011/2019 on the shared host network, so at
# most one may run. Owner decision 2026-10-07: GMLC runs as PRIMARY and this stack keeps
# only the shared postgres + nginx up, with the ussdgw service at 0 replicas (STANDBY).
# While gmlc_gmlc wants >0 replicas this deploy forces USSDGW_REPLICAS=0; moving the role
# back is docker/switch-primary.sh ussdgw, never a plain deploy that starts both.
GMLC_SERVICE="${GMLC_SERVICE:-gmlc_gmlc}"
GMLC_WANT="$(docker service inspect "$GMLC_SERVICE" --format '{{.Spec.Mode.Replicated.Replicas}}' 2>/dev/null || true)"
if [[ "${GMLC_WANT:-0}" =~ ^[1-9] ]]; then
  if [[ "${USSDGW_REPLICAS:-}" =~ ^[1-9] ]]; then
    die "USSDGW_REPLICAS=$USSDGW_REPLICAS but $GMLC_SERVICE is PRIMARY (replicas=$GMLC_WANT).
     Both gateways hold :8088 + SCTP 2011/2019 — running both is never allowed.
     Switch roles instead:  ./docker/switch-primary.sh ussdgw"
  fi
  USSDGW_REPLICAS=0
  info "GMLC is PRIMARY ($GMLC_SERVICE replicas=$GMLC_WANT) — this deploy is USSDGW STANDBY:"
  info "  shared postgres + nginx are deployed/updated, the ussdgw service stays at 0 replicas"
fi
USSDGW_REPLICAS="${USSDGW_REPLICAS:-1}"
export USSDGW_REPLICAS
STANDBY=0; [[ "$USSDGW_REPLICAS" == 0 ]] && STANDBY=1

# ==================================================================================
step "0/6 preflight — host, swarm, kernel SCTP, directories"
# ==================================================================================
command -v docker >/dev/null 2>&1 || die "docker not on PATH"
docker info >/dev/null 2>&1 || die "cannot talk to the docker daemon (permission or not running)"
[[ "$(id -u)" != 0 ]] || die "do not run this as root/sudo — run-build.sh builds as \$(id -u) and
     root-owned $OUT cannot be cleaned or rebuilt by the operator afterwards.
     host-prep.sh is the only step that wants sudo."

# Host prep is part of the one command. host-prep.sh is idempotent (modprobe sctp, sysctls,
# data dirs + owners) and is the only step that needs root, so it runs through sudo -n:
# passwordless sudo does it, anything else stops here with the exact command to run once.
need_prep=0
[[ -d /proc/net/sctp ]] || need_prep=1
for d in configs logs data pgdata clickhouse nginx/certs; do
  [[ -d "$DATA_ROOT/$d" ]] || sudo -n test -d "$DATA_ROOT/$d" 2>/dev/null || need_prep=1
done
if [[ "$need_prep" == 1 ]]; then
  info "host not prepared (sctp module or $DATA_ROOT dirs missing) — running host-prep.sh"
  sudo -n env DATA_ROOT="$DATA_ROOT" ./docker/host-prep.sh \
    || die "host-prep.sh needs root and sudo asked for a password. Run once:
     sudo DATA_ROOT=$DATA_ROOT ./docker/host-prep.sh      then re-run ./docker/full-build.sh"
fi

SWARM_STATE="$(docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null || true)"
if [[ "$SWARM_STATE" == "inactive" ]]; then
  # Single-node swarm on the host network; advertise the address of the default route.
  adv="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="src"){print $(i+1); exit}}' || true)"
  info "swarm inactive — docker swarm init --advertise-addr ${adv:-<auto>}"
  docker swarm init ${adv:+--advertise-addr "$adv"} >/dev/null \
    || die "docker swarm init failed — run it by hand with --advertise-addr <node-ip>"
  SWARM_STATE="$(docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null || true)"
fi
if [[ "$SWARM_STATE" == "active" ]] \
   && ! docker node inspect self --format '{{.Spec.Labels.ussdgw}}' 2>/dev/null | grep -q true; then
  info "labelling this node ussdgw=true (the stack pins every service to it)"
  docker node update --label-add ussdgw=true "$(docker node inspect self --format '{{.ID}}')" >/dev/null \
    || die "cannot label the node: docker node update --label-add ussdgw=true \$(hostname)"
fi
if [[ "$SWARM_STATE" != "active" ]]; then
  if [[ "$MODE_CHECK" == 1 ]]; then
    warn "swarm is '$SWARM_STATE' — a real deploy needs: docker swarm init --advertise-addr <node-ip>"
  else
    die "swarm state is '$SWARM_STATE', not 'active'. Run:
     docker swarm init --advertise-addr <node-ip>
     docker node update --label-add ussdgw=true \$(hostname)"
  fi
else
  LABELLED="$(docker node ls --format '{{.Hostname}} {{.Status}} {{.Availability}}' 2>/dev/null | grep -c 'Ready' || true)"
  [[ "${LABELLED:-0}" -ge 1 ]] || die "swarm is active but no node is Ready"
  if ! docker node inspect "$(hostname)" --format '{{.Spec.Labels.ussdgw}}' 2>/dev/null | grep -q true; then
    warn "this node has no ussdgw=true label — the stack pins every service to it, so tasks would stay Pending.
     Fix: docker node update --label-add ussdgw=true $(hostname)"
    [[ "$MODE_CHECK" == 1 ]] || die "missing node label ussdgw=true (see above)"
  fi
  info "swarm active, node $(hostname) Ready + labelled ussdgw=true"
fi

# No sctp module = the gateway boots, logs, and then fails at the first SCTP socket.
[[ -d /proc/net/sctp ]] || die "/proc/net/sctp is absent — the sctp kernel module is not loaded.
     Run: sudo ./docker/host-prep.sh   (a container cannot modprobe)"
for d in configs logs data pgdata clickhouse; do
  if [[ -d "$DATA_ROOT/$d" ]]; then :;
  elif [[ "$MODE_CHECK" == 1 ]]; then
    warn "$DATA_ROOT/$d missing — a real deploy needs: sudo ./docker/host-prep.sh"
  else
    die "$DATA_ROOT/$d missing — run: sudo ./docker/host-prep.sh"
  fi
done
# Same three states as the cert check itself: absent, unreadable, or fine. `[[ -d ]]` is a
# stat, so on the Digicom host (/srv/ussdgw/nginx is drwxr-x--- messagebus) this printed
# "missing" for a directory nginx was serving :443 from.
if [[ ! -d "$DATA_ROOT/nginx/certs" ]]; then
  if stat -c %a "$DATA_ROOT/nginx/certs" >/dev/null 2>&1; then :; else
    if stat -c %a "$DATA_ROOT/nginx" >/dev/null 2>&1; then
      warn "cannot read $DATA_ROOT/nginx/certs as $(id -un) — UNVERIFIED, not missing (nginx serves from a"
      warn "  swarm volume anyway; the cert check below validates the mounted copy)"
    else
      warn "$DATA_ROOT/nginx/certs unreadable as $(id -un) — UNVERIFIED, not missing"
    fi
  fi
fi
info "kernel SCTP present; $DATA_ROOT/{configs,logs,data,pgdata,clickhouse} present"

# Log4j2 writes to $DATA_ROOT/logs (ussd.log.dir). If a log is not appearing there,
# check this before anything else: a file that exists but is never written to is worse
# than a missing one, because "tail -f" on it returns nothing and reads as "no traffic".
# Print what each service mounts, with the HOST PATH a bind resolves to. Printing only the
# volume name (or its Mountpoint) is how "three copies of the config" looked real: swarm
# keeps the volume name in .Source and docker reports
# /var/lib/docker/volumes/<name>/_data as the Mountpoint, but with driver_opts {o: bind,
# type: none} that directory is never created and never read — the data is the declared
# device. On the Digicom host `findmnt` and the inode both said /srv/ussdgw/logs.
for svc in "${STACK_NAME}_ussdgw" "${STACK_NAME}_nginx" "${STACK_NAME}_postgres"; do
  docker service inspect "$svc" >/dev/null 2>&1 || continue
  docker service inspect "$svc" --format \
    '{{range .Spec.TaskTemplate.ContainerSpec.Mounts}}{{if .Source}}{{.Source}}{{if .VolumeOptions.DriverConfig.Options.device}} = {{.VolumeOptions.DriverConfig.Options.device}}{{end}} -> {{.Target}};{{end}}{{end}}' \
    2>/dev/null | tr ';' '\n'
# `|| true`: with no stack deployed yet (first deploy) the loop prints nothing, `grep .`
# exits 1, and under pipefail that killed the whole script at this line.
done | { grep . || true; } | sed 's/^/deploy: mount: /'

# Ports. Only a *first* deploy needs them free: an update of our own stack is expected
# to hold them. Fail-closed, because a host postgres/nginx left running means the
# container's bind mounts collide and restart_policy retires the task silently.
STACK_TASKS="$(docker stack ps "$STACK_NAME" --format '{{.Name}}' 2>/dev/null | head -1 || true)"
# Only a DEPLOY needs the ports: --build-only may run while the host services still serve,
# so the cutover outage is the deploy step alone.
if [[ "$MODE_DEPLOY" == 0 && "$MODE_CHECK" == 0 ]]; then
  info "build-only: port check skipped"
elif [[ -z "$STACK_TASKS" ]]; then
  PORTS_RE=':(80|443|5432|8088)$'
  [[ "$STANDBY" == 1 ]] && PORTS_RE=':(80|443|5432)$'   # :8088 belongs to the GMLC primary
  BUSY="$(ss -lnt 2>/dev/null | awk 'NR>1{print $4}' | grep -oE "$PORTS_RE" | sort -u | tr '\n' ' ' || true)"
  if [[ -n "${BUSY// /}" ]]; then
    # Host services (systemd GMLC / ussdgw, host PostgreSQL, host nginx) still hold the ports.
    # Stopping them is a carrier cutover, never something this script decides. Build and prove
    # everything anyway; stop before the deploy and say exactly what is in the way.
    HOLDERS="$(ss -lntpH 2>/dev/null | awk '{print $4, $6}' | grep -E "${PORTS_RE%\$} " | sort -u || true)"
    SCTP_HOLD="$(ss -lnp --sctp 2>/dev/null | awk 'NR>1{print $4}' | sort -u | tr '\n' ' ' || true)"
    DEPLOY_BLOCKED="ports already held by host services:$BUSY
$(printf '       %s\n' "$HOLDERS")
       SCTP listeners: ${SCTP_HOLD:-none}
     Cutover (operator decision — it takes the running gateway off the air):
       sudo systemctl stop gmlc ussdgw nginx postgresql   # whichever hold the ports above
       ./docker/full-build.sh
     Rollback: docker stack rm $STACK_NAME; sudo systemctl start postgresql nginx gmlc"
    warn "deploy will be SKIPPED — $DEPLOY_BLOCKED"
  else
    info "ports 80/443/5432/8088 free (first deploy)"
  fi
else
  info "stack '$STACK_NAME' already deployed — this run is an update"
fi

if [[ "$DEPLOY_PROFILE" == "5k" ]]; then
  info "DEPLOY_PROFILE=5k — running the capacity gate (it refuses small hosts)"
  ./docker/capacity/preflight-capacity.sh 5k
  OVERLAY="./docker/capacity/stack.capacity-5k.yml"
elif [[ "$DEPLOY_PROFILE" == "test" ]]; then
  OVERLAY="./docker/stack.test.yml"
else
  die "DEPLOY_PROFILE='$DEPLOY_PROFILE' — only 'test' or '5k'"
fi
[[ -f "$OVERLAY" ]] || die "profile overlay $OVERLAY not found"

# --- source chain ------------------------------------------------------------------
for s in sctp jss7 jain-slee corsac-diameter; do
  if [[ ! -d "$SOURCE_ROOT/$s" ]]; then
    # Missing tree → clone it at the pinned SHA (fetch-sources.sh in git mode verifies it).
    MODE_FETCH=1; SOURCE_MODE=git
    continue
  fi
  if [[ ! -d "$SOURCE_ROOT/$s" ]]; then
    if [[ "$MODE_FETCH" == 1 ]]; then continue; fi
    if [[ "$MODE_CHECK" == 1 ]]; then
      warn "$SOURCE_ROOT/$s missing — a real build needs an audited tree there or --fetch-sources"
      continue
    fi
    die "$SOURCE_ROOT/$s missing. Either hand over an audited source tree there, or re-run with
     --fetch-sources (SOURCE_MODE=git clones each repo at the SHA pinned in docker/sources.lock)."
  fi
done
if [[ "$MODE_FETCH" == 1 ]]; then
  step "0b/6 fetch upstream sources at the pinned SHAs (SOURCE_MODE=$SOURCE_MODE)"
  mkdir -p "$SOURCE_ROOT"
  # Always rebuild (cached): fetch-sources.sh and sources.lock are baked into this image, so
  # reusing an existing tag would fetch against stale pins. See docker/build/run-build.sh.
  docker build -f docker/build/Dockerfile -t ussdgw-builder:latest . \
    || die "builder image build failed (docker/build/Dockerfile)"
  # fetch-sources.sh verifies every SHA against sources.lock and is fatal on mismatch,
  # so a moved branch cannot silently change what we build.
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -e SOURCE_MODE="$SOURCE_MODE" -e SRC_DIR=/src ${GH_TOKEN:+-e GH_TOKEN} \
    -v "$SOURCE_ROOT:/src" ussdgw-builder:latest fetch-sources.sh
  # The latest-code step ran before these trees existed, so it could not read versions from
  # them; sync pom.xml / builder ARGs now (no-op when they already agree).
  SOURCE_ROOT="$SOURCE_ROOT" ./docker/build/update-sources.sh >/dev/null \
    || warn "update-sources.sh version sync after fetch failed — build-all.sh will report any mismatch"
  SOURCE_MODE=local
fi
if [[ "$MODE_CHECK" == 1 ]]; then
  info "sources: $(for s in sctp jss7 jain-slee corsac-diameter; do [[ -d "$SOURCE_ROOT/$s" ]] && printf '%s=%s ' "$s" "$(git -C "$SOURCE_ROOT/$s" rev-parse --short HEAD 2>/dev/null || echo '?')"; done)"
fi

# ==================================================================================
if [[ "$MODE_BUILD" == 1 ]]; then
  step "1/6 build from source (RUN_TESTS=$MODE_TESTS) — build-all.sh verifies sources.lock itself"
  # ==================================================================================
  # RUN_TESTS defaults to 0 inside run-build.sh. That default is why "the build
  # passed" once meant "no test ever ran". This orchestrator opts in explicitly and
  # prints the evidence, because the counts live in a log file, not on stdout.
  RUN_TESTS="$MODE_TESTS" SOURCE_MODE="$SOURCE_MODE" SOURCE_ROOT="$SOURCE_ROOT" \
  BUILD_ROOT="$BUILD_ROOT" USSDGW_SRC="$REPO_ROOT" OUT="$OUT" \
    ./docker/build/run-build.sh
  TESTLOG="$OUT/logs/ussdgw-test.log"
  if [[ "$MODE_TESTS" == 1 ]]; then
    [[ -f "$TESTLOG" ]] || die "RUN_TESTS=1 but $TESTLOG does not exist — the tests never ran"
    COUNTS="$(grep -E '^\[INFO\] Tests run:|^\[WARNING\] Tests run:|^Tests run:' "$TESTLOG" | tail -1 || true)"
    info "test evidence: ${COUNTS:-<no summary line found>} ($TESTLOG)"
    # Fail only on the per-module SUMMARY count, never on a per-class line.
    # JUnit5 @Nested containers (e.g. Map2MapAsWireContractExamplesTest, whose
    # tests all live in @Nested classes) legitimately report
    # "Tests run: 0 ... in <OuterClass>" while the module summary says 669.
    # Matching any 'Tests run: 0,' line mistook that container row for "no tests
    # ran" and killed a green 669-test build on the Digicom host.
    TOTAL="$(echo "$COUNTS" | grep -oE 'Tests run: [0-9]+' | grep -oE '[0-9]+' || echo 0)"
    if [[ "${TOTAL:-0}" == 0 ]]; then
      die "$TESTLOG reports 'Tests run: 0' in its summary — that looks green and proves nothing"
    fi
    if echo "$COUNTS" | grep -qE 'Failures: [1-9]|Errors: [1-9]'; then
      die "$TESTLOG summary reports failures/errors: $COUNTS"
    fi
  else
    warn "--skip-tests: NO test evidence for this build. $TESTLOG is from a previous run or absent."
  fi
  [[ -f "$OUT/BUILD-INFO.json" ]] || die "build finished but $OUT/BUILD-INFO.json is missing"
  info "BUILD-INFO: $(grep -oE '"(builtAt|bakedDbKind)": "[^"]*"' "$OUT/BUILD-INFO.json" | tr '\n' ' ')"
fi

# ==================================================================================
if [[ "$MODE_BUILD" == 1 || "$MODE_DEPLOY" == 1 ]]; then
  step "2/6 stage dist + build the three images under tag $TAG"
  # ==================================================================================
  if [[ "$MODE_BUILD" == 1 ]]; then
    # stage-dist.sh is a MIRROR (rm -rf then copy). Hand-copying merges, and a merged
    # lib/ ships two versions of one artifact — a classpath conflict resolved at
    # runtime by whichever class sorts first.
    ./docker/build/stage-dist.sh
    # TAG must be passed: build-images.sh otherwise recomputes it WITHOUT the -dirty suffix, and
    # every later step (provenance, --skip-build, deploy) looks for an image that does not exist.
    TAG="$TAG" ./docker/build/build-images.sh
  else
    for img in ussdgw ussdgw-nginx ussdgw-postgres ussdgw-clickhouse; do
      docker image inspect "$img:$TAG" >/dev/null 2>&1 \
        || die "--skip-build but $img:$TAG does not exist. Build it, or set TAG to a tag that exists
     (present ussdgw tags: $(docker images ussdgw --format '{{.Tag}}' | tr '\n' ' '))"
    done
    info "--skip-build: reusing ussdgw{,-nginx,-postgres}:$TAG"
  fi

  # Provenance: the tag must be the SHA the artifact was actually built from. Relabelling
  # an image is how a host ends up running code no commit describes. BUILD-INFO.json is
  # `"sources": { …, "ussdgw": "<40 hex>" }` — take that key, not the first SHA in the
  # file (sctp/jss7/jain-slee/corsac SHAs are in there too).
  BUILT_SHA="$(docker run --rm --entrypoint cat "ussdgw:$TAG" /opt/ussdgw/BUILD-INFO.json 2>/dev/null \
    | grep -oE '"ussdgw": *"[0-9a-f]{40}"' | grep -oE '[0-9a-f]{40}' | head -1 || true)"
  HEAD_SHA="$(git rev-parse HEAD 2>/dev/null || true)"
  if [[ -z "$BUILT_SHA" ]]; then
    warn "cannot read sources.ussdgw from ussdgw:$TAG — provenance unverified for this run"
  else
    # 1. the tag must NAME the commit the image was built from (no relabelling)
    TAG_SHA="$(git rev-parse --verify -q "${TAG}^{commit}" 2>/dev/null || true)"
    if [[ -n "$TAG_SHA" && "$TAG_SHA" != "$BUILT_SHA" ]]; then
      die "ussdgw:$TAG was built from ${BUILT_SHA:0:12} but the tag names ${TAG_SHA:0:12} — a relabelled image.
     Rebuild, or check out ${BUILT_SHA:0:12} and retag."
    fi
    # 2. HEAD may legitimately have moved on since the build (a docs-only merge does not
    #    change the binary). That is a warning, but only if no application source differs.
    if [[ "$BUILT_SHA" != "$HEAD_SHA" ]]; then
      if git diff --quiet "$BUILT_SHA" HEAD -- src pom.xml build/package-dist.sh docker/build 2>/dev/null; then
        warn "HEAD (${HEAD_SHA:0:12}) is past the built SHA (${BUILT_SHA:0:12}) but no application source differs — docs/config only"
      else
        die "HEAD (${HEAD_SHA:0:12}) has application changes that are NOT in ussdgw:$TAG (built ${BUILT_SHA:0:12}).
     Rebuild (drop --skip-build), or deploy the tag that matches HEAD."
      fi
    else
      info "provenance: ussdgw:$TAG ← ${BUILT_SHA:0:12} == HEAD"
    fi
  fi
fi

# --build-only stops here. --check keeps going: it validates config/secrets read-only and
# proves whatever is running now, which is the point of a dry run.
if [[ "$MODE_DEPLOY" == 0 && "$MODE_CHECK" == 0 ]]; then
  step "done — images built and asserted, nothing deployed (--build-only)"
  exit 0
fi
if [[ -n "${DEPLOY_BLOCKED:-}" ]]; then
  step "built and proven: images :$TAG — NOT deployed"
  echo "full-build: the stack was not deployed: $DEPLOY_BLOCKED" >&2
  exit 3
fi

# ==================================================================================
step "3/6 operator config → $DEST"
# ==================================================================================
# install-config.sh copies ONCE and never overwrites: the admin UI writes SS7 stack JSON
# back into configs/, so "refreshing" would destroy live edits. Its validate() is the gate
# that refuses an H2 db-kind, a non-SCTP channel, a missing sctp.backend and a dead TLS cert.
export CONFIG_SRC DEST

# WHICH directory does the gateway actually read? That is the question this step turns on,
# and on the Digicom host install-config was pointed somewhere else:
#
#   docker/.env   CONFIG_SRC=/home/app/ota-push-services/…/configs
#                 the retired systemd tree, whose ss7-digicom-balance.json still has no
#                 sctp.backend — so the gate reported the FSTACK_DPDK deafness against a
#                 file that does not boot, and said nothing about the one that does.
#                 (fixed in .env to /srv/ussdgw/configs; the guard below keeps it honest)
#
# There was only ever ONE live config: the bind mount works. My "three divergent copies"
# came from reading docker's bookkeeping instead of the filesystem — `findmnt` says
# /var/lib/docker/volumes/ussdgw_ussdgw-logs/_data is /dev/sda1[/srv/ussdgw/logs], same
# inode as /srv/ussdgw/logs.
#
# So read the declared bind device. Mountpoint is only the answer for a genuinely plain
# local volume, and that case now says so out loud instead of quietly pointing at a path
# that holds nothing.
volume_host_path() {  # $1 = service, $2 = container path
  local svc="$1" target="$2" dev opts name
  if docker service inspect "$svc" >/dev/null 2>&1; then
    dev="$(docker service inspect "$svc" \
      --format "{{range .Spec.TaskTemplate.ContainerSpec.Mounts}}{{if eq .Target \"$target\"}}{{.VolumeOptions.DriverConfig.Options.device}}{{end}}{{end}}" \
      2>/dev/null || true)"
    [[ -n "$dev" ]] && { printf '%s' "$dev"; return; }
    name="$(docker service inspect "$svc" \
      --format "{{range .Spec.TaskTemplate.ContainerSpec.Mounts}}{{if eq .Target \"$target\"}}{{.Source}}{{end}}{{end}}" \
      2>/dev/null || true)"
    # No driver_opts => a plain local volume, so Mountpoint IS the path.
    if [[ -n "$name" && "$name" != /* ]]; then
      opts="$(docker volume inspect "$name" --format '{{.Options.device}}' 2>/dev/null || true)"
      if [[ -n "$opts" ]]; then printf '%s' "$opts"; return; fi
      warn "$name has no bind device (Options.device empty) — using its Mountpoint; this is a"
      warn "  plain local volume, so its data lives under /var/lib/docker/volumes, NOT in $DATA_ROOT"
      docker volume inspect "$name" --format '{{.Mountpoint}}' 2>/dev/null || true
      return
    fi
    [[ -n "$name" ]] && { printf '%s' "$name"; return; }
  fi
  # Not deployed yet: the device stack.yml declares for this target.
  case "$target" in
    /opt/ussdgw/configs) printf '%s' "${CONFIG_SRC:-$DATA_ROOT/configs}" ;;
    /etc/nginx/certs)    printf '%s' "${CERT_DIR:-$DATA_ROOT/nginx/certs}" ;;
  esac
}
configs_source() { volume_host_path "${STACK_NAME:-ussdgw}_ussdgw" /opt/ussdgw/configs; }
certs_source()   { volume_host_path "${STACK_NAME:-ussdgw}_nginx" /etc/nginx/certs; }
LIVE_CONFIGS="$(configs_source)"; LIVE_CONFIGS="${LIVE_CONFIGS%/}"

# install-config must READ application.properties, and that file is 0600 owned by the
# container's uid (10001) because it holds the datasource password. So the live config is
# genuinely unreadable to whoever runs the deploy even though its directory is traversable.
# That is a permission boundary, not a path puzzle — read the bytes through the container
# that owns them.
SNAP=""
stage_live_snapshot() {
  local dir="$1" cid dst
  [[ -n "$dir" ]] || return 1
  [[ -r "$dir/application.properties" ]] && return 0        # readable directly: no copy
  cid="$(docker ps -q --filter "name=${STACK_NAME:-ussdgw}_ussdgw" | head -1 || true)"
  if [[ -z "$cid" ]]; then
    # First deploy (or the stack is down): no gateway to read through yet. Read the operator
    # tree with sudo -n — the same passwordless sudo host-prep.sh uses — into a private
    # snapshot (0700, removed on exit). digicom-nb 2026-11-15: without this the first deploy
    # after the GMLC cutover died here and the cutover rolled back.
    if sudo -n test -r "$dir/application.properties" 2>/dev/null; then
      SNAP="$(mktemp -d "${TMPDIR:-/tmp}/ussdgw-cfgsnap.XXXXXX")" || return 1
      chmod 0755 "$SNAP"
      dst="$SNAP/configs"; mkdir -p "$dst"; chmod 0700 "$dst"
      if sudo -n cp -a "$dir/." "$dst/" && sudo -n chown -R "$(id -u):$(id -g)" "$dst" \
         && chmod -R u+rwX "$dst" && [[ -f "$dst/application.properties" ]]; then
        info "staged a private snapshot of $dir via sudo -n (no running gateway to read it through)"
        return 0
      fi
      sudo -n rm -rf "$SNAP" 2>/dev/null || rm -rf "$SNAP"; SNAP=""
    fi
    warn "cannot read $dir/application.properties and no running ${STACK_NAME:-ussdgw}_ussdgw container to read it through"
    return 1
  fi
  SNAP="$(mktemp -d "${TMPDIR:-/tmp}/ussdgw-cfgsnap.XXXXXX")" || return 1
  chmod 0755 "$SNAP"
  dst="$SNAP/configs"; mkdir -p "$dst"; chmod 0755 "$dst"
  # `docker cp` preserves mode but not owner, and application.properties is 0600 by
  # design (it holds a datasource password), so copy each file to a readable mode in the
  # snapshot rather than depending on the uid that happens to own it today.
  local f
  for f in application.properties ss7-lab.json ss7-lab-sim-pull.json ss7-digicom-balance.json; do
    docker cp "$cid:/opt/ussdgw/configs/$f" "$dst/$f" >/dev/null 2>&1 || continue
    chmod u+rw "$dst/$f"
  done
  docker cp "$cid:/opt/ussdgw/configs/ss7-persist" "$dst/ss7-persist" >/dev/null 2>&1 || true
  # Anything else the operator added, that this script has never heard of.
  local n=0 extra
  while IFS= read -r extra; do
    [[ -n "$extra" ]] || continue
    docker cp "$cid:/opt/ussdgw/configs/$extra" "$dst/$extra" >/dev/null 2>&1 && { chmod -R u+rw "$dst/$extra"; n=$((n+1)); }
  done < <(docker exec "$cid" sh -c 'ls -1 /opt/ussdgw/configs' 2>/dev/null || true)
  [[ -f "$dst/application.properties" ]] \
    || { rm -rf "$SNAP"; SNAP=""; warn "snapshot of the live config is still incomplete — see the errors below"; return 1; }
  info "staged a read-only snapshot of the live config from container ${cid:0:12} ($n extra file(s))"
  return 0
}

# DEST is where install-config seeds; LIVE_CONFIGS is where the service reads. With the bind
# mounts stack.yml declares these are the same directory. If they are not, a deploy would
# seed a tree the gateway never opens — say so rather than proceed.
if [[ -n "$LIVE_CONFIGS" && "$LIVE_CONFIGS" != "$DEST" ]]; then
  die "config source mismatch: this stack's gateway reads $LIVE_CONFIGS, but DEST=$DEST.
     Seeding DEST would create a config tree the gateway never opens. Make DEST agree with
     the service, or make the service read DEST — don't run a deploy that quietly disagrees
     with itself."
fi
# CONFIG_SRC that is neither DEST nor where the service reads means .env points at a retired
# install tree. install-config would then validate files this deployment does not use and say
# nothing about the ones it does — which is exactly how a stale path survives unnoticed.
if [[ -n "$LIVE_CONFIGS" && "$CONFIG_SRC" != "$DEST" && "$CONFIG_SRC" != "$LIVE_CONFIGS" ]]; then
  warn "docker/.env sets CONFIG_SRC=$CONFIG_SRC, which is neither DEST ($DEST) nor the path"
  warn "this stack's gateway reads ($LIVE_CONFIGS). The findings below are about files this"
  warn "deployment does not use. Fix docker/.env: CONFIG_SRC=$DEST"
  CONFIG_SRC="$LIVE_CONFIGS"
fi

# The criterion for reading the config through the container is READABILITY, not whether the
# paths happen to differ. application.properties is 0600 owned by the container's uid (10001)
# because it holds the datasource password, so whoever runs the deploy cannot read it — on
# this host that is user app against a 10001-owned file. `grep: Permission denied` is not a
# config problem, and install-config must not be handed a path it cannot open.
if [[ -n "$CONFIG_SRC" && ! -r "$CONFIG_SRC/application.properties" ]]; then
  info "config at $CONFIG_SRC is not readable as $(id -un); reading it through the gateway"
  if stage_live_snapshot "$CONFIG_SRC"; then
    CONFIG_SRC="$SNAP/configs"
  fi
fi

# The certificate edge: same principle as the config. nginx's key is 0640 owned by uid 101
# under /srv/ussdgw/nginx, whose parent is drwxr-x--- messagebus — so the operator cannot
# stat it. Reading it through the nginx container is the honest way to check expiry and
# chain; inferring "no certificate" from a stat the operator was not allowed to make is a
# false negative on a live :443.
LIVE_CERTS="$(certs_source)"; LIVE_CERTS="${LIVE_CERTS%/}"
if ! stat -c %a "$LIVE_CERTS/fullchain.pem" >/dev/null 2>&1; then
  NCID="$(docker ps -q --filter "name=${STACK_NAME:-ussdgw}_nginx" | head -1 || true)"
  [[ -n "$SNAP" ]] || { SNAP="$(mktemp -d "${TMPDIR:-/tmp}/ussdgw-cfgsnap.XXXXXX")"; chmod 0755 "$SNAP"; }
  if [[ -n "$NCID" ]]; then
    mkdir -p "$SNAP/certs"
    for cf in fullchain.pem privkey.pem; do
      docker cp "$NCID:/etc/nginx/certs/$cf" "$SNAP/certs/$cf" >/dev/null 2>&1 || continue
      chmod u+rw "$SNAP/certs/$cf"
    done
    CERT_VIA="the nginx container ${NCID:0:12}"
  elif sudo -n test -r "$LIVE_CERTS/fullchain.pem" 2>/dev/null; then
    # First deploy: no nginx container yet. Same sudo -n snapshot as the config (private, 0700).
    mkdir -p "$SNAP/certs"; chmod 0700 "$SNAP/certs"
    for cf in fullchain.pem privkey.pem; do
      sudo -n cp "$LIVE_CERTS/$cf" "$SNAP/certs/$cf" 2>/dev/null || continue
      sudo -n chown "$(id -u):$(id -g)" "$SNAP/certs/$cf"; chmod u+rw "$SNAP/certs/$cf"
    done
    CERT_VIA="sudo -n (no nginx container yet)"
  fi
  if [[ -f "$SNAP/certs/fullchain.pem" ]]; then
    info "certificate read through ${CERT_VIA} ($LIVE_CERTS is not stat-able as $(id -un))"
    export CERT_DIR="$SNAP/certs"
  else
    warn "cannot inspect $LIVE_CERTS as $(id -un), and no nginx container to read it through —"
    warn "the certificate check below is UNVERIFIED, neither passed nor failed"
  fi
else
  info "certificate: $LIVE_CERTS"
fi

# First deploy seeds, every later deploy validates only. install-config copies ONCE and
# never overwrites: the admin UI writes SS7 stack JSON back into configs/, so a "refresh"
# would destroy live edits.
if [[ "$MODE_CHECK" == 1 || "$CONFIG_SRC" != "$DEST" ]]; then
  # Read-only: either a dry run, or a snapshot — and a snapshot means the gateway is already
  # running from a seeded config, so there is nothing to write. Validated, never written.
  if [[ ! -f "$CONFIG_SRC/application.properties" && "$MODE_CHECK" == 1 ]]; then
    warn "no $CONFIG_SRC/application.properties on this host — nothing to validate here (a real
    deploy validates the handed-over operator tree or the snapshot read through the gateway)"
  else
    ./docker/install-config.sh --check
    [[ "$CONFIG_SRC" == "$DEST" ]] || info "config validated, not written (read through the running gateway)"
  fi
else
  if [[ "$MODE_FORCE_CONFIG" == 1 ]]; then
    warn "--force-config: backing up $DEST before overwriting"
    ./docker/install-config.sh --force
  elif [[ ! -f "$DEST/application.properties" ]]; then
    info "first deploy — seeding $DEST from $CONFIG_SRC"
    ./docker/install-config.sh
  else
    ./docker/install-config.sh --check
  fi
fi

# ==================================================================================
step "4/6 swarm secrets (all three are external: true in stack.yml)"
# ==================================================================================
# A missing external secret is not caught by `docker stack deploy`: the task fails to
# start and restart_policy retires it, while `docker stack services` still lists the
# service. So create-what-is-missing here, and NEVER rotate an existing one — the
# database was initialised with the value it already has.
# ONE source of truth for the admin key: ussd.admin.api-key in the operator's
# application.properties. That is the value the gateway enforces (UssdConfigService), and
# nothing reads the swarm secret except the healthcheck and prove.sh. This step used to
# generate a RANDOM ussdgw_admin_key and print it as "the X-USSD-Admin-Key" — a key the
# gateway had never heard of, so the operator was handed a credential that 401s on every
# call. The secret is now a MIRROR of the property, never an independent value.
config_admin_key() {
  local f
  for f in "${CONFIG_SRC:-}/application.properties" "${DEST:-}/application.properties"; do
    [[ -r "$f" ]] || continue
    sed -n 's/^[[:space:]]*ussd\.admin\.api-key[[:space:]]*=[[:space:]]*//p' "$f" | head -1 | tr -d '\r\n'
    return 0
  done
  return 1
}

make_secret() {
  local name="$1" val
  if docker secret inspect "$name" >/dev/null 2>&1; then
    info "secret $name exists — left untouched (rotating it would break the running database)"
    return 0
  fi
  if [[ "$MODE_CHECK" == 1 ]]; then
    warn "secret $name MISSING — a real deploy would create it"
    return 0
  fi
  case "$name" in
    ussdgw_db_password)       val="$(openssl rand -base64 32)" ;;
    ussdgw_pg_super_password) val="$(openssl rand -base64 24 | tr -d '\n=')" ;;
    # Hex: it lands inside the CDATA of ClickHouse's generated users.d XML and in an HTTP
    # header; no character of it needs escaping in either.
    ussdgw_clickhouse_password) val="$(openssl rand -hex 24)" ;;
    ussdgw_admin_key)
      val="$(config_admin_key || true)"
      [[ -n "$val" ]] || die "cannot create $name: no readable ussd.admin.api-key in
     ${CONFIG_SRC:-<CONFIG_SRC unset>}/application.properties or ${DEST:-<DEST unset>}/application.properties.
     That property IS the admin key the gateway enforces; the secret only mirrors it for the
     healthcheck and prove.sh. Set it in the operator config (openssl rand -hex 24), then re-run."
      ;;
    *) die "make_secret: no generator for '$name'" ;;
  esac
  [[ -n "$val" ]] || die "generated an empty value for secret $name"
  printf '%s' "$val" | docker secret create "$name" - >/dev/null
  case "$name" in
    # Never echo the admin key: it is the operator's own config value, already in their file.
    ussdgw_admin_key) info "secret $name CREATED as a mirror of ussd.admin.api-key (value not printed — it is in application.properties)" ;;
    # The database passwords are deliberately not echoed. They are recoverable from a
    # running task: docker exec <cid> cat /run/secrets/<name>
    *) info "secret $name CREATED (value not printed; read it with:
       docker exec \$(docker ps -q --filter name=${STACK_NAME}_ | head -1) cat /run/secrets/$name)" ;;
  esac
}
openssl rand -hex 1 >/dev/null 2>&1 || die "openssl is required to generate missing secrets"
make_secret ussdgw_db_password
make_secret ussdgw_pg_super_password
make_secret ussdgw_clickhouse_password
make_secret ussdgw_admin_key

# An EXISTING ussdgw_admin_key may predate the mirror rule (random value) or the operator may
# have rotated ussd.admin.api-key since. Compare inside the running gateway — it can read both
# files and no value leaves the container. Drift is a warning, not a failure: the gateway
# enforces the property either way, and the healthcheck accepts 401.
ADMIN_CID="$(docker ps -q --filter "name=${STACK_NAME:-ussdgw}_ussdgw" | head -1 || true)"
if [[ -n "$ADMIN_CID" ]]; then
  if docker exec "$ADMIN_CID" sh -c '
       s="$(tr -d "\r\n" < "${USSD_ADMIN_API_KEY_FILE:-/run/secrets/ussdgw_admin_key}" 2>/dev/null)"
       p="$(sed -n "s/^[[:space:]]*ussd\.admin\.api-key[[:space:]]*=[[:space:]]*//p" /opt/ussdgw/configs/application.properties | head -1 | tr -d "\r\n")"
       [ -n "$s" ] && [ "$s" = "$p" ]' >/dev/null 2>&1; then
    info "ussdgw_admin_key mirrors ussd.admin.api-key in the running gateway ✓"
  else
    warn "ussdgw_admin_key does NOT match ussd.admin.api-key in the running gateway."
    warn "  The PROPERTY is the key in force; the secret only feeds the healthcheck/prove.sh."
    warn "  Re-mirror: docker stack rm ${STACK_NAME:-ussdgw}; docker secret rm ussdgw_admin_key; re-run this script."
  fi
fi

# ==================================================================================
step "5/6 deploy stack '$STACK_NAME' (profile $DEPLOY_PROFILE, images :$TAG)"
# ==================================================================================
# Image vars already pinned + exported before .env load (step 0).
# docker/.env was rewritten too, but `docker stack deploy` never reads it: a later manual
# deploy must `set -a; . docker/.env; set +a` first to land on the same artifact.

if [[ "$MODE_CHECK" == 1 ]]; then
  info "dry run: would run  docker stack deploy -c ./docker/stack.yml -c $OVERLAY $STACK_NAME"
  for img in "$USSDGW_IMAGE" "$NGINX_IMAGE" "$POSTGRES_IMAGE" "$CLICKHOUSE_IMAGE"; do
    docker image inspect "$img" >/dev/null 2>&1 && info "  image present: $img" || warn "  image MISSING: $img"
  done
else
  docker stack deploy --resolve-image always -c ./docker/stack.yml -c "$OVERLAY" "$STACK_NAME"
fi

# ==================================================================================
step "6/6 wait for ready, then prove the RUNNING container"
# ==================================================================================
if [[ "$MODE_CHECK" == 1 ]]; then
  CID="$(docker ps -q --filter "name=${STACK_NAME:-ussdgw}_ussdgw" | head -1 || true)"
  if [[ -n "$CID" ]]; then
    info "currently running: $(docker inspect --format '{{.Config.Image}} ({{.Image}})' "$CID")"
    ./docker/prove.sh "$CID" || die "prove.sh failed on the currently running container"
  else
    warn "no running ${STACK_NAME:-ussdgw}_ussdgw container to prove"
  fi
  step "dry run complete — nothing was changed"
  exit 0
fi

if [[ "$STANDBY" == 1 ]]; then
  # No ussdgw container on purpose. Prove what this stack DOES run in standby: the shared
  # database and the front door the GMLC primary is served through.
  for svc in "${STACK_NAME:-ussdgw}_postgres" "${STACK_NAME:-ussdgw}_nginx"; do
    deadline=$(( $(date +%s) + READY_TIMEOUT ))
    until [[ -n "$(docker ps -q --filter "label=com.docker.swarm.service.name=$svc" | head -1)" ]]; do
      (( $(date +%s) < deadline )) || { docker service ps "$svc" --no-trunc | head -5 >&2; die "$svc has no running task"; }
      sleep 3
    done
    info "$svc running"
  done
  code="$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1/healthz 2>/dev/null || true)"
  [[ "$code" == 200 ]] && info "nginx /healthz 200" || die "nginx /healthz returned '$code'"
  step "deployed: $STACK_NAME :$TAG as USSDGW STANDBY (GMLC primary on :8088)"
  info "switch roles: ./docker/switch-primary.sh ussdgw"
  exit 0
fi

# Swarm reports the SPEC, not the running task: `docker stack services` showed 1/1 with
# the new image while the task was Pending on a host-mode port and the OLD container kept
# serving. So check tasks and image agreement, not just the service table.
deadline=$(( $(date +%s) + READY_TIMEOUT )); CID=""
while [[ -z "$CID" ]]; do
  CID="$(docker ps -q --filter "name=${STACK_NAME:-ussdgw}_ussdgw" | head -1 || true)"
  [[ -n "$CID" ]] && break
  if (( $(date +%s) >= deadline )); then
    echo "--- docker stack ps $STACK_NAME ---" >&2
    docker stack ps "$STACK_NAME" --no-trunc >&2 || true
    die "no ${STACK_NAME:-ussdgw}_ussdgw container after ${READY_TIMEOUT}s (tasks above)"
  fi
  sleep 3
done
info "container $CID"

# Ready == :8088 answers. systemd/swarm "running" is not ready: Flyway and the profile
# tables run before the port binds, which is why start_period is 120s.
# ANY HTTP code counts as answering — including 401. /admin/status.json requires
# X-USSD-Admin-Key, so an unauthenticated curl gets 401; with `curl -f` that used
# to look like "not up" and the loop below spun until READY_TIMEOUT on a healthy
# gateway. 000 (no TCP) is the only "not answering".
deadline=$(( $(date +%s) + READY_TIMEOUT ))
until [[ "$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:8088/admin/status.json" 2>/dev/null)" != 000 ]]; do
  (( $(date +%s) < deadline )) || {
    echo "--- last 60 log lines ---" >&2
    docker logs --tail 60 "$CID" >&2 2>&1 || true
    die "gateway did not answer on :8088/admin/status.json within ${READY_TIMEOUT}s"
  }
  sleep 5
done
info ":8088/admin/status.json answers"

# Every service: desired == running, no Pending/Rejected/Failed task, and the running
# container's image ID equals the spec's image ID.
FAILED=0
while read -r svc want have img; do
  [[ -n "$svc" ]] || continue
  if [[ "$want" != "$have" ]]; then
    warn "$svc replicas $have/$want"; FAILED=1
    docker service ps "$svc" --no-trunc | head -8 >&2 || true
  fi
  spec_id="$(docker service inspect "$svc" --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' 2>/dev/null || true)"
  spec_digest="$(docker image inspect "$spec_id" --format '{{.Id}}' 2>/dev/null || true)"
  run_cid="$(docker ps -q --filter "label=com.docker.swarm.service.name=$svc" | head -1 || true)"
  run_digest=""
  [[ -n "$run_cid" ]] && run_digest="$(docker inspect "$run_cid" --format '{{.Image}}' 2>/dev/null || true)"
  if [[ -n "$spec_digest" && -n "$run_digest" && "$spec_digest" != "$run_digest" ]]; then
    warn "$svc is running a DIFFERENT image than its spec (spec ${spec_digest:7:12} / running ${run_digest:7:12}) — an old task is still serving"
    FAILED=1
  fi
done < <(docker stack services "$STACK_NAME" --format '{{.Name}} {{.Replicas}} {{.Image}}' 2>/dev/null \
         | awk '{split($2,r,"/"); print $1, r[1], r[2], $3}')
[[ "$FAILED" == 0 ]] || die "stack is not fully converged — see the warnings above"
info "all services converged on their spec images"

./docker/prove.sh "$CID" || die "prove.sh reported failures — the deploy is NOT proven"

step "deployed and proven: $STACK_NAME :$TAG"
cat <<EOF
deploy: admin UI      https://<host>/admin/    (and cleartext on :80 by design — see lessons.md)
deploy: status        curl -s 127.0.0.1:8088/admin/status.json -H "X-USSD-Admin-Key: \$(docker exec $CID cat /run/secrets/ussdgw_admin_key)"
deploy: logs          docker exec $CID tail -f /opt/ussdgw/logs/ussdgw.log
deploy: CDR ledger    tail -f $DATA_ROOT/logs/ussd-cdr.log
deploy: rollback      docker service rollback ${STACK_NAME:-ussdgw}_ussdgw   (or docker stack rm $STACK_NAME, then start host postgresql/nginx/gmlc in that order)
EOF
