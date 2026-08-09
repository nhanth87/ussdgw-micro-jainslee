package et.restlink.ussdgw.cdr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdrUssdSnippetTest {

    @Test
    void snippet_truncatesToFiftyWithEllipsis() {
        String longText = "A".repeat(60) + " tail";
        String snip = CdrUssdSnippet.of(longText);
        assertThat(snip).hasSize(51); // 50 + …
        assertThat(snip).startsWith("A".repeat(50)).endsWith("…");
        assertThat(CdrUssdSnippet.of("short")).isEqualTo("short");
        assertThat(CdrUssdSnippet.of("  a|b\nc\r  ")).isEqualTo("a/b c");
        assertThat(CdrUssdSnippet.of(null)).isEmpty();
        assertThat(CdrUssdSnippet.of("   ")).isEmpty();
    }

    @Test
    void asUssdDetail_carriesSnippetAndLen() {
        String body = "ውድ ደንበኛ ፤ ውጤቱ በአጭር መለእክት ተልኳል፡፡ ኢትዮ ቴሌኮም — extra padding for truncate";
        String d = CdrUssdSnippet.asUssdDetail(body);
        assertThat(d).startsWith("asUssd=");
        assertThat(d).contains("|asLen=" + body.trim().length());
        String snip = d.substring("asUssd=".length(), d.indexOf("|asLen="));
        assertThat(snip.length()).isLessThanOrEqualTo(CdrUssdSnippet.MAX_CHARS + 1); // +ellipsis
        assertThat(CdrUssdSnippet.asUssdDetail("")).isEqualTo("asUssd-empty");
        assertThat(CdrUssdSnippet.asUssdDetail(null)).isEqualTo("asUssd-empty");
        assertThat(CdrUssdSnippet.asUssdDetail("Thank you."))
                .isEqualTo("asUssd=Thank you.|asLen=10");
    }
}
