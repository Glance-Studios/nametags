package dev.nametags

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.nametags.api.NametagPayload
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.TeamColor
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Optional

/**
 * Server-side nicknames and role prefixes. Computes a display name of role prefix + name colour +
 * nickname or real name, and applies it to the tablist. A nickname over the head needs the client
 * mod, so only the role prefix reaches that surface from here.
 */
object NametagsMod : DedicatedServerModInitializer {

    val LOG = LoggerFactory.getLogger("nametags")

    lateinit var config: NametagsConfig
        private set

    private lateinit var configPath: Path

    override fun onInitializeServer() {
        configPath = FabricLoader.getInstance().configDir.resolve("nametags.json")
        config = NametagsConfig.load(configPath)

        PayloadTypeRegistry.clientboundPlay().register(NametagPayload.TYPE, NametagPayload.CODEC)

        ServerPlayConnectionEvents.JOIN.register(ServerPlayConnectionEvents.Join { handler, _, server ->
            apply(handler.player) // team + tab + broadcast this player's tag to everyone
            // catch the joiner up on everyone else's tags
            server.playerList.players.forEach { other ->
                ServerPlayNetworking.send(handler.player, payloadFor(other))
            }
        })

        registerCommand()
        registerChat()
        LOG.info("Nametags enabled with {} role(s).", config.roles.size)
    }

    /** Reformat global chat to one row: "[prefix] nick: message", with the name hoverable for full info. */
    private fun registerChat() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ServerMessageEvents.AllowChatMessage { message, sender, _ ->
            val server = (sender.level() as? ServerLevel)?.getServer() ?: return@AllowChatMessage true
            val text = message.signedContent()
            val line = Component.empty()
                .append(nameWithHover(sender))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(ChatBodyBridge.render(sender, text))
            server.playerList.players.forEach { it.sendSystemMessage(line) }
            LOG.info("<{}> {}", plainName(sender), text)
            false // we broadcast our own formatted line; cancel the vanilla one
        })
    }

    private fun roleId(player: ServerPlayer): String =
        config.players[player.uuid.toString()]?.role ?: config.defaultRole

    private fun plainName(player: ServerPlayer): String =
        config.players[player.uuid.toString()]?.nickname ?: player.gameProfile.name

    /** Display name with a hover tooltip showing the real name + role (chat use). */
    private fun nameWithHover(player: ServerPlayer): MutableComponent {
        val hover = Component.literal("Real name: ${player.gameProfile.name}\nRole: ${roleId(player)}")
        return displayName(player).withStyle { it.withHoverEvent(HoverEvent.ShowText(hover)) }
    }

    // --- display name + appliers ---

    /** role prefix + name color + (nickname or real name), as a legacy `&`-string. */
    fun legacyTag(player: ServerPlayer): String {
        val tag = config.players[player.uuid.toString()]
        val role = config.roles[tag?.role ?: config.defaultRole] ?: config.roles[config.defaultRole]
        val name = tag?.nickname ?: player.gameProfile.name
        val prefix = role?.prefix ?: ""
        // one space between prefix and name (unless the prefix already ends with one)
        val sep = if (prefix.isNotEmpty() && !prefix.endsWith(" ")) " " else ""
        return prefix + sep + (role?.nameColor ?: "&f") + name
    }

    /** Same, as a Component (chat/tab use). */
    fun displayName(player: ServerPlayer): MutableComponent = Format.legacy(legacyTag(player))

    private fun payloadFor(player: ServerPlayer): NametagPayload =
        NametagPayload("${player.uuid};${legacyTag(player)}")

    /**
     * Apply the player's role through a scoreboard team, which puts the role prefix and name colour
     * in the tablist and over the head. This surface carries the real name: the nickname needs the
     * player-info packet route in tab, and the client mod over the head.
     */
    fun apply(player: ServerPlayer) {
        val level = player.level() as? ServerLevel ?: return
        val scoreboard = level.scoreboard
        val roleId = config.players[player.uuid.toString()]?.role ?: config.defaultRole
        val role = config.roles[roleId] ?: config.roles[config.defaultRole] ?: NametagsConfig.Role()

        // Vanilla sorts the tab by team name, so encode the weight (higher = sorts first) as a
        // zero-padded prefix. Team names are length-limited (16), so keep it short.
        val sortKey = "%04d".format((1000 - role.weight).coerceIn(0, 9999))
        val teamName = "$sortKey$roleId".take(16)
        val team = scoreboard.getPlayerTeam(teamName) ?: scoreboard.addPlayerTeam(teamName)
        team.setPlayerPrefix(Format.legacy(role.prefix))
        colorOf(role.nameColor)?.let { team.setColor(Optional.of(TeamColor.valueOf(it.name))) }
        scoreboard.addPlayerToTeam(player.scoreboardName, team)

        // Refresh the tab-list name for everyone; the Mixin feeds the nickname into the entry.
        val server = level.getServer()
        server.playerList.broadcastAll(
            ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player),
        )

        // Push the over-head tag to clients running the companion mod (Phase 2 render).
        val payload = payloadFor(player)
        server.playerList.players.forEach { ServerPlayNetworking.send(it, payload) }
    }

    /** Called from the ServerPlayer mixin: the tab-list display name (prefix + nickname). */
    @JvmStatic
    fun tabNameFor(player: ServerPlayer): Component? {
        if (!::config.isInitialized) return null
        return displayName(player)
    }

    /** Legacy color code (e.g. "&c") -> ChatFormatting for the team's name color. */
    private fun colorOf(legacy: String): ChatFormatting? {
        val code = legacy.removePrefix("&").firstOrNull() ?: return null
        return ChatFormatting.getByCode(code)
    }

    fun applyAll(server: MinecraftServer) {
        server.playerList.players.forEach { apply(it) }
    }

    private fun tagOf(player: ServerPlayer): NametagsConfig.Tag =
        config.players.getOrPut(player.uuid.toString()) { NametagsConfig.Tag() }

    // --- command ---

    private fun registerCommand() {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
            dispatcher.register(
                literal("nametag")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // op level 2
                    .then(
                        literal("nick").then(
                            argument("player", EntityArgument.player()).then(
                                // greedy = supports spaces and &-color codes in the nickname
                                argument("nickname", StringArgumentType.greedyString())
                                    .executes { setNick(it); 1 }
                            )
                        )
                    )
                    .then(literal("unnick").then(argument("player", EntityArgument.player()).executes { clearNick(it); 1 }))
                    .then(
                        literal("role").then(
                            argument("player", EntityArgument.player()).then(
                                argument("role", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(config.roles.keys, b) }
                                    .executes { setRole(it); 1 }
                            )
                        )
                    )
                    .then(literal("unrole").then(argument("player", EntityArgument.player()).executes { clearRole(it); 1 }))
                    .then(literal("clear").then(argument("player", EntityArgument.player()).executes { clearAll(it); 1 }))
                    .then(literal("show").then(argument("player", EntityArgument.player()).executes { show(it); 1 }))
                    .then(
                        literal("roles")
                            .executes { listRoles(it.source); 1 }
                            .then(
                                literal("create")
                                    .then(argument("id", StringArgumentType.word()).executes { createRole(it); 1 })
                            )
                            .then(
                                literal("delete")
                                    .then(roleArg().executes { deleteRole(it); 1 })
                            )
                            .then(
                                literal("prefix").then(
                                    roleArg().then(
                                        argument("prefix", StringArgumentType.greedyString())
                                            .executes { setRolePrefix(it); 1 }
                                    )
                                )
                            )
                            .then(
                                literal("color").then(
                                    roleArg().then(
                                        argument("color", StringArgumentType.word())
                                            .executes { setRoleColor(it); 1 }
                                    )
                                )
                            )
                            .then(
                                literal("weight").then(
                                    roleArg().then(
                                        argument("weight", IntegerArgumentType.integer())
                                            .executes { setRoleWeight(it); 1 }
                                    )
                                )
                            )
                    )
                    .then(literal("refresh").executes { applyAll(it.source.server); reply(it.source, "Refreshed all players."); 1 })
                    .then(literal("reload").executes {
                        config = NametagsConfig.load(configPath)
                        applyAll(it.source.server)
                        reply(it.source, "Reloaded config + refreshed.")
                        1
                    })
            )
        })
    }

    private fun setNick(ctx: CommandContext<CommandSourceStack>) {
        val target = EntityArgument.getPlayer(ctx, "player")
        val nick = StringArgumentType.getString(ctx, "nickname")
        tagOf(target).nickname = nick
        config.save(configPath)
        apply(target)
        reply(ctx.source, "Set ${target.gameProfile.name}'s nickname to '$nick'.")
    }

    private fun clearNick(ctx: CommandContext<CommandSourceStack>) {
        val target = EntityArgument.getPlayer(ctx, "player")
        tagOf(target).nickname = null
        config.save(configPath)
        apply(target)
        reply(ctx.source, "Cleared ${target.gameProfile.name}'s nickname.")
    }

    private fun setRole(ctx: CommandContext<CommandSourceStack>) {
        val target = EntityArgument.getPlayer(ctx, "player")
        val role = StringArgumentType.getString(ctx, "role")
        if (!config.roles.containsKey(role)) {
            reply(ctx.source, "§cUnknown role '$role'. Defined: ${config.roles.keys.joinToString(", ")}")
            return
        }
        tagOf(target).role = role
        config.save(configPath)
        apply(target)
        reply(ctx.source, "Set ${target.gameProfile.name}'s role to '$role'.")
    }

    private fun clearRole(ctx: CommandContext<CommandSourceStack>) {
        val target = EntityArgument.getPlayer(ctx, "player")
        tagOf(target).role = null
        config.save(configPath)
        apply(target)
        reply(ctx.source, "Cleared ${target.gameProfile.name}'s role.")
    }

    private fun clearAll(ctx: CommandContext<CommandSourceStack>) {
        val target = EntityArgument.getPlayer(ctx, "player")
        config.players.remove(target.uuid.toString())
        config.save(configPath)
        apply(target)
        reply(ctx.source, "Cleared ${target.gameProfile.name}'s nickname + role.")
    }

    private fun show(ctx: CommandContext<CommandSourceStack>) {
        val target = EntityArgument.getPlayer(ctx, "player")
        val tag = config.players[target.uuid.toString()]
        reply(
            ctx.source,
            "${target.gameProfile.name}: nick=${tag?.nickname ?: "-"}, role=${tag?.role ?: config.defaultRole}",
        )
    }

    private fun listRoles(source: CommandSourceStack) {
        if (config.roles.isEmpty()) {
            reply(source, "No roles defined.")
            return
        }
        reply(source, "&6Roles:")
        config.roles.forEach { (id, r) ->
            reply(source, "&7 - &f$id&7: prefix='${r.prefix}' color='${r.nameColor}' weight=${r.weight}")
        }
    }

    // --- role management (ops) ---

    private fun roleArg() =
        argument("id", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(config.roles.keys, b) }

    private fun createRole(ctx: CommandContext<CommandSourceStack>) {
        val id = StringArgumentType.getString(ctx, "id")
        if (config.roles.containsKey(id)) {
            reply(ctx.source, "&cRole '$id' already exists.")
            return
        }
        config.roles[id] = NametagsConfig.Role()
        config.save(configPath)
        reply(ctx.source, "&aCreated role '$id'. Set it up: /nametag roles prefix $id <text>, /nametag roles color $id <color>")
    }

    private fun deleteRole(ctx: CommandContext<CommandSourceStack>) {
        val id = StringArgumentType.getString(ctx, "id")
        if (config.roles.remove(id) == null) {
            reply(ctx.source, "&cNo such role '$id'.")
            return
        }
        config.save(configPath)
        applyAll(ctx.source.server)
        reply(ctx.source, "&eDeleted role '$id'. Players with it now use '${config.defaultRole}'.")
    }

    private fun setRolePrefix(ctx: CommandContext<CommandSourceStack>) {
        val role = role(ctx) ?: return
        role.prefix = StringArgumentType.getString(ctx, "prefix")
        config.save(configPath)
        applyAll(ctx.source.server)
        val sep = if (role.prefix.isNotEmpty() && !role.prefix.endsWith(" ")) " " else ""
        reply(ctx.source, "&aPrefix updated. Preview: ${role.prefix}$sep&fPlayerName")
    }

    private fun setRoleColor(ctx: CommandContext<CommandSourceStack>) {
        val role = role(ctx) ?: return
        var color = StringArgumentType.getString(ctx, "color")
        if (!color.startsWith("&")) color = "&$color"
        role.nameColor = color
        config.save(configPath)
        applyAll(ctx.source.server)
        reply(ctx.source, "&aName color updated. Preview: ${role.nameColor}Name")
    }

    private fun setRoleWeight(ctx: CommandContext<CommandSourceStack>) {
        val role = role(ctx) ?: return
        role.weight = IntegerArgumentType.getInteger(ctx, "weight")
        config.save(configPath)
        applyAll(ctx.source.server) // re-team everyone so the new sort order takes effect
        reply(ctx.source, "&aWeight set to ${role.weight}. (higher = higher in tab)")
    }

    /** Resolve the "id" arg to a role, replying with an error if it doesn't exist. */
    private fun role(ctx: CommandContext<CommandSourceStack>): NametagsConfig.Role? {
        val id = StringArgumentType.getString(ctx, "id")
        val role = config.roles[id]
        if (role == null) reply(ctx.source, "&cNo such role '$id'.")
        return role
    }

    private fun reply(source: CommandSourceStack, msg: String) {
        source.sendSuccess({ Component.literal("[Nametags] ").append(Format.legacy(msg)) }, false)
    }

    /**
     * Soft link to the chat-extensions mod: turns the raw message body into a rich component
     * (expanding tokens like `[item]`). Resolved reflectively so nametags still builds and runs with
     * chat-extensions absent, in which case the plain text is shown.
     */
    private object ChatBodyBridge {
        private val method = try {
            Class.forName("dev.chatextensions.ChatExtensions")
                .getMethod("renderBody", ServerPlayer::class.java, String::class.java)
        } catch (t: Throwable) {
            null
        }

        fun render(sender: ServerPlayer, text: String): Component =
            try {
                (method?.invoke(null, sender, text) as? Component) ?: Component.literal(text)
            } catch (t: Throwable) {
                Component.literal(text)
            }
    }
}
