package net.ethanburkett.core

import org.bukkit.inventory.Inventory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MenuRegistry {
    private val byInv = ConcurrentHashMap<Inventory, UUID>()
    private val byId = ConcurrentHashMap<UUID, Inventory>()

    fun register(id: UUID, inv: Inventory) {
        byInv[inv] = id; byId[id] = inv; }

    fun idOf(inv: Inventory): UUID? = byInv[inv]
    fun invOf(id: UUID): Inventory? = byId[id]
    fun remove(id: UUID) {
        byId[id]?.let { byInv.remove(it) }; byId.remove(id)
    }
}