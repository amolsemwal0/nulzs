package org.zeronura.nulzs.studio;

import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.nodes.render.GeometryTrees;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Geometry Nodes Tool's trees, made as the tool makes them, saved, read back and run by the engine. */
class GeometryGraphTest {

    private static final Path ASSETS = Path.of("projects/Test/assets").toAbsolutePath();
    private static final Path FILE = ASSETS.resolve("geometry/Test.nodes.json");

    private static GeometryTrees.Result run(NodeGraph graph, Map<String, Object> inputs) {
        Map<String, Object> json = Json.parseObject(Json.pretty(graph.toPackage(FILE, ASSETS)));
        return GeometryTrees.run(FILE, json, inputs);
    }

    private static NodeGraph.Node only(NodeGraph graph, String type) {
        return graph.nodes.stream().filter(n -> n.type.equals(type)).findFirst().orElseThrow();
    }

    @Test
    void theCatalogOffersBlendersMenusOfWhatTheEngineRuns() {
        NodeCatalog catalog = NodeCatalog.geometry();
        assertFalse(catalog.isEmpty(), "nodes/geometry_nodes.json is in the build");
        assertTrue(catalog.categories().contains("Mesh/Primitives"));
        assertNotNull(catalog.byId("GeometryNodeMeshCube"));
        assertNotNull(catalog.byId("ShaderNodeMath"));
        assertTrue(catalog.byId("GeometryNodeJoinGeometry").template().toString().contains("multi_input=true"));
        NodeCatalog.Entry repeat = catalog.byId("zone:GeometryNodeRepeatInput");
        assertNotNull(repeat);
        assertTrue(repeat.isZone());
        for (String category : catalog.categories()) {
            for (NodeCatalog.Entry entry : catalog.in(category)) {
                assertTrue(org.zeronura.nulzs.nodes.geometry.GeometryEvaluator.supports(entry.type()),
                        entry.type() + " is offered but the engine doesn't run it");
            }
        }
    }

    @Test
    void theStarterTreeMakesACube() {
        NodeGraph graph = NodeGraph.geometryStarter(NodeCatalog.geometry(), "Box");
        GeometryTrees.Result result = run(graph, Map.of());
        assertTrue(result.ok(), String.valueOf(result.problems()));
        assertEquals(8, result.vertices());
        assertEquals(6, result.faces());
        assertEquals(1f, result.boundsMax()[0] - result.boundsMin()[0], 1e-4f);
    }

    @Test
    void anInputSetsWhatItIsJoinedTo() {
        NodeCatalog catalog = NodeCatalog.geometry();
        NodeGraph graph = NodeGraph.geometryStarter(catalog, "Sized box");
        NodeGraph.InterfaceItem size = graph.addInput("Size", "VALUE");
        size.defaultValue = 1.0;
        graph.syncInterface();
        NodeGraph.Node input = only(graph, "NodeGroupInput");
        assertNotNull(input.output(size.id), "the Group Input hands the new input out");
        assertTrue(graph.connect(input, size.id, only(graph, "GeometryNodeMeshCube"), "Size"));
        assertEquals(1f, width(run(graph, Map.of())), 1e-4f);
        assertEquals(3f, width(run(graph, Map.of("Size", 3.0))), 1e-4f, "set by name, as an entity sets it");
        assertEquals(2f, width(run(graph, Map.of(size.id, 2.0))), 1e-4f, "set by identifier");
        graph.removeInterfaceItem(size);
        assertTrue(input.outputs.isEmpty());
        assertTrue(graph.links.stream().noneMatch(l -> l.from() == input), "its link went with it");
    }

    private static float width(GeometryTrees.Result result) {
        assertTrue(result.ok(), String.valueOf(result.problems()));
        return result.boundsMax()[0] - result.boundsMin()[0];
    }

    @Test
    void joinGeometryTakesManyLinksAndKeepsThem() {
        NodeCatalog catalog = NodeCatalog.geometry();
        NodeGraph graph = NodeGraph.geometryStarter(catalog, "Joined");
        NodeGraph.Node cube = only(graph, "GeometryNodeMeshCube");
        NodeGraph.Node output = only(graph, "NodeGroupOutput");
        NodeGraph.Node ico = graph.add(catalog.byId("GeometryNodeMeshIcoSphere"), -60f, 200f);
        NodeGraph.Node join = graph.add(catalog.byId("GeometryNodeJoinGeometry"), 160f, 0f);
        assertTrue(graph.connect(cube, "Mesh", join, "Geometry"));
        assertTrue(graph.connect(ico, "Mesh", join, "Geometry"));
        assertTrue(graph.connect(join, "Geometry", output, "Socket_0"));
        assertEquals(2, graph.links.stream().filter(l -> l.to() == join).count(), "both joined, neither replaced");
        assertEquals(8 + 12, run(graph, Map.of()).vertices());

        Map<String, Object> saved = Json.parseObject(Json.pretty(graph.toPackage(FILE, ASSETS)));
        NodeGraph back = NodeGraph.geometryFromPackage(saved, catalog, FILE, ASSETS);
        assertEquals(graph.nodes.size(), back.nodes.size());
        assertEquals(graph.links.size(), back.links.size());
        assertEquals("Joined", back.materialName);
        assertTrue(NodeGraph.isGeometry(saved));
    }

    @Test
    void aRepeatZoneIsAddedPairedAndRuns() {
        NodeCatalog catalog = NodeCatalog.geometry();
        NodeGraph graph = NodeGraph.geometryStarter(catalog, "Stepped");
        NodeGraph.Node cube = only(graph, "GeometryNodeMeshCube");
        NodeGraph.Node output = only(graph, "NodeGroupOutput");
        NodeGraph.Node zoneIn = graph.add(catalog.byId("zone:GeometryNodeRepeatInput"), 100f, 0f);
        NodeGraph.Node zoneOut = graph.nodes.stream().filter(n -> n.type.equals("GeometryNodeRepeatOutput")).findFirst().orElseThrow();
        assertEquals(zoneOut.name, zoneIn.pair, "the input names its output");
        // Cube -> zone (moved 1 along x each of 3 times) -> output.
        NodeGraph.Node move = graph.add(catalog.byId("GeometryNodeTransform"), 250f, 0f);
        move.input("Translation").value = List.of(1.0, 0.0, 0.0);
        graph.disconnect(zoneOut, "Item_0");
        assertTrue(graph.connect(cube, "Mesh", zoneIn, "Item_0"));
        assertTrue(graph.connect(zoneIn, "Item_0", move, "Geometry"));
        assertTrue(graph.connect(move, "Geometry", zoneOut, "Item_0"));
        assertTrue(graph.connect(zoneOut, "Item_0", output, "Socket_0"));
        zoneIn.input("Iterations").value = 3L;
        GeometryTrees.Result result = run(graph, Map.of());
        assertTrue(result.ok(), String.valueOf(result.problems()));
        assertEquals(3f, (result.boundsMin()[0] + result.boundsMax()[0]) * 0.5f, 1e-4f);

        Map<String, Object> saved = Json.parseObject(Json.pretty(graph.toPackage(FILE, ASSETS)));
        NodeGraph back = NodeGraph.geometryFromPackage(saved, catalog, FILE, ASSETS);
        NodeGraph.Node backIn = back.nodes.stream().filter(n -> n.type.equals("GeometryNodeRepeatInput")).findFirst().orElseThrow();
        assertEquals(zoneOut.name, backIn.pair);
        // Removing one half takes the whole zone.
        back.remove(backIn);
        assertTrue(back.nodes.stream().noneMatch(n -> n.type.startsWith("GeometryNodeRepeat")));
    }

    @Test
    void setMaterialNamesASavedShader() {
        NodeCatalog catalog = NodeCatalog.geometry();
        NodeGraph graph = NodeGraph.geometryStarter(catalog, "Painted");
        NodeGraph.Node cube = only(graph, "GeometryNodeMeshCube");
        NodeGraph.Node output = only(graph, "NodeGroupOutput");
        NodeGraph.Node paint = graph.add(catalog.byId("GeometryNodeSetMaterial"), 120f, 0f);
        paint.input("Material").value = Map.of("ref", "materials/Stone.nodes.json", "kind", "MATERIAL");
        assertTrue(graph.connect(cube, "Mesh", paint, "Geometry"));
        assertTrue(graph.connect(paint, "Geometry", output, "Socket_0"));
        GeometryTrees.Result result = run(graph, Map.of());
        assertTrue(result.ok(), String.valueOf(result.problems()));
        assertEquals(List.of("materials/Stone.nodes.json"), result.geometry().mesh.materials);
    }
}
