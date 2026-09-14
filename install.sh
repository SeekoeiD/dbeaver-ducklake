#!/usr/bin/env bash
# Install the DuckLake plugin into a DBeaver installation (Linux/macOS).
#
# This DBeaver build loads bundles from Equinox simpleconfigurator (bundles.info),
# not loose dropins. So we copy the jars into plugins/ and register them in bundles.info.
#
# Usage: ./install.sh /path/to/dbeaver-install-dir
#   The install dir is the folder containing plugins/ and configuration/.
#   Linux (tarball):  /opt/dbeaver   (or wherever you extracted it)
#   macOS:            /Applications/DBeaver.app/Contents/Eclipse
set -euo pipefail

DBEAVER="${1:-}"
if [[ -z "$DBEAVER" ]]; then
  echo "Usage: $0 /path/to/dbeaver-install-dir" >&2
  exit 1
fi

SRC="$(cd "$(dirname "$0")" && pwd)"
PLUGINS="$DBEAVER/plugins"
BI="$DBEAVER/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
VERSION=1.1.1
JARS=(org.jkiss.dbeaver.ext.ducklake_$VERSION.jar org.jkiss.dbeaver.ext.ducklake.ui_$VERSION.jar)

[[ -d "$PLUGINS" ]] || { echo "plugins/ not found under $DBEAVER" >&2; exit 1; }
[[ -f "$BI" ]] || { echo "bundles.info not found under $DBEAVER" >&2; exit 1; }
for j in "${JARS[@]}"; do [[ -f "$SRC/$j" ]] || { echo "Missing $j next to this script" >&2; exit 1; }; done

# Remove any previously installed version first, then copy.
rm -f "$PLUGINS"/org.jkiss.dbeaver.ext.ducklake*.jar
for j in "${JARS[@]}"; do cp -f "$SRC/$j" "$PLUGINS/$j"; done

[[ -f "$BI.orig" ]] || cp "$BI" "$BI.orig"
grep -v '^org\.jkiss\.dbeaver\.ext\.ducklake' "$BI" > "$BI.tmp" || true
cat >> "$BI.tmp" <<EOF
org.jkiss.dbeaver.ext.ducklake,$VERSION,plugins/org.jkiss.dbeaver.ext.ducklake_$VERSION.jar,4,false
org.jkiss.dbeaver.ext.ducklake.ui,$VERSION,plugins/org.jkiss.dbeaver.ext.ducklake.ui_$VERSION.jar,4,false
EOF
mv "$BI.tmp" "$BI"

echo "DuckLake plugin installed into $DBEAVER"
echo "Restart DBeaver ONCE with -clean so it rebuilds from bundles.info."
