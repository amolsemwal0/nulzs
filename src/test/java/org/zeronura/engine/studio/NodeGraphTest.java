package org.zeronura.engine.studio;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.nodes.NodePackage;
import org.zeronura.engine.nodes.shader.ShaderCompiler;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeGraphTest {

    private static final Path FILE = Path.of("projects/Test/assets/materials/Test.nodes.json").toAbsolutePath();
    private static final Path ASSETS = Path.of("projects/Test/assets").toAbsolutePath();

    private static ShaderCompiler.Result compile(NodeGraph graph) {
        Map<String, Object> json = Json.parseObject(Json.compact(graph.toPackage(FILE, ASSETS)));
        NodePackage pkg = NodePackage.fromJson(FILE, json);
        return ShaderCompiler.compile(pkg, pkg.material(graph.materialName), true);
    }

    @Test
    void theCatalogHasEveryKindOfNode() {
        NodeCatalog catalog = NodeCatalog.shader();
        assertFalse(catalog.isEmpty());
        for (String category : catalog.categories()) {
            for (NodeCatalog.Entry entry : catalog.in(category)) {
                assertTrue(ShaderCompiler.supports(entry.type()), entry.type() + " is offered but the compiler can't take it");
            }
        }
        assertNotNull(catalog.byId("ShaderNodeBsdfPrincipled"));
        assertNotNull(catalog.byId("ShaderNodeMix:RGBA"));
    }

    @Test
    void theStarterMaterialCompiles() {
        NodeGraph graph = NodeGraph.starter(NodeCatalog.shader(), "Plain");
        assertEquals(1, graph.links.size());
        ShaderCompiler.Result result = compile(graph);
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.fragmentSource().contains("void main"));
    }

    @Test
    void aTextureThroughARampCompilesAndRoundTrips() {
        NodeCatalog catalog = NodeCatalog.shader();
        NodeGraph graph = NodeGraph.starter(catalog, "Marbled");
        NodeGraph.Node bsdf = graph.nodes.stream().filter(n -> n.type.equals("ShaderNodeBsdfPrincipled")).findFirst().orElseThrow();
        NodeGraph.Node noise = graph.add(catalog.byId("ShaderNodeTexNoise"), -600f, 0f);
        NodeGraph.Node ramp = graph.add(catalog.byId("ShaderNodeValToRGB"), -300f, 0f);
        assertTrue(graph.connect(noise, "Fac", ramp, "Fac"));
        assertTrue(graph.connect(ramp, "Color", bsdf, "Base Color"));
        assertFalse(graph.connect(bsdf, "BSDF", noise, "Scale"), "a loop is refused");
        ShaderCompiler.Result result = compile(graph);
        assertTrue(result.problems().isEmpty(), result.problems().toString());

        Map<String, Object> saved = Json.parseObject(Json.pretty(graph.toPackage(FILE, ASSETS)));
        NodeGraph back = NodeGraph.fromPackage(saved, catalog, FILE, ASSETS);
        assertEquals("Marbled", back.materialName);
        assertEquals(graph.nodes.size(), back.nodes.size());
        assertEquals(graph.links.size(), back.links.size());
        assertEquals(-600f, back.nodes.stream().filter(n -> n.name.equals(noise.name)).findFirst().orElseThrow().x);
    }

    @Test
    void choosingAnOperationShowsTheSocketsBlenderShows() {
        NodeCatalog catalog = NodeCatalog.shader();
        NodeGraph graph = new NodeGraph();
        NodeGraph.Node math = graph.add(catalog.byId("ShaderNodeMath"), 0f, 0f);
        assertTrue(math.input("Value_001").enabled);
        math.props.put("operation", "SINE");
        graph.applySettings(math);
        assertFalse(math.input("Value_001").enabled, "Sine takes one value");
        math.props.put("operation", "MULTIPLY_ADD");
        graph.applySettings(math);
        assertTrue(math.input("Value_002").enabled, "Multiply Add takes three");
    }
}
