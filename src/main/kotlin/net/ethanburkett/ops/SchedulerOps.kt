package net.ethanburkett.ops


import com.google.gson.JsonObject
import net.ethanburkett.bridge.OpModule
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin


class SchedulerOps(private val plugin: JavaPlugin) : OpModule {
    override fun handles(kind: String) = kind.startsWith("Scheduler.")


    override fun handle(kind: String, payload: JsonObject, respond: (Any) -> Unit, error: (String, String) -> Unit) {
        when (kind) {
            "Scheduler.runLater" -> {
                val ticks = payload["ticks"].asLong
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { respond(mapOf("done" to true)) }, ticks)
            }

            else -> error("UNKNOWN", kind)
        }
    }
}