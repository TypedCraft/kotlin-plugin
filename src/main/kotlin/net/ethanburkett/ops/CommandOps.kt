package net.ethanburkett.ops

import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.ethanburkett.bridge.OpModule
import net.ethanburkett.bridge.RpcServer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Dynamic subcommands under a single static root (safe with Paper 1.21).
 *
 * Root label defaults to the plugin's lowercase name, e.g. "/typecraft".
 * Usage: /<root> <subcommand> args...
 *
 * TS registers *subcommands* only; we never add/remove Brigadier roots at runtime.
 */
class CommandOps(
    private val plugin: JavaPlugin,
    private val rootLabel: String = plugin.name.lowercase(Locale.getDefault()),
) : OpModule {
    private val rpcRef = AtomicReference<RpcServer?>()
    fun attachRpc(rpc: RpcServer) {
        rpcRef.set(rpc)
    }

    private fun emit(kind: String, payload: Any) {
        rpcRef.get()?.emit(kind, payload)
    }

    /** Desired subcommands queued by TS (we can accept at any time). */
    private val pending = CopyOnWriteArrayList<Spec>()

    /** Active subcommands by primary name. */
    private val registry = ConcurrentHashMap<String, Spec>()

    /** alias -> primary name map (kept in sync with registry). */
    private val aliasToPrimary = ConcurrentHashMap<String, String>()

    /** Paper lifecycle registrar (only valid inside COMMANDS). */
    private val registrarFn = AtomicReference<(LiteralCommandNode<CommandSourceStack>) -> Unit>(null)

    override fun handles(kind: String) = kind.startsWith("Command.")

    override fun handle(
        kind: String,
        payload: JsonObject,
        respond: (Any) -> Unit,
        error: (String, String) -> Unit
    ) {
        when (kind) {
            "Command.register" -> {
                val rawName = payload["name"]?.asString ?: return error("BAD_REQUEST", "missing name")
                val name = sanitize(rawName)
                val perm = payload.get("permission")?.asString
                val aliases = payload.getAsJsonArray("aliases")?.map { sanitize(it.asString) } ?: emptyList()

                val spec = Spec(name = name, permission = perm, aliases = aliases)
                upsertSpec(spec)
                respond(mapOf("ok" to true, "root" to rootLabel, "sub" to name))
            }

            "Command.unregister" -> {
                val rawName = payload["name"]?.asString ?: return error("BAD_REQUEST", "missing name")
                val name = sanitize(rawName)
                removeSpec(name)
                respond(mapOf("ok" to true))
            }

            "Command.dispatch" -> {
                val asWho = payload["as"]?.asString ?: "CONSOLE"
                val line = payload["line"]?.asString ?: return error("BAD_REQUEST", "missing line")
                val full = "$rootLabel $line"
                val ok = when (asWho.uppercase(Locale.getDefault())) {
                    "PLAYER" -> {
                        val uuid = payload["uuid"]?.asString ?: return error("BAD_REQUEST", "missing uuid")
                        val p = plugin.server.getPlayer(UUID.fromString(uuid))
                        if (p != null) plugin.server.dispatchCommand(p, full) else false
                    }

                    else -> plugin.server.dispatchCommand(plugin.server.consoleSender, full)
                }
                respond(mapOf("ok" to ok))
            }

            else -> error("UNKNOWN", kind)
        }
    }

    /**
     * Register the static root during LifecycleEvents.COMMANDS.
     */
    fun bindBrigadier(registrar: (LiteralCommandNode<CommandSourceStack>) -> Unit) {
        registrarFn.set(registrar)

        // 1) Static root: /<rootLabel> <rest...>
        val root: LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(rootLabel)
        root.executes { ctx -> showHelp(ctx.source.sender); 1 }

        val suggester = SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val input = builder.input.substring(builder.start)
            val first = input.trim().split(' ', limit = 2).firstOrNull().orEmpty().lowercase(Locale.getDefault())
            val sender = ctx.source.sender
            val seen = HashSet<String>()
            allLabels()
                .asSequence()
                .filter { it.startsWith(first) }
                .filter { canUse(sender, resolvePrimary(it)) }
                .forEach { if (seen.add(it)) builder.suggest(it) }
            builder.buildFuture()
        }

        val restNode: RequiredArgumentBuilder<CommandSourceStack, String> =
            Commands.argument("rest", StringArgumentType.greedyString())
                .suggests(suggester)
                .executes { ctx ->
                    val raw = StringArgumentType.getString(ctx, "rest").trim()
                    val tokens = if (raw.isEmpty()) emptyList() else raw.split(' ')
                    route(ctx.source, tokens)
                    1
                }

        root.then(restNode)
        registrar(root.build())

        val primaries: List<String> = registry.keys.toList()
        primaries.forEach { primary ->
            val labels = buildList {
                add(primary)
                aliasToPrimary.forEach { (alias, p) -> if (p == primary) add(alias) }
            }

            labels.forEach { label ->
                val node = Commands.literal(label)
                    .requires { src -> canUse(src.sender, primary) }
                    .executes { ctx ->
                        executeLabel(ctx.source.sender, label, emptyList()); 1
                    }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val raw = StringArgumentType.getString(ctx, "args").trim()
                                val args = if (raw.isEmpty()) emptyList() else raw.split(' ')
                                executeLabel(ctx.source.sender, label, args); 1
                            }
                    )
                    .build()

                registrar(node)
            }
        }

        registrarFn.set(null)
    }

    // ----- internal routing / registry -----

    private data class Spec(
        val name: String,
        val permission: String?,
        val aliases: List<String>
    )

    private fun sanitize(input: String): String =
        input.trim().lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9_\\-]"), "-")
            .replace(Regex("^-+"), "")
            .ifEmpty { "cmd" }

    private fun upsertSpec(spec: Spec) {
        registry[spec.name] = spec
        // remove old aliases pointing to this primary
        aliasToPrimary.entries.removeIf { it.value == spec.name }
        // add current aliases
        spec.aliases.forEach { a -> aliasToPrimary[a] = spec.name }
        // keep a record of all desired subs (not strictly required now, but harmless)
        pending.removeIf { it.name == spec.name }
        pending.add(spec)
    }

    private fun removeSpec(primary: String) {
        registry.remove(primary)?.let { removed ->
            aliasToPrimary.entries.removeIf { it.value == removed.name }
        }
        pending.removeIf { it.name == primary }
    }

    private fun allLabels(): Set<String> =
        buildSet {
            addAll(registry.keys)
            addAll(aliasToPrimary.keys)
        }

    private fun resolvePrimary(label: String): String =
        registry[label]?.name ?: aliasToPrimary[label] ?: label

    private fun canUse(sender: CommandSender, primary: String): Boolean {
        val spec = registry[primary] ?: return false
        val perm = spec.permission ?: return true
        return sender.hasPermission(perm)
    }

    /** Handle `/root <tokens...>` */
    private fun route(source: CommandSourceStack, tokens: List<String>) {
        val sender = source.sender
        if (tokens.isEmpty()) {
            showHelp(sender); return
        }

        val label = sanitize(tokens[0])
        val primary = resolvePrimary(label)
        val spec = registry[primary]
        if (spec == null) {
            sender.sendMessage("§cUnknown subcommand: §f$label")
            showHelp(sender)
            return
        }

        if (!canUse(sender, primary)) {
            sender.sendMessage("§cYou don't have permission to use §f/$rootLabel $label")
            return
        }

        val args = if (tokens.size <= 1) emptyList() else tokens.drop(1)

        val payload = mutableMapOf<String, Any>(
            "name" to primary,
            "label" to label,
            "args" to args
        )
        if (sender is Player) {
            payload["sender"] = mapOf("type" to "PLAYER", "name" to sender.name, "uuid" to sender.uniqueId.toString())
        } else {
            payload["sender"] = mapOf("type" to "CONSOLE")
        }

        emit("Command.Execute", payload)
    }

    private fun showHelp(sender: CommandSender) {
        val visible = registry.keys
            .sorted()
            .filter { canUse(sender, it) }

        if (visible.isEmpty()) {
            sender.sendMessage("§7No subcommands available.")
            return
        }

        sender.sendMessage("§a/$rootLabel §7<subcommand> §8…")
        visible.forEach { name ->
            val aliases = registry[name]?.aliases.orEmpty()
            val aliasStr = if (aliases.isEmpty()) "" else " §8(aliases: ${aliases.joinToString(", ")})"
            sender.sendMessage("§7- §e$name$aliasStr")
        }
    }

    fun hasLabel(label: String): Boolean =
        registry.containsKey(label) || aliasToPrimary.containsKey(label)

    fun executeLabel(sender: CommandSender, labelRaw: String, args: List<String>) {
        val label = sanitize(labelRaw)
        val primary = resolvePrimary(label)
        registry[primary] ?: return
        if (!canUse(sender, primary)) return

        val payload = mutableMapOf<String, Any>(
            "name" to primary,
            "label" to label,
            "args" to args
        )
        if (sender is Player) {
            payload["sender"] = mapOf("type" to "PLAYER", "name" to sender.name, "uuid" to sender.uniqueId.toString())
        } else {
            payload["sender"] = mapOf("type" to "CONSOLE")
        }
        emit("Command.Execute", payload)
    }
}
