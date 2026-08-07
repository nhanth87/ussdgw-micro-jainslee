#!/usr/bin/env bash
# Install a packaged config file into dist/configs/ WITHOUT ever overwriting the operator's copy.
#
# Usage: ./build/install-config.sh <source-file> <dest-file>
#
# dist/configs/application.properties is the file docs/agents/schema.md tells operators to edit
# (PostgreSQL URL, credentials, secrets). Repackaging on the server must never revert it to the
# git default — routing rules, tenants, campaigns and CDR all live in whatever DB it points at.
#
# Exists  → write "<dest>.new" and tell the operator to diff. Exit 0.
# Absent  → install it. Exit 0.
set -euo pipefail

SRC="${1:?usage: install-config.sh <source-file> <dest-file>}"
DEST="${2:?usage: install-config.sh <source-file> <dest-file>}"

if [[ ! -f "$SRC" ]]; then
  echo "error: install-config.sh source not found: $SRC" >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"

if [[ ! -e "$DEST" ]]; then
  cp -f "$SRC" "$DEST"
  echo "  installed $(basename "$DEST") (first package)"
  exit 0
fi

NEW="${DEST}.new"
cp -f "$SRC" "$NEW"

if cmp -s "$SRC" "$DEST"; then
  rm -f "$NEW"
  echo "  kept $(basename "$DEST") (identical to packaged default)"
  exit 0
fi

echo "  KEPT existing $(basename "$DEST") — packaged default written to $(basename "$NEW")"
echo "      review with:  diff -u '$DEST' '$NEW'"
echo "      NOTE: quarkus.datasource.db-kind is BUILD-TIME; an H2→PostgreSQL switch needs a"
echo "            repackage with the new db-kind, not just an edit of $(basename "$DEST")."
exit 0
