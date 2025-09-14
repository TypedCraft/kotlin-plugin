package net.ethanburkett.ops

import com.google.gson.JsonObject
import net.ethanburkett.bridge.OpModule
import net.ethanburkett.core.Threading.runMain
import org.bukkit.plugin.java.JavaPlugin


class ItemsOps(private val plugin: JavaPlugin) : OpModule {
    override fun handles(kind: String) = kind.startsWith("Items.")


    override fun handle(kind: String, payload: JsonObject, respond: (Any) -> Unit, error: (String, String) -> Unit) {
        when (kind) {
            "Items.dummy" -> runMain(plugin, {
                mapOf("ok" to true)
            }, respond, error)

            else -> error("UNKNOWN", kind)
        }
    }
}