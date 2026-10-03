# Digicom-ET USSDGW — clone & build (from source, Docker)

Everything runs **without sudo** (except `host-prep.sh`). Host: `digicom-nb` = `app@100.110.205.176`.

## 1. One time only (needs sudo)

```bash
git clone git@github.com:digicom-et/ussdgw-micro-jainslee.git ~/ussd-docker/ussdgw-micro-jainslee
cd ~/ussd-docker/ussdgw-micro-jainslee
sudo ./docker/host-prep.sh          # modprobe sctp, sysctl, /srv/ussdgw/*, NTP
```

## 2. Back up before every deploy (~2 min)

```bash
ts=$(date +%Y%m%d-%H%M%S); d=/srv/ussdgw-backup/$ts; sudo mkdir -p $d; sudo chown app:app $d
docker exec $(docker ps -q -f name=ussdgw_postgres) pg_dumpall -U ussdgw_admin > $d/host-ussdgw.dump
sudo tar czf $d/srv-configs.tgz -C /srv ussdgw/configs
docker stack services ussdgw > $d/stack-before.txt
cp docker/.env $d/deploy.env.bak
```

The DB superuser is `ussdgw_admin`, not `postgres` (that role does not exist). Never `docker image prune` before a deploy — the previous tag is the rollback.

## 3. Deploy (every code change)

```bash
ssh digicom-nb
cd ~/ussd-docker/ussdgw-micro-jainslee
git pull --ff-only                   # tree must be clean after: git status --short
./docker/full-build.sh --check       # dry run, changes nothing
./docker/full-build.sh               # build + images + secrets + deploy + prove.sh
```

`full-build.sh` runs the whole chain: build from source (`RUN_TESTS=1`) → 3 images under **one tag = HEAD SHA** → config gate → secrets (create-if-missing, never rotate) → `docker stack deploy` → wait for `:8088` → spec-image-ID == running-image-ID → `prove.sh`.

First run takes ~40–60 min (`sctp` + `jss7` + `jain-slee` + `corsac-diameter`); later runs reuse `/srv/ussdgw-build/m2`. Expect **~8–17 s of M3UA downtime** at `stack deploy` (`stop-first`; the gateway boots to M3UA in ~8 s). Measure it, don't guess — see §7.

The test gate reads the **module summary** (`Tests run: 669`), never a per-class line: JUnit5 `@Nested` outer containers legitimately report `Tests run: 0`. The ready-wait accepts **any HTTP code** (401 included — `status.json` needs the admin key); only `000` (no TCP) waits.

## 4. Upstream sources (`/srv/ussdgw-build/src`)

The deploy needs 4 trees at the exact SHAs in `docker/sources.lock`. If already present, `full-build.sh` verifies and skips. If not:

```bash
./docker/full-build.sh --check --fetch-sources    # clone 4 repos + checkout pinned SHAs
```

## 5. Variants

```bash
./docker/full-build.sh --skip-build      # reuse images already built for this TAG
./docker/full-build.sh --build-only      # build + images + asserts, deploy nothing
./docker/full-build.sh --skip-tests      # faster, and it says out loud there is no test evidence
TAG=<sha> ./docker/full-build.sh --skip-build   # deploy a previous image
```

## 6. Verify after deploy

```bash
./docker/prove.sh                                        # 26 checks, exit 0
docker stack services ussdgw && docker service ps ussdgw_nginx --no-trunc
cat /proc/net/sctp/eps /proc/net/sctp/assocs             # SS7: ST=3 is ESTABLISHED
CID=$(docker ps -q --filter name=ussdgw_ussdgw | head -1)
curl -s 127.0.0.1:8088/admin/status.json \
  -H "X-USSD-Admin-Key: $(docker exec $CID cat /run/secrets/ussdgw_admin_key)" \
  | python3 -m json.tool | grep -E 'ss7.live|gateTicks'
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://127.0.0.1/admin/routing
# 301 https://... (:80 serves only /healthz, /metrics and ACME; everything else redirects)
curl -s -w ' %{http_code}\n' http://127.0.0.1/healthz    # ok 200
docker exec $CID cat /opt/ussdgw/.baked-db-kind          # postgresql
```

`docker stack services` prints the **spec**, not the running task — always pair it with `docker service ps` to see `Pending` tasks. Admin traffic must be `HTTP/2.0` (via `:443`); the AS posts straight to `127.0.0.1:8088`, never through nginx.

## 7. Measure the downtime (DowntimeProbe)

One virtual thread, 200 ms polls of `/proc/net/sctp/assocs` + `:8088` + `:80/healthz`, appends transitions to `/srv/ussdgw/logs/downtime-probe.jsonl`. Start it **before** `stack deploy` so it baselines on a healthy gateway:

```bash
docker run -d --name downtime-probe --network host --entrypoint java \
  -v /proc/net/sctp/assocs:/sctp/assocs:ro \
  -v /srv/ussdgw/logs:/out \
  -v $PWD/docker/tools:/tools:ro \
  ussdgw-builder:latest /tools/DowntimeProbe.java --assocs /sctp/assocs --out /out/downtime-probe.jsonl
docker logs downtime-probe | grep DOWNTIME_MS
```

## 8. Logs

Logs live in `/srv/ussdgw/logs` (bind-mounted by `stack.yml`). Never use `/var/lib/docker/volumes/ussdgw_ussdgw-logs/_data`: with `o: bind` that directory is docker bookkeeping, never created nor read.

```bash
sudo tail -f /srv/ussdgw/logs/ussdgw.log        # Log4j2 app log
sudo tail -f /srv/ussdgw/logs/ussdgw-slee.log   # SleeEventTrace
sudo tail -f /srv/ussdgw/logs/ussd-cdr.log      # CDR ledger (SoT of /admin/cdr)
tail -5 /srv/ussdgw-build/out/logs/ussdgw-test.log   # test evidence
```

`sudo` because the files belong to uid 10001 (the container user). nginx `access.log` is a symlink to stdout — read it via `docker logs`, never via `docker exec cat`.

## 9. Rollback (≤3 min)

```bash
sed -i 's/:<new-tag>/:<previous-tag>/' docker/.env
docker stack deploy -c docker/stack.yml --resolve-image always ussdgw
until curl -sf -H "X-USSD-Admin-Key: $(docker exec $(docker ps -q -f name=ussdgw_ussdgw) cat /run/secrets/ussdgw_admin_key)" \
      http://127.0.0.1:8088/admin/status.json >/dev/null; do sleep 3; done
./docker/prove.sh
```

> Step-by-step + rationale: [`../../docker/README.md`](../../docker/README.md). Lessons: [`../agents/lessons.md`](../agents/lessons.md).
