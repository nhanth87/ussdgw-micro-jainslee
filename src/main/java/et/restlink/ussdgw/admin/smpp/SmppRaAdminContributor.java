/*
 */
package et.restlink.ussdgw.admin.smpp;

import com.microjainslee.admin.RaAdminApiRegistrar;
import com.microjainslee.admin.RaAdminDashboardContributor;
import com.microjainslee.admin.RaAdminManifest;

/**
 * Local SMPP RA admin pack (ServiceLoader) for the jainslee-monitor hub.
 * RA name {@code smpp-ra} matches {@link et.restlink.ussdgw.ra.smpp.SmppRaEndpoint}.
 */
public final class SmppRaAdminContributor implements RaAdminDashboardContributor {

    private final SmppAdminController controller = new SmppAdminController();

    @Override
    public RaAdminManifest manifest() {
        return RaAdminManifest.of("smpp-ra", "smpp", "SMPP", 15);
    }

    @Override
    public void registerApis(RaAdminApiRegistrar registrar) {
        registrar.get("/status", controller::status);
        registrar.get("/status.html", controller::statusHtml);
        registrar.get("/config", controller::config);
        registrar.post("/validate", controller::validate);
        registrar.post("/apply", controller::apply);
        registrar.post("/start", controller::start);
        registrar.post("/stop", controller::stop);
    }
}
