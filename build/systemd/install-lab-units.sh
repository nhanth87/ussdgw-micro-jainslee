#!/usr/bin/env bash
# Install systemd units on lab host (run as app with passwordless sudo, or root).
# Usage: sudo ./build/systemd/install-lab-units.sh
set -euo pipefail
UNIT_DIR="$(cd "$(dirname "$0")" && pwd)"
SYS=/etc/systemd/system

for u in ussdgw.service ussdgw-as-node.service ussdgw-ss7sim.service; do
  install -m 0644 "${UNIT_DIR}/${u}" "${SYS}/${u}"
done
systemctl daemon-reload
systemctl enable ussdgw.service ussdgw-as-node.service ussdgw-ss7sim.service
echo "Installed. Start with:"
echo "  sudo systemctl restart ussdgw-ss7sim ussdgw-as-node ussdgw"
echo "  sudo systemctl status ussdgw ussdgw-as-node ussdgw-ss7sim --no-pager"
