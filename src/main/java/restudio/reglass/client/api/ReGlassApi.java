package restudio.reglass.client.api;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import restudio.reglass.client.LiquidGlassUniforms;
import restudio.reglass.client.gui.LiquidGlassGuiElementRenderState;

public final class ReGlassApi {
    private ReGlassApi() {}
    public static WidgetStyle inactiveStyle = new WidgetStyle().tint(0x000000, 0.3f);

    public static ReGlassConfig getGlobalConfig() {
        return ReGlassConfig.INSTANCE;
    }

    public static Builder create(GuiGraphics context) {
        return new Builder(context);
    }

    public static class Builder {
        private final GuiGraphics context;
        private int x, y, width, height;
        private float cornerRadius = -1f;
        @Nullable private Component Component = null;
        private WidgetStyle style = new WidgetStyle();
        private float hoverAmount = 0f;
        private float focusAmount = 0f;

        private Builder(GuiGraphics context) {
            this.context = context;
        }

        public Builder fromWidget(AbstractWidget widget) {
            this.position(widget.getX(), widget.getY());
            this.size(widget.getWidth(), widget.getHeight());
            this.Component(widget.getMessage());
            return this;
        }

        public Builder position(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder dimensions(int x, int y, int width, int height) {
            return this.position(x, y).size(width, height);
        }

        public Builder cornerRadius(float radius) {
            this.cornerRadius = radius;
            return this;
        }

        public Builder Component(Component Component) {
            this.Component = Component;
            return this;
        }

        public Builder style(WidgetStyle style) {
            this.style = style;
            return this;
        }

        public Builder hover(float amount) {
            if (Float.isNaN(amount)) amount = 0f;
            this.hoverAmount = Math.max(0f, Math.min(1f, amount));
            return this;
        }

        public Builder focus(float amount) {
            if (Float.isNaN(amount)) amount = 0f;
            this.focusAmount = Math.max(0f, Math.min(1f, amount));
            return this;
        }

        public Builder selected(float amount) {
            return this.focus(amount);
        }

public void render() {
            float finalCornerRadius = this.cornerRadius < 0 ? 0.5f * Math.min(this.width, this.height) : this.cornerRadius;
            Matrix3x2f pose = new Matrix3x2f();
            // In 1.21.1, there is no context.state.addSpecialElement / scissorStack accessor.
            // Register the widget directly with LiquidGlassUniforms for the blur pass.
            LiquidGlassUniforms.get().addWidget(new LiquidGlassGuiElementRenderState(
                    this.x, this.y, this.x + this.width, this.y + this.height,
                    finalCornerRadius, this.Component, this.style, pose, null,
                    this.hoverAmount, this.focusAmount
            ));
        }
    }
}
