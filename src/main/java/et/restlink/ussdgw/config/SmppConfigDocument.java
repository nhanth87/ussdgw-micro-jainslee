package et.restlink.ussdgw.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Multi SMPP client + ESME server profile (optional JSON; props fallback otherwise). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SmppConfigDocument(
        @JsonProperty("clients") List<Client> clients,
        @JsonProperty("server") Server server
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Client(
            @JsonProperty("name") String name,
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("systemId") String systemId,
            @JsonProperty("password") String password,
            @JsonProperty("systemType") String systemType,
            @JsonProperty("sourceAddr") String sourceAddr,
            @JsonProperty("role") String role,
            @JsonProperty("networkId") int networkId,
            @JsonProperty("enabled") Boolean enabled
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Server(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("port") int port,
            @JsonProperty("systemId") String systemId,
            @JsonProperty("password") String password,
            @JsonProperty("networkId") int networkId,
            @JsonProperty("esmeAllowlist") List<Esme> esmeAllowlist
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Esme(
            @JsonProperty("systemId") String systemId,
            @JsonProperty("password") String password,
            @JsonProperty("maxTps") int maxTps,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("networkId") Integer networkId
    ) { }
}
