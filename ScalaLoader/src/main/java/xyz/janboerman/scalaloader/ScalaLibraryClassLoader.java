package xyz.janboerman.scalaloader;

import xyz.janboerman.scalaloader.plugin.ScalaPluginLoader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Objects;

/**
 * ClassLoader that loads scala library classes.
 * The {@link ScalaPluginLoader} will create instances per scala version.
 */
public class ScalaLibraryClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final String scalaVersion;

    protected ScalaLibraryClassLoader(String scalaVersion, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.scalaVersion = Objects.requireNonNull(scalaVersion, "Scala version cannot be null!");
    }

    //TODO override the findClass method and 'relocate/transform' scala library classes? then I could inject them into the PluginLoader classes.

    public String getScalaVersion() {
        return scalaVersion;
    }

}

