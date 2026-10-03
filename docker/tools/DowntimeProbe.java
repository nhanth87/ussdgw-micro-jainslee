/// DowntimeProbe — measures gateway downtime during `docker stack deploy`.
///
/// Runs ONE virtual thread polling every 200ms:
///   * /proc/net/sctp/assocs  (kernel truth: how many associations are ESTABLISHED)
///   * http://127.0.0.1:8088/admin/status.json  (HTTP code only, no admin key needed)
///   * http://127.0.0.1/healthz                 (nginx :80, must stay 200)
///
/// Output: appends ONE JSON line per observed transition to
/// /srv/ussdgw/logs/downtime-probe.jsonl, and prints DOWNTIME_MS at the end.
///
/// Run (host has only Java 8, so run inside the builder image which has JDK 25):
///   docker run --rm --network host \
///     -v /proc/net/sctp/assocs:/sctp/assocs:ro \
///     -v /srv/ussdgw/logs:/out \
///     -v $PWD/docker/tools:/tools:ro \
///     ussdgw-builder:latest \
///     java /tools/DowntimeProbe.java --assocs /sctp/assocs --out /out/downtime-probe.jsonl
///
/// NUMA: this host has 1 node / 4 cpus, so no binding is meaningful yet.
/// When the host has >= 2 nodes, wrap with: taskset -c 2-3 docker run ...
/// (keep the gateway on 0-1). That is the only NUMA step; nothing else changes.
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class DowntimeProbe {
    public static void main(String[] args) throws Exception {
        String assocsPath = "/proc/net/sctp/assocs";
        String outPath = "/srv/ussdgw/logs/downtime-probe.jsonl";
        long intervalMs = 200;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--assocs" -> assocsPath = args[i + 1];
                case "--out" -> outPath = args[i + 1];
                case "--interval-ms" -> intervalMs = Long.parseLong(args[i + 1]);
            }
        }
        final String aPath = assocsPath;
        final String oPath = outPath;
        final long tick = intervalMs;
        AtomicBoolean stop = new AtomicBoolean(false);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            stop.set(true);
            System.out.println("probe: stopping");
        }));
        // ONE virtual thread. Nothing else.
        Thread v = Thread.ofVirtual().name("downtime-probe").start(() -> {
            int lastEstab = -1;
            int lastHttp = -1;
            int lastHz = -1;
            Long downStart = null;
            try {
                // Prove the probe works on a healthy gateway before any deploy:
                // first successful read prints the baseline.
                boolean baselined = false;
                while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                    long now = System.currentTimeMillis();
                    int estab = countEstablished(aPath);
                    int http = httpCode("http://127.0.0.1:8088/admin/status.json");
                    int hz = httpCode("http://127.0.0.1/healthz");
                    if (!baselined && estab >= 0) {
                        System.out.println("probe: baseline estab=" + estab
                                + " http=" + http + " healthz=" + hz);
                        baselined = true;
                    }
                    if (estab != lastEstab || http != lastHttp || hz != lastHz) {
                        String line = "{\"t\":" + now
                                + ",\"iso\":\"" + Instant.ofEpochMilli(now) + "\""
                                + ",\"estab\":" + estab
                                + ",\"http8088\":" + http
                                + ",\"healthz\":" + hz + "}";
                        try {
                            Files.writeString(Path.of(oPath), line + "\n",
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        } catch (IOException e) {
                            System.out.println("probe: write failed: " + e);
                        }
                        System.out.println("probe: " + line);
                        // Downtime = no ESTABLISHED association. HTTP alone is not
                        // the criterion: status.json can 200 while SS7 is down.
                        if (lastEstab >= 0) {
                            if (estab == 0 && downStart == null) {
                                downStart = now;
                                System.out.println("probe: DOWN_START " + now);
                            } else if (estab > 0 && downStart != null) {
                                System.out.println("probe: UP_AT " + now
                                        + " DOWNTIME_MS=" + (now - downStart));
                                downStart = null;
                            }
                        }
                        lastEstab = estab;
                        lastHttp = http;
                        lastHz = hz;
                    }
                    try {
                        Thread.sleep(tick);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                System.out.println("probe: fatal: " + e);
            }
        });
        v.join();
    }

    // Same rule as docker/prove.sh: skip header (NR>1), need a full row (NF>19),
    // field 5 is SST and 3 = ESTABLISHED.
    static int countEstablished(String path) {
        try {
            List<String> lines = Files.readAllLines(Path.of(path));
            int n = 0;
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).trim().split("\\s+");
                if (f.length > 19 && "3".equals(f[4])) n++;
            }
            return n;
        } catch (IOException e) {
            return -1;
        }
    }

    static int httpCode(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestMethod("GET");
            return c.getResponseCode();
        } catch (Exception e) {
            return -1;
        }
    }
}
