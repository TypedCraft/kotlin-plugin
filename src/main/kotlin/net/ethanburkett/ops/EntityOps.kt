package net.ethanburkett.ops

import com.google.gson.JsonObject
import net.ethanburkett.bridge.OpModule
import net.ethanburkett.core.Threading.runMain
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.plugin.java.JavaPlugin


class EntityOps(private val plugin: JavaPlugin) : OpModule {
    override fun handles(kind: String) = kind.startsWith("Entity.")

    override fun handle(kind: String, payload: JsonObject, respond: (Any) -> Unit, error: (String, String) -> Unit) {
        when (kind) {
            "Entity.spawn" -> runMain(plugin, {
                val type = EntityType.valueOf(payload["type"].asString)
                val w = plugin.server.getWorld(payload["world"].asString) ?: error("world not found")
                val loc = Location(
                    w,
                    payload["x"].asDouble,
                    payload["y"].asDouble,
                    payload["z"].asDouble,
                    payload["yaw"]?.asFloat ?: 0f,
                    payload["pitch"]?.asFloat ?: 0f
                )
                val e = w.spawnEntity(loc, type)
                payload["name"]?.asString?.let { nm ->
                    e.customName(Component.text(nm)); e.isCustomNameVisible = true
                }
                mapOf("entityUuid" to e.uniqueId.toString())
            }, respond, error)

            else -> error("UNKNOWN", kind)
        }
    }
}