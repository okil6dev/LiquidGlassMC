package restudio.reglass.mixin.widgets;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import restudio.reglass.client.LiquidGlassForegroundRuntime;
import restudio.reglass.client.api.ReGlassConfig;

/** Captures complete widget foreground content, including Component and icons. */
@Mixin(AbstractWidget.class)
public abstract class ClickableWidgetMixin {
    @Unique
    private boolean reglass$ownsForegroundCapture;

    @Inject(method = "render", at = @At("HEAD"))
    private void reglass$beginWidgetForeground(
            GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci
    ) {
        AbstractWidget widget = (AbstractWidget) (Object) this;
        ReGlassConfig.Features features = ReGlassConfig.INSTANCE.features;
        boolean redesign = features.enableRedesign;
        boolean supported = (widget instanceof AbstractButton && features.buttons)
                || (widget instanceof AbstractSliderButton && features.sliders);
        LiquidGlassForegroundRuntime foreground = LiquidGlassForegroundRuntime.get();
        if (redesign && supported && !foreground.isCapturing()) {
            foreground.beginCapture();
            reglass$ownsForegroundCapture = true;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void reglass$endWidgetForeground(
            GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci
    ) {
        if (reglass$ownsForegroundCapture) {
            LiquidGlassForegroundRuntime.get().endCapture();
            reglass$ownsForegroundCapture = false;
        }
    }
}
