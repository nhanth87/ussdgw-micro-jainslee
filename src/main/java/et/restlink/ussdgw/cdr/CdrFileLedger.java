package et.restlink.ussdgw.cdr;

import et.restlink.ussdgw.persist.CdrEntity;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Durable <b>file</b> CDR ledger — the source of truth for the admin ledger.
 *
 * <p>Every {@link CdrService#write} emits one self-describing line to the {@code USSD_CDR}
 * Log4j appender ({@code logs/ussd-cdr.log}) and appends the same delta here. The ring is the
 * hot read model; the file is what survives a restart. Both are warmed from the same codec, so
 * a restart renders the same rows as before the restart instead of an empty page.
 *
 * <p><b>Filter before cap</b> (GMLC 2026-09-30 lesson): the tenant/msisdn/status predicate runs
 * on the <em>rolled-up session</em>, and the walk only stops once {@code limit} <em>complete</em>
 * correlations have matched. Capping the newest N raw rows first and filtering afterwards
 * silently emptied the page for every tenant as soon as other users wrote N rows.
 *
 * <p>Hot path never blocks on disk: {@link #append} is a lock-free deque push plus a size trim.
 * File reads happen only on {@link #onStart} warm-up and are bounded by a byte budget.
 */
@ApplicationScoped
public class CdrFileLedger {
    private static final Logger LOG = LogManager.getLogger(CdrFileLedger.class);

    /** Active ledger file inside {@code ussd.log.dir}; rolled siblings are {@code ussd-cdr-*.log}. */
    static final String ACTIVE_FILE = "ussd-cdr.log";
    private static final String ROLLED_PREFIX = "ussd-cdr-";
    private static final String ROLLED_SUFFIX = ".log";

    /**
     * Emitted once per rotated file so operators can read the column order off the file.
     *
     * <p><b>Escaping (locked):</b> {@code detail} is the pipe-delimited {@code k=v} digest that
     * {@link CdrSessionDigest} parses, and {@code asUssd} is arbitrary operator/AS text — both may
     * legitimately contain {@code |}. Those fields use backslash escaping ({@code \|}, {@code \\}).
     * Identifier fields (corr / phase / status / …) are sanitized instead: {@code |} → {@code /}.
     * CR/LF always becomes a space — a raw newline would corrupt the line ledger.
     */
    public static final String LINE_HEADER =
            "# ussdgw-cdr v1 recordedAt|corr|phase|msisdn|shortCode|status|detail|networkId"
                    + "|tenantId|gateMs|observedEwmaMs|originationType|hopOutcome|refuseReason|asUssd"
                    + " (detail/asUssd/refuseReason use \\| escaping)";

    /** Legacy (pre-v1) line: log4j {@code %d} prefix + 10 pipe fields, no in-line instant. */
    private static final int LEGACY_STAMP_LEN = "yyyy-MM-dd HH:mm:ss.SSS".length();
    private static final int LEGACY_FIELDS = 10;

    private final Deque<CdrEntity> ring = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder warmed = new LongAdder();

    private final String logDir;
    private final int capacity;
    private final int warmBytes;

    public CdrFileLedger(
            @ConfigProperty(name = "ussd.log.dir", defaultValue = "logs") String logDir,
            @ConfigProperty(name = "ussd.cdr.recent-events", defaultValue = "50000") int capacity,
            @ConfigProperty(name = "ussd.cdr.warm-bytes", defaultValue = "4194304") int warmBytes) {
        this.logDir = logDir == null || logDir.isBlank() ? "logs" : logDir;
        // Production floor is applied in {@link #effectiveCapacity()}, not here, so tests can
        // exercise overflow with a tiny ring.
        this.capacity = capacity;
        this.warmBytes = warmBytes;
    }

    /**
     * Ring capacity actually enforced. 1024 events is the production floor — far above any burst
     * of MAP dialogs, and still only a few MB of heap.
     */
    private int effectiveCapacity() {
        return capacityOverride ? Math.max(1, capacity) : Math.max(1024, capacity);
    }

    /**
     * Test seam: in-memory ledger with a small capacity. A static factory rather than a second
     * constructor — CDI requires exactly one bean constructor, and a no-arg one would silently
     * disable the {@code @ConfigProperty} injection.
     */
    static CdrFileLedger forTests(String logDir, int capacity, int warmBytes) {
        CdrFileLedger l = new CdrFileLedger(logDir, capacity, warmBytes);
        l.capacityOverride = true;
        return l;
    }

    /** Set only by {@link #forTests}: keeps tiny test capacities below the production floor. */
    private boolean capacityOverride;

    // ------------------------------------------------------------------ write path

    /**
     * Record one CDR milestone. Called from {@link CdrService#write} on the MAP/SBB hot path —
     * lock-free push, drop-oldest when over capacity (the durable file already has the line).
     */
    public void append(CdrEntity delta) {
        if (delta == null) {
            return;
        }
        ring.addLast(delta);
        int over = size.incrementAndGet() - effectiveCapacity();
        while (over > 0 && ring.pollFirst() != null) {
            size.decrementAndGet();
            dropped.increment();
            over--;
        }
    }

    // ------------------------------------------------------------------ read path

    /**
     * Newest-first session rows (1 correlation → 1 row), filter applied <b>before</b> the cap.
     *
     * @param limit max sessions to return
     * @param keep  predicate over the rolled-up session (tenant/msisdn/corr/status)
     */
    public List<CdrEntity> sessions(int limit, Predicate<CdrEntity> keep) {
        int cap = Math.max(1, limit);
        List<CdrEntity> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return List.of();
        }
        Predicate<CdrEntity> filter = keep == null ? e -> true : keep;

        // Walk newest -> oldest. A correlation is COMPLETE once we step onto a different one,
        // so we never roll up half a session (and never stop before a session's first event).
        Map<String, List<CdrEntity>> pending = new LinkedHashMap<>();
        List<CdrEntity> out = new ArrayList<>(Math.min(cap, 64));
        String currentCorr = null;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            CdrEntity delta = snapshot.get(i);
            String corr = corrOf(delta);
            if (!corr.equals(currentCorr)) {
                if (currentCorr != null) {
                    complete(currentCorr, pending, filter, cap, out);
                    if (out.size() >= cap) {
                        return List.copyOf(out);
                    }
                }
                currentCorr = corr;
            }
            pending.computeIfAbsent(corr, k -> new ArrayList<>(4)).add(delta);
            trim(pending, corr);
        }
        if (currentCorr != null) {
            complete(currentCorr, pending, filter, cap, out);
        }
        return List.copyOf(out);
    }

    /**
     * Fold one correlation's events into its session row and keep it when it passes the filter.
     * {@code pending} holds the correlation's events newest-first; the rollup wants oldest-first
     * so {@code events_json} / the 6-hop spine / the multimenu tape read in causal order.
     */
    private static void complete(String corr, Map<String, List<CdrEntity>> pending,
                                 Predicate<CdrEntity> keep, int cap, List<CdrEntity> out) {
        List<CdrEntity> newestFirst = pending.remove(corr);
        if (newestFirst == null || newestFirst.isEmpty()) {
            return;
        }
        List<CdrEntity> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        CdrEntity session = CdrSessionRollup.coalesceByCorrelation(oldestFirst).stream()
                .findFirst()
                .orElse(null);
        if (session == null || !keep.test(session)) {
            return;
        }
        session.id = session.id != null ? session.id : UUID.nameUUIDFromBytes(corr.getBytes(StandardCharsets.UTF_8));
        out.add(session);
    }

    /**
     * Bound memory: an in-flight session can emit many milestones. Keep the newest
     * {@link CdrSessionRollup#MAX_EVENTS} so the walk stays O(cap) per request.
     */
    private static void trim(Map<String, List<CdrEntity>> pending, String corr) {
        List<CdrEntity> events = pending.get(corr);
        if (events != null && events.size() > CdrSessionRollup.MAX_EVENTS) {
            events.remove(0);
        }
    }

    private List<CdrEntity> snapshot() {
        return new ArrayList<>(ring);
    }

    private static String corrOf(CdrEntity e) {
        return e == null || e.correlationId == null ? "" : e.correlationId;
    }

    // ------------------------------------------------------------------ warm-up

    /**
     * Seed the ring from the ledger file so a restart does not show an empty page. Bounded by
     * {@code ussd.cdr.warm-bytes} read from the end of the newest files, oldest first.
     */
    void onStart(@Observes StartupEvent ev) {
        // Column contract into the active ledger first, so `head -1 logs/ussd-cdr.log` after any
        // rotation still documents the format. Parsers skip the '#' line.
        LogManager.getLogger("USSD_CDR").info(LINE_HEADER);
        try {
            int n = warmFromFile();
            if (n > 0) {
                LOG.info("[cdr-file] warmed {} CDR events from {} file(s) in {}",
                        n, ledgerFiles().size(), logDir);
            }
        } catch (RuntimeException e) {
            LOG.warn("[cdr-file] warm-up skipped: {}", e.toString());
        }
    }

    int warmFromFile() {
        List<Path> files = ledgerFiles();
        if (files.isEmpty()) {
            return 0;
        }
        int budget = warmBytes;
        int added = 0;
        // Ascending mtime = chronological: append oldest file first so the ring stays ordered.
        for (Path file : files) {
            if (budget <= 0) {
                break;
            }
            for (String line : tailLines(file, budget)) {
                CdrEntity delta = parse(line);
                if (delta == null) {
                    continue;
                }
                append(delta);
                added++;
            }
            budget -= sizeOf(file);
        }
        warmed.add(added);
        return added;
    }

    /**
     * Ledger files, oldest first: the active file plus its newest uncompressed rolled siblings.
     * {@code .gz} archives are skipped — tailing a 200 MB gz per admin request is not a read path.
     */
    List<Path> ledgerFiles() {
        Path dir = Path.of(logDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> candidates = new ArrayList<>();
        Path active = dir.resolve(ACTIVE_FILE);
        try {
            if (Files.isRegularFile(active)) {
                candidates.add(active);
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(ROLLED_PREFIX) && n.endsWith(ROLLED_SUFFIX) && Files.isRegularFile(p);
                }).forEach(candidates::add);
            }
        } catch (IOException e) {
            LOG.warn("[cdr-file] list {} failed: {}", logDir, e.toString());
            return List.of();
        }
        candidates.sort(Comparator.comparingLong(CdrFileLedger::lastModified)
                .thenComparing(p -> p.getFileName().toString().equals(ACTIVE_FILE) ? 1 : 0));
        return candidates;
    }

    private static int sizeOf(Path file) {
        try {
            return (int) Math.min(Integer.MAX_VALUE, Files.size(file));
        } catch (IOException e) {
            return 1;
        }
    }

    /** Last {@code maxBytes} of the file as lines, oldest first, first partial line dropped. */
    private static List<String> tailLines(Path file, int maxBytes) {
        List<String> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            int want = (int) Math.min(len, maxBytes);
            raf.seek(len - want);
            byte[] buf = new byte[want];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            List<String> all = new ArrayList<>(List.of(text.split("\n", -1)));
            if (len > want) {
                // The first fragment is a partial line — drop it.
                all.remove(0);
            }
            for (String s : all) {
                String t = s.strip();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        } catch (IOException e) {
            LOG.warn("[cdr-file] tail {} failed: {}", file, e.toString());
        }
        return out;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ codec

    /** One ledger line: v1 carries the instant in field 0, so the appender pattern is plain {@code %m%n}. */
    public static String line(CdrEntity row) {
        return String.join("|",
                flat(row.recordedAt == null ? Instant.now().toString() : row.recordedAt.toString()),
                flat(row.correlationId),
                flat(row.phase),
                flat(row.msisdn),
                flat(row.shortCode),
                flat(row.status),
                escaped(row.detail),
                row.networkId == null ? "" : Integer.toString(row.networkId),
                flat(row.tenantId),
                row.gateMs == null ? "" : Long.toString(row.gateMs),
                row.observedEwmaMs == null ? "" : Long.toString(row.observedEwmaMs),
                flat(row.originationType),
                flat(row.hopOutcome),
                escaped(row.refuseReason),
                escaped(row.asUssd));
    }

    /**
     * Parse a ledger line back into a {@link CdrEntity} delta. Understands v1 (ISO instant in
     * field 0) and the pre-v1 shape (log4j {@code %d} prefix + 10 fields), so rolled files
     * written before the upgrade still render in the ledger.
     *
     * @return the delta, or {@code null} for a header/blank/too-short line
     */
    public static CdrEntity parse(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.strip();
        if (line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        // Pre-v1 shape: log4j `%d{yyyy-MM-dd HH:mm:ss.SSS} ` prefix, then the 10-field line.
        if (line.length() > LEGACY_STAMP_LEN
                && Character.isDigit(line.charAt(0))
                && line.charAt(4) == '-'
                && line.charAt(10) == ' '
                && line.charAt(LEGACY_STAMP_LEN) == ' ') {
            return parseLegacy(line);
        }
        return parseV1(line);
    }

    private static CdrEntity parseV1(String line) {
        List<String> fields = splitEscaped(line);
        if (fields.size() < 11) {
            return null;
        }
        Instant at = parseInstant(fields.get(0));
        if (at == null) {
            return null;
        }
        CdrEntity e = new CdrEntity();
        e.id = UUID.randomUUID();
        e.recordedAt = at;
        e.startedAt = at;
        e.updatedAt = at;
        e.correlationId = fields.get(1);
        e.phase = fields.get(2);
        e.msisdn = blankToNull(fields.get(3));
        e.shortCode = blankToNull(fields.get(4));
        e.status = fields.get(5);
        e.detail = blankToNull(fields.get(6));
        e.networkId = parseInt(fields.get(7));
        e.tenantId = blankToNull(fields.get(8));
        e.gateMs = parseLong(fields.get(9));
        e.observedEwmaMs = parseLong(fields.get(10));
        e.originationType = at(fields, 11, "MAP");
        e.hopOutcome = at(fields, 12, null);
        e.refuseReason = at(fields, 13, null);
        e.asUssd = at(fields, 14, null);
        return e;
    }

    private static String at(List<String> fields, int i, String fallback) {
        if (i >= fields.size()) {
            return fallback;
        }
        return blankToNull(fields.get(i)) != null ? fields.get(i) : fallback;
    }

    /** Pre-v1: {@code yyyy-MM-dd HH:mm:ss.SSS corr|phase|msisdn|shortCode|status|detail|net|tenant|gate|ewma}. */
    private static CdrEntity parseLegacy(String line) {
        String[] f = line.substring(LEGACY_STAMP_LEN + 1).split("\\|", -1);
        if (f.length < LEGACY_FIELDS) {
            return null;
        }
        // f[0] is the legacy "corr" column — the old 10-field formatCsv order.
        CdrEntity e = new CdrEntity();
        e.id = UUID.randomUUID();
        e.recordedAt = legacyStamp(line);
        e.startedAt = e.recordedAt;
        e.updatedAt = e.recordedAt;
        e.correlationId = f[0];
        e.phase = f[1];
        e.msisdn = blankToNull(f[2]);
        e.shortCode = blankToNull(f[3]);
        e.status = f[4];
        e.detail = blankToNull(f[5]);
        e.networkId = parseInt(f[6]);
        e.tenantId = blankToNull(f[7]);
        e.gateMs = parseLong(f[8]);
        e.observedEwmaMs = parseLong(f[9]);
        e.originationType = "MAP";
        return e;
    }

    /**
     * Identifier field: no {@code |} (it is the column separator), no CR/LF (a raw newline would
     * corrupt the line ledger), no padding. Content loses {@code |} → {@code /}.
     */
    private static String flat(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').strip();
    }

    /** Free-text field (pipe-delimited {@code detail}, AS text): {@code \} → {@code \\}, {@code |} → {@code \|}. */
    private static String escaped(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .strip();
    }

    /** Escape-aware split on unescaped {@code |}; unescapes each field. */
    static List<String> splitEscaped(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escapedChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escapedChar) {
                // Keep unknown escapes verbatim (e.g. a Windows path) rather than eating the char.
                if (c != '|' && c != '\\') {
                    cur.append('\\');
                }
                cur.append(c);
                escapedChar = false;
            } else if (c == '\\') {
                escapedChar = true;
            } else if (c == '|') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (escapedChar) {
            cur.append('\\');
        }
        out.add(cur.toString());
        return out;
    }

    /** The log4j {@code %d} stamp of a pre-v1 line, as an {@link Instant} (no zone = UTC). */
    private static Instant legacyStamp(String line) {
        String stamp = line.substring(0, LEGACY_STAMP_LEN).trim();
        try {
            return Instant.parse(stamp.replace(' ', 'T') + "Z");
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.strip();
        return t.isEmpty() ? null : t;
    }

    // ------------------------------------------------------------------ telemetry

    public int size() {
        return Math.max(0, size.get());
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public long warmedCount() {
        return warmed.sum();
    }

    /** Unused ring handle kept for diagnostics/tests. */
    Deque<CdrEntity> ringView() {
        return new ArrayDeque<>(ring);
    }
}