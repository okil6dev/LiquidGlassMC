package restudio.reglass.mixin.widgets;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import restudio.reglass.client.api.ReGlassApi;
import restudio.reglass.client.api.ReGlassConfig;
import restudio.reglass.client.api.WidgetStyle;
import restudio.reglass.client.LiquidGlassUniforms;
import restudio.reglass.client.LiquidGlassForegroundRuntime;

@Mixin(GuiGraphics.class)
public abstract class DrawContextMixin {

    @Unique
    private static final ResourceLocation BUTTON_TEXTURE = ResourceLocation.withDefaultNamespace("widget/button");
    @Unique
    private static final ResourceLocation BUTTON_DISABLED_TEXTURE = ResourceLocation.withDefaultNamespace("widget/button_disabled");
    @Unique
    private static final ResourceLocation BUTTON_HIGHLIGHTED_TEXTURE = ResourceLocation.withDefaultNamespace("widget/button_highlighted");

    @Inject(method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V",
            at = @At("HEAD"), cancellable = true)
    private void onDrawTexture(ResourceLocation sprite, int x, int y, int width, int height, CallbackInfo ci) {
        boolean isButtonTexture = sprite.getPath().equals(BUTTON_TEXTURE.getPath())
                || sprite.getPath().equals(BUTTON_DISABLED_TEXTURE.getPath())
                || sprite.getPath().equals(BUTTON_HIGHLIGHTED_TEXTURE.getPath());

        if (isButtonTexture && (ReGlassConfig.INSTANCE.features.enableRedesign && ReGlassConfig.INSTANCE.features.buttons)) {
            boolean isHighlighted = sprite.getPath().equals(BUTTON_HIGHLIGHTED_TEXTURE.getPath());
            boolean isDisabled = sprite.getPath().equals(BUTTON_DISABLED_TEXTURE.getPath());
            ReGlassApi.create((GuiGraphics)(Object) this)
                    .position(x, y)
                    .size(width, height)
                    .hover(isHighlighted ? 1f : 0f)
                    .style(WidgetStyle.create().tint(isDisabled ? 0xFF000000 : 0xFFFFFFFF, isDisabled ? 0.4f : 0f))
                    .render();
            ci.cancel();
        }
    }

    // ---- Info text / tooltip on top fix ----
    // GuiGraphics buffers ALL drawing (button text, tooltips…) and only submits at
    // flush(). We capture the just-rendered tooltip into the FOREGROUND layer (which
    // composites LAST, on top of the glass buttons, at GameRenderer TAIL). We
    // anchor at the actual renderTooltip method (the shared sink every tooltip path
    // flows through) so this only fires when a tooltip truly draws, and always
    // pairs begin/end per call — no leak, works on every screen type.
    private static final String RENDER_TOOLTIP_INTERNAL =
            "renderTooltipInternal(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;)V";

    @Unique
    private boolean reglass$ownsTooltipCapture;

    @Inject(method = RENDER_TOOLTIP_INTERNAL, at = @At("HEAD"))
    private void reglass$captureTooltipStart(
            net.minecraft.client.gui.Font font,
            java.util.List<net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent> components,
            int mouseX, int mouseY,
            net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
            CallbackInfo ci) {

        // Only take ownership if no other capture is active so begin/end always pair.
        if (!LiquidGlassForegroundRuntime.get().isCapturing()) {
            // Submit whatever was recorded before (buttons / their text) into MAIN.
            ((GuiGraphics)(Object)this).flush();
            LiquidGlassForegroundRuntime.get().beginTooltipCapture();
            reglass$ownsTooltipCapture = true;
        }
    }

    @Inject(method = RENDER_TOOLTIP_INTERNAL, at = @At("TAIL"))
    private void reglass$captureTooltipEnd(
            net.minecraft.client.gui.Font font,
            java.util.List<net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent> components,
            int mouseX, int mouseY,
            net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
            CallbackInfo ci) {

        if (reglass$ownsTooltipCapture) {
            // The tooltip was recorded while the tooltip FBO was bound, so this flush
            // submits it INTO that layer. endTooltipCapture restores the main target.
            ((GuiGraphics)(Object)this).flush();
            LiquidGlassForegroundRuntime.get().endTooltipCapture();
            reglass$ownsTooltipCapture = false;
        }
    }

    // ---- Inventory / slot items on top of glass fix ----
    // The glass panels are composited over the main buffer at GameRenderer.render()
    // TAIL, so anything drawn straight into the main buffer (item icons in inventory
    // slots) ends up underneath the glass. Every GuiGraphics item path funnels into
    // the private renderItem(LivingEntity, Level, ItemStack, int, int, int, int)
    // sink — which also draws the count text, durability bar, cooldown overlay and
    // item decorators. We route that whole sink into the FOREGROUND layer (which
    // composites LAST, on top of the glass) exactly like the tooltip fix.
    private static final String RENDER_ITEM_SINK =
            "renderItem(Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/item/ItemStack;IIII)V";

    @Unique
    private boolean reglass$ownsItemCapture;

    @Inject(method = RENDER_ITEM_SINK, at = @At("HEAD"))
    private void reglass$captureItemStart(
            net.minecraft.world.entity.LivingEntity entity,
            net.minecraft.world.level.Level level,
            net.minecraft.world.item.ItemStack stack,
            int x, int y, int index, int seed, CallbackInfo ci) {

        // Only take ownership if nothing else already owns a foreground capture so
        // begin/end always pair symmetrically (prevents nested/leaked binds). This
        // also covers items rendered INSIDE a tooltip (which is already capturing
        // into the topmost tooltip layer) — we must not steal/close that capture.
        if (!LiquidGlassForegroundRuntime.get().isCapturing()) {
            // Submit whatever was recorded before (slot backgrounds, etc.) into MAIN,
            // then bind the foreground FBO so the item draws on top of the glass.
            ((GuiGraphics)(Object)this).flush();
            LiquidGlassForegroundRuntime.get().beginCapture();
            reglass$ownsItemCapture = true;
        }
    }

    @Inject(method = RENDER_ITEM_SINK, at = @At("TAIL"))
    private void reglass$captureItemEnd(CallbackInfo ci) {
        if (reglass$ownsItemCapture) {
            // The item was recorded while the foreground FBO was bound, so this flush
            // submits it INTO that layer. endCapture restores the main draw target.
            ((GuiGraphics)(Object)this).flush();
            LiquidGlassForegroundRuntime.get().endCapture();
            reglass$ownsItemCapture = false;
        }
    }
}
