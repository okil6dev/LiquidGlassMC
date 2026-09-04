package restudio.reglass.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import restudio.reglass.client.LiquidGlassForegroundRuntime;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/TitleScreen;renderPanorama(Lnet/minecraft/client/gui/GuiGraphics;F)V", shift = At.Shift.AFTER))
    private void reglass$prepareTitleLayers(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // NOTE: We intentionally do NOT call renderBlurredBackground() here. That method
        // applies a global full-screen blur of the background (gameRenderer.processBlurEffect),
        // which is what made the title screen backdrop blur for no reason. The ReGlass glass
        // buttons are separate registered widgets and are unaffected — they keep rendering
        // normally on top of the sharp panorama.
        LiquidGlassForegroundRuntime.get().beginCapture();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void reglass$endTitleForeground(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        LiquidGlassForegroundRuntime.get().endCapture();
    }
}
