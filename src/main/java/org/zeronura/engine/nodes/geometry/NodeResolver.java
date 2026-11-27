package org.zeronura.engine.nodes.geometry;

import org.zeronura.engine.nodes.NodeTree;

import java.util.List;

/**
 * What a geometry tree can reach outside itself: group trees, other objects and collections (for Object Info
 * and Collection Info), and images. A {@link org.zeronura.engine.nodes.NodePackage} provides all of it.
 */
public interface NodeResolver {

    NodeTree tree(String key);

    /** An object's geometry as it evaluates (its own node modifiers applied), in its own local space. */
    GeometrySet objectGeometry(String name, EvalEnv env);

    /** An object's world matrix, column-major, in Blender's space; identity if unknown. */
    float[] objectMatrix(String name);

    /** The objects directly in a collection, its child collections, and its instance offset. */
    CollectionContents collection(String name);

    /** An image's pixels for sampling on the CPU, or null when it is missing. */
    ImagePixels image(String name);

    record CollectionContents(List<String> objects, List<String> children, float[] offset) {
    }

    NodeResolver NONE = new NodeResolver() {
        @Override
        public NodeTree tree(String key) {
            return null;
        }

        @Override
        public GeometrySet objectGeometry(String name, EvalEnv env) {
            return GeometrySet.EMPTY;
        }

        @Override
        public float[] objectMatrix(String name) {
            return Transforms.identity();
        }

        @Override
        public CollectionContents collection(String name) {
            return null;
        }

        @Override
        public ImagePixels image(String name) {
            return null;
        }
    };
}
