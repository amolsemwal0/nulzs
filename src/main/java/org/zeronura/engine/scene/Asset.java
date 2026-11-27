package org.zeronura.engine.scene;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component's {@code String} field as the path of an asset, relative to the project's assets folder: the
 * editor's Inspector shows it with a picker, and files dragged in from the Project panel can be dropped on it.
 *
 * <pre>{@code
 * @Asset({"png", "jpg"})
 * public String image = "";
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Asset {

    /** The file extensions it takes, without dots; empty for any file. */
    String[] value() default {};
}
