#!/usr/bin/env sh
# Bakes a Shader Tool material (.nodes.json) into tiling pictures: <prefix>_albedo.png and <prefix>_normal.png.
# usage: tools/bake_material.sh <material.nodes.json> [prefix] [size]
# Without a prefix they go where the Shader Tool last baked the material, else in a pictures/ folder beside it.
# Size defaults to 1024.
set -e
cd "$(dirname "$0")/.."
if [ $# -lt 1 ]; then
    echo "usage: tools/bake_material.sh <material.nodes.json> [prefix] [size]"; exit 2
fi
material="$1"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
set -- "$material" --size "${3:-1024}" ${2:+--out "$2"}
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
    -cp "target/classes:$(cat target/classpath.txt)" org.zeronura.nulzs.nodes.render.NodeBaker "$@"
