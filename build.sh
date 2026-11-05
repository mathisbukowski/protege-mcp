#!/usr/bin/env bash
# Builds protege-mcp.jar with javac only. Usage: ./build.sh [install]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
PROTEGE_HOME="${PROTEGE_HOME:-/opt/Protege-5.6.9}"
VERSION=0.1.0
OUT=build
CLASSES="$OUT/classes"

CP=$(ls "$PROTEGE_HOME"/bundles/*.jar "$PROTEGE_HOME"/plugins/rdf-library-*.jar | tr '\n' ':')
rm -rf "$OUT"
mkdir -p "$CLASSES"
javac --release 11 -encoding UTF-8 -Xlint:-options -cp "$CP" -d "$CLASSES" src/dev/protegemcp/*.java
cp resources/plugin.xml "$CLASSES/"

# Derive Import-Package from the compiled code, so no runtime class is forgotten.
IMPORTS=$(jdeps -verbose:package "$CLASSES" 2>/dev/null \
    | sed -nE 's/.*-> +([^ ]+).*/\1/p' \
    | grep -vE '^(java\.|jdk\.|com\.sun\.|dev\.protegemcp|not$)' | sort -u \
    | grep -vE '^(org\.openrdf|info\.aduna)' \
    | paste -sd, -)
IMPORTS="org.protege.editor.core.plugin,$IMPORTS"
IMPORTS=$(echo "$IMPORTS" | tr ',' '\n' | sort -u | paste -sd, -)

python3 - "$OUT/MANIFEST.MF" "$VERSION" "$IMPORTS" <<'PY'
import sys
path, version, imports = sys.argv[1:]
lines = [
    "Manifest-Version: 1.0",
    "Bundle-ManifestVersion: 2",
    "Bundle-Name: Protege MCP Server",
    "Bundle-SymbolicName: dev.protegemcp;singleton:=true",
    "Bundle-Version: " + version,
    "Bundle-Activator: org.protege.editor.core.plugin.DefaultPluginActivator",
    "Import-Package: " + imports,
    # Bound at first use, so the server still runs without the rdf-library plugin.
    "DynamicImport-Package: org.openrdf.*,info.aduna.*",
]
def fold(line):
    out = [line[:70]]
    line = line[70:]
    while line:
        out.append(" " + line[:69])
        line = line[69:]
    return "\n".join(out)
open(path, "w").write("\n".join(fold(l) for l in lines) + "\n")
PY

jar cfm "$OUT/protege-mcp-$VERSION.jar" "$OUT/MANIFEST.MF" -C "$CLASSES" .
echo "Built $OUT/protege-mcp-$VERSION.jar"

if [ "${1:-}" = install ]; then
    PLUGINS="$HOME/.Protege/plugins"
    CONFIG="$HOME/.Protege/mcp/config.properties"
    mkdir -p "$PLUGINS"
    rm -f "$PLUGINS"/protege-mcp-*.jar
    cp "$OUT/protege-mcp-$VERSION.jar" "$PLUGINS/"
    if [ ! -f "$CONFIG" ]; then
        mkdir -p "$(dirname "$CONFIG")"
        chmod 700 "$(dirname "$CONFIG")"
        (umask 077; printf 'port=8765\ntoken=%s\n' "$(python3 -c 'import secrets; print(secrets.token_hex(32))')" > "$CONFIG")
    fi
    echo "Installed in $PLUGINS. Restart Protégé to load it."
fi
