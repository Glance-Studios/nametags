package dev.nametags.api

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * Server to client packet carrying one player's over-head tag, as "uuid;legacyText" where the
 * text may contain '&' colours and '\n'. `nametags-client` declares an identical payload, so the
 * id and encoding have to stay in sync across both.
 */
class NametagPayload(@JvmField val data: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<NametagPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nametags", "tag"))

        @JvmField
        val CODEC: StreamCodec<ByteBuf, NametagPayload> =
            ByteBufCodecs.STRING_UTF8.map({ NametagPayload(it) }, { it.data })
    }
}
