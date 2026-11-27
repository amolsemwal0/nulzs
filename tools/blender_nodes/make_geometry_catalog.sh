#!/usr/bin/env sh
# Makes src/main/resources/nodes/geometry_nodes.json, the nodes the editor's Geometry Nodes Tool offers: Blender's own
# Add menu, cut down to the nodes the engine's GeometryEvaluator runs. Run it again after the evaluator learns a node.
# usage: tools/blender_nodes/make_geometry_catalog.sh [path/to/blender]
set -e
cd "$(dirname "$0")/../.."
blender="${1:-${BLENDER:-blender}}"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
supported="$(mktemp)"
java -cp "target/classes:$(cat target/classpath.txt)" org.zeronura.engine.nodes.geometry.GeometryEvaluator "$supported"
"$blender" --background --factory-startup --python tools/blender_nodes/make_geometry_catalog.py -- \
    --supported "$supported" --out src/main/resources/nodes/geometry_nodes.json
rm -f "$supported"
