package xyz.janboerman.scalaloader.plugin;

import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.objectweb.asm.ClassReader;
import xyz.janboerman.scalaloader.ScalaLibraryClassLoader;
import xyz.janboerman.scalaloader.ScalaLoader;
import xyz.janboerman.scalaloader.event.ScalaPluginDisableEvent;
import xyz.janboerman.scalaloader.event.ScalaPluginEnableEvent;
import xyz.janboerman.scalaloader.plugin.description.ApiVersion;
import xyz.janboerman.scalaloader.plugin.description.DescriptionScanner;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BinaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class ScalaPluginLoader implements PluginLoader {

    private final Server server;
    private ScalaLoader lazyScalaLoader;
    private PluginLoader lazyJavaPluginLoader;

    private final Pattern[] pluginFileFilters = new Pattern[] { Pattern.compile("\\.jar$"), };

    //Map<ScalaVersion, Map<ClassName, Class<?>>>
    private final Map<String, Map<String, Class<?>>> sharedScalaPluginClasses = Collections.synchronizedMap(new HashMap<>());
    private final ConcurrentMap<String, CopyOnWriteArrayList<ScalaPluginClassLoader>> sharedScalaPluginClassLoaders = new ConcurrentHashMap<>();

    private final Map<String, ScalaPlugin> scalaPlugins = new HashMap<>();
    private final Map<File, ScalaPlugin> scalaPluginsByFile = new HashMap<>();

    public ScalaPluginLoader(Server server) {
        this.server = Objects.requireNonNull(server, "Server cannot be null!");
    }

    ScalaLoader getScalaLoader() {
        return lazyScalaLoader == null ? lazyScalaLoader = JavaPlugin.getPlugin(ScalaLoader.class) : lazyScalaLoader;
    }

    PluginLoader getJavaPluginLoader() {
        return lazyJavaPluginLoader == null ? lazyJavaPluginLoader = getScalaLoader().getPluginLoader() : lazyJavaPluginLoader;
    }

    @Override
    public PluginDescriptionFile getPluginDescription(File file) throws InvalidDescriptionException {
        final ScalaPlugin alreadyPresent = scalaPluginsByFile.get(file);
        if (alreadyPresent != null) return alreadyPresent.getDescription();

        //filled optionals are smaller then empty optionals.
        Comparator<Optional<?>> optionalComparator = Comparator.comparing(optional -> !optional.isPresent());
        //smaller package hierarchy = smaller string
        Comparator<String> packageComparator = Comparator.comparing(className -> className.split("\\."), Comparator.comparing(array -> array.length));

        //smaller element = better main class candidate!
        Comparator<DescriptionScanner> descriptionComparator = Comparator.nullsLast(Comparator /* get rid of null descriptors */
                .<DescriptionScanner, Optional<?>>comparing(DescriptionScanner::getMainClass, optionalComparator /* get rid of descriptions without a main class */)
                .thenComparing(DescriptionScanner::extendsScalaPlugin /* classes that extend ScalaPlugin directly are less likely to be the best candidate. */)
                .thenComparing(descriptionScanner -> descriptionScanner.getMainClass().get() /* never fails because empty optionals are larger anyway :) */, packageComparator)
                .thenComparing(descriptionScanner -> descriptionScanner.getMainClass().get() /* fallback - just compare the class strings */));

        try {
            DescriptionScanner mainClassCandidate = null;

            JarFile jarFile = new JarFile(file);
            Enumeration<JarEntry> entryEnumeration = jarFile.entries();
            while (entryEnumeration.hasMoreElements()) {
                JarEntry jarEntry = entryEnumeration.nextElement();
                if (jarEntry.getName().endsWith(".class")) {

                    InputStream classBytesInputStream = jarFile.getInputStream(jarEntry);

                    DescriptionScanner descriptionScanner = new DescriptionScanner();
                    ClassReader classReader = new ClassReader(classBytesInputStream);
                    classReader.accept(descriptionScanner, 0);

                    //Emit a warning when the class does extend ScalaPlugin, but does not have de @Scala or @CustomScala annotation
                    if (descriptionScanner.extendsScalaPlugin() && !descriptionScanner.getScalaVersion().isPresent()) {
                        getScalaLoader().getLogger().warning("Class " + jarEntry.getName() + " extends ScalaPlugin but does not have the @Scala or @CustomScala annotation.");
                    }

                    //TODO in the future I could transform the class to use the the relocated scala library?

                    //The smallest element is the best candidate!
                    mainClassCandidate = BinaryOperator.minBy(descriptionComparator).apply(mainClassCandidate, descriptionScanner);

                } else if (jarEntry.getName().equals("plugin.yml")) {
                    getScalaLoader().getLogger().warning("Found plugin.yml in scala plugin " + file.getName() + ". Ignoring..");
                    //TODO should probably inspect the plugin yaml.
                    //TODO if it contains a main class and it doesn't extend ScalaPlugin directly we should try to delegate to the JavaPluginLoader
                    //TODO if it doesn't contain a main class then we add the 'fields' of the plugin yaml to the ScalaPluginDescription.
                }
            } //end while - no more JarEntries

            if (mainClassCandidate == null || !mainClassCandidate.getMainClass().isPresent()) {
                getScalaLoader().getLogger().warning("Could not find main class in file " + file.getName() + ". Did you annotate your main class with @Scala and is it public?");
                getScalaLoader().getLogger().warning("Delegating to JavaPluginLoader...");
                return getJavaPluginLoader().getPluginDescription(file);
            }

            //null for unknown api version
            ApiVersion apiVersion = mainClassCandidate.getBukkitApiVersion().orElse(null);

            //TODO transform bytes if necessary based on apiVersion

            PluginScalaVersion scalaVersion = mainClassCandidate.getScalaVersion().get();

            try {
                //load scala version if not already present
                ScalaLibraryClassLoader scalaLibraryClassLoader = getScalaLoader().loadOrGetScalaVersion(scalaVersion);
                //create plugin classloader using the resolved scala classloader
                ScalaPluginClassLoader scalaPluginClassLoader = new ScalaPluginClassLoader(this, new URL[]{file.toURI().toURL()}, scalaLibraryClassLoader);
                sharedScalaPluginClassLoaders.computeIfAbsent(scalaVersion.getScalaVersion(), v -> new CopyOnWriteArrayList<>()).add(scalaPluginClassLoader);

                //create our plugin
                final String mainClass = mainClassCandidate.getMainClass().get();
                Class<? extends ScalaPlugin> pluginMainClass = (Class<? extends ScalaPlugin>) Class.forName(mainClass, true, scalaPluginClassLoader);
                ScalaPlugin plugin = createPluginInstance(pluginMainClass);

                //api version and main class are detected from the annotation
                plugin.getScalaDescription().setApiVersion(apiVersion == null ? null : apiVersion.getVersionString());
                plugin.getScalaDescription().setMain(mainClass); //required per PluginDescriptionFile constructor - not actually used.

                //just init, don't load yet.
                //TODO the fact that we are calling this stuff here means that this stuff is unavailable in the constructor.
                //TODO can we actually initialize/load this stuff in the ScalaPlugin (I mean the abstract class here) constructor?
                plugin.init(this, server, new File(file.getParent(), plugin.getName()), file, scalaPluginClassLoader);
                if (scalaPlugins.putIfAbsent(plugin.getName().toLowerCase(), plugin) != null) {
                    throw new InvalidDescriptionException("Duplicate plugin names found: " + plugin.getName());
                }

                //be sure to cache the plugin - later in loadPlugin we just return the cached instance!
                scalaPluginsByFile.put(file, plugin);
                return plugin.getDescription();

            } catch (ClassNotFoundException e) {
                throw new InvalidDescriptionException(e, "Could find the class that was found the main class");
            } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                throw new InvalidDescriptionException(e,
                        "Your plugin's constructor and/or initializers tried to access classes that were not yet loaded. " +
                                "Try to move stuff over to onLoad() and onEnable().");
            } catch (ScalaPluginLoaderException e) {
                throw new InvalidDescriptionException(e, "Failed to create scala library classloader");
            }

        } catch (IOException e) {
            throw new InvalidDescriptionException(e, "Could not read jar file " + file.getName());
        }
    }

    @Override
    public Plugin loadPlugin(File file) throws InvalidPluginException, UnknownDependencyException {
        ScalaPlugin plugin = scalaPluginsByFile.get(file);
        if (plugin == null) throw new InvalidPluginException("File " + file.getName() + " does not contain a ScalaPlugin");

        for (String dependency : plugin.getDescription().getDepend()) {
            boolean dependencyFound = server.getPluginManager().getPlugin(dependency) != null;
            if (!dependencyFound) {
                throw new UnknownDependencyException("Dependency " + dependency + " not found while loading plugin " + plugin.getName());
            }
        }

        plugin.getLogger().info("Loading " + plugin.getDescription().getFullName());
        plugin.onLoad();
        return plugin;
    }

    @Override
    public void enablePlugin(Plugin plugin) {
        if (plugin instanceof JavaPlugin) {
            getJavaPluginLoader().enablePlugin(plugin);
        } else if (plugin instanceof ScalaPlugin) {
            ScalaPlugin scalaPlugin = (ScalaPlugin) plugin;
            if (scalaPlugin.isEnabled()) return;

            ScalaPluginEnableEvent event = new ScalaPluginEnableEvent(scalaPlugin);
            server.getPluginManager().callEvent(event);
            if (event.isCancelled()) return;

            plugin.getLogger().info("Enabling " + plugin.getDescription().getFullName());
            scalaPlugin.setEnabled(true);
            scalaPlugin.onEnable();
        } else {
            throw new IllegalArgumentException("ScalaPluginLoader can only enable " + ScalaPlugin.class.getSimpleName() + "s");
        }
    }

    @Override
    public void disablePlugin(Plugin plugin) {
        if (plugin instanceof JavaPlugin) {
            getJavaPluginLoader().disablePlugin(plugin);
        } else if (plugin instanceof ScalaPlugin) {
            ScalaPlugin scalaPlugin = (ScalaPlugin) plugin;
            if (!scalaPlugin.isEnabled()) return;

            ScalaPluginDisableEvent event = new ScalaPluginDisableEvent(scalaPlugin);
            server.getPluginManager().callEvent(event);
            if (event.isCancelled()) return;

            plugin.getLogger().info("Disabling " + plugin.getDescription().getFullName());
            scalaPlugin.onDisable();
            scalaPlugin.setEnabled(false);

            //unload shared classes
            ScalaPluginClassLoader scalaPluginClassLoader = scalaPlugin.getClassLoader();
            String scalaVersion = scalaPluginClassLoader.getScalaVersion();
            Map<String, Class<?>> classes = sharedScalaPluginClasses.get(scalaVersion);
            if (classes != null) {
                scalaPluginClassLoader.getClasses().forEach(clazz -> classes.remove(clazz.getName(), clazz));
                if (classes.isEmpty()) {
                    sharedScalaPluginClasses.remove(scalaVersion);
                }
            }

            CopyOnWriteArrayList<ScalaPluginClassLoader> classLoaders = sharedScalaPluginClassLoaders.get(scalaVersion);
            if (classLoaders != null) {
                classLoaders.remove(scalaPluginClassLoader);
                //noinspection SuspiciousMethodCalls
                sharedScalaPluginClassLoaders.remove(scalaVersion, Collections.singletonList(scalaPluginClassLoader));
                //Atomic remove - CopyOnWriteArrayList will equal any List if the size and the elements are equal.
            }

            try {
                scalaPluginClassLoader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public Pattern[] getPluginFileFilters() {
        Pattern[] patterns = getScalaLoader().getJavaPluginLoaderPattners();
        if (patterns != null) return patterns;
        return pluginFileFilters.clone();
    }

    @Override
    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(Listener listener, Plugin plugin) {
        return getJavaPluginLoader().createRegisteredListeners(listener, plugin);
    }

    public boolean addClassGlobally(String scalaVersion, String className, Class<?> clazz) {
        if (clazz.getClassLoader() instanceof ScalaLibraryClassLoader) return false;

        return sharedScalaPluginClasses
                .computeIfAbsent(scalaVersion, version -> new HashMap<>())
                .putIfAbsent(className, clazz) == null;
    }

    public Class<?> getScalaPluginClass(final String scalaVersion, final String className) throws ClassNotFoundException {
        //try load from 'global' cache
        Map<String, Class<?>> scalaPluginClasses = sharedScalaPluginClasses.get(scalaVersion);
        Class<?> found = scalaPluginClasses == null ? null : scalaPluginClasses.get(className);
        if (found != null) return found;

        //try load from classloaders
        CopyOnWriteArrayList<ScalaPluginClassLoader> classLoaders = sharedScalaPluginClassLoaders.get(scalaVersion);
        if (classLoaders != null) {
            for (ScalaPluginClassLoader scalaPluginClassLoader : classLoaders) {
                try {
                    found = scalaPluginClassLoader.findClass(className, false);
                    break; //no need to call addClassGlobally here - the ScalaPluginClassLoader will do that for us.
                } catch (ClassNotFoundException justContinueOn) {
                }
            }
        }

        if (found == null) {
            throw new ClassNotFoundException("Couldn't find class " + className + " in any of the loaded ScalaPlugins.");
        }

        return found;
    }


    /**
     * Tries to get the plugin instance from the scala plugin class.
     * This method is able to get the static instances from scala `object`s,
     * as well as it is able to create plugins using their public NoArgsConstructors.
     * @param clazz the plugin class
     * @param <P> the plugin type
     * @return the plugin's instance.
     * @throws ScalaPluginLoaderException
     */
    private <P extends ScalaPlugin> P createPluginInstance(Class<P> clazz) throws ScalaPluginLoaderException {
        boolean weFoundAScalaSingletonObject = false;

        if (clazz.getName().endsWith("$")) {
            weFoundAScalaSingletonObject = true;

            //we found a scala singleton object.
            //the instance is already present in the MODULE$ field when this class is loaded.

            try {
                Field field = clazz.getField("MODULE$");
                Object pluginInstance = field.get(null);

                getScalaLoader().getLogger().info("DEBUG got plugin instance for class " + clazz.getName() + " from the static MODULE$ field.");

                return clazz.cast(pluginInstance);
            } catch (NoSuchFieldException e) {
                weFoundAScalaSingletonObject = false; //back paddle!
            } catch (IllegalAccessException e) {
                throw new ScalaPluginLoaderException("Couldn't access MODULE$ field in class " + clazz.getName(), e);
            }
        }

        if (!weFoundAScalaSingletonObject) /*IntelliJ your code inspection is lying. this is not an else-if.*/ {
            //we found are a regular class.
            //it should have a NoArgsConstructor.

            try {
                Constructor ctr = clazz.getConstructor();
                Object pluginInstance = ctr.newInstance();

                getScalaLoader().getLogger().info("DEBUG got plugin instance for class " + clazz.getName() + " form the NoArgsConstructor.");

                return clazz.cast(pluginInstance);
            } catch (IllegalAccessException e) {
                throw new ScalaPluginLoaderException("Could not access the NoArgsConstructor of " + clazz.getName() + ", please make it public", e);
            } catch (InvocationTargetException e) {
                throw new ScalaPluginLoaderException("Error instantiating class " + clazz.getName() + ", its constructor threw something at us", e);
            } catch (NoSuchMethodException e) {
                throw new ScalaPluginLoaderException("Could not find NoArgsConstructor in class " + clazz.getName(), e);
            } catch (InstantiationException e) {
                throw new ScalaPluginLoaderException("Could not instantiate class " + clazz.getName(), e);
            }
        }

        else return null;
    }

}

