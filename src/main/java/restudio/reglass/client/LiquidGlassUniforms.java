package restudio.reglass.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import restudio.reglass.client.api.ReGlassConfig;
import restudio.reglass.client.api.WidgetStyle;
import restudio.reglass.client.gui.LiquidGlassGuiElementRenderState;
import restudio.reglass.client.runtime.ReGlassAnim;

public final class LiquidGlassUniforms {

    private static final LiquidGlassUniforms INSTANCE = new LiquidGlassUniforms();
    public static final int MAX_WIDGETS = 64;
    public static final int MAX_BLUR_LEVELS = 5;

    public static LiquidGlassUniforms get() { return INSTANCE; }

    // â”€â”€ Stored uniform data (raw float arrays) â”€â”€

    /** samplerInfo: vec4 (outW, outH, outW, outH) â€“ 4 floats */
    private final float[] samplerInfoData = new float[4];

    /**
     * customUniforms: std140-aligned layout.
     * [0]  time
     * [1-3] padding
     * [4-7] mousePos vec4
     * [8]   wantsBlur
     * [9-11] padding
     * [12-14] rimLightDir vec3
     * [15] padding
     * [16-19] rimLightColor vec4
     * [20] pixelEpsilon
     * [21] debugStep
     * [22] pixelatedGrid
     * [23] pixelatedGridSize
     * [24] hoverScalePx
     * [25] focusScalePx
     * [26] focusBorderWidthPx
     * [27] focusBorderIntensity
     * [28] focusBorderSpeed
     */
    private final float[] customUniformsData = new float[29];

    /**
     * widgetInfo: header vec4 + MAX_WIDGETS Ã— 12 vec4 rows.
     * Total float slots: 4 (header) + MAX_WIDGETS * 12 * 4 = 4 + 3072 = 3076.
     * Row layout per widget:
     *   0: rect (x, y, w, h)
     *   1: corner radius (xxxx)
     *   2: tint color+alpha (r, g, b, a)
     *   3: refraction row 1 (thickness, factor, dispersion, fresnelRange)
     *   4: refraction row 2 (fresnelHardness, fresnelFactor, glareRange, glareHardness)
     *   5: glare (convergence, oppositeFactor, factor, angleRad)
     *   6: smoothing (smoothing, 0, 0, 0)
     *   7: scissor rect (left, bottom, right, top)
     *   8: shadow params (expand, factor, offsetX, offsetY)
     *   9: shadow color (r, g, b, alpha)
     *  10: blur fade (blurIndex, hover, focus, seed)
     *  11: reserved (0, 0, 0, 0)
     */
    private static final int WIDGET_HEADER_FLOATS = 4;
    private static final int WIDGET_ROW_FLOATS = 4;
    private static final int WIDGET_ROWS = 12;
    private final float[] widgetInfoData = new float[WIDGET_HEADER_FLOATS + MAX_WIDGETS * WIDGET_ROWS * WIDGET_ROW_FLOATS];

    /** bgConfig: shadowExpand, shadowFactor, shadowOffsetX, shadowOffsetY â€“ 4 floats */
    private final float[] bgConfigData = new float[4];

    // â”€â”€ Widget tracking â”€â”€

    private final List<LiquidGlassGuiElementRenderState> widgets = new ArrayList<>();
    private boolean screenWantsBlur = false;

    private List<Integer> usedBlurRadiiOrdered = new ArrayList<>();
    private final HashMap<Integer, Integer> blurRadiusToIndex = new HashMap<>();

    private static final class FadeState {
        float hover;
        float focus;
    }

    private final HashMap<Long, FadeState> fades = new HashMap<>();
    private double dtSeconds = 0.0;

    private LiquidGlassUniforms() {}

    // â”€â”€ Frame lifecycle â”€â”€

    public void beginFrame(double dtSeconds) {
        widgets.clear();
        screenWantsBlur = false;
        usedBlurRadiiOrdered.clear();
        blurRadiusToIndex.clear();
        this.dtSeconds = Math.max(0.0, dtSeconds);
    }

    public void setScreenWantsBlur(boolean wantsBlur) { this.screenWantsBlur = wantsBlur; }

    // â”€â”€ Uniform upload (store into class fields) â”€â”€

    public void uploadSharedUniforms() {
        Minecraft mc = Minecraft.getInstance();
        int outW = mc.getMainRenderTarget().width;
        int outH = mc.getMainRenderTarget().height;

        // samplerInfo: vec2(outW, outH), vec2(outW, outH)
        samplerInfoData[0] = (float) outW;
        samplerInfoData[1] = (float) outH;
        samplerInfoData[2] = (float) outW;
        samplerInfoData[3] = (float) outH;

        double[] mx = new double[1];
        double[] my = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), mx, my);
        float scale = (float) mc.getWindow().getGuiScale();
        int fbH = mc.getMainRenderTarget().height;

        float time = (float) GLFW.glfwGetTime();
        ReGlassConfig config = ReGlassConfig.INSTANCE;

        float x = (float) (mx[0] * scale);
        float y = fbH - (float) (my[0] * scale);
        var dir2 = config.rimLight.direction();
        int rc = config.rimLight.color();

        // [0] time
        customUniformsData[0] = time;
        // [1-3] padding (zeros from init)
        // [4-7] mousePos vec4
        customUniformsData[4] = x;
        customUniformsData[5] = y;
        customUniformsData[6] = 0f;
        customUniformsData[7] = 0f;
        // [8] wantsBlur
        customUniformsData[8] = this.screenWantsBlur ? 1.0f : 0f;
        // [9-11] padding
        // [12-14] rimLightDir vec3
        customUniformsData[12] = dir2.x;
        customUniformsData[13] = dir2.y;
        customUniformsData[14] = 0.0f;
        // [15] padding
        // [16-19] rimLightColor vec4
        customUniformsData[16] = (float)((rc >> 16) & 0xFF) / 255f;
        customUniformsData[17] = (float)((rc >> 8) & 0xFF) / 255f;
        customUniformsData[18] = (float)(rc & 0xFF) / 255f;
        customUniformsData[19] = config.rimLight.intensity();
        // [20-28] remaining scalars
        customUniformsData[20] = config.pixelEpsilon;
        customUniformsData[21] = ReGlassAnim.INSTANCE.debugStep();
        customUniformsData[22] = config.features.pixelatedGrid ? 1.0f : 0.0f;
        customUniformsData[23] = ReGlassAnim.INSTANCE.pixelatedGridSize();
        customUniformsData[24] = ReGlassAnim.INSTANCE.hoverScalePx();
        customUniformsData[25] = ReGlassAnim.INSTANCE.focusScalePx();
        customUniformsData[26] = ReGlassAnim.INSTANCE.focusBorderWidthPx();
        customUniformsData[27] = ReGlassAnim.INSTANCE.focusBorderIntensity();
        customUniformsData[28] = ReGlassAnim.INSTANCE.focusBorderSpeed();

        // bgConfig
        float s = (float) mc.getWindow().getGuiScale();
        bgConfigData[0] = ReGlassAnim.INSTANCE.shadowExpand();
        bgConfigData[1] = ReGlassAnim.INSTANCE.shadowFactor();
        bgConfigData[2] = ReGlassAnim.INSTANCE.shadowOffsetX() * s;
        bgConfigData[3] = ReGlassAnim.INSTANCE.shadowOffsetY() * s;
    }

    public void tryApplyBlur(GuiGraphics context) {
        // In 1.21.1, GuiRenderState / GuiRenderStateAccessor are not available.
        // Blur application is handled by the screen/background rendering system directly.
        // This method is intentionally a no-op; the blur pipeline runs in
        // GameRendererMixin.renderBlur() via LiquidGlassPrecomputeRuntime.
    }

    public void addWidget(LiquidGlassGuiElementRenderState element) {
        if (widgets.size() >= MAX_WIDGETS) return;
        widgets.add(element);
    }

    private static long rectKey(int x1, int y1, int x2, int y2) {
        long a = (((long) x1) & 0xFFFFFFFFL) | ((((long) y1) & 0xFFFFFFFFL) << 32);
        long b = (((long) x2) & 0xFFFFFFFFL) | ((((long) y2) & 0xFFFFFFFFL) << 32);
        long h = 1469598103934665603L;
        h ^= a; h *= 1099511628211L;
        h ^= b; h *= 1099511628211L;
        return h;
    }

    private float smoothToward(float current, float target, double dt, float tau) {
        if (tau <= 1e-5f) return target;
        float a = (float) (1.0 - Math.exp(-Math.max(0.0, dt) / Math.max(1e-4, tau)));
        float v = current + (target - current) * a;
        if (Math.abs(v - target) < 1e-4f) return target;
        return v;
    }

    public void uploadWidgetInfo() {
        Minecraft mc = Minecraft.getInstance();
        int fbH = mc.getMainRenderTarget().height;
        float scale = (float) mc.getWindow().getGuiScale();

        HashSet<Integer> requested = new HashSet<>();
        for (LiquidGlassGuiElementRenderState w : widgets) {
            WidgetStyle s = w.style();
            requested.add(Math.max(0, s.getBlurRadius()));
        }
        List<Integer> sorted = requested.stream().sorted().toList();
        usedBlurRadiiOrdered = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < MAX_BLUR_LEVELS; i++) usedBlurRadiiOrdered.add(sorted.get(i));
        if (usedBlurRadiiOrdered.isEmpty()) usedBlurRadiiOrdered.add(ReGlassAnim.INSTANCE.blurRadiusInt());
        blurRadiusToIndex.clear();
        for (int i = 0; i < usedBlurRadiiOrdered.size(); i++) blurRadiusToIndex.put(usedBlurRadiiOrdered.get(i), i);

        // Header: vec4 alignment â†’ store count in first float, rest padding
        widgetInfoData[0] = (float) widgets.size();
        widgetInfoData[1] = 0f;
        widgetInfoData[2] = 0f;
        widgetInfoData[3] = 0f;

        int base = WIDGET_HEADER_FLOATS;

        for (int row = 0; row < WIDGET_ROWS; row++) {
            for (int i = 0; i < MAX_WIDGETS; i++) {
                int offset = base + (row * MAX_WIDGETS + i) * WIDGET_ROW_FLOATS;
                if (i < widgets.size()) {
                    var w = widgets.get(i);
                    switch (row) {
                        case 0 -> { // rect (x, y, w, h)
                            float W = w.x2() - w.x1();
                            float H = w.y2() - w.y1();
                            float px = w.x1() * scale;
                            float pyTop = w.y1() * scale;
                            float pW = W * scale;
                            float pH = H * scale;
                            float cx = px + 0.5f * pW;
                            float cyTop = pyTop + 0.5f * pH;
                            float cyFB = (float) fbH - cyTop;
                            widgetInfoData[offset]     = cx - 0.5f * pW;
                            widgetInfoData[offset + 1] = cyFB - 0.5f * pH;
                            widgetInfoData[offset + 2] = pW;
                            widgetInfoData[offset + 3] = pH;
                        }
                        case 1 -> { // corner radius
                            float rad = w.cornerRadius() * scale;
                            widgetInfoData[offset]     = rad;
                            widgetInfoData[offset + 1] = rad;
                            widgetInfoData[offset + 2] = rad;
                            widgetInfoData[offset + 3] = rad;
                        }
                        case 2 -> { // tint color
                            int c = w.style().getTintColor();
                            widgetInfoData[offset]     = (float)((c >> 16) & 0xFF) / 255f;
                            widgetInfoData[offset + 1] = (float)((c >> 8) & 0xFF) / 255f;
                            widgetInfoData[offset + 2] = (float)(c & 0xFF) / 255f;
                            widgetInfoData[offset + 3] = w.style().getTintAlpha();
                        }
                        case 3 -> { // refraction row 1
                            WidgetStyle s = w.style();
                            widgetInfoData[offset]     = s.getRefThickness();
                            widgetInfoData[offset + 1] = s.getRefFactor();
                            widgetInfoData[offset + 2] = s.getRefDispersion();
                            widgetInfoData[offset + 3] = s.getRefFresnelRange();
                        }
                        case 4 -> { // refraction row 2
                            WidgetStyle s = w.style();
                            widgetInfoData[offset]     = s.getRefFresnelHardness();
                            widgetInfoData[offset + 1] = s.getRefFresnelFactor();
                            widgetInfoData[offset + 2] = s.getGlareRange();
                            widgetInfoData[offset + 3] = s.getGlareHardness();
                        }
                        case 5 -> { // glare
                            WidgetStyle s = w.style();
                            widgetInfoData[offset]     = s.getGlareConvergence();
                            widgetInfoData[offset + 1] = s.getGlareOppositeFactor();
                            widgetInfoData[offset + 2] = s.getGlareFactor();
                            widgetInfoData[offset + 3] = s.getGlareAngleRad();
                        }
                        case 6 -> { // smoothing
                            WidgetStyle s = w.style();
                            widgetInfoData[offset]     = s.getSmoothing();
                            widgetInfoData[offset + 1] = 0f;
                            widgetInfoData[offset + 2] = 0f;
                            widgetInfoData[offset + 3] = 0f;
                        }
                        case 7 -> { // scissor
                            ScreenRectangle sc = w.scissorArea();
                            if (sc != null) {
                                float sL = sc.left() * scale;
                                float sR = sc.right() * scale;
                                float sT = sc.top() * scale;
                                float sB = sc.bottom() * scale;
                                widgetInfoData[offset]     = sL;
                                widgetInfoData[offset + 1] = fbH - sB;
                                widgetInfoData[offset + 2] = sR;
                                widgetInfoData[offset + 3] = fbH - sT;
                            } else {
                                widgetInfoData[offset]     = 0f;
                                widgetInfoData[offset + 1] = 0f;
                                widgetInfoData[offset + 2] = (float) mc.getMainRenderTarget().width;
                                widgetInfoData[offset + 3] = (float) mc.getMainRenderTarget().height;
                            }
                        }
                        case 8 -> { // shadow params
                            WidgetStyle s = w.style();
                            float sx = s.getShadowOffsetX() * scale;
                            float sy = s.getShadowOffsetY() * scale;
                            widgetInfoData[offset]     = s.getShadowExpand();
                            widgetInfoData[offset + 1] = s.getShadowFactor();
                            widgetInfoData[offset + 2] = sx;
                            widgetInfoData[offset + 3] = sy;
                        }
                        case 9 -> { // shadow color
                            WidgetStyle s = w.style();
                            int col = s.getShadowColor();
                            widgetInfoData[offset]     = (float)((col >> 16) & 0xFF) / 255f;
                            widgetInfoData[offset + 1] = (float)((col >> 8) & 0xFF) / 255f;
                            widgetInfoData[offset + 2] = (float)(col & 0xFF) / 255f;
                            widgetInfoData[offset + 3] = s.getShadowColorAlpha();
                        }
                        case 10 -> { // blur index, hover, focus, seed
                            WidgetStyle s = w.style();
                            int radius = Math.max(0, s.getBlurRadius());
                            Integer idx = blurRadiusToIndex.get(radius);
                            if (idx == null) idx = 0;
                            long key = rectKey(w.x1(), w.y1(), w.x2(), w.y2());
                            FadeState fs = fades.computeIfAbsent(key, k -> new FadeState());
                            fs.hover = smoothToward(fs.hover, Math.max(0f, Math.min(1f, w.hover())), dtSeconds, 0.12f);
                            fs.focus = smoothToward(fs.focus, Math.max(0f, Math.min(1f, w.focus())), dtSeconds, 0.18f);
                            double h = Math.sin(w.x1() * 12.9898 + w.y1() * 78.233 + i * 37.719);
                            float seed = (float) (h - Math.floor(h));
                            widgetInfoData[offset]     = (float) idx;
                            widgetInfoData[offset + 1] = fs.hover;
                            widgetInfoData[offset + 2] = fs.focus;
                            widgetInfoData[offset + 3] = seed;
                        }
                        case 11 -> { // reserved
                            widgetInfoData[offset]     = 0f;
                            widgetInfoData[offset + 1] = 0f;
                            widgetInfoData[offset + 2] = 0f;
                            widgetInfoData[offset + 3] = 0f;
                        }
                    }
                } else {
                    widgetInfoData[offset]     = 0f;
                    widgetInfoData[offset + 1] = 0f;
                    widgetInfoData[offset + 2] = 0f;
                    widgetInfoData[offset + 3] = 0f;
                }
            }
        }
    }

    // â”€â”€ Accessors â”€â”€

    public int getCount() { return widgets.size(); }
    public List<Integer> getUsedBlurRadiiOrdered() { return usedBlurRadiiOrdered; }

    /** samplerInfo as a float array (4 floats: outW, outH, outW, outH). */
    public float[] getSamplerInfoData() { return samplerInfoData; }

    /** customUniforms as a float array (29 floats, std140-aligned). */
    public float[] getCustomUniformsData() { return customUniformsData; }

    /** widgetInfo as a flat float array. Use {@link #getWidgetInfoDataLength()} for the valid length. */
    public float[] getWidgetInfoData() { return widgetInfoData; }

    /**
     * Number of floats to upload. WidgetInfo contains arrays with a fixed
     * MAX_WIDGETS stride, so the shader's later arrays are not contiguous with
     * the number of widgets currently visible.
     */
    public int getWidgetInfoDataLength() {
        return widgetInfoData.length;
    }

    /** bgConfig as a float array (4 floats: shadowExpand, shadowFactor, shadowOffsetX, shadowOffsetY). */
    public float[] getBgConfigData() { return bgConfigData; }

    /** The current widget list (read-only view of widgets added this frame). */
    public List<LiquidGlassGuiElementRenderState> getAllWidgets() {
        return List.copyOf(widgets);
    }

    /** The blur radius â†’ index mapping computed during uploadWidgetInfo(). */
    public HashMap<Integer, Integer> getBlurRadiusToIndex() { return blurRadiusToIndex; }
}
