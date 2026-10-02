package et.restlink.ussdgw.bridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * P1-6 source guard: a CAS ({@code compareAndTransition} /
 * {@code claimForAsResponse}) must never be followed by a detached full-row
 * {@code store.put} in the same method — {@code UssdTxProfileMapper.write}
 * republishes every CMP field and silently reverts concurrent single-field
 * writes (notably {@code dialogAlive}) taken between the CAS and the read.
 *
 * <p>Single-field writers ({@code store.setXxx}) and terminal {@code remove}
 * are the allowed successors. Mirrors the {@code Log4j2OnlyPolicyTest} pattern:
 * fails the build, not just review.
 */
class CasLawGuardTest {
    private static final List<String> FILES = List.of(
            "bridge/VirtualSessionBridge.java",
            "bridge/UssdSagaCoordinator.java",
            "service/BridgeGateScheduler.java",
            "api/classic/ClassicNiHttpPark.java",
            "sbbs/MapUssdParentSbb.java",
            "sbbs/MapNiPushSbb.java",
            "sbbs/HttpServerSbb.java");

    /** Methods that were fixed in Step 2 and must never regress to full put. */
    private static final Map<String, List<String>> DENIED = Map.of(
            "sbbs/MapUssdParentSbb.java",
            List.of("clearMap2mapHopOutstanding", "onNotifyResponse", "markDialogDead"),
            "api/classic/ClassicNiHttpPark.java",
            List.of("stampSessionGate"),
            "sbbs/MapNiPushSbb.java",
            List.of("keepOrCompleteSession"),
            "bridge/UssdSagaCoordinator.java",
            List.of("compensate"),
            "bridge/VirtualSessionBridge.java",
            List.of("onNetworkAbort", "applyToLiveDialog", "onAsResponse", "onNiAsContinue"),
            "service/BridgeGateScheduler.java",
            List.of("sweepPendingCorrelations"),
            "sbbs/HttpServerSbb.java",
            List.of("handleNiContinue")); // handleNiFirst puts at creation (no CAS) — exempt

    @Test
    void noFullPutAfterCasInSameMethod() throws Exception {
        Path base = srcMain();
        List<String> violations = new ArrayList<>();
        for (String rel : FILES) {
            String src = Files.readString(base.resolve(rel));
            for (MethodBody m : methods(src)) {
                boolean claims = m.body.contains("compareAndTransition(")
                        || m.body.contains("claimForAsResponse(");
                boolean puts = m.body.contains("store.put(")
                        || m.body.contains("svc().store().put(");
                if (claims && puts) {
                    violations.add(rel + "#" + m.name);
                }
            }
        }
        assertThat(violations)
                .as("methods with CAS + detached full put (use store.setXxx / remove)")
                .isEmpty();
    }

    @Test
    void fixedSitesStayPutFree() throws Exception {
        Path base = srcMain();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : DENIED.entrySet()) {
            String src = Files.readString(base.resolve(e.getKey()));
            List<MethodBody> all = methods(src);
            for (String name : e.getValue()) {
                MethodBody m = all.stream()
                        .filter(x -> x.name.equals(name))
                        .findFirst()
                        .orElse(null);
                if (m == null) {
                    fail("guard outdated: method not found: " + e.getKey() + "#" + name);
                }
                if (m.body.contains("store.put(") || m.body.contains("svc().store().put(")) {
                    violations.add(e.getKey() + "#" + name);
                }
            }
        }
        assertThat(violations)
                .as("Step-2 fixed sites regressed to full put")
                .isEmpty();
    }

    private static Path srcMain() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("src/main/java"))) {
            dir = dir.getParent();
        }
        assertThat(dir)
                .as("worktree root with src/main/java above user.dir=%s",
                        System.getProperty("user.dir"))
                .isNotNull();
        return dir.resolve("src/main/java/et/restlink/ussdgw");
    }

    private record MethodBody(String name, String body) {}

    private static final Pattern SIG = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|private|protected)\\s+)?(?:static\\s+)?"
                    + "[\\w<>\\[\\],.? ]+\\s+(\\w+)\\s*\\(");
    private static final Set<String> KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "return", "new",
            "throw", "assert");

    /** Split top-level method bodies with a string/comment-aware brace matcher. */
    static List<MethodBody> methods(String src) {
        List<MethodBody> out = new ArrayList<>();
        Matcher m = SIG.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            if (KEYWORDS.contains(name)) continue;
            int i = m.end();
            i = skipParens(src, i);
            if (i < 0) continue;
            int j = i;
            while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
            if (j >= src.length() || src.charAt(j) != '{') continue;
            int end = matchBrace(src, j);
            if (end < 0) continue;
            out.add(new MethodBody(name, src.substring(j, end + 1)));
        }
        return out;
    }

    /** Index just past the matching ')' of the '(' ending at {@code open-1}; -1 if none. */
    private static int skipParens(String src, int i) {
        int depth = 1;
        while (i < src.length()) {
            i = skipNoise(src, i);
            if (i >= src.length()) return -1;
            char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        return -1;
    }

    /** Index of the '}' matching the '{' at {@code open}; -1 if none. */
    private static int matchBrace(String src, int open) {
        int depth = 0;
        int i = open;
        while (i < src.length()) {
            i = skipNoise(src, i);
            if (i >= src.length()) return -1;
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    /** Skip whitespace, comments, string/char/text-block literals; return first real index. */
    private static int skipNoise(String src, int i) {
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < src.length()) {
                char d = src.charAt(i + 1);
                if (d == '/') {
                    int nl = src.indexOf('\n', i + 2);
                    i = nl < 0 ? src.length() : nl + 1;
                    continue;
                }
                if (d == '*') {
                    int end = src.indexOf("*/", i + 2);
                    i = end < 0 ? src.length() : end + 2;
                    continue;
                }
            }
            if (c == '"') {
                // text block """...""" or "…" with escapes
                if (src.startsWith("\"\"\"", i)) {
                    int end = src.indexOf("\"\"\"", i + 3);
                    i = end < 0 ? src.length() : end + 3;
                } else {
                    i++;
                    while (i < src.length()) {
                        char e = src.charAt(i);
                        if (e == '\\') {
                            i += 2;
                            continue;
                        }
                        i++;
                        if (e == '"') break;
                    }
                }
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < src.length()) {
                    char e = src.charAt(i);
                    if (e == '\\') {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (e == '\'') break;
                }
                continue;
            }
            return i;
        }
        return i;
    }
}
