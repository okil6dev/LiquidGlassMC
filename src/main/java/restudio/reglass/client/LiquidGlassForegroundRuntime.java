package restudio.reglass.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * Owns the transparent foreground layer used by the liquid-glass pipeline.
 * The layer is written while the GUI is rendered and composited after glass.
 */
public final class LiquidGlassForegroundRuntime {
    private static final LiquidGlassForegroundRuntime INSTANCE = new LiquidGlassForegroundRuntime();

    public static LiquidGlassForegroundRuntime get() {
        return INSTANCE;
    }

    private Framebuffer foregroundFramebuffer;
    private int captureDepth;
    private boolean hasForeground;
    private int previousFramebuffer;
    private final int[] previousViewport = new int[4];

    private LiquidGlassForegroundRuntime() {}

    public void beginFrame() {
        if (captureDepth != 0) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }
        captureDepth = 0;
        hasForeground = false;
    }

    public boolean isCapturing() {
        return captureDepth > 0;
    }

    public void beginCapture() {
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer main = client.getFramebuffer();
        int width = main.textureWidth;
        int height = main.textureHeight;
        if (width <= 0 || height <= 0) return;

        ensureFramebuffer(width, height);
        if (captureDepth == 0) {
            previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
            if (!hasForeground) {
                clearForeground();
            }
            foregroundFramebuffer.beginWrite(true);
            BufferRenderer.reset();
        }
        captureDepth++;
    }

    public void endCapture() {
        if (captureDepth <= 0) return;
        captureDepth--;
        if (captureDepth == 0) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            hasForeground = true;
        }
    }

    public void composite() {
        if (captureDepth != 0 || !hasForeground || foregroundFramebuffer == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer main = client.getFramebuffer();
        main.beginWrite(true);

        BufferRenderer.reset();
        RenderSystem.disableBlend();
        RenderSystem.enableBlend();
        GL14.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        foregroundFramebuffer.draw(main.textureWidth, main.textureHeight, false);
        BufferRenderer.reset();
    }

    private void ensureFramebuffer(int width, int height) {
        if (foregroundFramebuffer != null
                && foregroundFramebuffer.textureWidth == width
                && foregroundFramebuffer.textureHeight == height) {
            return;
        }
        if (foregroundFramebuffer != null) foregroundFramebuffer.delete();
        foregroundFramebuffer = new SimpleFramebuffer(width, height, false, false);
        foregroundFramebuffer.setClearColor(0f, 0f, 0f, 0f);
    }

    private void clearForeground() {
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        foregroundFramebuffer.clear(false);
        if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
    }
}
