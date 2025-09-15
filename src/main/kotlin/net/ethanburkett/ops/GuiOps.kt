package net.ethanburkett.ops

import com.google.gson.JsonObject
import net.ethanburkett.bridge.OpModule
import net.ethanburkett.bridge.RpcServer
import net.ethanburkett.core.ItemUtil
import net.ethanburkett.core.MenuHolder
import net.ethanburkett.core.Threading.runMain
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.java.JavaPlugin
import java.util.*

class GuiOps(private val plugin: JavaPlugin, private val rpc: RpcServer) : OpModule, Listener {

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun handles(kind: String) = kind.startsWith("Gui.")

    override fun handle(kind: String, payload: JsonObject, respond: (Any) -> Unit, error: (String, String) -> Unit) {
        when (kind) {
            "Gui.open" -> runMain(plugin, {
                val playerUuid =
                    payload["uuid"]?.asString ?: return@runMain error("BAD_REQUEST", "missing uuid")
                val player = plugin.server.getPlayer(UUID.fromString(playerUuid)) ?: return@runMain error(
                    "NOT_ONLINE",
                    "player not online"
                )

                val title = payload["title"]?.asString ?: ""
                val size = payload["size"]?.asInt ?: 54
                if (size !in 9..54 || size % 9 != 0) return@runMain error(
                    "BAD_SIZE",
                    "size must be multiple of 9 in 9..54"
                )

                val id = UUID.randomUUID()
                val holder = MenuHolder(id)
                val inv: Inventory = Bukkit.createInventory(holder, size, Component.text(title))

                payload.getAsJsonArray("slots")?.forEach { el ->
                    val s = el.asJsonObject
                    val slot = s["slot"]?.asInt ?: return@runMain error("BAD_SLOT", "missing slot")
                    if (slot !in 0 until inv.size) return@runMain error(
                        "BAD_SLOT",
                        "slot $slot out of bounds 0..${inv.size - 1}"
                    )

                    val itemJson =
                        s.getAsJsonObject("item") ?: return@runMain error("BAD_ITEM", "missing item for slot $slot")
                    val item = try {
                        ItemUtil.fromPayload(itemJson)
                    } catch (t: Throwable) {
                        return@runMain error("BAD_ITEM", "invalid item at slot $slot: ${t.message}")
                    }
                    inv.setItem(slot, item)
                }

                player.openInventory(inv)
                mapOf("menuInstanceId" to id.toString())
            }, respond, error)

            "Gui.updateSlots" -> runMain(plugin, {
                val idStr =
                    payload["menuInstanceId"]?.asString ?: return@runMain error("BAD_REQUEST", "missing menuInstanceId")
                val targetId = runCatching { UUID.fromString(idStr) }.getOrNull()
                    ?: return@runMain error("BAD_REQUEST", "invalid menuInstanceId")

                val inv = plugin.server.onlinePlayers
                    .asSequence()
                    .mapNotNull { it.openInventory?.topInventory }
                    .firstOrNull { (it.holder as? MenuHolder)?.id == targetId }
                    ?: return@runMain error("NOT_FOUND", "menu not open")

                payload.getAsJsonArray("slots")?.forEach { el ->
                    val s = el.asJsonObject
                    val slot = s["slot"]?.asInt ?: return@runMain error("BAD_SLOT", "missing slot")
                    if (slot !in 0 until inv.size) return@runMain error(
                        "BAD_SLOT",
                        "slot $slot out of bounds 0..${inv.size - 1}"
                    )
                    val itemJson =
                        s.getAsJsonObject("item") ?: return@runMain error("BAD_ITEM", "missing item for slot $slot")
                    val item = try {
                        ItemUtil.fromPayload(itemJson)
                    } catch (t: Throwable) {
                        return@runMain error("BAD_ITEM", "invalid item at slot $slot: ${t.message}")
                    }
                    inv.setItem(slot, item)
                }
                mapOf("ok" to true)
            }, respond, error)

            "Gui.close" -> runMain(plugin, {
                val playerUuid = payload["uuid"]?.asString ?: return@runMain error("BAD_REQUEST", "missing uuid")
                val idStr = payload["menuInstanceId"]?.asString
                    ?: return@runMain error("BAD_REQUEST", "missing menuInstanceId")

                val targetId = runCatching { UUID.fromString(idStr) }.getOrNull()
                    ?: return@runMain error("BAD_REQUEST", "invalid menuInstanceId")

                val player = plugin.server.getPlayer(UUID.fromString(playerUuid))
                    ?: return@runMain error("NOT_ONLINE", "player not online")

                val top = player.openInventory?.topInventory
                    ?: return@runMain error("NOT_FOUND", "player has no open inventory")
                val holder = top.holder as? MenuHolder
                    ?: return@runMain error("NOT_FOUND", "player is not viewing a TypeCraft menu")

                if (holder.id != targetId) {
                    return@runMain error("MISMATCH", "different menu is open for player")
                }

                rpc.emit(
                    "Gui.Close",
                    mapOf("menuInstanceId" to holder.id.toString(), "uuid" to player.uniqueId.toString())
                )

                // Close for that player
                player.closeInventory()
                mapOf("ok" to true)
            }, respond, error)

            "Gui.get" -> runMain(plugin, {
                val idStr = payload["menuInstanceId"]?.asString
                    ?: return@runMain error("BAD_REQUEST", "missing menuInstanceId")
                val targetId = runCatching { UUID.fromString(idStr) }.getOrNull()
                    ?: return@runMain error("BAD_REQUEST", "invalid menuInstanceId")

                val inv = plugin.server.onlinePlayers
                    .asSequence()
                    .mapNotNull { it.openInventory?.topInventory }
                    .firstOrNull { (it.holder as? MenuHolder)?.id == targetId }
                    ?: return@runMain error("NOT_FOUND", "menu not open")

                val viewers = inv.viewers.mapNotNull { it as? Player }
                    .map { mapOf("name" to it.name, "uuid" to it.uniqueId.toString()) }

                val slots = (0 until inv.size).mapNotNull { slot ->
                    val item = inv.getItem(slot)
                    val json = ItemUtil.toPayload(item)
                    json?.let { mapOf("slot" to slot, "item" to it) }
                }

                mapOf(
                    "menuInstanceId" to targetId.toString(),
                    "size" to inv.size,
                    "viewers" to viewers,
                    "slots" to slots,
                    "ok" to true
                )
            }, respond, error)

            else -> error("UNKNOWN", kind)
        }
    }

    // --- events ---

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val top = e.view.topInventory
        val holder = top.holder as? MenuHolder ?: return
        val menuId = holder.id

        e.isCancelled = true

        val p = e.whoClicked as? Player ?: return
        val bottom = e.view.bottomInventory
        val region = when (e.clickedInventory) {
            top -> "TOP"
            bottom -> "PLAYER"
            else -> "OTHER"
        }

        val payload = mutableMapOf<String, Any>(
            "menuInstanceId" to menuId.toString(),
            "rawSlot" to e.rawSlot,
            "slot" to e.slot,
            "region" to region,
            "clickType" to e.click.name,
            "action" to e.action.name,
            "shift" to e.isShiftClick,
            "uuid" to p.uniqueId.toString()
        )

        ItemUtil.toPayload(e.currentItem)?.let { payload["item"] = it }
        ItemUtil.toPayload(e.cursor)?.let { payload["cursorItem"] = it }
        if (e.hotbarButton >= 0) {
            payload["hotbarButton"] = e.hotbarButton
            ItemUtil.toPayload(p.inventory.getItem(e.hotbarButton))?.let { payload["hotbarItem"] = it }
        }

        rpc.emit("Gui.Click", payload)
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val holder = e.view.topInventory.holder as? MenuHolder ?: return
        val p = e.player as? Player ?: return
        rpc.emit("Gui.Close", mapOf("menuInstanceId" to holder.id.toString(), "uuid" to p.uniqueId.toString()))
    }
}
