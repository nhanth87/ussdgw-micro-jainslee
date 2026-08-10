package et.digicom.ussdsim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lab load driver for Digicom-ET USSDGW Brook-like MO path.
 *
 * <p><b>TPS = MSISDN sessions / second</b> (MO {@code processUnstructuredSS-Request} starts with a
 * unique subscriber), <em>not</em> TCAP message count. One session ≈ MO + digit CONTINUE traffic.
 *
 * <ul>
 *   <li>{@code --engine mapload} (default when {@code --tps > 2}): jSS7 map/load Client — concurrent
 *       dialogs, rate-limited MO starts.
 *   <li>{@code --engine jmx}: sequential JMX dial/dt (ceiling = 1 concurrent dialog) — functional smoke.
 * </ul>
 *
 * <pre>
 *   java -jar ussd-load.jar --scenario brook
 *   java -jar ussd-load.jar --scenario brook --tps 1 --duration 30
 * </pre>
 *
 * <p>Oracle: {@code tools/ss7-simulator/BROOK-SCENARIO.md}. Never 100 TPS on Digicom without
 * approval. AS = real BPLUS, never as-node.
 */
public final class UssdLoadDriver {

    private String engine = "auto";
    private double tps = 1;
    private long durationSec = 30;
    private String shortCode = "*804#";
    private String digits = "1";
    private boolean msisdnRandom = true;
    private String msisdnPrefix = "25191";
    private String fixedMsisdn = "";
    private String jmxUrl = "service:jmx:rmi:///jndi/rmi://127.0.0.1:9999/server";
    private String hostObjectName = "SS7_Simulator_main:type=TesterHost";
    private String ussdObjectName = "SS7_Simulator_main:type=TestUssdClientMan";
    private long waitMs = 25_000;
    private String statusUrl = "http://127.0.0.1:8088/admin/status.json";
    private Path mapLoadJson =
            Path.of("tools/ss7-simulator/ss7-ussd-client-ussdgw-pull.json");
    private Path mapLoadJar;
    private Path mapLoadClasspathFile;
    private String javaBin = "java";
    /** map/load OPC (sim). Digicom L3-LAB sim = 2. */
    private int origPc = 2;
    /** map/load DPC (GW). Laptop pull-lab = 1; Digicom L3-LAB GW = 1470. */
    private int destPc = 1;
    /** Locked Brook Digicom prove: *804# + digit 1 after MAP2MAP→BPLUS. */
    private boolean brookScenario = false;

    private final LongAdder moStarted = new LongAdder();
    private final LongAdder moOk = new LongAdder();
    private final LongAdder moFail = new LongAdder();
    private final LongAdder digitOk = new LongAdder();
    private final AtomicLong sessionMsSum = new AtomicLong();
    private final AtomicInteger sessionMsSamples = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        UssdLoadDriver d = new UssdLoadDriver();
        d.parse(args);
        int code = d.run();
        System.exit(code);
    }

    private void parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--scenario" -> {
                    String sc = require(args, ++i, a).toLowerCase(Locale.ROOT);
                    if ("brook".equals(sc) || "bplus".equals(sc) || "804".equals(sc)) {
                        applyBrookScenario();
                    } else {
                        throw new IllegalArgumentException("Unknown --scenario " + sc + " (use brook)");
                    }
                }
                case "--tps" -> tps = Double.parseDouble(require(args, ++i, a));
                case "--duration", "--duration-sec" -> durationSec = Long.parseLong(require(args, ++i, a));
                case "--short-code" -> shortCode = require(args, ++i, a);
                case "--digits" -> digits = require(args, ++i, a);
                case "--msisdn-random" -> msisdnRandom = true;
                case "--msisdn-prefix" -> msisdnPrefix = require(args, ++i, a);
                case "--msisdn" -> {
                    fixedMsisdn = require(args, ++i, a);
                    msisdnRandom = false;
                }
                case "--engine" -> engine = require(args, ++i, a).toLowerCase(Locale.ROOT);
                case "--jmx" -> jmxUrl = require(args, ++i, a);
                case "--wait-ms" -> waitMs = Long.parseLong(require(args, ++i, a));
                case "--status-url" -> statusUrl = require(args, ++i, a);
                case "--map-json" -> mapLoadJson = Path.of(require(args, ++i, a));
                case "--map-jar" -> mapLoadJar = Path.of(require(args, ++i, a));
                case "--map-cp" -> mapLoadClasspathFile = Path.of(require(args, ++i, a));
                case "--orig-pc" -> origPc = Integer.parseInt(require(args, ++i, a));
                case "--dest-pc" -> destPc = Integer.parseInt(require(args, ++i, a));
                case "--java" -> javaBin = require(args, ++i, a);
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown arg: " + a);
            }
        }
    }

    private static String require(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[idx];
    }

    private void applyBrookScenario() {
        brookScenario = true;
        shortCode = "*804#";
        digits = "1";
        msisdnRandom = true;
        msisdnPrefix = "25191";
        // Always lock smoke defaults (do not key off stale 100/60 defaults).
        // Override with --tps / --duration *after* --scenario brook when green-lit.
        tps = 1;
        durationSec = 30;
        // Digicom L3-LAB-SIM: SCCP nwid=1, sim PC=2 → GW PC=1470 :8023 (not laptop nwid=0/PC1).
        origPc = 2;
        destPc = 1470;
        for (Path digicomLab : List.of(
                Path.of("ss7-ussd-client-digicom-lab.json"),
                Path.of("tools/ss7-simulator/ss7-ussd-client-digicom-lab.json"))) {
            if (Files.isRegularFile(digicomLab)) {
                mapLoadJson = digicomLab;
                break;
            }
        }
        if (!Files.isRegularFile(mapLoadJson)
                || !mapLoadJson.getFileName().toString().contains("digicom-lab")) {
            mapLoadJson = Path.of("tools/ss7-simulator/ss7-ussd-client-digicom-lab.json");
        }
        System.out.println("scenario=brook  *804# MAP2MAP→BPLUS multimenu digit=1  (AS=real BPLUS, not as-node)");
        System.out.println("  ss7-sim SCCP networkId=1 (L3-LAB :8024→:8023 PC 2→1470); live handset *804 stays nwid=0");
        System.out.println("  mapJson=" + mapLoadJson + "  origPc=" + origPc + " destPc=" + destPc);
        System.out.println("  See tools/ss7-simulator/BROOK-SCENARIO.md — wait operator green light.");
    }

    private int run() throws Exception {
        String resolved = resolveEngine();
        System.out.println("ussd-load engine=" + resolved
                + (brookScenario ? " scenario=brook nwid=1" : "")
                + "  tps=" + tps + " (MSISDN sessions/s)"
                + "  durationSec=" + durationSec
                + "  shortCode=" + shortCode
                + "  digits=" + digits
                + "  origPc=" + origPc + " destPc=" + destPc
                + "  msisdn=" + (msisdnRandom ? ("random:" + msisdnPrefix) : fixedMsisdn));
        printStatusSnapshot("before");
        int code = switch (resolved) {
            case "jmx" -> runJmxSequential();
            case "mapload" -> runMapLoad();
            default -> throw new IllegalStateException("engine=" + resolved);
        };
        printSummary();
        printStatusSnapshot("after");
        return code;
    }

    private String resolveEngine() {
        if (!"auto".equals(engine)) {
            return engine;
        }
        return tps > 2.0 ? "mapload" : "jmx";
    }

    /** Sequential JMX: one MSISDN session at a time (smoke / ≤~2 TPS). */
    private int runJmxSequential() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);
        long intervalNs = tps <= 0 ? 1_000_000_000L : (long) (1_000_000_000.0 / tps);
        long nextStart = System.nanoTime();
        try (JmxUssdSession session = new JmxUssdSession(jmxUrl, hostObjectName, ussdObjectName)) {
            session.connect();
            if (!session.isSimStarted()) {
                session.startSim();
                Thread.sleep(800);
            }
            session.setAutoDigits(digits, false);
            List<String> dtSeq = splitDigits(digits);
            while (System.nanoTime() < deadline) {
                long now = System.nanoTime();
                if (now < nextStart) {
                    TimeUnit.NANOSECONDS.sleep(nextStart - now);
                }
                nextStart += intervalNs;
                String msisdn = nextMsisdn();
                moStarted.increment();
                long t0 = System.nanoTime();
                try {
                    session.setMsisdn(msisdn);
                    String dialRes = session.dial(shortCode);
                    if (dialRes != null && dialRes.toLowerCase(Locale.ROOT).contains("exception")) {
                        moFail.increment();
                        session.closeDialog();
                        continue;
                    }
                    String menu = session.waitNetworkText(waitMs);
                    if (menu == null || menu.isBlank()) {
                        moFail.increment();
                        session.closeDialog();
                        continue;
                    }
                    for (String d : dtSeq) {
                        session.dt(d);
                        String next = session.waitNetworkText(waitMs);
                        if (next != null && !next.isBlank()) {
                            digitOk.increment();
                        }
                    }
                    moOk.increment();
                    recordSessionMs(t0);
                } catch (Exception e) {
                    moFail.increment();
                    System.err.println("jmx session fail msisdn=" + msisdn + " " + e.getMessage());
                    try {
                        session.closeDialog();
                    } catch (Exception ignored) {
                        // best-effort
                    }
                }
            }
        }
        return moFail.sum() > 0 && moOk.sum() == 0 ? 1 : 0;
    }

    /**
     * Concurrent MAP load via jSS7 map/load Client.
     * {@code ss7.load.rateLimit} = MO / MSISDN sessions per second.
     */
    private int runMapLoad() throws Exception {
        Path jar = mapLoadJar != null ? mapLoadJar : defaultMapLoadJar();
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IOException(
                    "map-load jar not found. Set --map-jar or build jSS7 map/load. See SPIKE-JMX-CONCURRENCY.md");
        }
        if (!Files.isRegularFile(mapLoadJson)) {
            throw new IOException("map JSON missing: " + mapLoadJson.toAbsolutePath());
        }
        long ndialogs = Math.max(1, Math.round(tps * durationSec));
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Dss7.load.rateLimit=" + formatRate(tps));
        cmd.add("-Dss7.load.ndialogs=" + ndialogs);
        cmd.add("-Dss7.load.shortCode=" + shortCode);
        cmd.add("-Dss7.load.digits=" + digits);
        cmd.add("-Dss7.load.msisdnPrefix=" + msisdnPrefix);
        cmd.add("-Dss7.load.origPc=" + origPc);
        cmd.add("-Dss7.load.destPc=" + destPc);
        cmd.add("-Dss7.load.ussdSsn=8");
        cmd.add("-Dss7.load.mscSsn=8");
        if (!msisdnRandom && fixedMsisdn != null && !fixedMsisdn.isBlank()) {
            cmd.add("-Dss7.load.msisdn=" + fixedMsisdn);
        } else {
            cmd.add("-Dss7.load.msisdn=");
        }
        String cp = buildClasspath(jar);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("org.restcomm.protocols.ss7.map.load.ussd.Client");
        cmd.add(mapLoadJson.toAbsolutePath().toString());

        System.out.println("mapload ndialogs=" + ndialogs + " (≈ tps×duration MSISDN sessions)");
        System.out.println("exec: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread poller = Thread.startVirtualThread(() -> pollStatusDuring(Duration.ofSeconds(durationSec + 30)));
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
                if (line.contains("Sent USSD dialog") || line.contains("Created")
                        || line.toLowerCase(Locale.ROOT).contains("dialog")) {
                    // best-effort: map/load prints batch notifs; count estimated from ndialogs at end
                }
            }
        }
        boolean finished = p.waitFor(durationSec + 120, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            moFail.increment();
            System.err.println("mapload timed out — killed");
            return 1;
        }
        int exit = p.exitValue();
        moStarted.add(ndialogs);
        if (exit == 0) {
            moOk.add(ndialogs);
            digitOk.add(ndialogs);
        } else {
            moFail.add(ndialogs);
        }
        poller.interrupt();
        return exit;
    }

    private void pollStatusDuring(Duration max) {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(5_000);
                printStatusSnapshot("mid");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("status poll: " + e.getMessage());
            }
        }
    }

    private Path defaultMapLoadJar() {
        List<Path> candidates = List.of(
                Path.of(System.getProperty("user.home"),
                        "Desktop/ethiopia-working-dir/worktrees/jSS7/coral-valley/jSS7/map/load/target/map-load-9.2.8-j25.jar"),
                Path.of("../../../../jSS7/coral-valley/jSS7/map/load/target/map-load-9.2.8-j25.jar"),
                Path.of("../../../jSS7/coral-valley/jSS7/map/load/target/map-load-9.2.8-j25.jar"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private String buildClasspath(Path jar) throws IOException {
        StringBuilder sb = new StringBuilder(jar.toAbsolutePath().toString());
        Path cpFile = mapLoadClasspathFile;
        if (cpFile == null) {
            Path sibling = jar.getParent().resolve("../ant-classpath.txt").normalize();
            Path alt = jar.getParent().getParent().resolve("ant-classpath.txt");
            if (Files.isRegularFile(sibling)) {
                cpFile = sibling;
            } else if (Files.isRegularFile(alt)) {
                cpFile = alt;
            }
        }
        if (cpFile != null && Files.isRegularFile(cpFile)) {
            String extra = Files.readString(cpFile, StandardCharsets.UTF_8).trim();
            if (!extra.isEmpty()) {
                sb.append(System.getProperty("path.separator")).append(extra.replace('\n', ':'));
            }
        }
        // Also add target/dependency if present
        Path deps = jar.getParent().resolve("dependency");
        if (Files.isDirectory(deps)) {
            try (var stream = Files.list(deps)) {
                stream.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                    sb.append(System.getProperty("path.separator")).append(p.toAbsolutePath());
                });
            }
        }
        return sb.toString();
    }

    private String nextMsisdn() {
        if (!msisdnRandom && fixedMsisdn != null && !fixedMsisdn.isBlank()) {
            return fixedMsisdn;
        }
        int seven = ThreadLocalRandom.current().nextInt(10_000_000);
        return msisdnPrefix + String.format("%07d", seven);
    }

    private static List<String> splitDigits(String csv) {
        return Arrays.stream(csv.split("[,|;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String formatRate(double tps) {
        if (tps == Math.rint(tps)) {
            return Integer.toString((int) tps);
        }
        return Double.toString(tps);
    }

    private void recordSessionMs(long t0Nanos) {
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0Nanos);
        sessionMsSum.addAndGet(ms);
        sessionMsSamples.incrementAndGet();
    }

    private void printSummary() {
        long started = moStarted.sum();
        long ok = moOk.sum();
        long fail = moFail.sum();
        double achieved = durationSec > 0 ? (started / (double) durationSec) : 0;
        double avgMs = sessionMsSamples.get() > 0
                ? sessionMsSum.get() / (double) sessionMsSamples.get()
                : 0;
        System.out.println("--- ussd-load summary ---");
        System.out.println("mo_started(MSISDN sessions)=" + started);
        System.out.println("mo_ok=" + ok + "  mo_fail=" + fail + "  digit_ok=" + digitOk.sum());
        System.out.println("achieved_tps(MSISDN/s)=" + String.format(Locale.ROOT, "%.2f", achieved)
                + "  target=" + tps);
        if (avgMs > 0) {
            System.out.println("avg_session_ms=" + String.format(Locale.ROOT, "%.1f", avgMs));
        }
        System.out.println("NOTE: TPS counts unique MSISDN MO starts, not TCAP messages.");
    }

    private void printStatusSnapshot(String label) {
        if (statusUrl == null || statusUrl.isBlank()) {
            return;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(statusUrl).toURL().openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            String body;
            try (var in = c.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String adaptive = extractJsonFragment(body, "adaptive");
            String ss7 = extractJsonFragment(body, "ss7");
            String gate = extractJsonField(body, "gateTicks");
            System.out.println("status[" + label + "] http=" + code
                    + " gateTicks≈" + gate
                    + " ss7≈" + truncate(ss7, 120)
                    + " adaptive≈" + truncate(adaptive, 160));
        } catch (Exception e) {
            System.out.println("status[" + label + "] unreachable: " + e.getMessage());
        }
    }

    private static String extractJsonFragment(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return "";
        }
        int start = json.indexOf('{', i);
        if (start < 0) {
            return extractJsonField(json, key);
        }
        int depth = 0;
        for (int j = start; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, j + 1);
                }
            }
        }
        return "";
    }

    private static String extractJsonField(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return "";
        }
        int end = colon + 1;
        while (end < json.length() && Character.isWhitespace(json.charAt(end))) {
            end++;
        }
        int stop = end;
        while (stop < json.length()) {
            char ch = json.charAt(stop);
            if (ch == ',' || ch == '}' || ch == ']') {
                break;
            }
            stop++;
        }
        return json.substring(end, stop).trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void printHelp() {
        System.out.println("""
                ussd-load — Brook Digicom prove (TPS = MSISDN sessions/s, not TCAP msgs)

                  --scenario brook       *804# + digit 1; Digicom L3-LAB nwid=1 JSON + destPc=1470; smoke tps=1
                  --tps 1                MO starts / unique MSISDNs per second (ask before 100 on Digicom)
                  --duration 30          seconds
                  --short-code '*804#'
                  --digits 1             Brook CDR prove digit; use 1,2 for deeper menu
                  --msisdn-random        (default) prefix+7 digits
                  --msisdn-prefix 25191
                  --engine auto|mapload|jmx
                  --map-json PATH        Digicom: ss7-ussd-client-digicom-lab.json (nwid=1)
                  --orig-pc 2            map/load OPC (sim)
                  --dest-pc 1470         Digicom GW PC (laptop pull-lab uses 1)
                  --map-jar PATH
                  --status-url URL
                  --jmx URL

                Oracle: tools/ss7-simulator/BROOK-SCENARIO.md — ss7-sim nwid=1; live *804 nwid=0; AS=BPLUS.
                """);
    }
}
