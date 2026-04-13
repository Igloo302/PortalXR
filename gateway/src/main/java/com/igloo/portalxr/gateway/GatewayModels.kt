package com.igloo.portalxr.gateway

import kotlinx.serialization.Serializable

/**
 * Gateway endpoint configuration
 */
@Serializable
data class GatewayEndpoint(
    val stableId: String,
    val host: String,
    val port: Int,
    val canvasPort: Int? = null,
    val lanHost: String? = null,
    val tailnetDns: String? = null,
) {
    companion object {
        fun manual(host: String, port: Int): GatewayEndpoint {
            return GatewayEndpoint(
                stableId = "manual:$host:$port",
                host = host,
                port = port,
            )
        }
    }
}

/**
 * TLS configuration for gateway connection
 */
data class GatewayTlsParams(
    val required: Boolean,
    val stableId: String?,
    val expectedFingerprint: String?,
)

/**
 * Client information sent during connection
 */
data class GatewayClientInfo(
    val id: String,
    val displayName: String?,
    val version: String,
    val platform: String,
    val mode: String,
    val instanceId: String?,
    val deviceFamily: String?,
    val modelIdentifier: String?,
)

/**
 * Connection options for gateway
 */
data class GatewayConnectOptions(
    val role: String,
    val scopes: List<String>,
    val caps: List<String>,
    val commands: List<String>,
    val permissions: Map<String, Boolean>,
    val client: GatewayClientInfo,
    val userAgent: String? = null,
)

/**
 * Authentication credentials for gateway connection
 */
data class GatewayConnectAuth(
    val token: String?,
    val bootstrapToken: String?,
    val password: String?,
)
