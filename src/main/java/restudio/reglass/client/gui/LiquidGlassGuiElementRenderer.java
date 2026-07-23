package restudio.reglass.client.gui;

import restudio.reglass.client.LiquidGlassUniforms;

public class LiquidGlassGuiElementRenderer {

    public void render(LiquidGlassGuiElementRenderState element) {
        LiquidGlassUniforms.get().addWidget(element);
    }
}