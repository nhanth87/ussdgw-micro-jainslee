package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.AdminUserEntity;
import et.restlink.ussdgw.persist.AppUserEntity;
import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.security.AsUrlValidator;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.tenant.AdminUserService;
import et.restlink.ussdgw.tenant.AppUserService;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTMX admin panels for routing, tenants (networkId), and users — OTA-shaped CRUD.
 * Full pages seed {@code {{ROWS}}} in disk templates; POST/partial returns table rows.
 *
 * <p>Routing ownership uses {@code ussd_tenant.tenant_id} + optional
 * {@code ussd_app_user.username} ({@code app_username}) — not portal
 * {@code ussd_admin_user} rows from the Users menu.
 */
@ApplicationScoped
public class AdminCatalogHandler {
    private static final String TD = " class=\"px-3 py-2\"";
    /** Muted outline — tenants/users Del (single action). */
    private static final String DEL_BTN =
            "rounded-md border border-ink-line px-2 py-1 text-[0.65rem] uppercase tracking-wider "
                    + "text-ink-mute hover:border-signal hover:text-signal";
    /** Live rules Del — red fill, keep beside Edit on one row. */
    private static final String RULE_DEL_BTN =
            "inline-flex items-center rounded-md bg-red-600 px-2 py-1 text-[0.65rem] font-semibold "
                    + "uppercase tracking-wider text-white hover:bg-red-500";
    /** Live rules Edit — amber/yellow fill (signal-adjacent). */
    private static final String RULE_EDIT_BTN =
            "inline-flex items-center rounded-md bg-amber-400 px-2 py-1 text-[0.65rem] font-semibold "
                    + "uppercase tracking-wider text-ink hover:bg-amber-300";
    private static final String RULE_ACTIONS =
            "flex flex-row flex-nowrap items-center gap-1 whitespace-nowrap";
    /** Digicom host SoT tenant id (live NI push brand); prefer over inventing digicom-et. */
    static final String PREFERRED_TENANT_ID = "digicom-push";
    /** Alias brand id — select if present; never invent a second tenant. */
    static final String ALIAS_TENANT_ID = "digicom-et";
    /** Digicom seeded NI app user under digicom-push. */
    static final String PREFERRED_APP_USERNAME = "ni-push";
    private static final String SELECT_CLS =
            "mt-1 w-full rounded-md border border-ink-line bg-ink-panel px-3 py-2 text-sm "
                    + "focus:border-signal focus:outline-none";

    @Inject ShortCodeRoutingService routing;
    @Inject TenantService tenants;
    @Inject AdminUserService users;
    @Inject AppUserService appUsers;
    @Inject AsUrlValidator asUrlValidator;
    @Inject AdminSipTrunkHandler sipTrunks;
    @Inject SipTrunkService sipTrunkService;
    @Inject UssdConfigService config;

    /** Tests construct this handler directly; fall back to the config-free defaults. */
    private AsUrlValidator asUrls() {
        AsUrlValidator v = asUrlValidator;
        if (v == null) {
            v = new AsUrlValidator();
            asUrlValidator = v;
        }
        return v;
    }

    /** Identity CRUD is ADMIN-only — OPS must not be able to mint an ADMIN principal. */
    private static boolean deniedForIdentityCrud(AdminAuthService.Principal who) {
        return who != null && !"ADMIN".equals(who.role());
    }

    private static AdminHttpHandler.HttpReply identityForbidden(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.text(403,
                "forbidden — requires role ADMIN (have "
                        + (who == null ? "anonymous" : String.valueOf(who.role())) + ")");
    }

    public AdminHttpHandler.HttpReply routingGet() {
        return routingGet(null);
    }

    /** HTMX fragment or automation: table rows (+ legacy notice wrapper when notice set). */
    public AdminHttpHandler.HttpReply routingGet(AdminAuthService.Principal who) {
        return AdminHttpHandler.HttpReply.html(routingRowsHtml(who))
                .withHeader("Vary", "HX-Request");
    }

    public Map<String, String> routingPageVars(AdminAuthService.Principal who) {
        return routingPageVars(who, null);
    }

    /**
     * Routing shell seeds. Optional {@code edit} + {@code app} query load an existing rule into
     * the create/update form (hop_dest_gt / hop_dest_ssn included). ADMIN/OPS edit hop fields;
     * TENANT sees hop read-only (save preserves existing hop).
     */
    public Map<String, String> routingPageVars(AdminAuthService.Principal who,
                                               Map<String, String> query) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{ROWS}}", routingRowsHtml(who));
        String editCode = query == null ? "" : nullToEmpty(query.get("edit")).trim();
        String editApp = query == null ? "" : nullToEmpty(query.get("app")).trim();
        Optional<ShortCodeRule> seed = Optional.empty();
        if (!editCode.isEmpty()) {
            seed = routing.byKey(editCode, editApp.isEmpty() ? null : editApp);
            if (seed.isEmpty() && who != null && who.isTenantScoped()) {
                seed = routing.listForTenant(who.tenantId()).stream()
                        .filter(r -> editCode.equals(r.shortCode()))
                        .filter(r -> editApp.isEmpty()
                                || editApp.equals(nullToEmpty(r.appUsername())))
                        .findFirst();
            }
        }
        String defaultTenant = seed.map(ShortCodeRule::tenantId)
                .filter(t -> t != null && !t.isBlank())
                .orElseGet(() -> resolveDefaultTenantId(who));
        if (who != null && who.isTenantScoped()) {
            m.put("{{TENANT_FIELD}}",
                    "<input type=\"hidden\" name=\"tenantId\" value=\"" + esc(who.tenantId()) + "\"/>"
                            + "<p class=\"text-xs text-ink-mute\">tenantId locked to "
                            + "<code class=\"font-mono text-slate-300\">" + esc(who.tenantId())
                            + "</code></p>");
        } else {
            m.put("{{TENANT_FIELD}}", tenantSelectHtml(defaultTenant));
        }
        m.put("{{APP_USER_FIELD}}", appUserSelectHtml(who, defaultTenant,
                seed.map(r -> r.appUsername() == null ? "" : r.appUsername()).orElse(null)));
        seedHttpAsWireField(m, defaultTenant);
        String upperGt = resolvedUpperHlrGtSeed();
        m.put("{{UPPER_HLR_GT}}", esc(upperGt));
        m.put("{{UPPER_HLR_GT_PLACEHOLDER}}", esc(upperGt.isEmpty()
                ? "blank = HLR Face upper-gt (ussd.hlr.upper-gt)"
                : "blank → " + upperGt + " (HLR Face)"));
        seedRoutingFormVars(m, seed.orElse(null), who);
        return m;
    }

    /**
     * AS HTTP wire is stored on {@code ussd_tenant.http_as_wire_format} (not per short-code).
     * Routing form exposes it so JSON can be enabled without Tenants catalog.
     */
    void seedHttpAsWireField(Map<String, String> m, String tenantId) {
        String wire = "XML";
        if (tenantId != null && !tenantId.isBlank() && tenants != null) {
            wire = tenants.byId(tenantId)
                    .map(t -> TenantService.normalizeHttpAsWireFormat(t.httpAsWireFormat))
                    .orElse("XML");
        }
        m.put("{{FORM_WIRE_XML_SEL}}", "XML".equals(wire) ? " selected" : "");
        m.put("{{FORM_WIRE_JSON_SEL}}", "JSON".equals(wire) ? " selected" : "");
    }

    /** Empty create form or Edit-seeded values for {@code routing.html}. */
    void seedRoutingFormVars(Map<String, String> m, ShortCodeRule r,
                             AdminAuthService.Principal who) {
        boolean hopEditable = who == null || who.isAdminOrOps();
        String ruleType = "HTTP";
        String asPull = "HTTP";
        if (r != null && r.map2mapArmed()) {
            ruleType = "RE_ROUTE";
            asPull = r.asPullType().name();
        } else if (r != null && r.ruleType() != null) {
            ruleType = r.ruleType().impliesReroute() ? "RE_ROUTE" : r.ruleType().name();
            asPull = r.asPullType().name();
        }
        m.put("{{FORM_RULE_TYPE}}", esc(ruleType));
        m.put("{{FORM_AS_PULL_TYPE}}", esc(asPull));
        m.put("{{FORM_SHORT_CODE}}", r == null ? "" : esc(nullToEmpty(r.shortCode())));
        m.put("{{FORM_AS_URL}}", r == null ? "" : esc(nullToEmpty(r.asUrl())));
        m.put("{{FORM_NETWORK_ID}}", r == null ? "0" : Integer.toString(r.networkId()));
        m.put("{{FORM_REDIRECT_USSD}}", r == null ? "" : esc(nullToEmpty(r.redirectUssdString())));
        m.put("{{FORM_HOP_DEST_GT}}", r == null ? "" : esc(nullToEmpty(r.hopDestGt())));
        m.put("{{FORM_HOP_DEST_SSN}}",
                r == null || r.hopDestSsn() == null ? "" : Integer.toString(r.hopDestSsn()));
        m.put("{{FORM_MARK_FALSE_SEL}}", r == null || !r.mark() ? " selected" : "");
        m.put("{{FORM_MARK_TRUE_SEL}}", r != null && r.mark() ? " selected" : "");
        m.put("{{FORM_ENABLED_TRUE_SEL}}", r == null || r.enabled() ? " selected" : "");
        m.put("{{FORM_ENABLED_FALSE_SEL}}", r != null && !r.enabled() ? " selected" : "");
        String hlr = r == null || r.hlrMode() == null || r.hlrMode().isBlank()
                ? "INHERIT" : r.hlrMode().toUpperCase();
        m.put("{{FORM_HLR_INHERIT_SEL}}", "INHERIT".equals(hlr) ? " selected" : "");
        m.put("{{FORM_HLR_FAKE_SEL}}", "FAKE".equals(hlr) ? " selected" : "");
        m.put("{{FORM_HLR_PROXY_SEL}}", "PROXY_MAP".equals(hlr) ? " selected" : "");
        if (r != null) {
            m.put("{{FORM_EDIT_BANNER}}",
                    "<p class=\"mb-3 rounded-md border border-signal/40 bg-signal/10 px-3 py-2 text-sm text-slate-200\">"
                            + "Editing <code class=\"font-mono text-signal\">"
                            + esc(r.shortCode()) + "</code>"
                            + (r.appUsername() == null || r.appUsername().isBlank()
                            ? "" : " · app <code class=\"font-mono text-signal\">"
                            + esc(r.appUsername()) + "</code>")
                            + " — save updates live map. "
                            + "<a class=\"text-signal underline\" href=\"/admin/routing\">Clear form</a></p>");
        } else {
            m.put("{{FORM_EDIT_BANNER}}", "");
        }
        if (hopEditable) {
            m.put("{{HOP_INPUT_ATTRS}}", "");
            m.put("{{HOP_CLEAR_FIELD}}",
                    "<label class=\"mt-2 flex items-center gap-2 text-xs text-ink-mute\">"
                            + "<input type=\"checkbox\" name=\"hopDestClear\" value=\"true\" "
                            + "class=\"rounded border-ink-line\"/>"
                            + "Clear hop dest (blank → HLR Face upper-gt)</label>");
            m.put("{{HOP_ROLE_HINT}}", "ADMIN/OPS can set or clear. Blank + no clear keeps existing hop on update.");
        } else {
            m.put("{{HOP_INPUT_ATTRS}}", " readonly");
            m.put("{{HOP_CLEAR_FIELD}}", "");
            m.put("{{HOP_ROLE_HINT}}", "TENANT: hop dest read-only; save keeps existing hop_dest_*.");
        }
        // asPullType <option selected>
        m.put("{{AS_PULL_HTTP_SEL}}", "HTTP".equals(asPull) ? " selected" : "");
        m.put("{{AS_PULL_GRPC_SEL}}", "GRPC".equals(asPull) ? " selected" : "");
        m.put("{{AS_PULL_SIP_SEL}}", "SIP".equals(asPull) ? " selected" : "");
    }

    /** HLR Face upper-gt for Hop HLR placeholder (admin overlay → props). */
    String resolvedUpperHlrGtSeed() {
        if (config == null) {
            return "";
        }
        try {
            String g = config.hlrUpperGt();
            return g == null ? "" : g.trim();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    public AdminHttpHandler.HttpReply tenantsGet() {
        return tenantsGet(null);
    }

    public AdminHttpHandler.HttpReply tenantsGet(AdminAuthService.Principal who) {
        if (deniedForIdentityCrud(who)) {
            return identityForbidden(who);
        }
        return AdminHttpHandler.HttpReply.html(tenantRowsHtml())
                .withHeader("Vary", "HX-Request");
    }

    public Map<String, String> tenantsPageVars() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{ROWS}}", tenantRowsHtml());
        m.put("{{SIP_TRUNK_OPTS}}", sipTrunks != null ? sipTrunks.trunkOptionsHtml(null) : "<option value=\"\">—</option>");
        return m;
    }

    public AdminHttpHandler.HttpReply usersGet() {
        return usersGet(null);
    }

    public AdminHttpHandler.HttpReply usersGet(AdminAuthService.Principal who) {
        if (deniedForIdentityCrud(who)) {
            return identityForbidden(who);
        }
        return AdminHttpHandler.HttpReply.html(userRowsHtml())
                .withHeader("Vary", "HX-Request");
    }

    public Map<String, String> usersPageVars() {
        return Map.of(
                "{{ROWS}}", userRowsHtml(),
                "{{TENANT_OPTS}}", tenantOptionsHtml());
    }

    public AdminHttpHandler.HttpReply routingPost(String body) {
        return routingPost(body, null);
    }

    public AdminHttpHandler.HttpReply routingPost(String body, AdminAuthService.Principal who) {
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("reload".equalsIgnoreCase(action)) {
                routing.reloadFromDb();
                int n = routing.list().size();
                return routingRowsOk(who, "reloaded " + n + " rules - live");
            }
            if ("delete".equalsIgnoreCase(action)) {
                String sc = f.getOrDefault("shortCode", "");
                String appUser = f.getOrDefault("appUsername", "");
                if (who != null && who.isTenantScoped()) {
                    boolean owned = routing.listForTenant(who.tenantId()).stream()
                            .anyMatch(r -> sc.equals(r.shortCode()));
                    if (!owned) {
                        return routingRowsErr(who, "forbidden");
                    }
                }
                routing.delete(sc, appUser.isBlank() ? null : appUser);
                return routingRowsOk(who, "deleted - live");
            }
            String code = f.getOrDefault("shortCode", "").trim();
            String typeRaw = f.getOrDefault("ruleType", "HTTP").trim();
            RuleType formType;
            try {
                formType = RuleType.parse(typeRaw);
            } catch (IllegalArgumentException ex) {
                return routingRowsErr(who, "ruleType invalid: " + typeRaw
                        + " (HTTP|GRPC|SIP|RE_ROUTE)");
            }
            // micro-jainslee: persist AS plane HTTP|GRPC|SIP. RE_ROUTE is Case 2 UX —
            // optional asPullType selects the SLEE plane after hop (default HTTP).
            RuleType ruleType = formType;
            if (formType.impliesReroute()) {
                String asPullRaw = f.getOrDefault("asPullType", "HTTP").trim();
                try {
                    RuleType plane = RuleType.parse(asPullRaw);
                    if (plane.impliesReroute()) {
                        plane = RuleType.HTTP;
                    }
                    ruleType = plane.asPullPlane();
                } catch (IllegalArgumentException ex) {
                    return routingRowsErr(who, "asPullType invalid: " + asPullRaw
                            + " (HTTP|GRPC|SIP)");
                }
            }
            String url = f.getOrDefault("asUrl", "").trim();
            String tenantId = f.getOrDefault("tenantId", "").trim();
            int networkId = parseInt(f.get("networkId"), 0);
            boolean enabled = !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true"));
            boolean mark = "true".equalsIgnoreCase(f.getOrDefault("mark", "false"));
            // RE_ROUTE form type implies Case 2; else prefer positive rerouteEnable / legacy bypass.
            boolean rerouteEnable;
            if (formType.impliesReroute()) {
                rerouteEnable = true;
            } else if (f.containsKey("rerouteEnable")) {
                rerouteEnable = "true".equalsIgnoreCase(f.getOrDefault("rerouteEnable", "false"));
            } else if (f.containsKey("bypass")) {
                rerouteEnable = "false".equalsIgnoreCase(f.getOrDefault("bypass", "true"));
            } else {
                rerouteEnable = false;
            }
            String redirectUssd = firstNonBlank(
                    f.getOrDefault("redirectUssd", ""),
                    f.getOrDefault("map2mapGt", "")).trim();
            String hlrMode = f.getOrDefault("hlrMode", "").trim();
            String hopDestGt = firstNonBlank(
                    f.getOrDefault("hopDestGt", ""),
                    f.getOrDefault("hop_dest_gt", ""),
                    f.getOrDefault("map2mapDestGt", ""),
                    f.getOrDefault("map2map_dest_gt", "")).trim();
            String hopDestSsnRaw = firstNonBlank(
                    f.getOrDefault("hopDestSsn", ""),
                    f.getOrDefault("hop_dest_ssn", ""),
                    f.getOrDefault("map2mapDestSsn", ""),
                    f.getOrDefault("map2map_dest_ssn", "")).trim();
            String appUsername = f.getOrDefault("appUsername", "").trim();
            if (code.isEmpty() || url.isEmpty()) {
                return routingRowsErr(who, "shortCode and asUrl required");
            }
            if (who != null && who.isTenantScoped()) {
                tenantId = who.tenantId();
            }
            if (rerouteEnable && redirectUssd.isEmpty()) {
                return routingRowsErr(who,
                        "redirectUssd required when type=re-route (or Re-route=true)");
            }
            Optional<ShortCodeRule> existing = routing.byKey(code,
                    appUsername.isEmpty() ? null : appUsername);
            boolean hopEditable = who == null || who.isAdminOrOps();
            boolean clearHop = "true".equalsIgnoreCase(f.getOrDefault("hopDestClear", "false"));
            Integer hopDestSsn = null;
            if (!hopEditable) {
                // TENANT: hop_dest_* ADMIN/OPS only — never wipe Digicom fixed hop on tenant save.
                if (existing.isPresent()) {
                    hopDestGt = nullToEmpty(existing.get().hopDestGt());
                    hopDestSsn = existing.get().hopDestSsn();
                } else {
                    hopDestGt = "";
                    hopDestSsn = null;
                }
            } else if (clearHop) {
                hopDestGt = "";
                hopDestSsn = null;
            } else {
                if (!hopDestSsnRaw.isEmpty()) {
                    try {
                        int ssn = Integer.parseInt(hopDestSsnRaw);
                        if (ssn < 1 || ssn > 255) {
                            return routingRowsErr(who,
                                    "hopDestSsn must be 1..255 (HLR face peer often 6)");
                        }
                        hopDestSsn = ssn;
                    } catch (NumberFormatException nfe) {
                        return routingRowsErr(who, "hopDestSsn must be an integer");
                    }
                }
                if (!hopDestGt.isEmpty()) {
                    String digits = ShortCodeRule.map2mapCalledGtDigits(hopDestGt);
                    if (digits.isEmpty()) {
                        return routingRowsErr(who,
                                "hopDestGt must contain digits (SCCP CalledParty GT)");
                    }
                    hopDestGt = digits;
                } else if (existing.isPresent()) {
                    // Blank hop on update → keep prior hop_dest_* (unrelated field edits).
                    hopDestGt = nullToEmpty(existing.get().hopDestGt());
                    if (hopDestSsn == null) {
                        hopDestSsn = existing.get().hopDestSsn();
                    }
                } else if (hopDestSsn != null && !rerouteEnable) {
                    return routingRowsErr(who,
                            "hopDestSsn requires hopDestGt (or type=re-route for Case 2 upper-gt + SSN)");
                }
            }
            if (!hlrMode.isEmpty()) {
                String n = hlrMode.toUpperCase().replace('-', '_');
                if (!List.of("INHERIT", "GLOBAL", "DEFAULT", "FAKE", "PROXY_MAP", "PROXY",
                        "MAP", "PROXY_DIAMETER", "DIAMETER", "DIAM", "FAKE_THEN_RESOLVE",
                        "FAKE_THEN", "FAKE_RESOLVE").contains(n)) {
                    return routingRowsErr(who, "hlrMode invalid: " + hlrMode);
                }
                if ("INHERIT".equals(n) || "GLOBAL".equals(n) || "DEFAULT".equals(n)) {
                    hlrMode = "";
                }
            }
            if (ruleType.usesHttpAsPull()) {
                Optional<String> ssrf = asUrls().reject(url);
                if (ssrf.isPresent()) {
                    return routingRowsErr(who, ssrf.get());
                }
            }
            if (ruleType == RuleType.SIP) {
                Optional<String> sipErr = validateSipRoute(url, tenantId.isEmpty() ? null : tenantId);
                if (sipErr.isPresent()) {
                    return routingRowsErr(who, sipErr.get());
                }
            }
            if (!appUsername.isEmpty()) {
                Optional<String> appErr = validateAppUsername(appUsername, tenantId, who);
                if (appErr.isPresent()) {
                    return routingRowsErr(who, appErr.get());
                }
            }
            if (!tenantId.isEmpty() && networkId == 0) {
                networkId = tenants.byId(tenantId).map(t -> t.networkId).orElse(0);
            }
            String wireNote = "";
            if (f.containsKey("httpAsWireFormat")) {
                String wireRaw = f.getOrDefault("httpAsWireFormat", "XML");
                String wire = TenantService.normalizeHttpAsWireFormat(wireRaw);
                if (tenantId.isEmpty()) {
                    if ("JSON".equals(wire)) {
                        return routingRowsErr(who,
                                "tenantId required to enable JSON AS wire "
                                        + "(stored on ussd_tenant.http_as_wire_format)");
                    }
                } else {
                    Optional<TenantEntity> wired = tenants.updateHttpAsWireFormat(tenantId, wire);
                    if (wired.isEmpty()) {
                        return routingRowsErr(who,
                                "tenant not found: " + tenantId + " (create tenant before setting wire)");
                    }
                    wireNote = " wire=" + wire;
                }
            }
            routing.putAndPersist(ShortCodeRule.ofReroute(
                    code, ruleType, url, enabled,
                    tenantId.isEmpty() ? null : tenantId, networkId, mark,
                    appUsername.isEmpty() ? null : appUsername,
                    rerouteEnable, redirectUssd.isEmpty() ? null : redirectUssd,
                    hlrMode.isEmpty() ? null : hlrMode.toUpperCase().replace('-', '_'),
                    hopDestGt.isEmpty() ? null : hopDestGt, hopDestSsn));
            return routingRowsOk(who, "saved " + code + wireNote + " - live");
        } catch (RuntimeException ex) {
            return routingRowsErr(who, "error: " + nullToEmpty(ex.getMessage()));
        }
    }

    /** SIP asUrl = trunkId; trunk must exist, be enabled, and allow the rule tenant. */
    private Optional<String> validateSipRoute(String asUrl, String ruleTenantId) {
        if (sipTrunkService == null) {
            return Optional.of("SIP trunks unavailable");
        }
        String trunkId = asUrl.split("\\|", 2)[0].trim();
        Optional<SipTrunkEntity> trunk = sipTrunkService.byId(trunkId);
        if (trunk.isEmpty() || !trunk.get().enabled) {
            return Optional.of("SIP trunk not found or disabled: " + trunkId);
        }
        if (!SipTrunkService.trunkAllowsTenant(trunk.get(), ruleTenantId)) {
            return Optional.of("SIP trunk " + trunkId + " does not allow tenant "
                    + (ruleTenantId == null ? "(none)" : ruleTenantId));
        }
        return Optional.empty();
    }

    public AdminHttpHandler.HttpReply tenantsPost(String body) {
        return tenantsPost(body, null);
    }

    public AdminHttpHandler.HttpReply tenantsPost(String body, AdminAuthService.Principal who) {
        if (deniedForIdentityCrud(who)) {
            return identityForbidden(who);
        }
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "save");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                tenants.delete(f.getOrDefault("tenantId", ""));
                return tenantRowsOk("deleted");
            }
            String tenantId = f.getOrDefault("tenantId", "").trim();
            if (tenantId.isEmpty()) {
                return tenantRowsErr("tenantId required");
            }
            TenantEntity e = tenants.upsert(
                    tenantId,
                    f.get("displayName"),
                    parseInt(f.get("networkId"), 0),
                    !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true")),
                    f.get("httpApiKey"),
                    f.get("smppSystemId"),
                    f.get("smppPassword"),
                    f.get("asCallbackBase"),
                    parseInt(f.get("maxTps"), 50),
                    f.get("httpAsWireFormat"),
                    f.get("sipTrunkId"));
            String notice = "saved " + e.tenantId + " networkId=" + e.networkId
                    + " wire=" + nullToEmpty(e.httpAsWireFormat)
                    + " sipTrunk=" + nullToEmpty(e.sipTrunkId)
                    + " key=" + maskKey(e.httpApiKey);
            return tenantRowsOk(notice);
        } catch (RuntimeException ex) {
            return tenantRowsErr("error: " + nullToEmpty(ex.getMessage()));
        }
    }

    public AdminHttpHandler.HttpReply usersPost(String body) {
        return usersPost(body, null);
    }

    public AdminHttpHandler.HttpReply usersPost(String body, AdminAuthService.Principal who) {
        if (deniedForIdentityCrud(who)) {
            return identityForbidden(who);
        }
        Map<String, String> f = parseForm(body);
        String action = f.getOrDefault("action", "create");
        try {
            if ("delete".equalsIgnoreCase(action)) {
                users.delete(f.getOrDefault("username", ""));
                return userRowsOk("deleted");
            }
            String username = f.getOrDefault("username", "").trim();
            String password = f.getOrDefault("password", "");
            String role = f.getOrDefault("role", "OPS");
            String tenantId = f.getOrDefault("tenantId", "");
            String display = f.getOrDefault("displayName", "");
            boolean enabled = !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true"));
            if (username.isEmpty()) {
                return userRowsErr("username required");
            }
            if ("update".equalsIgnoreCase(action)) {
                users.update(username, password, role, tenantId, display, enabled);
                return userRowsOk("updated " + username);
            }
            if (password.isBlank()) {
                return userRowsErr("password required for create");
            }
            users.create(username, password, role, tenantId, display, enabled);
            return userRowsOk("created " + username);
        } catch (RuntimeException ex) {
            return userRowsErr("error: " + nullToEmpty(ex.getMessage()));
        }
    }

    String routingRowsHtml(AdminAuthService.Principal who) {
        StringBuilder sb = new StringBuilder();
        var rules = who != null && who.isTenantScoped()
                ? routing.listForTenant(who.tenantId()) : routing.list();
        if (rules.isEmpty()) {
            sb.append("<tr><td colspan=\"15\" class=\"px-3 py-4 text-ink-mute italic\">No short-code rules.</td></tr>");
            return sb.toString();
        }
        for (ShortCodeRule r : rules) {
            String typeLabel = r.rerouteEnable() || r.ruleType() == RuleType.RE_ROUTE
                    ? "RE_ROUTE/" + r.asPullType().name()
                    : String.valueOf(r.ruleType());
            String wire = tenantWireLabel(r.tenantId());
            sb.append("<tr><td").append(TD).append(">").append(esc(r.shortCode())).append("</td><td")
                    .append(TD).append(">").append(esc(typeLabel)).append("</td><td")
                    .append(TD).append(">").append(esc(r.asUrl())).append("</td><td")
                    .append(TD).append(">").append(esc(r.tenantId())).append("</td><td")
                    .append(TD).append(">").append(esc(wire)).append("</td><td")
                    .append(TD).append(">").append(r.networkId()).append("</td><td")
                    .append(TD).append(">").append(esc(r.appUsername())).append("</td><td")
                    .append(TD).append(">").append(r.mark()).append("</td><td")
                    .append(TD).append(">").append(r.rerouteEnable()).append("</td><td")
                    .append(TD).append(">").append(esc(r.redirectUssdString())).append("</td><td")
                    .append(TD).append(">").append(esc(r.hopDestGt())).append("</td><td")
                    .append(TD).append(">").append(r.hopDestSsn() == null ? "" : r.hopDestSsn()).append("</td><td")
                    .append(TD).append(">").append(esc(r.hlrMode() == null ? "INHERIT" : r.hlrMode())).append("</td><td")
                    .append(TD).append(">").append(r.enabled()).append("</td><td").append(TD).append(">");
            sb.append("<div class=\"").append(RULE_ACTIONS).append("\">")
                    .append("<form hx-post=\"/admin/routing\" hx-target=\"#rule-rows\" hx-swap=\"innerHTML\" class=\"m-0 inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"shortCode\" value=\"").append(esc(r.shortCode())).append("\"/>")
                    .append("<input type=\"hidden\" name=\"appUsername\" value=\"").append(esc(r.appUsername())).append("\"/>")
                    .append("<button type=\"submit\" class=\"").append(RULE_DEL_BTN).append("\">Del</button></form>")
                    .append("<a class=\"").append(RULE_EDIT_BTN).append("\" href=\"/admin/routing?edit=")
                    .append(esc(java.net.URLEncoder.encode(nullToEmpty(r.shortCode()),
                            java.nio.charset.StandardCharsets.UTF_8)));
            if (r.appUsername() != null && !r.appUsername().isBlank()) {
                sb.append("&amp;app=").append(esc(java.net.URLEncoder.encode(r.appUsername(),
                        java.nio.charset.StandardCharsets.UTF_8)));
            }
            sb.append("\">Edit</a></div></td></tr>");
        }
        return sb.toString();
    }

    String tenantRowsHtml() {
        StringBuilder sb = new StringBuilder();
        var list = tenants.list();
        if (list.isEmpty()) {
            sb.append("<tr><td colspan=\"10\" class=\"px-3 py-4 text-ink-mute italic\">No tenants.</td></tr>");
            return sb.toString();
        }
        for (TenantEntity t : list) {
            sb.append("<tr><td").append(TD).append(">").append(esc(t.tenantId)).append("</td><td")
                    .append(TD).append(">").append(esc(t.displayName)).append("</td><td")
                    .append(TD).append(">").append(t.networkId).append("</td><td")
                    .append(TD).append(">").append(esc(t.httpAsWireFormat)).append("</td><td")
                    .append(TD).append(">").append(esc(t.sipTrunkId)).append("</td><td")
                    .append(TD).append(">").append(esc(t.smppSystemId)).append("</td><td")
                    .append(TD).append(">").append(esc(maskKey(t.httpApiKey))).append("</td><td")
                    .append(TD).append(">").append(t.maxTps).append("</td><td")
                    .append(TD).append(">").append(t.enabled).append("</td><td").append(TD).append(">");
            sb.append("<form hx-post=\"/admin/tenants\" hx-target=\"#tenant-rows\" hx-swap=\"innerHTML\" class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"tenantId\" value=\"").append(esc(t.tenantId)).append("\"/>")
                    .append("<button type=\"submit\" class=\"").append(DEL_BTN).append("\">Del</button></form></td></tr>");
        }
        return sb.toString();
    }

    String userRowsHtml() {
        StringBuilder sb = new StringBuilder();
        var list = users.list();
        if (list.isEmpty()) {
            sb.append("<tr><td colspan=\"6\" class=\"px-3 py-4 text-ink-mute italic\">No users.</td></tr>");
            return sb.toString();
        }
        for (AdminUserEntity u : list) {
            sb.append("<tr><td").append(TD).append(">").append(esc(u.username)).append("</td><td")
                    .append(TD).append(">").append(esc(u.role)).append("</td><td")
                    .append(TD).append(">").append(esc(u.tenantId)).append("</td><td")
                    .append(TD).append(">").append(esc(u.displayName)).append("</td><td")
                    .append(TD).append(">").append(u.enabled).append("</td><td").append(TD).append(">");
            sb.append("<form hx-post=\"/admin/users\" hx-target=\"#user-rows\" hx-swap=\"innerHTML\" class=\"inline\">")
                    .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                    .append("<input type=\"hidden\" name=\"username\" value=\"").append(esc(u.username)).append("\"/>")
                    .append("<button type=\"submit\" class=\"").append(DEL_BTN).append("\">Del</button></form></td></tr>");
        }
        return sb.toString();
    }

    private String tenantSelectHtml(String selectedTenantId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div><label class=\"block text-xs uppercase tracking-wider text-ink-mute\">tenantId</label>")
                .append("<select name=\"tenantId\" class=\"").append(SELECT_CLS).append("\">")
                .append("<option value=\"\">— unbound —</option>");
        List<TenantEntity> list = safeTenantList();
        for (TenantEntity t : list) {
            if (t == null || t.tenantId == null || t.tenantId.isBlank()) {
                continue;
            }
            boolean sel = t.tenantId.equals(selectedTenantId);
            sb.append("<option value=\"").append(esc(t.tenantId)).append("\"")
                    .append(sel ? " selected" : "").append(">")
                    .append(esc(t.tenantId));
            if (t.displayName != null && !t.displayName.isBlank()
                    && !t.displayName.equals(t.tenantId)) {
                sb.append(" — ").append(esc(t.displayName));
            }
            sb.append("</option>");
        }
        sb.append("</select>")
                .append("<p class=\"mt-1 text-xs text-ink-mute\">From Tenants admin. Default: Digicom seed when present.</p>")
                .append("</div>");
        return sb.toString();
    }

    private String appUserSelectHtml(AdminAuthService.Principal who, String defaultTenantId) {
        return appUserSelectHtml(who, defaultTenantId, null);
    }

    private String appUserSelectHtml(AdminAuthService.Principal who, String defaultTenantId,
                                     String preferredUsername) {
        String scope = who != null && who.isTenantScoped() ? who.tenantId() : null;
        List<AppUserEntity> list = safeAppUserList(scope);
        String preferred = preferredUsername != null && !preferredUsername.isBlank()
                ? preferredUsername.trim()
                : resolveDefaultAppUsername(list, defaultTenantId);
        StringBuilder sb = new StringBuilder();
        sb.append("<div><label class=\"block text-xs uppercase tracking-wider text-ink-mute\">")
                .append("appUsername (App users)</label>")
                .append("<select name=\"appUsername\" class=\"").append(SELECT_CLS).append("\">")
                .append("<option value=\"\">— none (shared rule) —</option>");
        for (AppUserEntity u : list) {
            if (u == null || u.username == null || u.username.isBlank()) {
                continue;
            }
            boolean sel = u.username.equals(preferred);
            sb.append("<option value=\"").append(esc(u.username)).append("\"")
                    .append(sel ? " selected" : "").append(">")
                    .append(esc(u.username));
            if (u.tenantId != null && !u.tenantId.isBlank()) {
                sb.append(" @ ").append(esc(u.tenantId));
            }
            if (!u.enabled) {
                sb.append(" (disabled)");
            }
            sb.append("</option>");
        }
        sb.append("</select>")
                .append("<p class=\"mt-1 text-xs text-ink-mute\">")
                .append("Optional ownership from <strong>App users</strong> (<code class=\"font-mono\">ussd_app_user</code>) — ")
                .append("not portal Users. NI key / MAP2MAP rule binding.")
                .append("</p></div>");
        return sb.toString();
    }

    /** Prefer Digicom live seed {@code digicom-push}, then alias {@code digicom-et}, else first. */
    String resolveDefaultTenantId(AdminAuthService.Principal who) {
        if (who != null && who.isTenantScoped()) {
            return who.tenantId() == null ? "" : who.tenantId();
        }
        List<TenantEntity> list = safeTenantList();
        if (list.isEmpty()) {
            return "";
        }
        for (String prefer : List.of(PREFERRED_TENANT_ID, ALIAS_TENANT_ID)) {
            for (TenantEntity t : list) {
                if (t != null && prefer.equals(t.tenantId) && t.enabled) {
                    return prefer;
                }
            }
        }
        for (TenantEntity t : list) {
            if (t != null && t.enabled && t.tenantId != null && !t.tenantId.isBlank()) {
                return t.tenantId;
            }
        }
        TenantEntity first = list.get(0);
        return first == null || first.tenantId == null ? "" : first.tenantId;
    }

    String resolveDefaultAppUsername(List<AppUserEntity> list, String tenantId) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        List<AppUserEntity> scoped = new ArrayList<>();
        for (AppUserEntity u : list) {
            if (u == null || !u.enabled || u.username == null || u.username.isBlank()) {
                continue;
            }
            if (tenantId == null || tenantId.isBlank() || tenantId.equals(u.tenantId)) {
                scoped.add(u);
            }
        }
        List<AppUserEntity> pool = scoped.isEmpty() ? list : scoped;
        for (AppUserEntity u : pool) {
            if (u != null && PREFERRED_APP_USERNAME.equals(u.username) && u.enabled) {
                return u.username;
            }
        }
        for (AppUserEntity u : pool) {
            if (u != null && u.enabled && u.username != null && !u.username.isBlank()) {
                return u.username;
            }
        }
        return "";
    }

    private Optional<String> validateAppUsername(String appUsername, String tenantId,
                                                   AdminAuthService.Principal who) {
        if (appUsers == null) {
            return Optional.empty();
        }
        Optional<AppUserEntity> found = appUsers.byUsername(appUsername);
        if (found.isEmpty()) {
            return Optional.of("unknown appUsername (create under App users): " + appUsername);
        }
        AppUserEntity u = found.get();
        if (!u.enabled) {
            return Optional.of("appUsername disabled: " + appUsername);
        }
        if (who != null && who.isTenantScoped()
                && !who.tenantId().equals(u.tenantId)) {
            return Optional.of("forbidden — appUsername not in your tenant");
        }
        if (tenantId != null && !tenantId.isBlank()
                && u.tenantId != null && !tenantId.equals(u.tenantId)) {
            return Optional.of("appUsername " + appUsername + " belongs to tenant "
                    + u.tenantId + ", not " + tenantId);
        }
        return Optional.empty();
    }

    private List<TenantEntity> safeTenantList() {
        if (tenants == null) {
            return List.of();
        }
        try {
            List<TenantEntity> list = tenants.list();
            return list == null ? List.of() : list;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<AppUserEntity> safeAppUserList(String tenantScope) {
        if (appUsers == null) {
            return List.of();
        }
        try {
            List<AppUserEntity> list = appUsers.list(tenantScope);
            return list == null ? List.of() : list;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private String tenantOptionsHtml() {
        StringBuilder sb = new StringBuilder("<option value=\"\">—</option>");
        for (TenantEntity t : safeTenantList()) {
            sb.append("<option value=\"").append(esc(t.tenantId)).append("\">")
                    .append(esc(t.tenantId)).append("</option>");
        }
        return sb.toString();
    }

    private AdminHttpHandler.HttpReply routingRowsOk(AdminAuthService.Principal who, String message) {
        return AdminHttpHandler.HttpReply.html(routingRowsHtml(who))
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, "ok", "/admin/routing/partial", "#rule-rows"))
                .withHeader("Vary", "HX-Request");
    }

    private AdminHttpHandler.HttpReply routingRowsErr(AdminAuthService.Principal who, String message) {
        return AdminHttpHandler.HttpReply.html(routingRowsHtml(who))
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, "error", "/admin/routing/partial", "#rule-rows"))
                .withHeader("Vary", "HX-Request");
    }

    private AdminHttpHandler.HttpReply tenantRowsOk(String message) {
        return AdminHttpHandler.HttpReply.html(tenantRowsHtml())
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, "ok", "/admin/tenants/partial", "#tenant-rows"))
                .withHeader("Vary", "HX-Request");
    }

    private AdminHttpHandler.HttpReply tenantRowsErr(String message) {
        return AdminHttpHandler.HttpReply.html(tenantRowsHtml())
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, "error", "/admin/tenants/partial", "#tenant-rows"))
                .withHeader("Vary", "HX-Request");
    }

    private AdminHttpHandler.HttpReply userRowsOk(String message) {
        return AdminHttpHandler.HttpReply.html(userRowsHtml())
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, "ok", "/admin/users/partial", "#user-rows"))
                .withHeader("Vary", "HX-Request");
    }

    private AdminHttpHandler.HttpReply userRowsErr(String message) {
        return AdminHttpHandler.HttpReply.html(userRowsHtml())
                .withHeader("HX-Trigger", AdminHtmx.triggerToast(
                        message, "error", "/admin/users/partial", "#user-rows"))
                .withHeader("Vary", "HX-Request");
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return m;
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    /** Tenant AS wire for Live rules table; unbound → dash (global props apply). */
    private String tenantWireLabel(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || tenants == null) {
            return "—";
        }
        return tenants.byId(tenantId)
                .map(t -> TenantService.normalizeHttpAsWireFormat(t.httpAsWireFormat))
                .orElse("—");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
