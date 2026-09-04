package restudio.reglass.mixin.logical;

import java.util.List;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import restudio.reglass.client.LiquidGlassPipelines;
import restudio.reglass.client.LiquidGlassPrecomputeRuntime;
import restudio.reglass.client.LiquidGlassForegroundRuntime;
import restudio.reglass.client.LiquidGlassUniforms;
import restudio.reglass.client.api.ReGlassConfig;
import restudio.reglass.client.runtime.ReGlassAnim;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    // Cached VAO/VBO for fullscreen quad
    @Unique
    private static int cachedVao = -1;
    @Unique
    private static int cachedVbo = -1;
    @Unique
    private static int reglass$lastFbW = -1;
    @Unique
    private static int reglass$lastFbH = -1;

    @Shadow @Final private Minecraft minecraft;

    // â”€â”€ Frame setup â”€â”€

    @Inject(method = "render", at = @At("HEAD"))
    private void reglass$beginGuiFrame(DeltaTracker tickCounter, boolean tick, CallbackInfo ci) {
        double deltaTicks;
        try {
            deltaTicks = tickCounter.getGameTimeDeltaPartialTick(true);
        } catch (Throwable t) {
            deltaTicks = 1.0 / 60.0 * 20.0;
        }
        double dt = deltaTicks / 20.0;
        LiquidGlassUniforms.get().beginFrame(dt);
        LiquidGlassForegroundRuntime.get().beginFrame();
        ReGlassAnim.INSTANCE.update(ReGlassConfig.INSTANCE, dt);
    }

    // â”€â”€ Liquid-glass rendering â”€â”€
    //
    // NOTE: In Minecraft 1.21.1, GameRenderer.renderBlur() is never called â€” it exists as a
    // method but no code path invokes it. We inject at render() TAIL instead, which runs
    // every frame after HUD + screen rendering is complete and widgets have been registered.

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("ReGlass/GameRenderer");
    @Inject(method = "render", at = @At("TAIL"))
    private void reglass$renderLiquidGlass(DeltaTracker tickCounter, boolean tick, CallbackInfo ci) {
        LiquidGlassUniforms uniforms = LiquidGlassUniforms.get();
        LiquidGlassForegroundRuntime foreground = LiquidGlassForegroundRuntime.get();
        int count = uniforms.getCount();
        if (count <= 0) {
            foreground.composite();
            return;
        }

        RenderTarget mainFb = this.minecraft.getMainRenderTarget();
        if (mainFb.width != reglass$lastFbW || mainFb.height != reglass$lastFbH) {
            // Window or resolution scaled: skip glass one frame so the off-screen
            // targets settle at the new size. Rendering on the transition frame
            // can snapshot mismatched framebuffers and paint the whole screen black.
            reglass$lastFbW = mainFb.width;
            reglass$lastFbH = mainFb.height;
            // DIAG: report every frame where the pipeline sees a size transition, so a
            // black-background-on-resize can be tied to the exact metric that moved.
            try {
                int ww = (int) this.minecraft.getWindow().getWidth();
                int wh = (int) this.minecraft.getWindow().getHeight();
                float gs = (float) this.minecraft.getWindow().getGuiScale();
                LOGGER.info("ReGlass resize detect: mainFb={}x{} win={}x{} guiScale={} widgets={}",
                        mainFb.width, mainFb.height, ww, wh, gs, count);
            } catch (Throwable t) {
                LOGGER.info("ReGlass resize detect (bare): mainFb={}x{} widgets={}",
                        mainFb.width, mainFb.height, count);
            }
            // Force full re-creation of all off-screen framebuffers so the blur and
            // foreground targets are re-allocated against the freshly-regenerated main
            // target. This is the fix for the known "incomplete buffer regeneration on
            // resize" bug that otherwise leaves the background black.
            LiquidGlassPrecomputeRuntime.get().invalidateBuffers();
            LiquidGlassForegroundRuntime.get().invalidateBuffer();
            foreground.composite();
            return;
        }

        uniforms.uploadSharedUniforms();
        uniforms.uploadWidgetInfo();

        List<Integer> radii = uniforms.getUsedBlurRadiiOrdered();
        LiquidGlassPrecomputeRuntime.get().setRequestedRadii(radii);
        LiquidGlassPrecomputeRuntime.get().run();

        int program = LiquidGlassPipelines.getGuiProgram();
        if (program == -1) {
            LOGGER.warn("ReGlass: shader program failed to compile, skipping render");
            foreground.composite();
            return;
        }

        // Save GL state
        int prevProgram = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depthWasOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendWasOn = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean cullWasOn = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMaskWasOn = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int[] prevTextures = new int[LiquidGlassUniforms.MAX_BLUR_LEVELS + 1];
        for (int i = 0; i < prevTextures.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            prevTextures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL13.glActiveTexture(prevActiveTexture);

        // Disable scissor so our fullscreen quad isn't clipped by Minecraft's GUI scissor rect
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        // Disable blend for clean shader output (alpha=1.0 output doesn't need blending)
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);

        GL20.glUseProgram(program);

        // Ensure UBOs exist
        if (!LiquidGlassPipelines.ensureUbos()) {
            GL20.glUseProgram(prevProgram);
            foreground.composite();
            return;
        }

        // Upload uniforms via UBOs
        LiquidGlassPipelines.uploadSamplerInfo(uniforms.getSamplerInfoData());
        LiquidGlassPipelines.uploadCustomUniforms(uniforms.getCustomUniformsData());
        LiquidGlassPipelines.uploadWidgetInfo(uniforms.getWidgetInfoData(),
                uniforms.getWidgetInfoDataLength());
        LiquidGlassPipelines.uploadBgConfig(uniforms.getBgConfigData());

        // Bind all UBOs to their binding points
        LiquidGlassPipelines.bindUbos();

        // Set DiffuseSampler to texture unit 0
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "DiffuseSampler"), 0);

        // Copy main RenderTarget colour attachment to a temp texture so we don't
        // read from the same FBO we are writing to (undefined behaviour in GL).
        int srcTex = mainFb.getColorTextureId();
        int fbW = mainFb.width;
        int fbH = mainFb.height;

        int tmpTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tmpTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, fbW, fbH, 0,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // Use glBlitFramebuffer to copy: set READ to mainFb, DRAW to a temp FBO with tmpTex
        int prevReadFbo = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        int tmpFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, tmpFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, tmpTex, 0);

        // READ from the main render target own framebuffer (not whatever the
        // current draw binding happens to be) so the snapshot is always the main
        // target. A stale/wrong read binding here is what turns the background black.
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainFb.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, fbW, fbH, 0, 0, fbW, fbH,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        // Restore mainFb as draw target
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);

        // Bind the copy as Sampler0
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tmpTex);

        // Bind per-radius blur textures to units 1-5
        for (int i = 0; i < LiquidGlassUniforms.MAX_BLUR_LEVELS; i++) {
            int unit = i + 1;
            int texId = resolveBlurTextureId(mainFb, radii, i, tmpTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler" + (i + 1)), unit);
        }

        // Restore active texture unit
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        // Draw fullscreen quad using raw OpenGL to avoid Minecraft's RenderSystem
       // overriding our shader program (BufferRenderer.drawWithGlobalProgram does that).
       GL11.glDisable(GL11.GL_DEPTH_TEST);
       GL11.glDepthMask(false);

       // Ensure viewport covers the full RenderTarget so our [0,1] NDC quad maps correctly
       int[] viewport = new int[4];
       GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glViewport(0, 0, fbW, fbH);

        // Initialize cached VAO/VBO on first use
        if (cachedVao == -1) {
            cachedVao = GL30.glGenVertexArrays();
            if (cachedVao == 0) {
                LOGGER.error("ReGlass: Failed to generate VAO (OpenGL context may not support VAOs)");
                GL20.glUseProgram(prevProgram);
                foreground.composite();
                return;
            }
            cachedVbo = GL30.glGenBuffers();
            GL30.glBindVertexArray(cachedVao);

            // 4 vertices: counter-clockwise TRIANGLE_STRIP order: BL, BR, TL, TR
            // Position (vec3, normalized [0,1]) + TexCoord (vec2)
            float[] verts = {
                0, 0, 0,  0, 0,  // BL
                1, 0, 0,  1, 0,  // BR
                0, 1, 0,  0, 1,  // TL
                1, 1, 0,  1, 1,  // TR
            };
            java.nio.FloatBuffer vertBuf = org.lwjgl.BufferUtils.createFloatBuffer(verts.length);
            vertBuf.put(verts).flip();

            GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, cachedVbo);
            GL30.glBufferData(GL30.GL_ARRAY_BUFFER, vertBuf, GL30.GL_STATIC_DRAW);

            // Attrib 0: Position (vec3) â€” location 0 in vertex shader
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 5 * 4, 0);
            // Attrib 1: TexCoord (vec2) â€” not used by our shader but required by POSITION_TEXTURE format
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 5 * 4, 3 * 4);

            GL30.glBindVertexArray(0);
            GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);
        }
        GL30.glBindVertexArray(cachedVao);

        // 4 vertices: counter-clockwise TRIANGLE_STRIP order: BL, BR, TL, TR
        // Position (vec3, normalized [0,1]) + TexCoord (vec2)

        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Attribute enable state belongs to the VAO. Keep the cached VAO intact
        // so subsequent frames do not draw with all attributes disabled.
        GL30.glBindVertexArray(prevVao);

        // Restore viewport
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

        // Clean up temp copy resources
        GL11.glDeleteTextures(tmpTex);
        GL30.glDeleteFramebuffers(tmpFbo);

        for (int i = 0; i < prevTextures.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTextures[i]);
        }
        GL13.glActiveTexture(prevActiveTexture);

        GL11.glDepthMask(depthMaskWasOn);
        if (depthWasOn) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasOn) GL11.glEnable(GL11.GL_BLEND);
        if (scissorWasOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        if (cullWasOn) GL11.glEnable(GL11.GL_CULL_FACE);

        // Restore previous program
        GL20.glUseProgram(prevProgram);

        // 1.21.8 renders the glass special element before widget foregrounds.
        // Replaying widgets here restores that ordering on 1.21.1; glass
        // registration and vanilla button backgrounds stay suppressed while
        // the replay is active.
        foreground.composite();
    }

    // â”€â”€ Texture resolution â”€â”€

    private int resolveBlurTextureId(RenderTarget mainFb, List<Integer> radii, int index, int fallbackTex) {
        // Fallback MUST be the frozen pre-pass copy (tmpTex / Sampler0's texture),
        // never the live main target. The glass pass draws INTO mainFb, so binding
        // mainFb's own color texture as a sampler while writing to it is undefined
        // GL behavior — on many GPUs it scrambles the whole frame, most visibly over
        // the bright sky/world in-game.
        int fallback = fallbackTex;
        int radius;
        if (index < radii.size()) {
            radius = radii.get(index);
        } else if (!radii.isEmpty()) {
            radius = radii.get(0);
        } else {
            return fallback;
        }
        if (radius <= 0) return fallback;
        RenderTarget blurFb = LiquidGlassPrecomputeRuntime.get().getBlurredViewForRadius(radius);
        return (blurFb != null) ? blurFb.getColorTextureId() : fallback;
    }

}
