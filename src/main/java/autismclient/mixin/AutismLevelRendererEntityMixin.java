package autismclient.mixin;

import autismclient.modules.PackModuleRenderUtil;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class AutismLevelRendererEntityMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void autism$espEntityOutline(Entity entity, float partialTickTime, CallbackInfoReturnable<EntityRenderState> cir) {
        if (!PackModuleRenderUtil.hasAnyOutlineWork()) return;
        EntityRenderState state = cir.getReturnValue();
        if (state == null) return;
        if (PackModuleRenderUtil.shouldUseItemOutline() && PackModuleRenderUtil.shouldItemEsp(entity)) {
            state.outlineColor = PackModuleRenderUtil.itemEspOutlineColor(entity);
            return;
        }
        if (PackModuleRenderUtil.shouldUseEntityOutline() && PackModuleRenderUtil.shouldEsp(entity)) {
            state.outlineColor = PackModuleRenderUtil.espOutlineColor(entity);
        }
    }

}
