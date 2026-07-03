#!/usr/bin/env bash
# Remove the DuckLake plugin from a DBeaver installation (Linux/macOS).
# Usage: ./uninstall.sh /path/to/dbeaver-install-dir
set -euo pipefail

DBEAVER="${1:-}"
if [[ -z "$DBEAVER" ]]; then
  echo "Usage: $0 /path/to/dbeaver-install-dir" >&2
  exit 1
fi

BI="$DBEAVER/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
if [[ -f "$BI" ]]; then
  grep -v '^org\.jkiss\.dbeaver\.ext\.ducklake' "$BI" > "$BI.tmp" || true
  mv "$BI.tmp" "$BI"
fi
rm -f "$DBEAVER"/plugins/org.jkiss.dbeaver.ext.ducklake*.jar

echo "DuckLake plugin removed. Restart DBeaver with -clean."
