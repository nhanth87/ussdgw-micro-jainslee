# gRPC AS wire (greenfield)

Gateway `GrpcClientSbb` sends **UTF-8 JSON** `AsRequest` bytes on
`InvokeGrpc` (`application/json` semantic). The AS returns JSON `AsResponse`
in the gRPC response payload.

This is intentional for P1 (same schema as HTTP `/ussd/pull`). A future
`.proto` stub can wrap the same fields without changing SBB routing.

Lab: `tools/as-grpc-json-sim.py` and `tools/as-http-sim.py`.
