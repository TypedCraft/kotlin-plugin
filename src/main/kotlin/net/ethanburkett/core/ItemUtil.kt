package net.ethanburkett.core

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

object ItemUtil {
    private val mm = MiniMessage.miniMessage()
    fun fromPayload(js: com.google.gson.JsonObject): ItemStack {
        fun str(key: String): String? = js.get(key)?.takeUnless { it.isJsonNull }?.asString
        fun i(key: String): Int? = js.get(key)?.takeUnless { it.isJsonNull }?.asInt
        fun b(key: String): Boolean? = js.get(key)?.takeUnless { it.isJsonNull }?.asBoolean

        val materialStr = str("material") ?: throw IllegalArgumentException("material is required")
        val material = org.bukkit.Material.matchMaterial(materialStr)
            ?: throw IllegalArgumentException("unknown material '$materialStr'")

        val amount = i("amount") ?: 1
        val item = ItemStack(material, amount.coerceIn(1, 64))

        val meta = item.itemMeta

        str("name")?.let { meta.displayName(mm.deserialize(it)) }

        js.getAsJsonArray("lore")?.let { arr ->
            val lines = arr.mapNotNull { el -> el.takeUnless { it.isJsonNull }?.asString }
                .map { mm.deserialize(it) }
            meta.lore(lines)
        }

        i("customModelData")?.let { meta.setCustomModelData(it) }
        b("unbreakable")?.let { meta.isUnbreakable = it }

        js.getAsJsonArray("flags")?.forEach { el ->
            val name = el.asString
            runCatching { ItemFlag.valueOf(name) }
                .onSuccess { meta.addItemFlags(it) }
        }

        js.getAsJsonArray("enchantments")?.forEach { el ->
            val obj = el.asJsonObject
            val enchKey = obj.get("type")?.asString ?: return@forEach
            val level = obj.get("level")?.asInt ?: 1
            val ench = Enchantment.getByName(enchKey.uppercase())
                ?: return@forEach
            meta.addEnchant(ench, level, true)
        }

        item.itemMeta = meta
        return item
    }

    fun toPayload(item: ItemStack?): Map<String, Any>? {
        if (item == null || item.type.isAir) return null
        val out = mutableMapOf<String, Any>(
            "material" to item.type.name,
            "amount" to item.amount
        )
        val meta = item.itemMeta ?: return out
        val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        meta.displayName()?.let { out["name"] = plain.serialize(it) }
        meta.lore()?.let { out["lore"] = it.map { c -> plain.serialize(c) } }
        meta.customModelData?.let { out["customModelData"] = it }
        if (meta.isUnbreakable) out["unbreakable"] = true
        if (meta.itemFlags.isNotEmpty()) out["flags"] = meta.itemFlags.map { it.name }
        if (meta.hasEnchants()) {
            out["enchantments"] = meta.enchants.map { (ench, lvl) ->
                mapOf("type" to ench.key.key.uppercase(), "level" to lvl)
            }
        }
        return out
    }
}