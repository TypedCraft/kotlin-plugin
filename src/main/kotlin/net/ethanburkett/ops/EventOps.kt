package net.ethanburkett.ops

import com.google.gson.JsonObject
import io.papermc.paper.event.player.AsyncChatEvent
import net.ethanburkett.bridge.OpModule
import net.ethanburkett.bridge.RpcServer
import net.ethanburkett.core.Threading.runMain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class EventsOps(
    private val plugin: JavaPlugin,
    private val rpc: RpcServer,
    private val commands: CommandOps
) : OpModule, Listener {

    private val mode = AtomicReference(ChatMode.VANILLA)
    private val mm = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    enum class ChatMode { VANILLA, PROXY }

    override fun handles(kind: String) = kind.startsWith("Events.Chat.")

    override fun handle(
        kind: String,
        payload: JsonObject,
        respond: (Any) -> Unit,
        error: (String, String) -> Unit
    ) {
        when (kind) {
            "Events.Chat.setMode" -> {
                val m = payload["mode"]?.asString ?: return error("BAD_REQUEST", "missing mode")
                val newMode = runCatching { ChatMode.valueOf(m.uppercase(Locale.getDefault())) }.getOrNull()
                    ?: return error("BAD_REQUEST", "mode must be VANILLA or PROXY")
                mode.set(newMode)
                respond(mapOf("ok" to true, "mode" to newMode.name))
            }

            "Events.Chat.broadcast" -> {
                val text = payload["text"]?.asString ?: return error("BAD_REQUEST", "missing text")
                val fmt = payload["format"]?.asString ?: "mini" // "mini" | "plain"
                val recips = payload.getAsJsonArray("recipients")?.mapNotNull {
                    runCatching { UUID.fromString(it.asString) }.getOrNull()
                } ?: emptyList()

                val component: Component = if (fmt.equals("plain", true)) {
                    Component.text(text)
                } else {
                    mm.deserialize(text)
                }

                runMain(plugin, {
                    if (recips.isEmpty()) {
                        plugin.server.onlinePlayers.forEach { it.sendMessage(component) }
                    } else {
                        recips.forEachNotNull { uuid ->
                            plugin.server.getPlayer(uuid)?.sendMessage(component)
                        }
                    }

                    mapOf("ok" to true)
                }, respond, error)
            }

            else -> error("UNKNOWN", kind)
        }
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onAsyncChatModern(e: AsyncChatEvent) {
        rpc.emit(
            "Player.Chat",
            mapOf(
                "player" to mapOf(
                    "name" to e.player.name,
                    "uuid" to e.player.uniqueId.toString()
                ),
                "message" to plain.serialize(e.message())
            )
        )

        if (mode.get() == ChatMode.PROXY) {
            e.isCancelled = true
            e.viewers().clear()
        }
    }

    /**
     * Legacy Bukkit event — keep it only to cancel for old listeners,
     * but DO NOT emit from here or we'll double-send messages...
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onAsyncChatLegacy(e: AsyncPlayerChatEvent) {
        if (mode.get() == ChatMode.PROXY) {
            e.isCancelled = true
        }
    }

    /**
     * Command overrider
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPreprocess(e: PlayerCommandPreprocessEvent) {
        val msg = e.message
        if (!msg.startsWith("/")) return
        val parts = msg.substring(1).trim().split(' ')
        if (parts.isEmpty()) return

        val label = parts[0].lowercase()
        val args = if (parts.size > 1) parts.drop(1) else emptyList()

        val ownsReal = plugin.server.commandMap.getCommand(label) != null
        if (ownsReal) return

        if (commands.hasLabel(label)) {
            e.isCancelled = true
            commands.executeLabel(e.player, label, args)
        }
    }
}

private inline fun <T> Iterable<T?>.forEachNotNull(block: (T) -> Unit) {
    for (e in this) if (e != null) block(e)
}
