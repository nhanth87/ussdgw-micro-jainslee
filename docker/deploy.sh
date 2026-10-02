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
# Usage:
#   ./docker/deploy.sh                    # everything: build + images + config + secrets + deploy + prove
#   ./docker/deploy.sh --check            # dry run — validate all of it, change nothing
#   ./docker/deploy.sh --skip-build       # reuse the images already built (config/secrets/deploy/prove)
#   ./docker/deploy.sh --skip-tests       # build without `mvn test` — the log then says so out loud
#   ./docker/deploy.sh --build-only       # stop after the images are built and asserted
#   ./docker/deploy.sh --force-config     # re-seed configs (timestamped backup first)
#   ./docker/deploy.sh --fetch-sources    # clone the four upstream trees at the pinned SHAs first
#   ./docker/deploy.sh --allow-dirty      # build from an uncommitted tree (tag gets -dirty)
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
# (that was a real defect in entrypoint.sh step 4b). So the trap prints the line.
trap 'echo "deploy: FAILED at line $LINENO (exit $?)" >&2' ERR

die()  { echo "deploy: ERROR: $*" >&2; exit 1; }
info() { echo "deploy: $*"; }
warn() { echo "deploy: WARN: $*" >&2; }
step() { echo; echo "=== $* ==="; }

MODE_BUILD=1; MODE_TESTS=1; MODE_DEPLOY=1; MODE_CHECK=0; MODE_FORCE_CONFIG=0; MODE_FETCH=0; ALLOW_DIRTY=0
for arg in "$@"; do
  case "$arg" in
    --check)          MODE_CHECK=1; MODE_BUILD=0; MODE_DEPLOY=0 ;;
    --skip-build)     MODE_BUILD=0 ;;
    --skip-tests)     MODE_TESTS=0 ;;
    --build-only)     MODE_DEPLOY=0 ;;
    --force-config)   MODE_FORCE_CONFIG=1 ;;
    --fetch-sources)  MODE_FETCH=1 ;;
    --allow-dirty)    ALLOW_DIRTY=1 ;;
    -h|--help)        sed -n '2,40p' "$0"; exit 0 ;;
    *)                die "unknown argument: $arg (try --help)" ;;
  esac
done

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

STACK_NAME="${STACK_NAME:-ussdgw}"
DATA_ROOT="${DATA_ROOT:-/srv/ussdgw}"
BUILD_ROOT="${BUILD_ROOT:-/srv/ussdgw-build}"
SOURCE_ROOT="${SOURCE_ROOT:-$BUILD_ROOT/src}"
OUT="${OUT:-$BUILD_ROOT/out}"
CONFIG_SRC="${CONFIG_SRC:-$DATA_ROOT/configs}"
DEST="${DEST:-$DATA_ROOT/configs}"
DEPLOY_PROFILE="${DEPLOY_PROFILE:-test}"
SOURCE_MODE="${SOURCE_MODE:-local}"
READY_TIMEOUT="${READY_TIMEOUT:-300}"
GIT_SHA="$(git rev-parse --short HEAD)"
DIRTY=""
if [[ -n "$(git status --porcelain 2>/dev/null || true)" ]]; then
  DIRTY="-dirty"
  [[ "$ALLOW_DIRTY" == 1 || "$MODE_BUILD" == 0 ]] \
    || die "the working tree is uncommitted, so a tag of $GIT_SHA would not identify what was built.
     Commit first, or pass --allow-dirty (the tag then becomes ${GIT_SHA}-dirty)."
fi
TAG="${TAG:-${GIT_SHA}${DIRTY}}"
export TAG

# ==================================================================================
step "0/6 preflight — host, swarm, kernel SCTP, directories"
# ==================================================================================
command -v docker >/dev/null 2>&1 || die "docker not on PATH"
docker info >/dev/null 2>&1 || die "cannot talk to the docker daemon (permission or not running)"
[[ "$(id -u)" != 0 ]] || die "do not run this as root/sudo — run-build.sh builds as \$(id -u) and
     root-owned $OUT cannot be cleaned or rebuilt by the operator afterwards.
     host-prep.sh is the only step that wants sudo."

SWARM_STATE="$(docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null || true)"
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
for d in configs logs data pgdata; do
  [[ -d "$DATA_ROOT/$d" ]] || die "$DATA_ROOT/$d missing — run: sudo ./docker/host-prep.sh"
done
[[ -d "$DATA_ROOT/nginx/certs" ]] || warn "$DATA_ROOT/nginx/certs missing — nginx :443 will not start"
info "kernel SCTP present; $DATA_ROOT/{configs,logs,data,pgdata} present"

# Log4j2 writes to $DATA_ROOT/logs (ussd.log.dir). If a log is not appearing there,
# check this before anything else: a file that exists but is never written to is worse
# than a missing one, because "tail -f" on it returns nothing and reads as "no traffic".
for svc in "${STACK_NAME}_ussdgw" "${STACK_NAME}_nginx" "${STACK_NAME}_postgres"; do
  docker service inspect "$svc" >/dev/null 2>&1 || continue
  docker service inspect "$svc" --format '{{range .Spec.TaskTemplate.ContainerSpec.Mounts}}{{.Type}} {{.Source}} -> {{.Target}};{{end}}' 2>/dev/null | tr ';' '\n'
done | sed 's/^/deploy: mount: /'

# Ports. Only a *first* deploy needs them free: an update of our own stack is expected
# to hold them. Fail-closed, because a host postgres/nginx left running means the
# container's bind mounts collide and restart_policy retires the task silently.
STACK_TASKS="$(docker stack ps "$STACK_NAME" --format '{{.Name}}' 2>/dev/null | head -1 || true)"
if [[ -z "$STACK_TASKS" ]]; then
  BUSY="$(ss -lnt 2>/dev/null | awk 'NR>1{print $4}' | grep -oE ':(80|443|5432|8088)$' | sort -u | tr '\n' ' ' || true)"
  if [[ -n "${BUSY// /}" ]]; then
    die "no $STACK_NAME stack is deployed but these ports are already held:$BUSY
     A host service is still running. Ordered rollback/start:
       docker stack rm $STACK_NAME; sudo systemctl start postgresql; sudo systemctl start nginx"
  fi
  info "ports 80/443/5432/8088 free (first deploy)"
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
    if [[ "$MODE_FETCH" == 1 ]]; then continue; fi
    die "$SOURCE_ROOT/$s missing. Either hand over an audited source tree there, or re-run with
     --fetch-sources (SOURCE_MODE=git clones each repo at the SHA pinned in docker/sources.lock)."
  fi
done
if [[ "$MODE_FETCH" == 1 ]]; then
  step "0b/6 fetch upstream sources at the pinned SHAs (SOURCE_MODE=$SOURCE_MODE)"
  mkdir -p "$SOURCE_ROOT"
  docker image inspect ussdgw-builder:latest >/dev/null 2>&1 \
    || docker build -f docker/build/Dockerfile -t ussdgw-builder:latest .
  # fetch-sources.sh verifies every SHA against sources.lock and is fatal on mismatch,
  # so a moved branch cannot silently change what we build.
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -e SOURCE_MODE="$SOURCE_MODE" -e SRC_DIR=/src ${GH_TOKEN:+-e GH_TOKEN} \
    -v "$SOURCE_ROOT:/src" ussdgw-builder:latest fetch-sources.sh
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
    if grep -qE 'Tests run: 0,' "$TESTLOG"; then
      die "$TESTLOG reports 'Tests run: 0' — that looks green and proves nothing"
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
    ./docker/build/build-images.sh
  else
    for img in ussdgw ussdgw-nginx ussdgw-postgres; do
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

# ==================================================================================
step "3/6 operator config → $DEST"
# ==================================================================================
# install-config.sh copies ONCE and never overwrites: the admin UI writes SS7 stack JSON
# back into configs/, so "refreshing" would destroy live edits. Its validate() is the gate
# that refuses an H2 db-kind, a non-SCTP channel, a missing sctp.backend and a dead TLS cert.
export CONFIG_SRC DEST

# WHICH directory does the gateway actually read? This is the question the whole
# config step turns on, and on the Digicom host the answer was NOT the one install-config
# was pointed at — two separate copies of the config existed:
#
#   docker/.env          CONFIG_SRC=/home/app/ota-push-services/…/configs   (the old
#                        systemd tree, still carrying an ss7-digicom-balance.json with no
#                        sctp.backend — the FSTACK_DPDK deafness)
#   install-config DEST  /srv/ussdgw/configs                                (edited to
#                        NETTY_KERNEL, byte-identical content)
#   the running service  mounts the swarm VOLUME ussdgw_ussdgw-configs
#
# The gate validated the first two and never asked about the third. A validation that
# inspects a directory nothing reads is the "config present ≠ config read" lesson one
# level up: here it would have blocked a good deploy and passed a broken one.
configs_source() {
  if docker service inspect "${STACK_NAME}_ussdgw" >/dev/null 2>&1; then
    docker service inspect "${STACK_NAME}_ussdgw" \
      --format '{{range .Spec.TaskTemplate.ContainerSpec.Mounts}}{{if eq .Target "/opt/ussdgw/configs"}}{{.Source}}{{end}}{{end}}' 2>/dev/null || true
  else
    # Not deployed yet: the volume name stack.yml will create, which for a `driver: local`
    # volume without an explicit `device` lives under /var/lib/docker/volumes.
    printf '%s' "/var/lib/docker/volumes/${STACK_NAME}_ussdgw-configs/_data"
  fi
}
LIVE_CONFIGS="$(configs_source)"; LIVE_CONFIGS="${LIVE_CONFIGS%/}"
if [[ -n "$LIVE_CONFIGS" && "$LIVE_CONFIGS" != "$DEST" ]]; then
  warn "the running gateway mounts configs from $LIVE_CONFIGS, but install-config validates/seeds $DEST"
  warn "-> these are different files. install-config will report on a tree the service never reads."
  if [[ "$MODE_CHECK" == 1 || "$MODE_DEPLOY" == 0 ]]; then
    info "validating the MOUNTED copy instead: CONFIG_SRC=$LIVE_CONFIGS"
    CONFIG_SRC="$LIVE_CONFIGS"
  else
    die "config source mismatch: DEST=$DEST but ${STACK_NAME}_ussdgw mounts $LIVE_CONFIGS.
     Seeding $DEST would produce a config tree the gateway never reads — the silent-failure
     shape this script exists to prevent. Pick one and state it:
       a) keep the current topology and validate/seed the mounted path:
            CONFIG_SRC=$LIVE_CONFIGS ./docker/deploy.sh
       b) move the stack onto the bind mounts stack.yml declares (/srv/ussdgw/...), which
          means copying the live volume contents to /srv/ussdgw/{configs,logs,data,pgdata}
          first — data-affecting, so do it deliberately, not from this script."
  fi
fi
# CONFIG_SRC outside DATA_ROOT and outside the live mount means .env still points at a
# retired install tree; the gate would pass on files the deploy does not use.
if [[ "$CONFIG_SRC" != "$DEST" && "$CONFIG_SRC" != "$LIVE_CONFIGS" ]]; then
  warn "CONFIG_SRC=$CONFIG_SRC is neither DEST ($DEST) nor the mounted $LIVE_CONFIGS —"
  warn "that path is not what this stack reads; expect the findings below to be about the wrong files."
fi

if [[ "$CONFIG_SRC" == "$DEST" ]]; then
  info "CONFIG_SRC == DEST ($DEST) — validating the live config in place, not re-seeding"
  ./docker/install-config.sh --check
else
  ./docker/install-config.sh --check
  if [[ "$MODE_FORCE_CONFIG" == 1 ]]; then
    warn "--force-config: backing up and overwriting $DEST"
    ./docker/install-config.sh --force
  elif [[ ! -f "$DEST/application.properties" ]]; then
    ./docker/install-config.sh
  else
    info "$DEST already populated — left untouched (use --force-config to re-seed with a backup)"
  fi
fi

# ==================================================================================
step "4/6 swarm secrets (all three are external: true in stack.yml)"
# ==================================================================================
# A missing external secret is not caught by `docker stack deploy`: the task fails to
# start and restart_policy retires it, while `docker stack services` still lists the
# service. So create-what-is-missing here, and NEVER rotate an existing one — the
# database was initialised with the value it already has.
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
    ussdgw_admin_key)         val="ussd-admin-$(openssl rand -hex 12)" ;;
    *) die "make_secret: no generator for '$name'" ;;
  esac
  [[ -n "$val" ]] || die "generated an empty value for secret $name"
  printf '%s' "$val" | docker secret create "$name" - >/dev/null
  case "$name" in
    # The admin key is the one an operator needs to see: it is the X-USSD-Admin-Key.
    ussdgw_admin_key) info "secret $name CREATED — value: $val" ;;
    # The database passwords are deliberately not echoed. They are recoverable from a
    # running task: docker exec <cid> cat /run/secrets/<name>
    *) info "secret $name CREATED (value not printed; read it with:
       docker exec \$(docker ps -q --filter name=${STACK_NAME}_ | head -1) cat /run/secrets/$name)" ;;
  esac
}
openssl rand -hex 1 >/dev/null 2>&1 || die "openssl is required to generate missing secrets"
make_secret ussdgw_db_password
make_secret ussdgw_pg_super_password
make_secret ussdgw_admin_key

# ==================================================================================
step "5/6 deploy stack '$STACK_NAME' (profile $DEPLOY_PROFILE, images :$TAG)"
# ==================================================================================
# Pin the three images to the tag just built, in the shell AND in .env, so the stack
# cannot resolve `latest` to something older. Compose interpolation prefers the
# exported environment over the .env file; rewriting .env keeps a later manual
# `docker stack deploy` on the same artifact.
USSDGW_IMAGE="ussdgw:$TAG"; NGINX_IMAGE="ussdgw-nginx:$TAG"; POSTGRES_IMAGE="ussdgw-postgres:$TAG"
export USSDGW_IMAGE NGINX_IMAGE POSTGRES_IMAGE
if [[ -w "$ENV_FILE" && "$MODE_CHECK" == 0 ]]; then
  tmp="$(mktemp)"
  sed -e "s|^USSDGW_IMAGE=.*|USSDGW_IMAGE=$USSDGW_IMAGE|" \
      -e "s|^NGINX_IMAGE=.*|NGINX_IMAGE=$NGINX_IMAGE|" \
      -e "s|^POSTGRES_IMAGE=.*|POSTGRES_IMAGE=$POSTGRES_IMAGE|" "$ENV_FILE" > "$tmp"
  cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
  info "$ENV_FILE pinned to :$TAG"
fi

if [[ "$MODE_CHECK" == 1 ]]; then
  info "dry run: would run  docker stack deploy -c ./docker/stack.yml -c $OVERLAY $STACK_NAME"
  for img in "$USSDGW_IMAGE" "$NGINX_IMAGE" "$POSTGRES_IMAGE"; do
    docker image inspect "$img" >/dev/null 2>&1 && info "  image present: $img" || warn "  image MISSING: $img"
  done
else
  docker stack deploy -c ./docker/stack.yml -c "$OVERLAY" "$STACK_NAME"
fi

# ==================================================================================
step "6/6 wait for ready, then prove the RUNNING container"
# ==================================================================================
if [[ "$MODE_CHECK" == 1 ]]; then
  CID="$(docker ps -q --filter "name=${STACK_NAME}_ussdgw" | head -1 || true)"
  if [[ -n "$CID" ]]; then
    info "currently running: $(docker inspect --format '{{.Config.Image}} ({{.Image}})' "$CID")"
    ./docker/prove.sh "$CID" || die "prove.sh failed on the currently running container"
  else
    warn "no running ${STACK_NAME}_ussdgw container to prove"
  fi
  step "dry run complete — nothing was changed"
  exit 0
fi

# Swarm reports the SPEC, not the running task: `docker stack services` showed 1/1 with
# the new image while the task was Pending on a host-mode port and the OLD container kept
# serving. So check tasks and image agreement, not just the service table.
deadline=$(( $(date +%s) + READY_TIMEOUT )); CID=""
while [[ -z "$CID" ]]; do
  CID="$(docker ps -q --filter "name=${STACK_NAME}_ussdgw" | head -1 || true)"
  [[ -n "$CID" ]] && break
  if (( $(date +%s) >= deadline )); then
    echo "--- docker stack ps $STACK_NAME ---" >&2
    docker stack ps "$STACK_NAME" --no-trunc >&2 || true
    die "no ${STACK_NAME}_ussdgw container after ${READY_TIMEOUT}s (tasks above)"
  fi
  sleep 3
done
info "container $CID"

# Ready == :8088 answers. systemd/swarm "running" is not ready: Flyway and the profile
# tables run before the port binds, which is why start_period is 120s.
deadline=$(( $(date +%s) + READY_TIMEOUT ))
until curl -fsS -o /dev/null "http://127.0.0.1:8088/admin/status.json" 2>/dev/null; do
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
deploy: rollback      docker service rollback ${STACK_NAME}_ussdgw   (or docker stack rm $STACK_NAME, then start host postgresql/nginx/gmlc in that order)
EOF
