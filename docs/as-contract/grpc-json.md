# gRPC AS wire (greenfield)

Gateway `GrpcClientSbb` sends **UTF-8 JSON** `AsRequest` bytes on
`InvokeGrpc` (`application/json` semantic). The AS returns JSON `AsResponse`
in the gRPC response payload.

This is intentional for P1 (same schema as HTTP JSON `/ussd/pull`). A future
`.proto` stub can wrap the same fields without changing SBB routing — see
[`ussd_as.proto`](ussd_as.proto).

## Shared late-push metadata (HTTP JSON ≡ gRPC bytes)

| Field | Role |
|-------|------|
| `correlationId` | **Real session id for push-back** — store / bridge key |
| `sessionId` | Logical `virtualSessionId` |
| `virtualBridgeId` | Bridge arm id when armed (usually = `correlationId`) |
| `adaptiveTimeoutMs` | Effective adaptive gate ms for this pull |
| `asMode` | `SYNC` \| `BRIDGE` hint on pull; ASYNC_ACK via `async:true` on response |
| `async` | On `AsResponse`: park and expect gRPC `Callback` or HTTP `/as/callback` |

`AsPullRouter` enriches both HTTP and gRPC pulls via `AsPullMetadata` before encode.
`GrpcServerSbb` accepts the same `AsResponse` JSON for late reconcile (same
`VirtualSessionBridge.onAsResponse` path as HTTP).

Lab: `tools/as-grpc-json-sim.py` and `tools/as-http-sim.py`.
Node AS sim (`tools/as-node/`) is **HTTP-only**; use the Python gRPC sim for Callback RPC.
