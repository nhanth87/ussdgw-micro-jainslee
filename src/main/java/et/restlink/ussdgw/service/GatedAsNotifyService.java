package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.classic.ClassicDialogXmlCodec;
import et.restlink.ussdgw.bridge.GatedSessionMeta;
import et.restlink.ussdgw.bridge.VirtualSession;
import et.restlink.ussdgw.cdr.CdrPhase;
import et.restlink.ussdgw.cdr.CdrService;
import et.restlink.ussdgw.cdr.CdrStatuses;
import et.restlink.ussdgw.events.GatedAsNotifyEvent;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * After gate expiry, POST a new classic XmlMAPDialog XML to the session's AS
 * ({@code ShortCodeRule.asUrl}) so the application server learns the prior
 * session was gated and can re-push with {@code virtualBridgeId} / {@code jsessionId}.
 */
@ApplicationScoped
public class GatedAsNotifyService {
    private static final Logger LOG = LogManager.getLogger(GatedAsNotifyService.class);

    @Inject ShortCodeRoutingService routing;
    @Inject MicroSleeContainer container;
    @Inject CdrService cdr;

    private final AtomicLong pushed = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    /** Unit-test sink; when set, skips SLEE container route. */
    private volatile Consumer<GatedAsNotifyEvent> testDispatch;

    /**
     * Stamp is assumed already done by the caller. Resolves HTTP asUrl from short-code
     * routing and routes {@link GatedAsNotifyEvent}. No-op when URL missing / non-HTTP.
     * Stamps {@link CdrStatuses#GATED_AS_NOTIFY} / {@link CdrStatuses#GATED_AS_SKIP}.
     *
     * @return true when an event was routed toward HttpClientSbb
     */
    public boolean pushToAs(GatedSessionMeta meta, VirtualSession session) {
        if (meta == null || meta.correlationId() == null || meta.correlationId().isBlank()) {
            skipped.incrementAndGet();
            return false;
        }
        Optional<String> url = resolveHttpAsUrl(meta, session);
        if (url.isEmpty()) {
            skipped.incrementAndGet();
            LOG.info("Gated AS XML push skipped (no HTTP asUrl) corr={} sc={} reason={}",
                    meta.correlationId(),
                    meta.shortCode() != null ? meta.shortCode()
                            : (session == null ? null : session.shortCode()),
                    meta.gateReason());
            cdrWrite(meta, session, CdrStatuses.GATED_AS_SKIP,
                    "service=GatedAsNotifyService|skip=no-http-asUrl");
            return false;
        }
        String xml = ClassicDialogXmlCodec.encodeGatedPush(meta);
        GatedAsNotifyEvent ev = new GatedAsNotifyEvent(url.get(), xml, meta);
        if (!dispatch(ev)) {
            skipped.incrementAndGet();
            cdrWrite(meta, session, CdrStatuses.GATED_AS_SKIP,
                    "service=GatedAsNotifyService|skip=dispatch|asUrl=" + url.get());
            return false;
        }
        pushed.incrementAndGet();
        LOG.info("Gated AS XML push queued corr={} asUrl={} reason={} jsession={} gateMs={}",
                meta.correlationId(), url.get(), meta.gateReason(),
                meta.jsessionId(), meta.gateMs());
        cdrWrite(meta, session, CdrStatuses.GATED_AS_NOTIFY,
                "service=GatedAsNotifyService|asUrl=" + url.get()
                        + "|reason=" + (meta.gateReason() == null ? "" : meta.gateReason()));
        return true;
    }

    private void cdrWrite(GatedSessionMeta meta, VirtualSession session, String status, String detail) {
        if (cdr == null || meta == null) {
            return;
        }
        try {
            String msisdn = firstNonBlank(meta.msisdn(),
                    session == null ? null : session.msisdn());
            String sc = firstNonBlank(meta.shortCode(),
                    session == null ? null : session.shortCode());
            String tenant = session == null ? null : session.tenantId();
            String origin = session == null || session.originationType() == null
                    ? "MAP" : session.originationType().name();
            int networkId = meta.networkId();
            if (session != null) {
                networkId = session.networkId();
            }
            Long gate = meta.gateMs() > 0 ? meta.gateMs() : null;
            Long ewma = meta.observedEwmaMs();
            cdr.write(meta.correlationId(), CdrPhase.S1_RELEASED, msisdn, sc, status, detail,
                    networkId, tenant, origin, gate, ewma);
        } catch (RuntimeException e) {
            LOG.debug("Gated AS CDR skipped corr={}: {}", meta.correlationId(), e.toString());
        }
    }

    private boolean dispatch(GatedAsNotifyEvent ev) {
        Consumer<GatedAsNotifyEvent> sink = testDispatch;
        if (sink != null) {
            sink.accept(ev);
            return true;
        }
        if (container == null) {
            LOG.warn("Gated AS XML push skipped (no SLEE container) corr={}",
                    ev.meta().correlationId());
            return false;
        }
        try {
            String activity = GatedAsNotifyEvent.httpSessionId(ev.meta().correlationId());
            container.routeEvent(ev, container.createActivityContext(activity));
            return true;
        } catch (RuntimeException e) {
            LOG.warn("Gated AS XML push route failed corr={}: {}",
                    ev.meta().correlationId(), e.toString());
            return false;
        }
    }

    /** Package-visible for unit tests. */
    void bindTestDispatch(Consumer<GatedAsNotifyEvent> sink) {
        this.testDispatch = sink;
    }

    /** Package-visible for unit tests. */
    Optional<String> resolveHttpAsUrl(GatedSessionMeta meta, VirtualSession session) {
        String sc = firstNonBlank(
                meta == null ? null : meta.shortCode(),
                session == null ? null : session.shortCode());
        if (sc == null || routing == null) {
            return Optional.empty();
        }
        Optional<ShortCodeRule> rule = routing.find(sc);
        if (rule.isEmpty()) {
            return Optional.empty();
        }
        ShortCodeRule r = rule.get();
        if (r.asPullType() == null || !r.asPullType().usesHttpAsPull()) {
            return Optional.empty();
        }
        String asUrl = r.asUrl();
        if (asUrl == null || asUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(asUrl.trim());
    }

    /** Encode-only helper for tests / docs. Always classic XML. */
    public static String encodeXml(GatedSessionMeta meta) {
        return ClassicDialogXmlCodec.encodeGatedPush(meta);
    }

    public static AsHttpWireFormat wireFormat() {
        return AsHttpWireFormat.XML;
    }

    public long pushed() { return pushed.get(); }
    public long skipped() { return skipped.get(); }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
