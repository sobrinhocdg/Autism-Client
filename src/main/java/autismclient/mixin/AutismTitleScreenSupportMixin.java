package autismclient.mixin;

import autismclient.modules.PackHideState;
import autismclient.gui.screen.AutismIconButton;
import autismclient.gui.screen.AutismModuleScreen;
import autismclient.gui.vanillaui.assets.UiAssets;
import autismclient.util.AutismLinks;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class AutismTitleScreenSupportMixin extends Screen {
    protected AutismTitleScreenSupportMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void autism$addSupportButtons(CallbackInfo ci) {
        if (PackHideState.isActive()) return;

        int menuRows = this.minecraft.isDemo() ? 2 : 3;
        int rowY = this.height / 4 + 48 + 24 * menuRows;
        AutismIconButton modules = this.addRenderableWidget(new AutismIconButton(
            this.width / 2 + 38, rowY, 20, 20, Component.empty(),
            UiAssets.ICON_MAIN_MENU_CATEGORY, 32, 16,
            button -> {
                if (!PackHideState.isHardLocked()) {
                    this.minecraft.gui.setScreen(new AutismModuleScreen(this, AutismModuleScreen.Mode.TITLE_SETUP));
                }
            }));
        modules.setTooltip(Tooltip.create(Component.literal("Modules & Macros")));

        Component cardDonateLabel = Component.literal("Card/PayPal");
        this.addRenderableWidget(Button.builder(cardDonateLabel, b -> AutismLinks.open(AutismLinks.KOFI))
            .bounds(4, 4, supportWidth(cardDonateLabel), 20).build());

        Component cryptoDonateLabel = Component.literal("Crypto");
        this.addRenderableWidget(Button.builder(cryptoDonateLabel, b -> AutismLinks.open(AutismLinks.CRYPTO_DONATE))
            .bounds(4, 28, supportWidth(cryptoDonateLabel), 20).build());

        Component incDiscordLabel = Component.literal("AUTISM INC");
        this.addRenderableWidget(Button.builder(incDiscordLabel, b -> AutismLinks.open(AutismLinks.AUTISM_INC_DISCORD))
            .bounds(4, 52, supportWidth(incDiscordLabel), 20).build());

        Component clientDiscordLabel = Component.literal("AUTISM Client");
        this.addRenderableWidget(Button.builder(clientDiscordLabel, b -> AutismLinks.open(AutismLinks.DISCORD))
            .bounds(4, 76, supportWidth(clientDiscordLabel), 20).build());
    }

    private int supportWidth(Component label) {
        return Math.max(78, Math.min(this.width - 8, this.font.width(label) + 20));
    }
}
