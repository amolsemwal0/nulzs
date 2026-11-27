package org.zeronura.nulzs.nodes;

/**
 * A socket or setting that points at another data-block: an object, a collection, a material, an image or a
 * node tree, named as it is in the package.
 */
public record Ref(String name, String kind) {

    public boolean isObject() {
        return "OBJECT".equals(kind);
    }

    public boolean isCollection() {
        return "COLLECTION".equals(kind);
    }

    public boolean isMaterial() {
        return "MATERIAL".equals(kind);
    }

    public boolean isImage() {
        return "IMAGE".equals(kind);
    }
}
