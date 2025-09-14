package net.ethanburkett.core

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import java.util.*

class MenuHolder(val id: UUID) : InventoryHolder {
    private lateinit var inv: Inventory
    fun attach(inventory: Inventory) {
        this.inv = inventory
    }

    override fun getInventory(): Inventory = inv
}