package autismclient.mixin;

import autismclient.gui.screen.AutismModuleScreen;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class AutismFramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void autism$useConfiguredLimitInTitleSetup(CallbackInfoReturnable<Integer> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null
            || !(minecraft.gui.screen() instanceof AutismModuleScreen screen)
            || !screen.isTitleSetup()) {
            return;
        }

        FramerateLimitTracker tracker = (FramerateLimitTracker) (Object) this;
        if (tracker.getThrottleReason() == FramerateLimitTracker.FramerateThrottleReason.OUT_OF_LEVEL_MENU) {
            cir.setReturnValue(minecraft.options.framerateLimit().get());
        }
    }
}
