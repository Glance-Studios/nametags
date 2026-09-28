# Nametags

Nicknames and role prefixes, in chat, the tablist and over the head.

A player's display name is `role prefix + role colour + nickname or real name`. Roles are defined
once and assigned to players; a player with no role gets the default one.

```
/nametag nick <player> <nickname>       set a nickname
/nametag unnick <player>
/nametag role <player> <role>           assign a role
/nametag unrole <player>
/nametag clear <player>                 both
/nametag show <player>                  what they currently resolve to
/nametag roles                          list roles
/nametag roles create <name>
/nametag roles delete <name>
/nametag roles prefix <name> <prefix>
/nametag roles color <name> <colour>
/nametag roles weight <name> <n>
/nametag refresh                        recompute everyone
/nametag reload
```

## Three surfaces, two of them server-side

**Chat** and the **tablist** need nothing on the client. The tablist works through a mixin on
`ServerPlayer.getTabListDisplayName()`, which vanilla returns null from to mean "use the real name";
overriding it puts the formatted name in the player-info packet, which every client already reads.

**Over the head** is the awkward one. A scoreboard team carries the role prefix and colour there
with no client mod at all, but a team prefix cannot replace the name itself - so vanilla clients see
`[Member] RealName`, never the nickname. Showing a nickname over the head needs the client module.

## Two jars out of this repo

| Module | Goes where | Contains |
| --- | --- | --- |
| `nametags-server` | the server's `mods/` | commands, config, tablist mixin, chat |
| `nametags-client` | the modpack | the over-head render mixin and tag store |
| `nametags-api` | nested inside both | the payload the two speak |

The api module is the wire format. It ships jar-in-jar inside each of the other two rather than
being compiled into both, because Fabric mods share a classloader and two copies of the same class
would clash for anyone running both jars.

The server works on its own. The client module only adds the over-head nickname.

## Config (`config/nametags.json`)

```json
{
  "defaultRole": "default",
  "roles": {
    "default": { "prefix": "", "nameColor": "&f", "weight": 0 },
    "member":  { "prefix": "&8[&aMember&8] ", "nameColor": "&a", "weight": 10 }
  },
  "players": {
    "<uuid>": { "nickname": "Bee", "role": "member" }
  }
}
```

`weight` sorts the tablist, higher above lower.

## Chat integration

If [chat-extensions](https://github.com/Glance-Studios/chat-extensions) is installed, this mod owns
the chat line and hands it the message body to expand tokens in - two mods both claiming
`ALLOW_CHAT_MESSAGE` would fight. The call is reflective, so neither has a compile-time dependency
on the other and either works alone.

## Build

```
./gradlew build
```

Jars land in each module's `build/libs/`.

## Requirements

Minecraft **26.2**, Fabric Loader **0.19.3+**, **Java 25**, plus
[Fabric API](https://modrinth.com/mod/fabric-api) `0.155.2+26.2` and
[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) `1.13.12+kotlin.2.4.0` on
whichever side you install.

## Limits

Nicknames are display-only - `/msg`, `/tell` and every other command still want the real name. The
over-head tag is single line; multi-line needs custom drawing in the render hook and is not there
yet.
