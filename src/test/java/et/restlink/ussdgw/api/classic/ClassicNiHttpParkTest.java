package et.restlink.ussdgw.api.classic;

import et.restlink.ussdgw.api.AsAction;
import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsWireFacade;
import et.restlink.ussdgw.bridge.AdaptiveTimeout;
import et.restlink.ussdgw.config.UssdConfigService;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClassicNiHttpParkTest {
    private ClassicNiHttpPark park;
    private CapturingHttp http;

    @BeforeEach
    void setUp() {
        park = new ClassicNiHttpPark();
        set(park, "adaptive", new AdaptiveTimeout());
        set(park, "config", new UssdConfigService());
        set(park, "wireFacade", new AsWireFacade());
        http = new CapturingHttp();
        park.bindHttp(() -> http);
    }

    @AfterEach
    void tearDown() {
        park.shutdown();
    }

    @Test
    void parkFindUnparkByCorrAndJsession() {
        ClassicNiHttpPark.ParkRecord rec = park.park(
                "http-1", "js-1", "corr-1", AsHttpWireFormat.XML, 0, false);
        assertThat(rec.correlationId()).isEqualTo("corr-1");
        assertThat(park.findByJsession("js-1")).contains(rec);
        assertThat(park.findByCorr("corr-1")).contains(rec);
        assertThat(park.size()).isEqualTo(1);

        assertThat(park.unpark("corr-1")).contains(rec);
        assertThat(park.findByJsession("js-1")).isEmpty();
        assertThat(park.findByCorr("corr-1")).isEmpty();
        assertThat(park.size()).isZero();
    }

    @Test
    void completeParkedRepliesAndKeepsJsessionMapping() {
        park.park("http-1", "js-keep", "corr-keep", AsHttpWireFormat.JSON, 0, false);
        assertThat(park.completeParked("corr-keep", "Menu", AsAction.CONTINUE)).isTrue();
        assertThat(http.last).isNotNull();
        assertThat(http.last).isInstanceOf(HttpServerCommand.HttpResponseExCommand.class);
        var ex = (HttpServerCommand.HttpResponseExCommand) http.last;
        assertThat(ex.sessionId()).isEqualTo("http-1");
        assertThat(ex.headers()).containsEntry("Set-Cookie", "JSESSIONID=js-keep; Path=/; HttpOnly");
        assertThat(ex.contentType()).contains("json");

        // Mapping retained for classic continue; no pending HTTP until re-park.
        assertThat(park.findByJsession("js-keep")).isPresent();
        assertThat(park.findByCorr("corr-keep").orElseThrow().httpSessionId()).isNull();
        assertThat(park.completeParked("corr-keep", "x", AsAction.CONTINUE)).isFalse();
    }

    @Test
    void reparkUpdatesHttpSessionId() {
        park.park("http-old", "js-2", "corr-2", AsHttpWireFormat.XML, 1, false);
        park.completeParked("corr-2", "ok", AsAction.CONTINUE);
        ClassicNiHttpPark.ParkRecord again = park.park(
                "http-new", "js-2", "corr-2", AsHttpWireFormat.XML, 1, false);
        assertThat(again.httpSessionId()).isEqualTo("http-new");
        assertThat(park.findByJsession("js-2").orElseThrow().httpSessionId()).isEqualTo("http-new");
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CapturingHttp implements RaCommandPort {
        private final AtomicReference<OutboundCommand> lastRef = new AtomicReference<>();
        volatile OutboundCommand last;
        final List<OutboundCommand> all = new ArrayList<>();

        @Override
        public void sendCommand(OutboundCommand command) {
            last = command;
            lastRef.set(command);
            all.add(command);
        }
    }
}
