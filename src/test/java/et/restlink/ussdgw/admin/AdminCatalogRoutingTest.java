package et.restlink.ussdgw.admin;

import et.restlink.ussdgw.persist.AppUserEntity;
import et.restlink.ussdgw.persist.SipTrunkEntity;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.routing.RuleType;
import et.restlink.ussdgw.routing.ShortCodeRule;
import et.restlink.ussdgw.routing.ShortCodeRoutingService;
import et.restlink.ussdgw.sip.SipTrunkService;
import et.restlink.ussdgw.tenant.TenantService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCatalogRoutingTest {
    private AdminCatalogHandler catalog;
    private MemoryRouting routing;

    @BeforeEach
    void setUp() {
        catalog = new AdminCatalogHandler();
        routing = new MemoryRouting();
        set(catalog, "routing", routing);
        set(catalog, "tenants", new TenantService() {
            @Override
            public java.util.List<et.restlink.ussdgw.persist.TenantEntity> list() {
                return java.util.List.of();
            }

            @Override
            public java.util.Optional<et.restlink.ussdgw.persist.TenantEntity> byId(String tenantId) {
                return java.util.Optional.empty();
            }
        });
    }

    @Test
    void saveAppearsInGetAndIsLive() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A999%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fpull&enabled=true",
                null);
        assertThat(new String(saved.body())).contains("*999#").contains("http://as/pull");
        String hx = saved.headers().get("HX-Trigger");
        assertThat(hx).contains("saved").contains("live");
        assertThat(hx).doesNotContain("\u2014");
        assertThat(hx).contains("ussdCatalogChanged").contains("/admin/routing/partial").contains("#rule-rows");
        assertThat(saved.headers().get("Vary")).isEqualTo("HX-Request");
        assertThat(routing.find("*999#")).isPresent();

        AdminHttpHandler.HttpReply get = catalog.routingGet(null);
        assertThat(new String(get.body())).contains("*999#").contains("http://as/pull");
    }

    @Test
    void saveMarkAppearsAndRoutesPrefix() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A100%2A&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fmark"
                        + "&enabled=true&mark=true",
                null);
        assertThat(new String(saved.body())).contains("*100*").contains("true");
        assertThat(routing.find("*100*123456#")).isPresent()
                .get().extracting(ShortCodeRule::asUrl).isEqualTo("http://as/mark");
        assertThat(routing.find("*100*123456#").get().mark()).isTrue();
    }

    @Test
    void saveRerouteEnableAndRedirectUssd() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fuserinfo"
                        + "&enabled=true&rerouteEnable=true&redirectUssd=%2A8744%23&hlrMode=FAKE",
                null);
        assertThat(saved.headers().get("HX-Trigger")).contains("saved");
        ShortCodeRule r = routing.find("*804#").orElseThrow();
        assertThat(r.rerouteEnable()).isTrue();
        assertThat(r.map2mapArmed()).isTrue();
        assertThat(r.redirectUssdString()).isEqualTo("*8744#");
        assertThat(r.hlrMode()).isEqualTo("FAKE");
        assertThat(r.bypass()).isFalse();
        assertThat(r.fixedHopArmed()).isFalse();
        assertThat(new String(saved.body())).contains("true").contains("*8744#").contains("FAKE");
    }

    @Test
    void saveTypeReRouteImpliesRerouteAndStoresAsPullPlaneHttp() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fuserinfo"
                        + "&enabled=true&redirectUssd=%2A875%23",
                null);
        assertThat(saved.headers().get("HX-Trigger")).contains("saved");
        ShortCodeRule r = routing.find("*804#").orElseThrow();
        // jainslee: RE_ROUTE form → persist HTTP|GRPC|SIP + reroute (default HTTP)
        assertThat(r.ruleType()).isEqualTo(RuleType.HTTP);
        assertThat(r.asPullType()).isEqualTo(RuleType.HTTP);
        assertThat(r.rerouteEnable()).isTrue();
        assertThat(r.map2mapArmed()).isTrue();
        assertThat(r.redirectUssdString()).isEqualTo("*875#");
        assertThat(r.ruleType().usesHttpAsPull()).isTrue();
        assertThat(new String(saved.body())).contains("RE_ROUTE/HTTP").contains("*875#");
    }

    @Test
    void saveTypeReRouteWithGrpcAsPullPlane() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A807%23&ruleType=RE_ROUTE&asPullType=GRPC"
                        + "&asUrl=127.0.0.1%3A9000%7Cet.restlink.ussdgw.as.UssdAs%2FPull"
                        + "&enabled=true&redirectUssd=%2A875%23",
                null);
        assertThat(saved.headers().get("HX-Trigger")).contains("saved");
        ShortCodeRule r = routing.find("*807#").orElseThrow();
        assertThat(r.ruleType()).isEqualTo(RuleType.GRPC);
        assertThat(r.asPullType()).isEqualTo(RuleType.GRPC);
        assertThat(r.rerouteEnable()).isTrue();
        assertThat(r.map2mapArmed()).isTrue();
        assertThat(new String(saved.body())).contains("RE_ROUTE/GRPC");
    }

    @Test
    void saveTypeReRouteAliasReDashRoute() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A806%23&ruleType=re-route&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&redirectUssd=%2A875%23&hopDestGt=251971200201",
                null);
        assertThat(saved.headers().get("HX-Trigger")).contains("saved");
        ShortCodeRule r = routing.find("*806#").orElseThrow();
        assertThat(r.ruleType()).isEqualTo(RuleType.HTTP);
        assertThat(r.rerouteEnable()).isTrue();
        assertThat(r.hopDestGt()).isEqualTo("251971200201");
    }

    @Test
    void saveTypeReRouteWithoutRedirectRejected() {
        AdminHttpHandler.HttpReply r = catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true",
                null);
        assertThat(r.headers().get("HX-Trigger")).contains("error");
        assertThat(routing.find("*804#")).isEmpty();
    }

    @Test
    void routingPageVarsSeedUpperHlrGtPlaceholder() {
        set(catalog, "config", new et.restlink.ussdgw.config.UssdConfigService() {
            @Override
            public String hlrUpperGt() {
                return "251971200201";
            }
        });
        Map<String, String> vars = catalog.routingPageVars(null);
        assertThat(vars.get("{{UPPER_HLR_GT}}")).isEqualTo("251971200201");
        assertThat(vars.get("{{UPPER_HLR_GT_PLACEHOLDER}}")).isEqualTo("blank → 251971200201 (HLR Face)");
        assertThat(vars.get("{{FORM_RULE_TYPE}}")).isEqualTo("HTTP");
        assertThat(vars.get("{{FORM_HOP_DEST_GT}}")).isEqualTo("");
    }

    @Test
    void routingPageVarsUpperHlrFallbackWhenConfigAbsent() {
        Map<String, String> vars = catalog.routingPageVars(null);
        assertThat(vars.get("{{UPPER_HLR_GT}}")).isEqualTo("");
        assertThat(vars.get("{{UPPER_HLR_GT_PLACEHOLDER}}"))
                .isEqualTo("blank = HLR Face upper-gt (ussd.hlr.upper-gt)");
    }

    @Test
    void saveBlankHopOnUpdatePreservesExistingHopDest() {
        catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fold"
                        + "&enabled=true&redirectUssd=%2A875%23"
                        + "&hopDestGt=251971200201&hopDestSsn=6",
                new AdminAuthService.Principal("ADMIN", null));
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fnew"
                        + "&enabled=true&redirectUssd=%2A875%23",
                new AdminAuthService.Principal("ADMIN", null));
        assertThat(saved.headers().get("HX-Trigger")).contains("saved");
        ShortCodeRule r = routing.byKey("*804#", null).orElseThrow();
        assertThat(r.asUrl()).isEqualTo("http://as/new");
        assertThat(r.hopDestGt()).isEqualTo("251971200201");
        assertThat(r.hopDestSsn()).isEqualTo(6);
    }

    @Test
    void saveHopDestClearWipesFixedHop() {
        catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&redirectUssd=%2A875%23"
                        + "&hopDestGt=251971200201&hopDestSsn=6",
                new AdminAuthService.Principal("ADMIN", null));
        catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&redirectUssd=%2A875%23&hopDestClear=true",
                new AdminAuthService.Principal("ADMIN", null));
        ShortCodeRule r = routing.byKey("*804#", null).orElseThrow();
        assertThat(r.hopDestGt()).isNull();
        assertThat(r.hopDestSsn()).isNull();
        assertThat(r.fixedHopArmed()).isFalse();
    }

    @Test
    void tenantCannotChangeHopDestOnUpdate() {
        catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&redirectUssd=%2A875%23&tenantId=digicom-push"
                        + "&hopDestGt=251971200201&hopDestSsn=6",
                new AdminAuthService.Principal("ADMIN", null));
        catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fy"
                        + "&enabled=true&redirectUssd=%2A875%23&tenantId=digicom-push"
                        + "&hopDestGt=999&hopDestSsn=8",
                new AdminAuthService.Principal("TENANT", "digicom-push"));
        ShortCodeRule r = routing.byKey("*804#", null).orElseThrow();
        assertThat(r.asUrl()).isEqualTo("http://as/y");
        assertThat(r.hopDestGt()).isEqualTo("251971200201");
        assertThat(r.hopDestSsn()).isEqualTo(6);
    }

    @Test
    void editQuerySeedsHopDestIntoFormVars() {
        catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=RE_ROUTE&asUrl=http%3A%2F%2Fas%2Fsp"
                        + "&enabled=true&redirectUssd=%2A875%23"
                        + "&hopDestGt=251971200201&hopDestSsn=6",
                new AdminAuthService.Principal("ADMIN", null));
        Map<String, String> vars = catalog.routingPageVars(
                new AdminAuthService.Principal("ADMIN", null),
                Map.of("edit", "*804#"));
        assertThat(vars.get("{{FORM_RULE_TYPE}}")).isEqualTo("RE_ROUTE");
        assertThat(vars.get("{{FORM_HOP_DEST_GT}}")).isEqualTo("251971200201");
        assertThat(vars.get("{{FORM_HOP_DEST_SSN}}")).isEqualTo("6");
        assertThat(vars.get("{{FORM_REDIRECT_USSD}}")).isEqualTo("*875#");
        assertThat(vars.get("{{FORM_EDIT_BANNER}}")).contains("*804#");
        assertThat(vars.get("{{HOP_CLEAR_FIELD}}")).contains("hopDestClear");
        String rows = new String(catalog.routingGet(null).body());
        assertThat(rows).contains("Edit").contains("Del");
        assertThat(rows).contains("bg-red-600").contains("bg-amber-400");
        assertThat(rows).contains("flex flex-row flex-nowrap");
    }

    @Test
    void saveFixedHopDestGtAndSsn() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fsp"
                        + "&enabled=true&rerouteEnable=true&redirectUssd=%2A875%23"
                        + "&hopDestGt=251971200201&hopDestSsn=6",
                null);
        assertThat(saved.headers().get("HX-Trigger")).contains("saved");
        ShortCodeRule r = routing.find("*804#").orElseThrow();
        assertThat(r.map2mapArmed()).isTrue();
        assertThat(r.fixedHopArmed()).isTrue();
        assertThat(r.redirectUssdString()).isEqualTo("*875#");
        assertThat(r.hopDestGt()).isEqualTo("251971200201");
        assertThat(r.hopDestSsn()).isEqualTo(6);
        assertThat(new String(saved.body())).contains("251971200201").contains("*875#");
    }

    @Test
    void saveHopDestSsnAloneWithRerouteUsesUpperGtSsn() {
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&rerouteEnable=true&redirectUssd=%2A875%23&hopDestSsn=6",
                null);
        assertThat(saved.headers().get("HX-Trigger")).contains("saved");
        ShortCodeRule r = routing.find("*804#").orElseThrow();
        assertThat(r.map2mapArmed()).isTrue();
        assertThat(r.fixedHopArmed()).isFalse();
        assertThat(r.hopDestSsn()).isEqualTo(6);
    }

    @Test
    void saveHopDestSsnWithoutGtRejectedWhenRerouteOff() {
        AdminHttpHandler.HttpReply r = catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&rerouteEnable=false&hopDestSsn=6",
                null);
        assertThat(r.headers().get("HX-Trigger")).contains("error");
        assertThat(routing.find("*804#")).isEmpty();
    }

    @Test
    void saveRerouteWithoutRedirectRejected() {
        AdminHttpHandler.HttpReply r = catalog.routingPost(
                "action=save&shortCode=%2A804%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&rerouteEnable=true",
                null);
        assertThat(r.headers().get("HX-Trigger")).contains("error");
        assertThat(routing.find("*804#")).isEmpty();
    }

    @Test
    void deleteRemovesFromLiveMap() {
        routing.putAndPersist(new ShortCodeRule("*888#", RuleType.HTTP, "http://x", true));
        AdminHttpHandler.HttpReply del = catalog.routingPost(
                "action=delete&shortCode=%2A888%23", null);
        assertThat(del.headers().get("HX-Trigger")).contains("deleted");
        assertThat(routing.find("*888#")).isEmpty();
        assertThat(new String(catalog.routingGet(null).body())).doesNotContain("*888#");
    }

    @Test
    void reloadCallsReloadFromDb() {
        routing.putAndPersist(new ShortCodeRule("*777#", RuleType.GRPC, "127.0.0.1:9|m", true));
        AdminHttpHandler.HttpReply r = catalog.routingPost("action=reload", null);
        assertThat(routing.reloadCalls.get()).isEqualTo(1);
        assertThat(r.headers().get("HX-Trigger")).contains("reloaded").contains("live");
        assertThat(new String(r.body())).contains("*777#");
    }

    @Test
    void saveHttpAsWireJsonUpdatesTenantAndAppearsInRows() {
        TenantEntity bank = new TenantEntity();
        bank.tenantId = "bank1";
        bank.httpAsWireFormat = "XML";
        bank.enabled = true;
        AtomicReference<String> lastWire = new AtomicReference<>("XML");
        set(catalog, "tenants", new TenantService() {
            @Override
            public List<TenantEntity> list() {
                return List.of(bank);
            }

            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                return "bank1".equals(tenantId) ? Optional.of(bank) : Optional.empty();
            }

            @Override
            public Optional<TenantEntity> updateHttpAsWireFormat(String tenantId, String httpAsWireFormat) {
                if (!"bank1".equals(tenantId)) {
                    return Optional.empty();
                }
                bank.httpAsWireFormat = TenantService.normalizeHttpAsWireFormat(httpAsWireFormat);
                lastWire.set(bank.httpAsWireFormat);
                return Optional.of(bank);
            }
        });
        AdminHttpHandler.HttpReply saved = catalog.routingPost(
                "action=save&shortCode=%2A901%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fjson"
                        + "&enabled=true&tenantId=bank1&httpAsWireFormat=JSON",
                null);
        assertThat(saved.headers().get("HX-Trigger")).contains("wire=JSON").contains("live");
        assertThat(lastWire.get()).isEqualTo("JSON");
        assertThat(routing.find("*901#")).isPresent()
                .get().extracting(ShortCodeRule::tenantId).isEqualTo("bank1");
        assertThat(new String(saved.body())).contains("JSON");
    }

    @Test
    void saveHttpAsWireJsonWithoutTenantRejected() {
        AdminHttpHandler.HttpReply r = catalog.routingPost(
                "action=save&shortCode=%2A902%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&httpAsWireFormat=JSON",
                null);
        assertThat(r.headers().get("HX-Trigger")).contains("tenantId required");
        assertThat(routing.find("*902#")).isEmpty();
    }

    @Test
    void routingPageVarsSeedHttpAsWireFromTenant() {
        TenantEntity digicom = new TenantEntity();
        digicom.tenantId = "digicom-push";
        digicom.httpAsWireFormat = "JSON";
        digicom.enabled = true;
        set(catalog, "tenants", new TenantService() {
            @Override
            public List<TenantEntity> list() {
                return List.of(digicom);
            }

            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                return "digicom-push".equals(tenantId) ? Optional.of(digicom) : Optional.empty();
            }
        });
        Map<String, String> vars = catalog.routingPageVars(
                new AdminAuthService.Principal("ADMIN", null));
        assertThat(vars.get("{{FORM_WIRE_JSON_SEL}}")).isEqualTo(" selected");
        assertThat(vars.get("{{FORM_WIRE_XML_SEL}}")).isEqualTo("");
    }

    @Test
    void tenantsPostPassesSipTrunkIdToUpsert() {
        AtomicReference<String> capturedTrunk = new AtomicReference<>();
        set(catalog, "tenants", new TenantService() {
            @Override
            public List<TenantEntity> list() {
                return List.of();
            }

            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                return Optional.empty();
            }

            @Override
            public TenantEntity upsert(String tenantId, String displayName, int networkId,
                                       boolean enabled, String httpApiKey, String smppSystemId,
                                       String smppPasswordOrBlank, String asCallbackBase, int maxTps,
                                       String httpAsWireFormat, String sipTrunkId) {
                capturedTrunk.set(sipTrunkId);
                TenantEntity e = new TenantEntity();
                e.tenantId = tenantId;
                e.networkId = networkId;
                e.httpAsWireFormat = httpAsWireFormat == null ? "XML" : httpAsWireFormat;
                e.sipTrunkId = sipTrunkId;
                e.httpApiKey = "ussd_test";
                return e;
            }
        });
        AdminHttpHandler.HttpReply r = catalog.tenantsPost(
                "action=save&tenantId=bank1&displayName=Bank&networkId=1&enabled=true"
                        + "&httpAsWireFormat=XML&sipTrunkId=trunk-as1&maxTps=50",
                new AdminAuthService.Principal("ADMIN", null));
        assertThat(r.status()).isEqualTo(200);
        assertThat(capturedTrunk.get()).isEqualTo("trunk-as1");
        assertThat(r.headers().get("HX-Trigger")).contains("sipTrunk=trunk-as1");
    }

    @Test
    void sipRouteRejectsCrossTenantTrunk() {
        SipTrunkEntity foreign = new SipTrunkEntity();
        foreign.trunkId = "bank2-trunk";
        foreign.enabled = true;
        foreign.tenantId = "bank2";
        set(catalog, "sipTrunkService", new SipTrunkService() {
            @Override
            public Optional<SipTrunkEntity> byId(String trunkId) {
                return "bank2-trunk".equals(trunkId) ? Optional.of(foreign) : Optional.empty();
            }
        });
        AdminAuthService.Principal tenant = new AdminAuthService.Principal("TENANT", "bank1");
        AdminHttpHandler.HttpReply r = catalog.routingPost(
                "action=save&shortCode=%2A9%23&ruleType=SIP&asUrl=bank2-trunk&enabled=true",
                tenant);
        assertThat(r.headers().get("HX-Trigger")).contains("does not allow tenant");
        assertThat(routing.find("*9#")).isEmpty();
    }

    @Test
    void routingPageVarsUseTenantAndAppUserSelectsWithDigicomDefaults() {
        TenantEntity digicom = new TenantEntity();
        digicom.tenantId = "digicom-push";
        digicom.displayName = "Digicom NI Push";
        digicom.enabled = true;
        digicom.networkId = 0;
        TenantEntity other = new TenantEntity();
        other.tenantId = "bank1";
        other.displayName = "Bank";
        other.enabled = true;
        set(catalog, "tenants", new TenantService() {
            @Override
            public List<TenantEntity> list() {
                return List.of(other, digicom);
            }

            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                return list().stream().filter(t -> t.tenantId.equals(tenantId)).findFirst();
            }
        });
        AppUserEntity ni = new AppUserEntity();
        ni.username = "ni-push";
        ni.tenantId = "digicom-push";
        ni.enabled = true;
        AppUserEntity bankApp = new AppUserEntity();
        bankApp.username = "bank-app-a";
        bankApp.tenantId = "bank1";
        bankApp.enabled = true;
        set(catalog, "appUsers", new et.restlink.ussdgw.tenant.AppUserService() {
            @Override
            public List<et.restlink.ussdgw.persist.AppUserEntity> list(String tenantScope) {
                if (tenantScope == null || tenantScope.isBlank()) {
                    return List.of(ni, bankApp);
                }
                return List.of(ni, bankApp).stream()
                        .filter(u -> tenantScope.equals(u.tenantId)).toList();
            }

            @Override
            public Optional<et.restlink.ussdgw.persist.AppUserEntity> byUsername(String username) {
                return List.of(ni, bankApp).stream()
                        .filter(u -> u.username.equals(username)).findFirst();
            }
        });

        Map<String, String> vars = catalog.routingPageVars(new AdminAuthService.Principal("ADMIN", null));
        assertThat(vars.get("{{TENANT_FIELD}}"))
                .contains("<select name=\"tenantId\"")
                .contains("value=\"digicom-push\" selected")
                .contains("value=\"bank1\"")
                .doesNotContain("list=\"tenant-ids\"");
        assertThat(vars.get("{{APP_USER_FIELD}}"))
                .contains("<select name=\"appUsername\"")
                .contains("value=\"ni-push\" selected")
                .contains("bank-app-a")
                .contains("App users");
        assertThat(catalog.resolveDefaultTenantId(null)).isEqualTo("digicom-push");
    }

    @Test
    void tenantScopedRoutingLocksTenantAndFiltersAppUsers() {
        TenantEntity digicom = new TenantEntity();
        digicom.tenantId = "digicom-push";
        digicom.enabled = true;
        set(catalog, "tenants", new TenantService() {
            @Override
            public List<TenantEntity> list() {
                return List.of(digicom);
            }

            @Override
            public Optional<TenantEntity> byId(String tenantId) {
                return Optional.of(digicom);
            }
        });
        AppUserEntity ni = new AppUserEntity();
        ni.username = "ni-push";
        ni.tenantId = "digicom-push";
        ni.enabled = true;
        AppUserEntity foreign = new AppUserEntity();
        foreign.username = "other-app";
        foreign.tenantId = "bank1";
        foreign.enabled = true;
        set(catalog, "appUsers", new et.restlink.ussdgw.tenant.AppUserService() {
            @Override
            public List<et.restlink.ussdgw.persist.AppUserEntity> list(String tenantScope) {
                if ("digicom-push".equals(tenantScope)) {
                    return List.of(ni);
                }
                return List.of(ni, foreign);
            }

            @Override
            public Optional<et.restlink.ussdgw.persist.AppUserEntity> byUsername(String username) {
                if ("ni-push".equals(username)) return Optional.of(ni);
                if ("other-app".equals(username)) return Optional.of(foreign);
                return Optional.empty();
            }
        });
        AdminAuthService.Principal tenant = new AdminAuthService.Principal("TENANT", "digicom-push");
        Map<String, String> vars = catalog.routingPageVars(tenant);
        assertThat(vars.get("{{TENANT_FIELD}}"))
                .contains("type=\"hidden\"")
                .contains("value=\"digicom-push\"")
                .doesNotContain("<select name=\"tenantId\"");
        assertThat(vars.get("{{APP_USER_FIELD}}"))
                .contains("ni-push")
                .doesNotContain("other-app");

        AdminHttpHandler.HttpReply rejected = catalog.routingPost(
                "action=save&shortCode=%2A9%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&appUsername=other-app",
                tenant);
        assertThat(rejected.headers().get("HX-Trigger")).contains("forbidden");
        assertThat(routing.find("*9#")).isEmpty();

        AdminHttpHandler.HttpReply ok = catalog.routingPost(
                "action=save&shortCode=%2A9%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&tenantId=ignored&appUsername=ni-push",
                tenant);
        assertThat(ok.headers().get("HX-Trigger")).contains("saved");
        ShortCodeRule r = routing.find("*9#").orElseThrow();
        assertThat(r.tenantId()).isEqualTo("digicom-push");
        assertThat(r.appUsername()).isEqualTo("ni-push");
    }

    @Test
    void saveRejectsUnknownAppUsername() {
        set(catalog, "appUsers", new et.restlink.ussdgw.tenant.AppUserService() {
            @Override
            public Optional<et.restlink.ussdgw.persist.AppUserEntity> byUsername(String username) {
                return Optional.empty();
            }
        });
        AdminHttpHandler.HttpReply r = catalog.routingPost(
                "action=save&shortCode=%2A9%23&ruleType=HTTP&asUrl=http%3A%2F%2Fas%2Fx"
                        + "&enabled=true&tenantId=digicom-push&appUsername=missing",
                new AdminAuthService.Principal("ADMIN", null));
        assertThat(r.headers().get("HX-Trigger")).contains("unknown appUsername");
        assertThat(routing.find("*9#")).isEmpty();
    }


    static final class MemoryRouting extends ShortCodeRoutingService {
        final ConcurrentHashMap<String, ShortCodeRule> map = new ConcurrentHashMap<>();
        final AtomicInteger reloadCalls = new AtomicInteger();

        @Override
        public void reloadFromDb() {
            reloadCalls.incrementAndGet();
        }

        @Override
        public Optional<ShortCodeRule> byKey(String shortCode, String appUsername) {
            return Optional.ofNullable(map.get(memKey(shortCode, appUsername)));
        }

        @Override
        public void putAndPersist(ShortCodeRule rule) {
            map.put(memKey(rule.shortCode(), rule.appUsername()), rule);
        }

        @Override
        public boolean delete(String shortCode) {
            return delete(shortCode, null);
        }

        @Override
        public boolean delete(String shortCode, String appUsername) {
            return map.remove(memKey(shortCode, appUsername)) != null;
        }

        @Override
        public Optional<ShortCodeRule> find(String shortCode) {
            String sc = shortCode == null ? "" : shortCode.trim();
            ShortCodeRule exact = null;
            for (ShortCodeRule r : map.values()) {
                if (!r.enabled() || r.mark()) continue;
                if (sc.equalsIgnoreCase(r.shortCode() == null ? "" : r.shortCode())) {
                    if (r.appUsername() == null || r.appUsername().isBlank()) {
                        return Optional.of(r);
                    }
                    if (exact == null) exact = r;
                }
            }
            if (exact != null) return Optional.of(exact);
            ShortCodeRule best = null;
            int bestLen = -1;
            for (ShortCodeRule r : map.values()) {
                if (!r.enabled() || !r.mark()) continue;
                String prefix = r.shortCode() == null ? "" : r.shortCode();
                if (!prefix.isEmpty() && sc.startsWith(prefix) && prefix.length() > bestLen) {
                    best = r;
                    bestLen = prefix.length();
                }
            }
            return Optional.ofNullable(best);
        }

        @Override
        public Collection<ShortCodeRule> list() {
            return map.values();
        }

        @Override
        public Collection<ShortCodeRule> listForTenant(String tenantId) {
            if (tenantId == null || tenantId.isBlank()) return list();
            return map.values().stream().filter(r -> tenantId.equals(r.tenantId())).toList();
        }

        private static String memKey(String shortCode, String appUsername) {
            String sc = shortCode == null ? "" : shortCode.trim().toLowerCase();
            String app = appUsername == null || appUsername.isBlank() ? null : appUsername.trim();
            return app == null ? sc : sc + "\t" + app;
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    var f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(field);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
