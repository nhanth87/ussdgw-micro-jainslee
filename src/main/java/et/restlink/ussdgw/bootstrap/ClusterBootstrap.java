package et.restlink.ussdgw.bootstrap;

import com.microjainslee.cluster.ClusterManager;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Digicom-safe stub: do <em>not</em> construct {@link ClusterManager} until
 * Infinispan API on the Digicom classpath matches jainslee-cluster (Attribute.set).
 * Peer-route LB stays off ({@code ussd.ss7.peer-route-lb.enabled=false}).
 */
@ApplicationScoped
public class ClusterBootstrap {
    private static final Logger LOG = LogManager.getLogger(ClusterBootstrap.class);

    void onStart(@Observes @Priority(50) StartupEvent ev) {
        LOG.info("ClusterBootstrap stub (no ISPN ClusterManager) — peer-route LB inactive");
    }

    public ClusterManager clusterManager() {
        return null;
    }
}
