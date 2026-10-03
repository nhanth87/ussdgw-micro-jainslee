#!/usr/bin/env bash
# Fetch sources từ digicom-et (nếu có) hoặc fallback về nhanth87/mobius-software-ltd
# Usage: ./docker/build/fetch-sources-digicom.sh
#
# Script này tạo sources.lock.digicom với URLs từ digicom-et,
# sau đó gọi fetch-sources.sh với lock mới.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/../.."

echo "=== FETCH SOURCES FROM DIGICOM-ET ==="
echo

# Create digicom-et sources.lock
LOCK_FILE="docker/sources.lock.digicom"

echo "Creating $LOCK_FILE..."
cat > "$LOCK_FILE" << 'EOF'
# sources.lock.digicom — Digicom-ET mirrors (fallback to upstream if missing)
# Format: <name>|<url>|<branch-or-tag>|<commit-sha>

sctp|git@github.com-digicom:digicom-et/sctp.git|java25-upgrade|18097d68c977951dbe7a402a1d512128effd0c59

jss7|git@github.com-digicom:digicom-et/jss7.git|release/9.2.9-j25|dfac9bbe1f785cdb002ff06847c368d3d52e3eeb

jain-slee|git@github.com-digicom:digicom-et/jain-slee.git|micro-jainslee-2|0538b082fe2de073926945120ca7292c2f9019a3

corsac-diameter|git@github.com-digicom:digicom-et/corsac-diameter.git|diameter-parent-10.0.0-41|0bc4629186a7153ab365568c3c8d7e340c77d862

# Base images (same as upstream)
base-ubuntu|docker.io/library/ubuntu:26.04|sha256:61ebaa5cc23ca45450db85eac015435199ec569e28ec222ea13f2aed2110b8a6
base-temurin|docker.io/library/eclipse-temurin:25-jdk|sha256:f1a8a92fd5da34482dd3ace226120d6a75c1c4ca0a5a364f65ec6ba430eea6d1_FETCHED_AT_BUILD_TIME
base-postgres|docker.io/library/postgres:16|sha256:2c72031ac25606bf94fd2fece7c35efdf263a48b08446950034f0725653f4efc
base-nginx|docker.io/library/nginx:1.27-alpine|sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
EOF

echo "✓ Created $LOCK_FILE"
echo

# Test if digicom-et repos are accessible
echo "Testing digicom-et repo access..."
TEST_REPO="git@github.com-digicom:digicom-et/sctp.git"
if git ls-remote "$TEST_REPO" &> /dev/null; then
    echo "✓ digicom-et repos accessible"
else
    echo "⚠ digicom-et repos NOT accessible"
    echo
    echo "You need to fork repos first:"
    echo "  1. Install GitHub CLI: https://cli.github.com/"
    echo "  2. Authenticate: gh auth login"
    echo "  3. Run: ./docker/build/fork-repos.sh"
    echo
    echo "Or fork manually:"
    echo "  - https://github.com/nhanth87/sctp → digicom-et/sctp"
    echo "  - https://github.com/nhanth87/jss7 → digicom-et/jss7"
    echo "  - https://github.com/nhanth87/jain-slee → digicom-et/jain-slee"
    echo "  - https://github.com/mobius-software-ltd/corsac-diameter → digicom-et/corsac-diameter"
    echo
    read -p "Continue with fallback to nhanth87? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
    # Fallback to upstream
    LOCK_FILE="docker/sources.lock"
    echo "Using upstream sources.lock"
fi

echo
echo "Fetching sources from $LOCK_FILE..."
LOCK_FILE="$LOCK_FILE" "$HERE/fetch-sources.sh"

echo
echo "=== FETCH COMPLETE ==="
