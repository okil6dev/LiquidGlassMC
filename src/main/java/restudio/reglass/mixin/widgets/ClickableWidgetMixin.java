package restudio.reglass.mixin.widgets;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import restudio.reglass.client.LiquidGlassForegroundRuntime;
import restudio.reglass.client.api.ReGlassConfig;

/** Captures complete widget foreground content, including text and icons. */
@Mixin(ClickableWidget.class)
public abstract class ClickableWidgetMixin {
    @Unique
    private boolean reglass$ownsForegroundCapture;

    @Inject(method = "render", at = @At("HEAD"))
    private void reglass$beginWidgetForeground(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci
    ) {
        ClickableWidget widget = (ClickableWidget) (Object) this;
        ReGlassConfig.Features features = ReGlassConfig.INSTANCE.features;
        boolean redesign = features.enableRedesign;
        boolean supported = (widget instanceof PressableWidget && features.buttons)
                || (widget instanceof SliderWidget && features.sliders);
        LiquidGlassForegroundRuntime foreground = LiquidGlassForegroundRuntime.get();
        if (redesign && supported && !foreground.isCapturing()) {
            foreground.beginCapture();
            reglass$ownsForegroundCapture = true;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void reglass$endWidgetForeground(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci
    ) {
        if (reglass$ownsForegroundCapture) {
            LiquidGlassForegroundRuntime.get().endCapture();
            reglass$ownsForegroundCapture = false;
        }
    }
}
