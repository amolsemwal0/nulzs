package org.zeronura.engine.studio;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Loads a project's own classes — its scripts — afresh, so the editor can compile them again and use the new versions
 * without restarting. Classes in the project's package are read from the compiled classes folder by this loader first;
 * everything else, the engine included, comes from the editor's own class loader, so a script's {@code Component} is
 * the engine's {@code Component}.
 *
 * <p>Each compile makes a new loader; the scene being edited is saved and rebuilt from the new one.
 */
final class ScriptClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final String prefix;

    ScriptClassLoader(Path classes, String projectPackage, ClassLoader parent) {
        super(urls(classes), parent);
        this.prefix = projectPackage.isEmpty() ? "\u0000" : projectPackage + ".";
    }

    private static URL[] urls(Path classes) {
        try {
            return new URL[]{classes.toAbsolutePath().toUri().toURL()};
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    boolean isScript(String className) {
        return className.startsWith(prefix);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isScript(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException notHere) {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }
}
