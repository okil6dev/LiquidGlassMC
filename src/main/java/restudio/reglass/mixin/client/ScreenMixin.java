package restudio.reglass.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import restudio.reglass.client.LiquidGlassUniforms;
import restudio.reglass.client.api.ReGlassConfig;

@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "applyBlur(F)V", at = @At("HEAD"))
    private void reglass$onScreenBlur(float delta, CallbackInfo ci) {
        LiquidGlassUniforms.get().setScreenWantsBlur(true);
    }

    @Inject(
            method = "renderDarkening(Lnet/minecraft/client/gui/DrawContext;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void reglass$onRenderDarkening(DrawContext context, CallbackInfo ci) {
        if (ReGlassConfig.INSTANCE.features.enableRedesign && ReGlassConfig.INSTANCE.features.cancelScreenDarkening) {
            ci.cancel();
        }
    }
}