#!/usr/bin/env bash
# Install systemd units + SCTP buffer sysctl on Digicom host (run as app with
# passwordless sudo, or root).
# Usage: sudo ./build/systemd/install-on-digicom.sh
set -euo pipefail
UNIT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYS=/etc/systemd/system
SYSCTL_D=/etc/sysctl.d
SYSCTL_DROPIN=99-ussdgw-sctp-buffers.conf

for u in ussdgw.service ussdgw-as-node.service ussdgw-ss7sim.service; do
  install -m 0644 "${UNIT_DIR}/${u}" "${SYS}/${u}"
done
systemctl daemon-reload
systemctl enable ussdgw.service ussdgw-as-node.service ussdgw-ss7sim.service

if [[ -f "${UNIT_DIR}/${SYSCTL_DROPIN}" ]]; then
  BAK_DIR="${UNIT_DIR}/.sysctl-bak-$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "${BAK_DIR}"
  {
    echo "# backup before ${SYSCTL_DROPIN}"
    sysctl net.core.rmem_max net.core.wmem_max net.core.rmem_default net.core.wmem_default \
      net.sctp.sctp_rmem net.sctp.sctp_wmem 2>/dev/null || true
  } > "${BAK_DIR}/sysctl-before.txt"
  if [[ -f "${SYSCTL_D}/${SYSCTL_DROPIN}" ]]; then
    cp -a "${SYSCTL_D}/${SYSCTL_DROPIN}" "${BAK_DIR}/${SYSCTL_DROPIN}.bak"
  fi
  install -m 0644 "${UNIT_DIR}/${SYSCTL_DROPIN}" "${SYSCTL_D}/${SYSCTL_DROPIN}"
  # Load only our drop-in (avoid full --system surprise on unrelated keys).
  sysctl -p "${SYSCTL_D}/${SYSCTL_DROPIN}"
  echo "Installed sysctl ${SYSCTL_D}/${SYSCTL_DROPIN} (backup ${BAK_DIR})."
  echo "Restart ussdgw so SCTP assocs pick new rcvbuf/sndbuf:"
  echo "  sudo systemctl restart ussdgw.service"
else
  echo "warn: missing ${UNIT_DIR}/${SYSCTL_DROPIN} — units only" >&2
fi

echo "Installed. Start with:"
echo "  sudo systemctl restart ussdgw-ss7sim ussdgw-as-node ussdgw"
echo "  sudo systemctl status ussdgw ussdgw-as-node ussdgw-ss7sim --no-pager"
