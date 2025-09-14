package net.ethanburkett.bridge

data class RpcEnvelope(
    val t: String, // cmd | evt | res | err | hello | hello_ack
    val id: String? = null,
    val kind: String? = null,
    val payload: Any? = null,
    val protocolVersion: Int? = null,
    val sig: String? = null,
)