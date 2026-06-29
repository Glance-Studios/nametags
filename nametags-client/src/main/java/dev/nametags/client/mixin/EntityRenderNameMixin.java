package dev.nametags.client.mixin;

import dev.nametags.client.Legacy;
import dev.nametags.client.NametagStore;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.String;

/**
 * Over-head nametag override. When the server has sent a custom tag and vanilla has decided to show
 * a name, so distance and visibility rules still apply, the render state's name is swapped for the
 * formatted one. Single-line only; multi-line needs custom drawing in renderNameTag.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRenderNameMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void nametags$override(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (state.nameTag == null) {
            return; // vanilla is not showing a name here, so respect that
        }
        String legacy = NametagStore.get(entity.getUUID());
        if (legacy == null) {
            return; // no server-provided tag for this player
        }
        state.nameTag = Legacy.parse(legacy);
    }
}
