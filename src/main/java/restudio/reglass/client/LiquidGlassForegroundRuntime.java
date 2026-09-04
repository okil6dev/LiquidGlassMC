package restudio.reglass.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.BufferUploader;
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

    private RenderTarget foregroundFramebuffer;
    private RenderTarget tooltipFramebuffer;
    private int captureDepth;
    private boolean hasForeground;
    private boolean hasTooltip;
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
        hasTooltip = false;
    }

    public boolean isCapturing() {
        return captureDepth > 0;
    }

    public void beginCapture() {
        beginCaptureInto(false);
    }

    public void endCapture() {
        endCaptureInto(false);
    }

    /** Begin capturing into the topmost tooltip layer (composited after items/buttons). */
    public void beginTooltipCapture() {
        beginCaptureInto(true);
    }

    public void endTooltipCapture() {
        endCaptureInto(true);
    }

    private void beginCaptureInto(boolean tooltip) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        int width = main.width;
        int height = main.height;
        if (width <= 0 || height <= 0) return;

        ensureFramebuffers(width, height);
        if (captureDepth == 0) {
            previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
            RenderTarget target = tooltip ? tooltipFramebuffer : foregroundFramebuffer;
            boolean already = tooltip ? hasTooltip : hasForeground;
            if (!already) {
                clearFramebuffer(target);
            }
            target.bindWrite(true);
            BufferUploader.reset();
        }
        captureDepth++;
    }

    private void endCaptureInto(boolean tooltip) {
        if (captureDepth <= 0) return;
        captureDepth--;
        if (captureDepth == 0) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            if (tooltip) hasTooltip = true; else hasForeground = true;
        }
    }

    public void composite() {
        if (captureDepth != 0) return;

        Minecraft client = Minecraft.getInstance();
        RenderTarget main = client.getMainRenderTarget();
        main.bindWrite(true);

        BufferUploader.reset();
        RenderSystem.disableBlend();
        RenderSystem.enableBlend();
        GL14.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Composite the item/button/glass-foreground layer first, then the tooltip
        // layer last so tooltips always sit on top of 3D item models in inventories.
        if (hasForeground && foregroundFramebuffer != null) {
            foregroundFramebuffer.blitToScreen(main.width, main.height, false);
            BufferUploader.reset();
        }
        if (hasTooltip && tooltipFramebuffer != null) {
            tooltipFramebuffer.blitToScreen(main.width, main.height, false);
            BufferUploader.reset();
        }
    }

    public void invalidateBuffer() {
        if (foregroundFramebuffer != null) { foregroundFramebuffer.destroyBuffers(); foregroundFramebuffer = null; }
        if (tooltipFramebuffer != null) { tooltipFramebuffer.destroyBuffers(); tooltipFramebuffer = null; }
        hasForeground = false;
        hasTooltip = false;
    }

    private void ensureFramebuffers(int width, int height) {
        if (foregroundFramebuffer == null || foregroundFramebuffer.width != width || foregroundFramebuffer.height != height) {
            if (foregroundFramebuffer != null) foregroundFramebuffer.destroyBuffers();
            // 3rd arg = useDepth. The foreground holds the 3D inventory block-item models
            // (composter, ender chest, chest …); those need a depth buffer to hide their
            // interior faces, otherwise they render "hollow" with interiors showing through.
            foregroundFramebuffer = new TextureTarget(width, height, true, false);
            foregroundFramebuffer.setClearColor(0f, 0f, 0f, 0f);
        }
        if (tooltipFramebuffer == null || tooltipFramebuffer.width != width || tooltipFramebuffer.height != height) {
            if (tooltipFramebuffer != null) tooltipFramebuffer.destroyBuffers();
            // The tooltip is flat 2D, so it needs no depth buffer.
            tooltipFramebuffer = new TextureTarget(width, height, false, false);
            tooltipFramebuffer.setClearColor(0f, 0f, 0f, 0f);
        }
    }

    private void clearFramebuffer(RenderTarget target) {
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        target.clear(Minecraft.ON_OSX);
        if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
    }
}
