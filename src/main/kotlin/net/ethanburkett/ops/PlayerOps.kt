package net.ethanburkett.ops

import com.google.gson.JsonObject
import net.ethanburkett.bridge.OpModule
import net.ethanburkett.core.ItemUtil
import net.ethanburkett.core.Threading.runMain
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.*

class PlayerOps(private val plugin: JavaPlugin) : OpModule {
    override fun handles(kind: String) = kind.startsWith("Player.")

    override fun handle(kind: String, payload: JsonObject, respond: (Any) -> Unit, error: (String, String) -> Unit) {
        fun badRequest(msg: String) = error("BAD_REQUEST", msg)

        when (kind) {
            "Player.get" -> runMain(plugin, {
                val uuidStr = payload.get("uuid")?.asString ?: payload.get("playerUuid")?.asString
                val nameStr = payload.get("name")?.asString

                val online: Player?
                val offline: OfflinePlayer?

                if (uuidStr != null) {
                    val uuid = parseUuidOrThrow(uuidStr)
                    online = plugin.server.getPlayer(uuid)
                    offline = online ?: plugin.server.getOfflinePlayer(uuid)
                } else if (nameStr != null) {
                    online = plugin.server.getPlayerExact(nameStr)
                    offline = online ?: plugin.server.getOfflinePlayer(nameStr)
                } else {
                    throw IllegalArgumentException("Provide 'uuid' (or 'playerUuid') or 'name'")
                }

                val p = online
                var base = mutableMapOf<String, Any?>(
                    "uuid" to (p?.uniqueId ?: offline?.uniqueId)?.toString(),
                    "name" to (p?.name ?: offline?.name),
                    "online" to (p != null)
                )
                if (p != null) {
                    base = playerSnapshot(p)
                }
                mapOf("player" to base, "ok" to true)
            }, respond) { code, msg -> if (code == "EX") badRequest(msg) else error(code, msg) }

            "Player.getFromUUID" -> runMain(plugin, {
                val uuid = parseUuidOrThrow(requireString(payload, "uuid"))
                val online = plugin.server.getPlayer(uuid)
                val offline = online ?: plugin.server.getOfflinePlayer(uuid)
                mapOf(
                    "player" to mapOf(
                        "uuid" to uuid.toString(),
                        "name" to (online?.name ?: offline.name),
                        "online" to (online != null)
                    )
                )
            }, respond) { code, msg -> if (code == "EX") badRequest(msg) else error(code, msg) }

            "Player.sendMessage" -> runMain(plugin, {
                val uuid = parseUuidOrThrow(requireAnyString(payload, "uuid", "playerUuid"))
                val text = requireString(payload, "text")
                val p = plugin.server.getPlayer(uuid) ?: throw IllegalStateException("Player not online")
                p.sendMessage(text)
                mapOf("ok" to true)
            }, respond) { code, msg -> if (code == "EX") badRequest(msg) else error(code, msg) }

            "Player.give" -> runMain(plugin, {
                val uuid = parseUuidOrThrow(requireAnyString(payload, "playerUuid", "uuid"))
                val p = plugin.server.getPlayer(uuid) ?: throw IllegalStateException("Player not online")
                val item = ItemUtil.fromPayload(
                    payload.getAsJsonObject("item")
                        ?: throw IllegalArgumentException("Missing field 'item'")
                )
                p.inventory.addItem(item)
                mapOf("ok" to true)
            }, respond) { code, msg -> if (code == "EX") badRequest(msg) else error(code, msg) }

            else -> error("UNKNOWN", kind)
        }
    }
}

/* ---------- helpers ---------- */

private fun requireString(o: JsonObject, key: String): String {
    return o.get(key)?.asString ?: throw IllegalArgumentException("Missing field '$key'")
}

private fun requireAnyString(o: JsonObject, vararg keys: String): String {
    for (k in keys) o.get(k)?.asString?.let { return it }
    throw IllegalArgumentException("Missing one of fields ${keys.joinToString(", ") { "'$it'" }}")
}

private fun parseUuidOrThrow(s: String): UUID =
    try {
        UUID.fromString(s)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid UUID format")
    }

private fun locDto(loc: Location?): Map<String, Any?>? = loc?.let {
    mapOf(
        "world" to it.world?.name,
        "x" to it.x,
        "y" to it.y,
        "z" to it.z,
        "yaw" to it.yaw,
        "pitch" to it.pitch
    )
}

private fun playerSnapshot(p: Player): MutableMap<String, Any?> = mutableMapOf(
    "uuid" to p.uniqueId,
    "name" to p.name,
    "online" to true,
    "world" to p.world.name,
    "x" to p.location.x,
    "y" to p.location.y,
    "z" to p.location.z,
    "compassTarget" to locDto(p.compassTarget), // <— flattened
    "exp" to p.exp,
    "experiencePointsNeededForNextLevel" to p.experiencePointsNeededForNextLevel,
    "healthScale" to p.healthScale,
    "health" to p.health,
    "isFlying" to p.isFlying,
    "isSleeping" to p.isSleeping,
    "level" to p.level,
    "totalExperience" to p.totalExperience,
    "ping" to p.ping,
    "walkSpeed" to p.walkSpeed
)