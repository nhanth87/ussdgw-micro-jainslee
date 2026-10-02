package et.restlink.ussdgw.bridge;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsResponse;
import et.restlink.ussdgw.api.UssdAlphabet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Language shifting between USSD menu turns — each turn can have a different alphabet.
 * AS controls the alphabet per turn via {@code AsResponse.alphabet}; GW stores it in
 * {@code VirtualSession.pendingAlphabet} and uses it for MAP encoding.
 *
 * <p>Example flow:
 * <ul>
 *   <li>Turn 1: AS returns {@code {"text": "Welcome", "alphabet": "ucs7"}} → GSM-7 (English)</li>
 *   <li>Turn 2: AS returns {@code {"text": "ሰላም", "alphabet": "unicode"}} → UCS-2 (Amharic)</li>
 *   <li>Turn 3: AS returns {@code {"text": "مرحبا", "alphabet": "unicode"}} → UCS-2 (Arabic)</li>
 * </ul>
 */
class LanguageShiftingTest {

    @Test
    void asResponseCarriesAlphabetPerTurn() {
        // Turn 1: English (GSM-7)
        AsResponse turn1 = new AsResponse("corr-1", "req-1", 1, "Welcome", AsAction.CONTINUE,
                false, UssdAlphabet.UCS7);
        assertThat(turn1.alphabet()).isEqualTo(UssdAlphabet.UCS7);

        // Turn 2: Amharic (UCS-2)
        AsResponse turn2 = new AsResponse("corr-1", "req-2", 2, "ሰላም", AsAction.CONTINUE,
                false, UssdAlphabet.UNICODE);
        assertThat(turn2.alphabet()).isEqualTo(UssdAlphabet.UNICODE);

        // Turn 3: Arabic (UCS-2)
        AsResponse turn3 = new AsResponse("corr-1", "req-3", 3, "مرحبا", AsAction.END,
                false, UssdAlphabet.UNICODE);
        assertThat(turn3.alphabet()).isEqualTo(UssdAlphabet.UNICODE);
    }

    @Test
    void virtualSessionStoresPendingAlphabet() {
        VirtualSession session = new VirtualSession("vs-1", "corr-1", "req-1",
                "251911000000", 0, "dlg-1", "*123#");

        // Default: AUTO
        assertThat(session.pendingAlphabet()).isEqualTo(UssdAlphabet.AUTO);

        // Turn 1: AS sets UCS7
        session.setPendingAlphabet(UssdAlphabet.UCS7);
        assertThat(session.pendingAlphabet()).isEqualTo(UssdAlphabet.UCS7);

        // Turn 2: AS shifts to UNICODE (Amharic)
        session.setPendingAlphabet(UssdAlphabet.UNICODE);
        assertThat(session.pendingAlphabet()).isEqualTo(UssdAlphabet.UNICODE);

        // Turn 3: AS shifts back to UCS7 (English)
        session.setPendingAlphabet(UssdAlphabet.UCS7);
        assertThat(session.pendingAlphabet()).isEqualTo(UssdAlphabet.UCS7);
    }

    @Test
    void nullAlphabetDefaultsToAuto() {
        AsResponse resp = new AsResponse("corr-1", "req-1", 1, "Hello", AsAction.END,
                false, null);
        assertThat(resp.alphabet()).isEqualTo(UssdAlphabet.AUTO);

        VirtualSession session = new VirtualSession("vs-1", "corr-1", "req-1",
                "251911000000", 0, "dlg-1", "*123#");
        session.setPendingAlphabet(null);
        assertThat(session.pendingAlphabet()).isEqualTo(UssdAlphabet.AUTO);
    }

    @Test
    void multiLanguageSessionFlow() {
        // Simulate a multi-language USSD session:
        // Turn 1: English menu (GSM-7)
        // Turn 2: Amharic menu (UCS-2)
        // Turn 3: Arabic confirmation (UCS-2)

        VirtualSession session = new VirtualSession("vs-1", "corr-1", "req-1",
                "251911000000", 0, "dlg-1", "*123#");

        // Turn 1: English
        AsResponse turn1 = new AsResponse("corr-1", "req-1", 1, "1. English\n2. Amharic",
                AsAction.CONTINUE, false, UssdAlphabet.UCS7);
        session.setPendingAlphabet(turn1.alphabet());
        assertThat(session.pendingAlphabet()).isEqualTo(UssdAlphabet.UCS7);

        // User selects "2" → Turn 2: Amharic
        session.nextGeneration(); // gen = 2
        AsResponse turn2 = new AsResponse("corr-1", "req-2", 2, "ሰላም ምርጥ",
                AsAction.CONTINUE, false, UssdAlphabet.UNICODE);
        session.setPendingAlphabet(turn2.alphabet());
        assertThat(session.pendingAlphabet()).isEqualTo(UssdAlphabet.UNICODE);

        // User confirms → Turn 3: Arabic
        session.nextGeneration(); // gen = 3
        AsResponse turn3 = new AsResponse("corr-1", "req-3", 3, "تم",
                AsAction.END, false, UssdAlphabet.UNICODE);
        session.setPendingAlphabet(turn3.alphabet());
        assertThat(session.pendingAlphabet()).isEqualTo(UssdAlphabet.UNICODE);
    }
}
