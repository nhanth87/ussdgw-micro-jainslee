package et.restlink.ussdgw.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** AS-facing SIP trunk (peer + URI templates). One global RA; trunks select peer routing. */
@Entity
@Table(name = "ussd_sip_trunk")
public class SipTrunkEntity extends PanacheEntityBase {
    @Id
    @Column(name = "trunk_id", nullable = false, length = 64)
    public String trunkId;

    @Column(name = "display_name", length = 256)
    public String displayName;

    @Column(name = "peer_host", nullable = false, length = 256)
    public String peerHost;

    @Column(name = "peer_port", nullable = false)
    public int peerPort = 5060;

    @Column(nullable = false, length = 8)
    public String transport = "UDP";

    @Column(name = "from_uri", length = 512)
    public String fromUri;

    @Column(name = "request_uri_template", length = 512)
    public String requestUriTemplate;

    /** BODY = raw MESSAGE body; SDP = parse a=ussd-string: */
    @Column(name = "inbound_body", nullable = false, length = 16)
    public String inboundBody = "BODY";

    @Column(name = "tenant_id", length = 64)
    public String tenantId;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
