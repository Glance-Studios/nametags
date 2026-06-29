package dev.nametags.mixin;

import dev.nametags.NametagsMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds the computed tab-list display name (role prefix + nickname) into the player-info entry.
 * Vanilla's ServerPlayer.getTabListDisplayName() returns null, meaning use the real name; this
 * overrides it with the formatted name, which the player-info packet carries to every client.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabMixin {

    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void nametags$tabName(CallbackInfoReturnable<Component> cir) {
        Component name = NametagsMod.tabNameFor((ServerPlayer) (Object) this);
        if (name != null) {
            cir.setReturnValue(name);
        }
    }
}
