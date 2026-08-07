package et.restlink.ussdgw.service;

import et.restlink.ussdgw.api.AsHttpWireFormat;
import et.restlink.ussdgw.api.AsRequest;

/**
 * Everything needed to re-send an AS pull, captured at submit time.
 *
 * <p>{@link #circuitKey()} is the identity {@link AsPullClient} breaks on. It is never blank —
 * a completion that cannot resolve a target must leave every breaker untouched rather than
 * collapse unrelated AS endpoints onto one key.
 */
public sealed interface AsPullTarget {

    /** Per-endpoint circuit-breaker key. */
    String circuitKey();

    /** HTTP pull — raw wire body plus the format that decided its Content-Type. */
    record Http(String url, String body, AsHttpWireFormat format) implements AsPullTarget {
        public Http {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("AS url required");
            }
            if (body == null) {
                throw new IllegalArgumentException("AS body required");
            }
            format = format == null ? AsHttpWireFormat.XML : format;
        }

        @Override
        public String circuitKey() {
            return url;
        }
    }

    /** gRPC pull — the request is kept, not the encoded bytes, so the record stays immutable. */
    record Grpc(String endpoint, String fullMethod, AsRequest request) implements AsPullTarget {
        public Grpc {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("gRPC endpoint required");
            }
            if (fullMethod == null || fullMethod.isBlank()) {
                throw new IllegalArgumentException("gRPC method required");
            }
            if (request == null) {
                throw new IllegalArgumentException("AS request required");
            }
        }

        @Override
        public String circuitKey() {
            return endpoint + "|" + fullMethod;
        }
    }
}
