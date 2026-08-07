package et.digicom.ussdsim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interactive / one-shot USSD MO CLI for Digicom-ET USSDGW lab.
 * Speaks MAP via jSS7 {@code USSD_TEST_CLIENT} over JMX (Java 25).
 *
 * <pre>
 *   mise exec zulu-25 -- java -jar ussd-cli.jar
 *   ussd&gt; connect
 *   ussd&gt; msisdn 251911000001
 *   ussd&gt; dial *100#
 *   ussd&gt; dt 1
 *   ussd&gt; dial *519812345678901234#
 *   ussd&gt; quit
 * </pre>
 */
public final class UssdCli {

    private static final String DEFAULT_JMX = "service:jmx:rmi:///jndi/rmi://127.0.0.1:9999/server";
    private static final String DEFAULT_HOST = "SS7_Simulator_main:type=TesterHost";
    private static final String DEFAULT_USSD = "SS7_Simulator_main:type=TestUssdClientMan";

    private String jmxUrl = DEFAULT_JMX;
    private String hostObjectName = DEFAULT_HOST;
    private String ussdObjectName = DEFAULT_USSD;
    private String msisdn = "251911000001";
    private List<String> msisdnList = List.of("251911000001");
    private int msisdnRotate = 0;
    private String autoDigits = "1,2,3,4";
    private boolean autoDt = false;
    private long waitMs = 4000;
    private Path configPath;

    private JmxUssdSession session;

    public static void main(String[] args) throws Exception {
        UssdCli cli = new UssdCli();
        cli.loadDefaultsFromEnv();
        List<String> rest = cli.parseArgs(args);
        if (cli.configPath != null) {
            cli.applyConfigFile(cli.configPath);
        }
        if (rest.isEmpty()) {
            cli.repl();
            return;
        }
        int code = cli.runOneShot(rest);
        System.exit(code);
    }

    private void loadDefaultsFromEnv() {
        String jmx = System.getenv("USSD_CLI_JMX");
        if (jmx != null && !jmx.isBlank()) {
            jmxUrl = jmx.trim();
        }
        String cfg = System.getenv("CONFIG");
        if (cfg != null && !cfg.isBlank()) {
            configPath = Path.of(cfg);
        }
        String digits = System.getenv("USSD_SIM_AUTO_DIGITS");
        if (digits != null && !digits.isBlank()) {
            autoDigits = digits.trim();
        }
        String msisdns = System.getenv("USSD_SIM_MSISDNS");
        if (msisdns != null && !msisdns.isBlank()) {
            setMsisdnList(msisdns);
        }
    }

    private List<String> parseArgs(String[] args) {
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--config", "-c" -> configPath = Path.of(requireArg(args, ++i, a));
                case "--jmx" -> jmxUrl = requireArg(args, ++i, a);
                case "--msisdn" -> {
                    msisdn = requireArg(args, ++i, a);
                    msisdnList = List.of(msisdn);
                }
                case "--msisdns" -> setMsisdnList(requireArg(args, ++i, a));
                case "--dt" -> {
                    autoDigits = requireArg(args, ++i, a);
                    autoDt = true;
                }
                case "--auto" -> autoDt = true;
                case "--manual" -> autoDt = false;
                case "--wait-ms" -> waitMs = Long.parseLong(requireArg(args, ++i, a));
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> rest.add(a);
            }
        }
        return rest;
    }

    private static String requireArg(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[idx];
    }

    private void setMsisdnList(String csv) {
        List<String> list = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (list.isEmpty()) {
            return;
        }
        msisdnList = list;
        msisdn = list.get(0);
        msisdnRotate = 0;
    }

    private void applyConfigFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Config not found: " + path);
        }
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        Map<String, Object> root = MiniJson.parseObject(raw);
        if (root.get("shortCode") instanceof String sc && !sc.isBlank()) {
            // used only as default hint in help / one-shot if dial omitted
            System.setProperty("ussd.cli.defaultCode", sc);
        }
        if (root.get("digits") instanceof String d) {
            autoDigits = d;
        }
        if (root.get("digitDelayMs") instanceof Number n) {
            // hint for sim JVM; CLI wait uses waitMs
            waitMs = Math.max(waitMs, n.longValue() + 1500);
        }
        if (root.get("msisdns") instanceof List<?> list) {
            List<String> msisdns = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    msisdns.add(o.toString());
                }
            }
            if (!msisdns.isEmpty()) {
                msisdnList = List.copyOf(msisdns);
                msisdn = msisdnList.get(0);
            }
        }
        Object jmx = root.get("jmx");
        if (jmx instanceof Map<?, ?> jm) {
            Object url = jm.get("url");
            if (url != null) {
                jmxUrl = url.toString();
            }
            Object host = jm.get("hostObjectName");
            if (host != null) {
                hostObjectName = host.toString();
            }
            Object ussd = jm.get("ussdClientObjectName");
            if (ussd != null) {
                ussdObjectName = ussd.toString();
            }
        }
    }

    private void ensureSession() {
        if (session == null) {
            session = new JmxUssdSession(jmxUrl, hostObjectName, ussdObjectName);
        }
    }

    private void ensureConnected() throws Exception {
        ensureSession();
        if (!session.isConnected()) {
            doConnect(true);
        }
    }

    private void doConnect(boolean startIfNeeded) throws Exception {
        ensureSession();
        try {
            if (!session.isConnected()) {
                session.connect();
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "JMX connect failed (" + jmxUrl + "). Start sim first: ./tools/ss7-simulator/run.sh sim\n"
                            + "Cause: " + e.getMessage(),
                    e);
        }
        session.setMsisdn(msisdn);
        session.setAutoDigits(autoDigits, autoDt);
        if (startIfNeeded && !session.isSimStarted()) {
            System.out.println("Starting USSD_TEST_CLIENT stack via JMX…");
            session.startSim();
            Thread.sleep(800);
        }
        System.out.println("Connected  jmx=" + jmxUrl);
        System.out.println("  host=" + hostObjectName);
        System.out.println("  ussd=" + ussdObjectName);
        System.out.println("  started=" + session.isSimStarted() + "  L1=" + session.getL1State());
        System.out.println("  msisdn=" + msisdn + "  autoDt=" + autoDt + "  digits=" + autoDigits);
    }

    private int runOneShot(List<String> rest) throws Exception {
        String cmd = rest.get(0).toLowerCase(Locale.ROOT);
        return switch (cmd) {
            case "repl" -> {
                repl();
                yield 0;
            }
            case "dial" -> {
                String code = rest.size() > 1 ? rest.get(1) : System.getProperty("ussd.cli.defaultCode", "*100#");
                List<String> dtSeq = autoDt ? splitDigits(autoDigits) : List.of();
                if (rest.size() > 2 && !rest.get(2).startsWith("-")) {
                    // dial *100# 1,2,3
                    dtSeq = splitDigits(rest.get(2));
                    autoDt = false; // manual sequence after dial wait
                }
                doConnect(true);
                yield runDialFlow(code, dtSeq);
            }
            case "help" -> {
                printHelp();
                yield 0;
            }
            default -> {
                System.err.println("Unknown command: " + cmd);
                printHelp();
                yield 2;
            }
        };
    }

    private int runDialFlow(String code, List<String> manualDigits) throws Exception {
        session.setMsisdn(msisdn);
        if (autoDt && manualDigits.isEmpty()) {
            session.setAutoDigits(autoDigits, true);
        } else {
            session.setAutoDigits(autoDigits, false);
        }
        System.out.println("dial " + code + "  msisdn=" + msisdn);
        String sendRes = session.dial(code);
        System.out.println("  → " + sendRes);
        String text = session.waitNetworkText(waitMs);
        if (!text.isBlank()) {
            System.out.println("  network: " + text);
        } else {
            System.out.println("  (no UnstructuredSS-Request within " + waitMs + " ms — check AS/routing)");
        }
        if (!manualDigits.isEmpty()) {
            for (String d : manualDigits) {
                Thread.sleep(200);
                System.out.println("dt " + d);
                System.out.println("  → " + session.dt(d));
                text = session.waitNetworkText(waitMs);
                if (!text.isBlank()) {
                    System.out.println("  network: " + text);
                }
            }
        } else if (autoDt) {
            // let sim auto-digit; poll a bit for final END
            Thread.sleep(Math.min(waitMs, autoDigits.split(",").length * 500L + 500L));
            System.out.println("  dialog: " + session.currentRequestDef());
        }
        return 0;
    }

    private void repl() throws Exception {
        printBanner();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (true) {
                System.out.print("ussd> ");
                System.out.flush();
                line = in.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    if (!dispatchRepl(line)) {
                        break;
                    }
                } catch (Exception e) {
                    System.err.println("error: " + e.getMessage());
                }
            }
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private boolean dispatchRepl(String line) throws Exception {
        String[] tok = tokenize(line);
        String cmd = tok[0].toLowerCase(Locale.ROOT);
        return switch (cmd) {
            case "quit", "exit", "q" -> false;
            case "help", "?" -> {
                printReplHelp();
                yield true;
            }
            case "connect" -> {
                boolean start = tok.length < 2 || !tok[1].equalsIgnoreCase("--nostart");
                doConnect(start);
                yield true;
            }
            case "disconnect" -> {
                if (session != null) {
                    session.close();
                    session = null;
                }
                System.out.println("Disconnected");
                yield true;
            }
            case "start" -> {
                ensureConnected();
                session.startSim();
                System.out.println("start → L1=" + session.getL1State());
                yield true;
            }
            case "stop" -> {
                ensureConnected();
                session.stopSim();
                System.out.println("Stopped");
                yield true;
            }
            case "status" -> {
                ensureConnected();
                System.out.println("started=" + session.isSimStarted()
                        + " L1=" + session.getL1State()
                        + " msisdn=" + msisdn
                        + " autoDt=" + autoDt
                        + " digits=" + autoDigits);
                System.out.println("dialog: " + session.currentRequestDef());
                yield true;
            }
            case "msisdn" -> {
                if (tok.length < 2) {
                    System.out.println("msisdn=" + msisdn + "  list=" + msisdnList);
                } else {
                    msisdn = tok[1];
                    if (session != null && session.isConnected()) {
                        session.setMsisdn(msisdn);
                    }
                    System.out.println("msisdn=" + msisdn);
                }
                yield true;
            }
            case "msisdns" -> {
                if (tok.length < 2) {
                    System.out.println("msisdns=" + msisdnList);
                } else {
                    setMsisdnList(String.join(",", Arrays.copyOfRange(tok, 1, tok.length)));
                    System.out.println("msisdns=" + msisdnList);
                }
                yield true;
            }
            case "next-msisdn" -> {
                rotateMsisdn();
                System.out.println("msisdn=" + msisdn);
                yield true;
            }
            case "auto" -> {
                if (tok.length >= 2) {
                    autoDigits = tok[1];
                }
                autoDt = true;
                if (session != null && session.isConnected()) {
                    session.setAutoDigits(autoDigits, true);
                }
                System.out.println("autoDt=ON digits=" + autoDigits);
                yield true;
            }
            case "manual" -> {
                autoDt = false;
                if (session != null && session.isConnected()) {
                    session.setAutoDigits(autoDigits, false);
                }
                System.out.println("autoDt=OFF (use 'dt <digit>')");
                yield true;
            }
            case "dial" -> {
                if (tok.length < 2) {
                    System.out.println("usage: dial <ussd-string>   e.g. dial *100#");
                    yield true;
                }
                String code = tok[1];
                ensureConnected();
                if (autoDt) {
                    session.setAutoDigits(autoDigits, true);
                } else {
                    session.setAutoDigits(autoDigits, false);
                }
                session.setMsisdn(msisdn);
                System.out.println("dial " + code + "  msisdn=" + msisdn);
                System.out.println("  → " + session.dial(code));
                String text = session.waitNetworkText(waitMs);
                if (!text.isBlank()) {
                    System.out.println("  network: " + text);
                }
                yield true;
            }
            case "dt" -> {
                if (tok.length < 2) {
                    System.out.println("usage: dt <digits-or-text>   e.g. dt 1");
                    yield true;
                }
                ensureConnected();
                String payload = String.join(" ", Arrays.copyOfRange(tok, 1, tok.length));
                System.out.println("dt " + payload);
                System.out.println("  → " + session.dt(payload));
                String text = session.waitNetworkText(waitMs);
                if (!text.isBlank()) {
                    System.out.println("  network: " + text);
                }
                yield true;
            }
            case "close" -> {
                ensureConnected();
                System.out.println(session.closeDialog());
                yield true;
            }
            case "wait" -> {
                ensureConnected();
                long ms = tok.length >= 2 ? Long.parseLong(tok[1]) : waitMs;
                String text = session.waitNetworkText(ms);
                System.out.println(text.isBlank() ? "(timeout)" : "network: " + text);
                yield true;
            }
            default -> {
                System.out.println("Unknown command '" + cmd + "' — type help");
                yield true;
            }
        };
    }

    private void rotateMsisdn() throws Exception {
        if (msisdnList.isEmpty()) {
            return;
        }
        msisdnRotate = (msisdnRotate + 1) % msisdnList.size();
        msisdn = msisdnList.get(msisdnRotate);
        if (session != null && session.isConnected()) {
            session.setMsisdn(msisdn);
        }
    }

    private static List<String> splitDigits(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String[] tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote && Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out.toArray(String[]::new);
    }

    private static void printBanner() {
        System.out.println("Digicom-ET USSD CLI (JMX → jSS7 USSD_TEST_CLIENT)");
        System.out.println("Type 'help'. Short: dial *100#   Long/mark: dial *5198…# or *100*1234567890#");
    }

    private static void printReplHelp() {
        System.out.println("""
                Commands:
                  connect [--nostart]   Connect JMX; start stack unless --nostart
                  disconnect            Close JMX
                  start | stop          TesterHost start/stop
                  status                Link + current dialog def
                  msisdn [digits]       Get/set MSISDN
                  msisdns a,b,c         Set MSISDN rotation list
                  next-msisdn           Rotate to next MSISDN
                  auto [1,2,3,4]        Auto-reply digits on UnstructuredSS-Request
                  manual                Disable auto; use dt
                  dial <code>           MO ProcessUnstructuredSS-Request
                  dt <text>             UnstructuredSS-Response (menu digit / free text)
                  wait [ms]             Wait for next network menu text
                  close                 Close current MAP dialog
                  quit                  Exit
                Examples:
                  dial *100#
                  dt 1
                  dial *519812345678901234#
                  dial '*100*1234567890#'
                """);
    }

    private static void printHelp() {
        System.out.println("""
                Digicom-ET USSD CLI — Java 25 JMX driver for jSS7 USSD_TEST_CLIENT

                Usage:
                  java -jar ussd-cli.jar [--config tools/ss7-simulator/config.example.json]
                  java -jar ussd-cli.jar dial '*100#' --msisdn 251911000001 --dt 1,2,3
                  java -jar ussd-cli.jar dial '*519812345678901234#' --manual

                Options:
                  --config, -c FILE   JSON (jmx.url, msisdns, digits, peer note)
                  --jmx URL           default rmi://127.0.0.1:9999/server
                  --msisdn N          single MSISDN
                  --msisdns a,b,c     MSISDN list
                  --dt 1,2,3          auto digit sequence (enables auto)
                  --auto / --manual
                  --wait-ms N         poll timeout for network text (default 4000)

                Prereq: jSS7 sim core with RMI, lab XML, ussdgw SS7 up (:8013).
                  ./tools/ss7-simulator/run.sh sim
                  ./tools/ss7-simulator/run.sh cli
                """);
    }

    /**
     * Minimal JSON object parser for our flat config (no external deps).
     * Supports objects, arrays of strings/numbers/booleans, nested one level for {@code jmx}/{@code peer}.
     */
    static final class MiniJson {
        private MiniJson() {}

        static Map<String, Object> parseObject(String raw) {
            String s = stripComments(raw).trim();
            Parser p = new Parser(s);
            Object v = p.parseValue();
            if (!(v instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("Config root must be a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) m;
            return out;
        }

        private static String stripComments(String raw) {
            // strip // line comments outside strings — config.example uses none; keep simple
            return raw;
        }

        private static final class Parser {
            private final String s;
            private int i;

            Parser(String s) {
                this.s = s;
            }

            Object parseValue() {
                skipWs();
                if (i >= s.length()) {
                    throw new IllegalArgumentException("Unexpected end of JSON");
                }
                char c = s.charAt(i);
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't' -> parseLiteral("true", Boolean.TRUE);
                    case 'f' -> parseLiteral("false", Boolean.FALSE);
                    case 'n' -> parseLiteral("null", null);
                    default -> parseNumber();
                };
            }

            private Map<String, Object> parseObject() {
                expect('{');
                Map<String, Object> map = new LinkedHashMap<>();
                skipWs();
                if (peek('}')) {
                    i++;
                    return map;
                }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object val = parseValue();
                    map.put(key, val);
                    skipWs();
                    if (peek('}')) {
                        i++;
                        return map;
                    }
                    expect(',');
                }
            }

            private List<Object> parseArray() {
                expect('[');
                List<Object> list = new ArrayList<>();
                skipWs();
                if (peek(']')) {
                    i++;
                    return list;
                }
                while (true) {
                    list.add(parseValue());
                    skipWs();
                    if (peek(']')) {
                        i++;
                        return list;
                    }
                    expect(',');
                }
            }

            private String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\' && i < s.length()) {
                        char e = s.charAt(i++);
                        sb.append(switch (e) {
                            case '"', '\\', '/' -> e;
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            default -> e;
                        });
                    } else {
                        sb.append(c);
                    }
                }
                throw new IllegalArgumentException("Unterminated string");
            }

            private Object parseNumber() {
                int start = i;
                if (peek('-')) {
                    i++;
                }
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                String n = s.substring(start, i);
                if (n.contains(".")) {
                    return Double.parseDouble(n);
                }
                return Long.parseLong(n);
            }

            private Object parseLiteral(String lit, Object val) {
                if (!s.startsWith(lit, i)) {
                    throw new IllegalArgumentException("Expected " + lit);
                }
                i += lit.length();
                return val;
            }

            private void skipWs() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
            }

            private boolean peek(char c) {
                return i < s.length() && s.charAt(i) == c;
            }

            private void expect(char c) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != c) {
                    throw new IllegalArgumentException("Expected '" + c + "' at " + i);
                }
                i++;
            }
        }
    }
}
