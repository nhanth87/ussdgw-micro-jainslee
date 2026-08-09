#!/usr/bin/env bash
# Install USSDGW SCTP/socket buffer sysctl drop-in (public-safe; no Digicom secrets).
# Usage: sudo ./build/systemd/install-sctp-buffers.sh
# After: restart ussdgw so SCTP associations recreate with new rcvbuf/sndbuf.
set -euo pipefail
UNIT_DIR="$(cd "$(dirname "$0")" && pwd)"
DROPIN=99-ussdgw-sctp-buffers.conf
SYSCTL_D=/etc/sysctl.d
[[ -f "${UNIT_DIR}/${DROPIN}" ]] || { echo "missing ${UNIT_DIR}/${DROPIN}" >&2; exit 1; }
BAK="/tmp/ussdgw-sysctl-bak-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$BAK"
{
  echo "# before ${DROPIN}"
  sysctl net.core.rmem_max net.core.wmem_max net.core.rmem_default net.core.wmem_default \
    net.sctp.sctp_rmem net.sctp.sctp_wmem 2>/dev/null || true
} > "${BAK}/sysctl-before.txt"
[[ -f "${SYSCTL_D}/${DROPIN}" ]] && cp -a "${SYSCTL_D}/${DROPIN}" "${BAK}/${DROPIN}.bak" || true
install -m 0644 "${UNIT_DIR}/${DROPIN}" "${SYSCTL_D}/${DROPIN}"
sysctl -p "${SYSCTL_D}/${DROPIN}"
echo "Installed ${SYSCTL_D}/${DROPIN} (backup ${BAK})."
echo "Restart the GW so assocs pick new buffers, e.g.:"
echo "  sudo systemctl restart ussdgw.service"
