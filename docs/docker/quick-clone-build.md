# Digicom-ET USSDGW — clone & build (from source, Docker)

Tất cả chạy **không sudo** (trừ `host-prep.sh`). Host: `digicom-nb` = `app@100.110.205.176`.

## 1. Một lần duy nhất (cần sudo)

```bash
git clone git@github.com:digicom-et/ussdgw-micro-jainslee.git ~/ussd-docker/ussdgw-micro-jainslee
cd ~/ussd-docker/ussdgw-micro-jainslee
sudo ./docker/host-prep.sh          # modprobe sctp, sysctl, /srv/ussdgw/*, NTP
```

## 2. Deploy (mỗi lần đổi code)

```bash
ssh digicom-nb
cd ~/ussd-docker/ussdgw-micro-jainslee
./docker/full-build.sh --check          # kiểm tra, KHÔNG thay đổi gì
./docker/full-build.sh                  # build + ảnh + secrets + deploy + prove.sh
```

 `full-build.sh` chạy hết chuỗi: build từ source (có `RUN_TESTS=1`) → 3 image → config →
secrets → `docker stack deploy` → chờ `:8088` → `prove.sh`.

Cần ~40–60′ cho lần đầu (build `sctp` + `jss7` + `jain-slee` + `corsac-diameter`),
lần sau nhanh hơn (cache `/srv/ussdgw-build/m2`).

## 3. Nguồn upstream (`/srv/ussdgw-build/src`)

Deploy cần 4 cây ở đúng SHA trong `docker/sources.lock`. Nếu host đã có sẵn thì
`full-build.sh` tự verify và bỏ qua. Nếu chưa:

```bash
./docker/full-build.sh --check --fetch-sources    # clone 4 repo + checkout SHA đã ghim
```

## 4. Biến thể

```bash
./docker/full-build.sh --skip-build      # dùng lại image của TAG hiện tại
./docker/full-build.sh --build-only      # chỉ build + assert, không deploy
./docker/full-build.sh --skip-tests      # nhanh hơn (và in ra là không có test evidence)
TAG=<sha> ./docker/full-build.sh --skip-build   # deploy image cũ
docker service rollback ussdgw_ussdgw      # lui 1 bước
```

## 5. Kiểm tra sau khi deploy

```bash
./docker/prove.sh                                        # 25 check
docker stack services ussdgw && docker service ps ussdgw_nginx --no-trunc
cat /proc/net/sctp/eps /proc/net/sctp/assocs             # SS7: ST=3 là ESTABLISHED
CID=$(docker ps -q --filter name=ussdgw_ussdgw | head -1)
curl -s 127.0.0.1:8088/admin/status.json \
  -H "X-USSD-Admin-Key: $(docker exec $CID cat /run/secrets/ussdgw_admin_key)" \
  | python3 -m json.tool | grep -E 'ss7.live|gateTicks'
```

`docker stack services` in ra **spec**, không phải task đang chạy — phải kèm
`docker service ps` để thấy task `Pending`.

## 6. Log

Log nằm ở `/srv/ussdgw/logs` (stack.yml bind-mount vào đó). Không dùng
`/var/lib/docker/volumes/ussdgw_ussdgw-logs/_data`: với `o: bind` thư mục đó không
được tạo và không được đọc — nó chỉ là sổ sách của docker, không phải chỗ chứa data.

```bash
sudo tail -f /srv/ussdgw/logs/ussdgw.log        # Log4j2 app log
sudo tail -f /srv/ussdgw/logs/ussdgw-slee.log   # SleeEventTrace
sudo tail -f /srv/ussdgw/logs/ussd-cdr.log      # CDR ledger (SoT của /admin/cdr)
tail -5 /srv/ussdgw-build/out/logs/ussdgw-test.log   # test evidence
```

`sudo` vì các file thuộc uid 10001 (user của container).

## 7. Rollback

```bash
docker service rollback ussdgw_ussdgw
# hoặc gỡ hẳn (đúng thứ tự, container đang giữ 5432/80/443):
docker stack rm ussdgw
sudo systemctl start postgresql && sudo systemctl start nginx && sudo systemctl start gmlc
```

> Chi tiết từng bước + lý do: [`docker/README.md`](docker/README.md). Bài học:
> [`docs/agents/lessons.md`](docs/agents/lessons.md).