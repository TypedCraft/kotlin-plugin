package net.ethanburkett

import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.ethanburkett.bridge.OpModule
import net.ethanburkett.bridge.RpcServer
import net.ethanburkett.ops.*
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration

class TypeCraft : JavaPlugin(), Listener {
    private val host = "0.0.0.0"
    private val port = 3001
    private val secret = "supersecret"

    private lateinit var commandOps: CommandOps

    @Volatile
    private var rpc: RpcServer? = null

    private val moduleFactories = mutableListOf<(RpcServer) -> OpModule>()

    fun addModule(factory: (RpcServer) -> OpModule) {
        moduleFactories += factory
    }

    private fun newRpc(): RpcServer {
        val s = RpcServer(this, host, port, secret)
        moduleFactories.forEach { s.register(it(s)) }
        return s
    }

    private fun RpcServer.stopAndWait(timeout: Duration = Duration.ofSeconds(10), pollMs: Long = 50): Boolean {
        this.stop()

        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (!this.isRunning) return true
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                break
            }
        }
        return !this.isRunning
    }

    override fun onLoad() {
        commandOps = CommandOps(this, rootLabel = "typecraft")
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            commandOps.bindBrigadier { node -> event.registrar().register(node) }

            val admin = Commands.literal("typecraftctl")
                .then(
                    Commands.literal("reload")
                        .executes { ctx ->
                            val sender = ctx.source.sender
                            val mm = MiniMessage.miniMessage()

                            sender.sendMessage(mm.deserialize("<gray>[<green>TypeCraft<gray>] <white>Reloading RPC server…"))

                            val plugin = this@TypeCraft
                            Bukkit.getAsyncScheduler().runNow(plugin) {
                                val old = rpc
                                runCatching { old?.stop(1_000) }

                                Thread.sleep(150) // small grace

                                val freshResult = runCatching {
                                    val fresh = newRpc()
                                    fresh.start()
                                    fresh
                                }

                                Bukkit.getGlobalRegionScheduler().execute(plugin) {
                                    freshResult.onSuccess { fresh ->
                                        rpc = fresh
                                        sender.sendMessage(mm.deserialize("<gray>[<green>TypeCraft<gray>] <green>Successfully reloaded!"))
                                    }.onFailure { e ->
                                        rpc = null
                                        sender.sendMessage(mm.deserialize("<gray>[<green>TypeCraft<gray>] <red>Failed to start: ${e.message}"))
                                    }
                                }
                            }
                            1
                        }
                )
                .build()

            event.registrar().register(admin)
        }
    }

    override fun onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this)

        addModule { s -> PlayerOps(this) }
        addModule { s ->
            commandOps.attachRpc(s)
            commandOps
        }
        addModule { s -> RecipeOps(this) }
        addModule { s -> ItemsOps(this) }
        addModule { s -> EntityOps(this) }
        addModule { s -> SchedulerOps(this) }
        addModule { s -> GuiOps(this, s) }
        addModule { s -> EventsOps(this, s, commandOps) }

        val fresh = newRpc()
        fresh.start()
        rpc = fresh

        logger.info("TypeCraft listening on ws://0.0.0.0:3001")
    }

    override fun onDisable() {
        rpc?.stop(1000)
        rpc = null
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        rpc?.emit(
            "Player.Join", mapOf(
                "player" to mapOf("name" to e.player.name, "uuid" to e.player.uniqueId.toString())
            )
        )
    }
}
