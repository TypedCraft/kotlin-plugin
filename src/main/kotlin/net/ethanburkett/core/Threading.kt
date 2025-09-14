package net.ethanburkett.core

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

object Threading {
    inline fun runMain(
        plugin: JavaPlugin,
        crossinline block: () -> Any,
        crossinline onOk: (Any) -> Unit,
        crossinline onErr: (String, String) -> Unit
    ) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                onOk(block())
            } catch (t: Throwable) {
                onErr("EX", t.message ?: "")
            }
        })
    }
}