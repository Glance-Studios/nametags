package dev.nametags.client

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player over-head tag text received from the server, keyed by UUID.
 * The render hook reads this to draw the nametag.
 */
object NametagStore {
    private val tags = ConcurrentHashMap<UUID, String>()

    fun put(id: UUID, text: String) {
        tags[id] = text
    }

    /** Legacy `&`-string for this player, or null if the server hasn't sent one. */
    @JvmStatic
    fun get(id: UUID): String? = tags[id]

    fun remove(id: UUID) {
        tags.remove(id)
    }
}
