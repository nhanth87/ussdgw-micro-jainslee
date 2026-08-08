#!/usr/bin/env bash
# Smoke Test AS MAP2MAP enrich + gated notify (AS must be on :8090).
set -euo pipefail
AS="${AS_URL:-http://127.0.0.1:8090/ussd/pull}"
echo "== pull enrich =="
curl -sS -X POST "$AS" -H 'Content-Type: text/xml; charset=utf-8' -d @- <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<dialog appCntx="networkUnstructuredSsContext" localId="corr-lab"
        sessionId="vs-lab" virtualBridgeId="corr-lab" adaptiveTimeoutMs="4200"
        asMode="BRIDGE" shortCode="*804#" originatedUssd="*804#"
        codeKind="SHORT" networkId="0">
  <processUnstructuredSSRequest_Request dataCodingScheme="15" string="MAP2MAP hop UserInfo">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </processUnstructuredSSRequest_Request>
</dialog>
EOF
echo
echo "== gated notify =="
curl -sS -X POST "$AS" -H 'Content-Type: text/xml; charset=utf-8' -d @- <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<dialog appCntx="networkUnstructuredSsContext" localId="corr-lab"
        virtualBridgeId="corr-lab" adaptiveTimeoutMs="7000" asMode="BRIDGE"
        gateReason="GATE_EXPIRED" mapMessagesSize="1">
  <unstructuredSSNotify_Request dataCodingScheme="15" string="GATE_EXPIRED">
    <msisdn nai="international_number" npi="ISDN" number="251911000001"/>
  </unstructuredSSNotify_Request>
</dialog>
EOF
echo
curl -sS "${AS%/ussd/pull}/health" || true
echo
