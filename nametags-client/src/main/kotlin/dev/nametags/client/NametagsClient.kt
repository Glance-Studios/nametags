package dev.nametags.client

import dev.nametags.api.NametagPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Client companion: receives over-head tag data from the server and stashes it in [NametagStore].
 * The render hook is still TODO, so stored tags are not drawn yet.
 */
object NametagsClient : ClientModInitializer {

    private val LOG = LoggerFactory.getLogger("nametags-client")

    override fun onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(NametagPayload.TYPE, NametagPayload.CODEC)

        ClientPlayNetworking.registerGlobalReceiver(NametagPayload.TYPE) { payload, _ ->
            val data = payload.data
            val idStr = data.substringBefore(';')
            val text = data.substringAfter(';', "")
            runCatching { NametagStore.put(UUID.fromString(idStr), text) }
        }

        LOG.info("nametags-client ready, receiving over-head tags.")
    }
}
