#### Motivation

So you want to write plugins in Scala? Great! Or not so great? Scala runtime classes are not on the class/module path
by default. Server administrators *could* add them using the -classpath commandline option, but in reality most bukkit
servers run in managed environments by minecraft-specialized server hosts. The standard remedy for this problem is to
include the classes in your own plugin and relocate them using a shading plugin in your build process.
While this *does* work, it is not ideal because your plugin will increase in size by a lot. As of Scala 2.12.6, the
standard library has a size of 3.5 MB. The reflection library is another 5 MB. Using both libraries in multiple plugins
results unnessesarily large plugins sizes, while really those Scala classes should only be loaded once. Introducing...

# ScalaLoader

ScalaLoader uses a custom PluginLoader that loads the Scala runtime classes for you!

#### Pros
- Write idiomatic Scala!
- No need to shade anymore!
- Support multiple Scala versions at the same time!
- Supports custom scala versions
- Annotation-based detection of the plugin's main class - no need to write a plugin.yml

#### Cons
- Scala library classes are only accessible to ScalaPlugins (You can still write them in Java though).
- By default ScalaLoader downloads the scala libraries from over the network the first time.
If you're a security-focused person you might want to provide your own jars by changing the URLs to "file://some/location".
The scala classes aren't actually loaded until there's a plugin that needs them, so you can run ScalaLoader once without
ScalaPlugins to generate the config.
- ScalaLoaders uses a lot of reflection/injection hacks to make ScalaPlugins accessible to JavaPlugins.
- Existing libraries *might* rely on the fact that your Plugin is a JavaPlugin.

### Roadmap
There's only three features that are missing in my opinion:
- The first con. I want JavaPlugins te be able to access the Scala library classes, however they will need to tell
ScalaLoader somehow which version they want to use.
- An idiomatic Scala 'wrapper' for the bukkit api. This will likely be provided in a separate plugin writtin in Scala.
Things that come to mind: Use of Options instead of null, using the type-class pattern for ConfigurationSerializable things.
- Use bukkit's api-version to transform classes so that plugins will be compatible once they are loaded.

### Example Plugin

```
package xyz.janboerman.scalaloader.example.scala

import org.bukkit.{ChatColor}
import org.bukkit.command.{CommandSender, Command}
import org.bukkit.event.{EventHandler, Listener}
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.permissions.PermissionDefault
import xyz.janboerman.scalaloader.plugin.ScalaPluginDescription.{Command => SPCommand, Permission => SPPermission}
import xyz.janboerman.scalaloader.plugin.{ScalaPlugin, ScalaPluginDescription}
import xyz.janboerman.scalaloader.plugin.description.{Scala, ScalaVersion}

object Description {
    val value = new ScalaPluginDescription("ScalaExample", "0.1-SNAPSHOT")
        .commands(new SPCommand("foo")
            .permission("scalaexample.foo"))
        .permissions(new SPPermission("scalaexample.foo")
            .permissionDefault(PermissionDefault.TRUE))
}

@Scala(version = ScalaVersion.v2_12_6)
object ExamplePlugin extends ScalaPlugin(Description.value) with Listener {

    getLogger().info("ScalaExample - I am constructed!")

    override def onLoad(): Unit = {
        getLogger.info("ScalaExample - I am loaded!")
    }

    override def onEnable(): Unit = {
        getLogger.info("ScalaExample - I am enabled!")
        getServer.getPluginManager.registerEvents(this, this)
    }

    override def onDisable(): Unit = {
        getLogger.info("ScalaExample - I am disabled!")
    }

    @EventHandler
    def onJoin(event: PlayerJoinEvent): Unit = {
        event.setJoinMessage(ChatColor.GREEN + "Howdy " + event.getPlayer.getName + "!")
    }

    override def onCommand(sender: CommandSender, command: Command, label: String, args: Array[String]): Boolean = {
        sender.sendMessage("Executed foo command!")
        true
    }

    def getInt() = 42

}
```

### Depending on a ScalaPlugin from a JavaPlugin

plugin.yml:
```
name: DummyPlugin
version: 1.0
main: xyz.janboerman.dummy.dummyplugin.DummyPlugin
depend: [ScalaLoader]
softdepend: [ScalaExample] #A hard dependency will not work! Your plugin will not load!
```

Java code:
```
package xyz.janboerman.dummy.dummyplugin;

import org.bukkit.plugin.java.JavaPlugin;
import xyz.janboerman.scalaloader.example.scala.ExamplePlugin$;

public final class DummyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        ExamplePlugin$ plugin = (ExamplePlugin$) getServer().getPluginManager().getPlugin("ScalaExample");
        getLogger().info("We got " + plugin.getInt() + " from Scala!");
    }

}
```
