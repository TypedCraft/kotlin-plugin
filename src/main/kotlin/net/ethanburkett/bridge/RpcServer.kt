package net.ethanburkett.bridge

import com.google.gson.JsonObject
import net.ethanburkett.bridge.Json.gson
import org.bukkit.plugin.java.JavaPlugin
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface OpModule {
    fun handles(kind: String): Boolean
    fun handle(kind: String, payload: JsonObject, respond: (Any) -> Unit, error: (String, String) -> Unit)
}

class RpcServer(private val plugin: JavaPlugin, host: String, port: Int, private val secret: String) :
    WebSocketServer(InetSocketAddress(host, port)) {
    private val clients: MutableSet<WebSocket> = Collections.newSetFromMap(ConcurrentHashMap())
    private val modules = mutableListOf<OpModule>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var stoppedFuture: CompletableFuture<Void> = CompletableFuture()

    val isRunning: Boolean get() = running.get()

    fun whenStopped(): CompletableFuture<Void> = stoppedFuture

    fun register(m: OpModule) {
        modules += m
    }

    fun emit(kind: String, payload: Any) {
        val msg = gson.toJson(RpcEnvelope(t = "evt", kind = kind, payload = payload))
        clients.forEach { it.send(msg) }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clients.remove(conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        ex.printStackTrace()
    }

    override fun start() {
        stoppedFuture = CompletableFuture()
        super.start()
    }

    override fun onStart() {
        running.set(true)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val root = gson.fromJson(message, JsonObject::class.java)

        when (root.get("t")?.asString) {
            "hello" -> {
                clients.add(conn)
                conn.send(gson.toJson(RpcEnvelope(t = "hello_ack", protocolVersion = 1)))
            }

            "cmd" -> {
                val id = root.get("id")?.asString ?: UUID.randomUUID().toString()
                val kind = root.get("kind")?.asString ?: return
                val payload = root.getAsJsonObject("payload") ?: JsonObject()

                val mod = modules.firstOrNull { it.handles(kind) }
                if (mod == null) {
                    conn.send(
                        gson.toJson(
                            RpcEnvelope(
                                t = "err",
                                kind = kind,
                                payload = mapOf("code" to "UNKNOWN KIND")
                            )
                        )
                    )
                    return
                }

                mod.handle(
                    kind,
                    payload,
                    respond = { res ->
                        conn.send(
                            gson.toJson(
                                RpcEnvelope(
                                    t = "res",
                                    id = id,
                                    kind = kind,
                                    payload = res
                                )
                            )
                        )
                    },
                    error = { code, msg ->
                        conn.send(
                            gson.toJson(
                                RpcEnvelope(
                                    t = "err",
                                    id = id,
                                    kind = kind,
                                    payload = mapOf("code" to code, "message" to msg)
                                )
                            )
                        )
                    })
            }
        }
    }

    override fun stop(timeout: Int) {
        try {
            super.stop(timeout)
        } finally {
            running.set(false)
            stoppedFuture.complete(null)
        }
    }
}