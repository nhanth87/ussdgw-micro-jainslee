package et.restlink.ussdgw.api;

import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.tenant.TenantService;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WireFormatResolverTest {
    @Test
    void defaultsToXmlWhenNoTenantAndBlankGlobal() throws Exception {
        WireFormatResolver resolver = new WireFormatResolver();
        set(resolver, "tenants", new StubTenants(null));
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "asHttpWireFormatProp", "");
        set(resolver, "config", cfg);
        assertThat(resolver.resolve(null)).isEqualTo(AsHttpWireFormat.XML);
        assertThat(resolver.resolve("missing")).isEqualTo(AsHttpWireFormat.XML);
    }

    @Test
    void usesGlobalConfigWhenTenantUnset() throws Exception {
        WireFormatResolver resolver = new WireFormatResolver();
        set(resolver, "tenants", new StubTenants(null));
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "asHttpWireFormatProp", "JSON");
        set(resolver, "config", cfg);
        assertThat(resolver.resolve("any")).isEqualTo(AsHttpWireFormat.JSON);
    }

    @Test
    void tenantOverrideBeatsGlobal() throws Exception {
        TenantEntity tenant = new TenantEntity();
        tenant.tenantId = "t1";
        tenant.httpAsWireFormat = "xml";
        WireFormatResolver resolver = new WireFormatResolver();
        set(resolver, "tenants", new StubTenants(tenant));
        UssdConfigService cfg = new UssdConfigService();
        set(cfg, "asHttpWireFormatProp", "JSON");
        set(resolver, "config", cfg);
        assertThat(resolver.resolve("t1")).isEqualTo(AsHttpWireFormat.XML);

        tenant.httpAsWireFormat = "JSON";
        assertThat(resolver.resolve("t1")).isEqualTo(AsHttpWireFormat.JSON);
    }

    @Test
    void httpAsWireFormatOfReadsFieldDirectly() {
        TenantEntity e = new TenantEntity();
        e.httpAsWireFormat = "JSON";
        assertThat(WireFormatResolver.httpAsWireFormatOf(e)).isEqualTo("JSON");
        assertThat(WireFormatResolver.httpAsWireFormatOf(null)).isNull();
    }

    static final class StubTenants extends TenantService {
        private final TenantEntity entity;

        StubTenants(TenantEntity entity) {
            this.entity = entity;
        }

        @Override
        public Optional<TenantEntity> byId(String tenantId) {
            if (entity == null) {
                return Optional.empty();
            }
            return Optional.of(entity);
        }
    }

    private static void set(Object target, String field, Object value) throws Exception {
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
    }
}
