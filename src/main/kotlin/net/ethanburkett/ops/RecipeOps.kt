package net.ethanburkett.ops

import com.google.gson.JsonObject
import net.ethanburkett.bridge.OpModule
import net.ethanburkett.core.ItemUtil
import net.ethanburkett.core.Threading.runMain
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.plugin.java.JavaPlugin

class RecipeOps(private val plugin: JavaPlugin) : OpModule {
    override fun handles(kind: String) = kind.startsWith("Recipe.")

    override fun handle(kind: String, payload: JsonObject, respond: (Any) -> Unit, error: (String, String) -> Unit) {
        when (kind) {
            "Recipe.upsertShaped" -> runMain(plugin, {
                val keyObj = payload.getAsJsonObject("key")
                val ns = keyObj["namespace"].asString
                val kk = keyObj["key"].asString
                val namespaced = NamespacedKey(ns, kk)

                Bukkit.removeRecipe(namespaced)

                val result = ItemUtil.fromPayload(payload.getAsJsonObject("result"))
                val shaped = ShapedRecipe(namespaced, result)

                val shape = payload.getAsJsonArray("shape").map { it.asString }.toTypedArray()
                shaped.shape(*shape)

                val keys = payload.getAsJsonObject("keys")
                for ((ch, ing) in keys.entrySet()) {
                    val choice = toChoice(ing.asJsonObject)
                    shaped.setIngredient(ch[0], choice)
                }

                Bukkit.addRecipe(shaped)
                mapOf("ok" to true)
            }, respond, error)

            else -> error("UNKNOWN", kind)
        }
    }

    private fun toChoice(o: JsonObject): RecipeChoice {
        return when (o["type"].asString) {
            "MATERIAL" -> RecipeChoice.MaterialChoice(org.bukkit.Material.matchMaterial(o["material"].asString)!!)
            else -> RecipeChoice.MaterialChoice(org.bukkit.Material.STONE)
        }
    }
}