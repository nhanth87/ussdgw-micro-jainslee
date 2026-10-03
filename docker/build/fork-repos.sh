#!/usr/bin/env bash
# Fork repos từ nhanth87/mobius-software-ltd sang digicom-et
# Requires: GitHub CLI (gh) với token có quyền admin trên digicom-et org

set -euo pipefail

echo "=== FORK REPOS TO DIGICOM-ET ==="
echo

# Check if gh is installed
if ! command -v gh &> /dev/null; then
    echo "ERROR: GitHub CLI (gh) not found."
    echo "Install: https://cli.github.com/"
    exit 1
fi

# Check if authenticated
if ! gh auth status &> /dev/null; then
    echo "ERROR: Not authenticated with GitHub CLI."
    echo "Run: gh auth login"
    exit 1
fi

# Repos to fork
REPOS=(
    "nhanth87/sctp"
    "nhanth87/jss7"
    "nhanth87/jain-slee"
    "mobius-software-ltd/corsac-diameter"
)

for repo in "${REPOS[@]}"; do
    echo "Forking $repo → digicom-et/$(basename $repo)..."
    if gh repo fork "$repo" --org digicom-et --clone=false --remote=false 2>&1; then
        echo "  ✓ Forked successfully"
    else
        echo "  ⚠ Fork failed or already exists"
    fi
    echo
done

echo "=== FORK COMPLETE ==="
echo "Now run: ./docker/build/fetch-sources-digicom.sh"
