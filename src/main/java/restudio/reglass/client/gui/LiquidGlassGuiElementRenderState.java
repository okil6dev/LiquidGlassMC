package restudio.reglass.client.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import restudio.reglass.client.api.WidgetStyle;

public record LiquidGlassGuiElementRenderState(int x1, int y1, int x2, int y2, float cornerRadius, @Nullable Component Component, WidgetStyle style, Matrix3x2f pose, @Nullable ScreenRectangle scissorArea, float hover, float focus) {

    @Nullable
    public ScreenRectangle scissorArea() {
        return this.scissorArea;
    }

    public @Nullable ScreenRectangle bounds() {
        ScreenRectangle ownBounds = new ScreenRectangle(x1, y1, x2 - x1, y2 - y1);
        return scissorArea != null ? scissorArea.intersection(ownBounds) : ownBounds;
    }
}