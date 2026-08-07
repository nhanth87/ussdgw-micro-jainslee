package et.restlink.ussdgw.api;

import et.restlink.ussdgw.config.UssdConfigService;
import et.restlink.ussdgw.persist.TenantEntity;
import et.restlink.ussdgw.tenant.TenantService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves AS HTTP wire format: tenant.httpAsWireFormat → global ussd.as.http.wire-format → XML.
 */
@ApplicationScoped
public class WireFormatResolver {
    @Inject TenantService tenants;
    @Inject UssdConfigService config;

    public AsHttpWireFormat resolve(String tenantId) {
        String tenantFmt = readTenantWireFormat(tenantId);
        if (tenantFmt != null && !tenantFmt.isBlank()) {
            return AsHttpWireFormat.parse(tenantFmt);
        }
        String global = config == null ? null : config.asHttpWireFormat();
        return AsHttpWireFormat.parse(global);
    }

    private String readTenantWireFormat(String tenantId) {
        if (tenants == null || tenantId == null || tenantId.isBlank()) {
            return null;
        }
        return tenants.byId(tenantId.trim())
                .map(WireFormatResolver::httpAsWireFormatOf)
                .orElse(null);
    }

    static String httpAsWireFormatOf(TenantEntity e) {
        return e == null ? null : e.httpAsWireFormat;
    }
}
