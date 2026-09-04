package restudio.reglass.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import restudio.reglass.client.LiquidGlassUniforms;
import restudio.reglass.client.api.ReGlassConfig;

@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Shadow
    protected abstract void renderBlurredBackground(float delta);

    @Inject(method = "renderBlurredBackground(F)V", at = @At("HEAD"))
    private void reglass$onScreenBlur(float delta, CallbackInfo ci) {
        LiquidGlassUniforms.get().setScreenWantsBlur(true);
    }

    @Inject(
            method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void reglass$onRenderBackground(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (ReGlassConfig.INSTANCE.features.enableRedesign && ReGlassConfig.INSTANCE.features.cancelScreenDarkening) {
            // On NeoForge 1.21.1 the darkening is folded into renderBackground
            // (panorama + blurred background + menu background). Keeping only
            // the blurred background reproduces "no screen darkening".
            ci.cancel();
            renderBlurredBackground(delta);
        }
    }
}