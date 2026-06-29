package dev.nametags

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Roles and per-player tags (JSON via Minecraft's bundled Gson). A player is assigned a role, a
 * nickname, or both, and displays as role prefix + role name colour + nickname or real name.
 */
class NametagsConfig {

    /** A role/rank: a prefix shown before the name, and a color applied to the name. */
    class Role {
        @JvmField var prefix: String = ""      // legacy &-codes, e.g. "&8[&aMember&8] "
        @JvmField var nameColor: String = "&f" // legacy color applied to the name
        @JvmField var weight: Int = 0          // higher = sorts above in tab (used later)
    }

    /** Per-player assignment. */
    class Tag {
        @JvmField var nickname: String? = null
        @JvmField var role: String? = null
    }

    @JvmField var defaultRole: String = "default"

    @JvmField var roles: MutableMap<String, Role> = linkedMapOf(
        "default" to Role(),
        "admin" to Role().apply { prefix = "&8[&cAdmin&8] "; nameColor = "&c"; weight = 100 },
    )

    /** Keyed by player UUID string. */
    @JvmField var players: MutableMap<String, Tag> = linkedMapOf()

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { GSON.toJson(this, it) }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        fun load(path: Path): NametagsConfig {
            if (Files.exists(path)) {
                runCatching {
                    Files.newBufferedReader(path).use { GSON.fromJson(it, NametagsConfig::class.java) }
                }.getOrNull()?.let { return it }
            }
            val fresh = NametagsConfig()
            runCatching { fresh.save(path) }
            return fresh
        }
    }
}
