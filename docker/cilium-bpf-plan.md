# Plan — Cilium / BPF: đưa traffic từ card mạng vào container USSDGW

> Mục tiêu: giảm overhead đường vào từ NIC tới container ussdgw mà **không phá vỡ SS7/SCTP**.
> Trạng thái: **plan, chưa quyết định**. Mọi claim về version Cilium trong file này phải được
> verify lại (§4) trước khi build — web search tại thời điểm viết không khả dụng.

## 1. Hiện trạng (fact từ repo, không phải giả định)

| # | Fact | Nguồn |
|---|------|-------|
| F1 | Stack Swarm chạy cả 3 service trên **host network** (`networks.hostnet` external `host`), single-node, `replicas: 1` + node label | `docker/stack.yml:1-15` |
| F2 | Lý do host network: SCTP **multihoming nhúng host IP thật vào INIT chunk** (`172.16.144.163:2011/2019`); bridge/ingress mesh đưa peer sai source IP; **SS7 cấm load-balance** | `docker/stack.yml:4-8` |
| F3 | JDK SCTP (`jdk.sctp`) cần `libsctp.so.1` trong image (đã có `libsctp1`) **và module `sctp` của host kernel** — container không tự `modprobe` được | `docker/ussdgw/Dockerfile:43`, `docker/host-prep.sh:22-37` |
| F4 | Sysctl `net.core.*mem*`, `net.sctp.sctp_*mem` **không namespaced** — chỉ set được trên host | `docker/host-prep.sh:40-59`, `plan.md §1` |
| F5 | Quy tắc toàn workplace: **SS7 = SCTP ONLY**, kernel SCTP qua `NETTY_KERNEL`; TCP cho M3UA là vi phạm | `AGENTS.md` |
| F6 | Image runtime là jlink JRE (có `jdk.sctp`), user `10001`, HEALTHCHECK = app-ready (không gắn `ss7.live`) | `docker/ussdgw/Dockerfile` |

Hệ quả của F2: **bất kỳ lớp NAT/overlay nào đổi source IP của SCTP INIT đều giết SS7**.
Đây là filter loại bỏ đầu tiên cho mọi phương án.

## 2. Vì sao Cilium CNI gần như chắc chắn KHÔNG phải đáp án

| Vấn đề | Chi tiết |
|--------|----------|
| P1. Cilium không còn hỗ trợ Docker Swarm | Plugin libnetwork của Cilium đã bị bỏ từ lâu; Cilium hiện tại là **Kubernetes-only**. Stack này là Swarm (§R8 `plan.md`) — muốn Cilium CNI phải bỏ Swarm, tức viết lại toàn bộ `docker/stack.yml`, secrets, deploy, `prove.sh`. |
| P2. Datapath eBPF của Cilium không làm SCTP | kube-proxy replacement và LB của Cilium chỉ xử TCP/UDP; SCTP không được datapath parse/LB. (TO-VERIFY §4 trên version cụ thể — nhưng ngay cả khi pass-through được, nó cũng không mang lại gì.) |
| P3. Không được load-balance SS7 | F2 cấm LB. Lợi ích lớn nhất của Cilium (service LB, L7 policy) **bị cấm dùng** cho path SCTP. Còn path HTTP/admin (`:8088`, nginx `:80`) đã chạy tốt trên hostnet qua `127.0.0.1`. |
| P4. Policy enforcement cho SCTP là hạn chế | NetworkPolicy L3/L4 cho SCTP trên eBPF là vùng xám theo version — trong khi hiện tại firewall/host iptables đã làm việc này đơn giản hơn. |

**Kết luận sơ bộ: Cilium CNI = chi phí migration lớn, lợi ích ≈ 0 cho path SCTP, rủi ro cao.**
Không đề xuất đi hướng này trừ khi có yêu cầu Kubernetes hóa toàn stack (quyết định của owner).

## 3. Cái BPF thực sự làm được cho path này (nếu vẫn muốn BPF)

Mục tiêu đúng của BPF ở đây không phải "thay network driver" mà là **steering + filter sớm
ngay tại NIC**, trước khi packet leo lên stack:

| Phương án | Mô tả | Hợp với F1–F6? |
|-----------|-------|----------------|
| **B1. XDP program trên NIC vật lý** | `XDP_DROP` SCTP không từ carrier IP whitelist; `XDP_PASS` phần còn lại lên host stack như cũ. Chạy trước cả netfilter, giảm CPU cho junk/flood. Container giữ nguyên hostnet. | ✅ Có — không đổi source IP, không đụng container |
| **B2. XDP_REDIRECT vào netns** | Chỉ có ý nghĩa nếu bỏ hostnet — mà bỏ hostnet thì vỡ F2. | ❌ Không |
| **B3. ipvlan L2 / macvlan + carrier IP** | Cho container IP L2 riêng thay vì hostnet; hữu ích khi **multi-node** hoặc khi host có nhiều service tranh port. Nhưng SCTP INIT vẫn phải mang đúng IP whitelist — cấu hình phức tạp hơn hostnet, lợi ích chưa rõ trên single-node. | ⚠️ Dự phòng |
| **B4. Giữ nguyên hostnet** | Không BPF. Baseline để so sánh: mọi phương án phải chứng minh hơn baseline qua số đo, không bằng lý thuyết. | ✅ Baseline |

Khuyến nghị: **B4 làm baseline + pilot B1 (XDP drop/filter) trên lab**, đo rồi mới quyết.
B1 là BPF duy nhất không xung đột với F2/F5.

## 4. Bước triển khai (chỉ làm sau khi owner chốt B1)

> TO-VERIFY trước bước 1: kernel node lab có `CONFIG_XDP_SOCKETS`, NIC driver hỗ trợ
> `XDP_DROP`/`XDP_PASS` native (không generic), `libbpf`/`bpftool` có sẵn.
> Ghi kết quả verify vào bảng dưới, không làm tiếp khi chưa đủ.

| Bước | Việc | Prove gate |
|------|------|------------|
| 0 | Baseline: chạy `prove.sh` + đo CPU softirq/pps trên NIC khi có MAP load lab (`ss -ln --sctp`, `/proc/net/sctp/assocs`, `mpstat`) | Có số baseline trong file kết quả |
| 1 | Viết XDP prog tối giản: parse eth/ip/sctp, drop SCTP có source IP ngoài whitelist carrier, pass hết còn lại. Pin vào NIC lab, **không đụng node Digicom** | `bpftool prog show` + counter drop/pass; traffic hợp lệ không đổi |
| 2 | Replay lab MO/NI qua path có XDP: `ss7.live=true`, CDR đủ 6 hop, không `SRI_TIMEOUT` mới | `prove.sh` xanh + CDR tape |
| 3 | Đo lại CPU/pps, so với bước 0. Chỉ merge khi cải thiện rõ rệt và không có regression | Số đo trong commit message |
| 4 | Tài liệu hóa: whitelist carrier IP lấy từ đâu (operator SoT, không hardcode), unload procedure khi XDP lỗi | Runbook trong `docker/README.md` |

**Cấm:**
- Thử B1/B2/B3 trực tiếp trên node Digicom (carrier peer thật, GMLC đang giữ port).
- Dùng XDP_REDIRECT/TC-BPF NAT đổi source IP của SCTP INIT (vỡ F2, mất SS7 mà log vẫn đẹp).
- Thêm libbpf-toolchain vào **runtime image** ussdgw (vi phạm "runtime chỉ có artifact + JRE tối thiểu", `docker/ussdgw/Dockerfile:1-5`).
- Claim "xong" bằng unit test hoặc `status.json` 200 — prove = `prove.sh` + CDR tape + số đo (luật prove-artifact, `AGENTS.md`).

## 5. Rủi ro và chi phí

1. XDP prog lỗi = drop traffic SS7 thật → chỉ lab, luôn có `bpftool net detach` rollback 1 lệnh.
2. Kernel/NIC lab khác Digicom → kết quả lab có thể không mang sang được; ghi rõ model NIC + driver trong kết quả.
3. Công sức: B1 ~ 1–2 ngày (prog + đo). Cilium CNI full (P1–P4): hàng tuần + bỏ Swarm — **không đề xuất**.
4. Không xung đột agent đang chạy: file này độc lập; code B1 (khi làm) nằm ở `docker/xdp/` mới, không sửa file hiện có.

## 6. Quyết định cần owner

- [ ] Chốt **B1 pilot trên lab** hay **B4 giữ nguyên** (không làm gì)?
- [ ] Nếu B1: node lab nào, NIC nào, whitelist carrier IP lấy từ nguồn nào?
- [ ] Cilium CNI chỉ xem xét lại nếu có yêu cầu Kubernetes hóa toàn stack (ngoài scope file này).
