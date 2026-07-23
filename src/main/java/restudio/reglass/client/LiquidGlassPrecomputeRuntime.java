package restudio.reglass.client;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryStack;
import restudio.reglass.client.api.ReGlassConfig;

/**
 * Two-pass Gaussian blur precomputation runtime.
 * <p>
 * Reads the main framebuffer and blurs it at each requested radius
 * (horizontal pass → temp FBO, vertical pass → per-radius output FBO).
 * Uses raw OpenGL shader compilation, uniform-buffer objects and
 * Minecraft 1.21.1 {@link Framebuffer} as off-screen render targets.
 */
public final class LiquidGlassPrecomputeRuntime {

    private static final LiquidGlassPrecomputeRuntime INSTANCE = new LiquidGlassPrecomputeRuntime();

    public static LiquidGlassPrecomputeRuntime get() {
        return INSTANCE;
    }

    private static final int MAX_RADIUS = 64;

    /** Uniform-block binding points shared across all draw calls. */
    private static final int SI_BINDING = 0;
    private static final int CFG_BINDING = 1;

    /** Compiled blur shader program handle. */
    private int program = -1;

    /** Location of the "DiffuseSampler" sampler uniform (always unit 0). */
    private int uSampler = -1;

    /** Uniform-buffer-object handles. */
    private int uboSamplerInfo = -1;
    private int uboConfigX = -1;
    private int uboConfigY = -1;

    /** Fullscreen-quad VAO / VBO (shared by every draw call). */
    private int quadVao = -1;
    private int quadVbo = -1;

    /** Intermediate FBO between the two blur passes. */
    private Framebuffer tempFb;

    /** Per-radius output FBOs. */
    private final HashMap<Integer, Framebuffer> outputByRadius = new HashMap<>();

    private List<Integer> requestedRadii = new ArrayList<>();

    private LiquidGlassPrecomputeRuntime() {}

    /* ================================================================
     *  Shader helpers
     * ================================================================ */

    private static String readResource(String path) {
        try {
            var opt = MinecraftClient.getInstance().getResourceManager()
                    .getResource(Identifier.of("reglass", path));
            if (opt.isPresent()) {
                try (var is = opt.get().getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
        throw new RuntimeException("Resource not found: " + path);
    }

    private static int compile(int type, String source) {
        int handle = GL20.glCreateShader(type);
        GL20.glShaderSource(handle, source);
        GL20.glCompileShader(handle);
        if (GL20.glGetShaderi(handle, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(handle);
            GL20.glDeleteShader(handle);
            throw new RuntimeException("Shader compile error: " + log);
        }
        return handle;
    }

    private int buildProgram() {
        int vs = compile(GL20.GL_VERTEX_SHADER,
                readResource("shaders/core/blit_fullscreen.vsh"));
        int fs = compile(GL20.GL_FRAGMENT_SHADER,
                readResource("shaders/program/blur.fsh"));

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);

        // Force "Position" to location 0 so it matches our VAO layout.
        GL20.glBindAttribLocation(p, 0, "Position");

        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(p);
            GL20.glDeleteProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            throw new RuntimeException("Program link error: " + log);
        }

        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return p;
    }

    /* ================================================================
     *  Lazy initialisation
     * ================================================================ */

    private void initProgram() {
        if (program != -1) return;

        program = buildProgram();
        uSampler = GL20.glGetUniformLocation(program, "DiffuseSampler");

        int siIdx = GL31.glGetUniformBlockIndex(program, "SamplerInfo");
        if (siIdx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, siIdx, SI_BINDING);
        }
        int cfgIdx = GL31.glGetUniformBlockIndex(program, "Config");
        if (cfgIdx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, cfgIdx, CFG_BINDING);
        }
    }

    private void initUbos() {
        if (uboSamplerInfo != -1) return;

        uboSamplerInfo = createUbo(16);
        int cfgSize = 16 + (MAX_RADIUS + 1) * 16;
        uboConfigX = createUbo(cfgSize);
        uboConfigY = createUbo(cfgSize);
    }

    private static int createUbo(int sizeBytes) {
        int handle = GL30.glGenBuffers();
        GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, handle);
        GL30.glBufferData(GL31.GL_UNIFORM_BUFFER, sizeBytes, GL30.GL_DYNAMIC_DRAW);
        GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        return handle;
    }

    private void initQuad() {
        if (quadVao != -1) return;

        quadVao = GL30.glGenVertexArrays();
        quadVbo = GL30.glGenBuffers();

        GL30.glBindVertexArray(quadVao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, quadVbo);

        // Four corners of a unit quad – vertex shader maps [0,1] → NDC [-1,1].
        float[] verts = {
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f,
                1f, 1f, 0f
        };
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(verts.length);
            buf.put(verts).flip();
            GL30.glBufferData(GL30.GL_ARRAY_BUFFER, buf, GL30.GL_STATIC_DRAW);
        }

        // Attribute 0 = Position (vec3), stride 12 bytes, offset 0.
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GL20.glEnableVertexAttribArray(0);

        GL30.glBindVertexArray(0);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);
    }

    private void ensureTempFb(int w, int h) {
        if (tempFb != null && tempFb.textureWidth == w && tempFb.textureHeight == h) {
            return;
        }
        if (tempFb != null) {
            tempFb.delete();
        }
        tempFb = allocateFb(w, h);
    }

    private void ensureOutputFb(int w, int h, int radius) {
        Framebuffer fb = outputByRadius.get(radius);
        if (fb != null && fb.textureWidth == w && fb.textureHeight == h) {
            return;
        }
        if (fb != null) {
            fb.delete();
        }
        outputByRadius.put(radius, allocateFb(w, h));
    }

    private static Framebuffer allocateFb(int w, int h) {
        Framebuffer fb = new SimpleFramebuffer(w, h, false, false);

        // Set bilinear filtering and clamp-to-edge on the colour attachment.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.getColorAttachment());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        return fb;
    }

    /* ================================================================
     *  Gaussian kernel (unchanged from original)
     * ================================================================ */

    private static float[] gaussian(int radius) {
        radius = Math.max(0, Math.min(radius, MAX_RADIUS));
        float sigma = radius / 3.0f;
        if (radius == 0) return new float[]{1f};

        float[] kernel = new float[radius + 1];
        float sum = 0f;
        for (int i = 0; i <= radius; i++) {
            float w = (float) Math.exp(-0.5 * ((float) i * (float) i) / (sigma * sigma));
            kernel[i] = w;
            sum += (i == 0) ? w : (2f * w);
        }
        for (int i = 0; i <= radius; i++) kernel[i] /= sum;
        return kernel;
    }

    /* ================================================================
     *  UBO upload helpers
     * ================================================================ */

    /**
     * Upload the {@code SamplerInfo} uniform block (vec2 OutSize, vec2 InSize).
     */
    private void uploadSamplerInfo(int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buf = stack.malloc(16);
            buf.putFloat((float) w);   // OutSize.x
            buf.putFloat((float) h);   // OutSize.y
            buf.putFloat((float) w);   // InSize.x
            buf.putFloat((float) h);   // InSize.y
            buf.flip();

            GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uboSamplerInfo);
            GL30.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, buf);
            GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        }
    }

    /**
     * Upload the {@code Config} uniform block (vec4 Params, float Weights[65]).
     * std140 layout: each array element occupies 16 bytes.
     */
    private void uploadConfig(int ubo, float dx, float dy, int radius) {
        radius = Math.max(0, Math.min(radius, MAX_RADIUS));
        float[] weights = gaussian(radius);

        int size = 16 + (MAX_RADIUS + 1) * 16;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buf = stack.malloc(size);

            // vec4 Params: direction (dx, dy), radius, pad
            buf.putFloat(dx);
            buf.putFloat(dy);
            buf.putFloat((float) radius);
            buf.putFloat(0f);

            // float Weights[65] – std140 pads each float to 16 bytes.
            for (int i = 0; i <= MAX_RADIUS; i++) {
                buf.putFloat((i <= radius) ? weights[i] : 0f);
                buf.putFloat(0f); // pad
                buf.putFloat(0f); // pad
                buf.putFloat(0f); // pad
            }

            buf.flip();
            GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
            GL30.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, buf);
            GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        }
    }

    /* ================================================================
     *  Public API
     * ================================================================ */

    public void setRequestedRadii(List<Integer> ordered) {
        requestedRadii = new ArrayList<>(ordered);
    }

    /**
     * Execute the two-pass Gaussian blur for every requested radius.
     */
    public void run() {
        initProgram();
        initUbos();
        initQuad();

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer main = mc.getFramebuffer();
        int w = main.textureWidth;
        int h = main.textureHeight;
        if (w <= 0 || h <= 0) return;

        ensureTempFb(w, h);
        uploadSamplerInfo(w, h);

        int max = Math.min(LiquidGlassUniforms.MAX_BLUR_LEVELS,
                requestedRadii == null ? 0 : requestedRadii.size());
        if (max == 0) {
            requestedRadii = List.of(ReGlassConfig.INSTANCE.defaultBlurRadius);
            max = 1;
        }

        // ── Save GL state ────────────────────────────────────────────
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depthWasOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendWasOn = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        // ── Configure shader ─────────────────────────────────────────
        GL20.glUseProgram(program);
        GL20.glUniform1i(uSampler, 0); // DiffuseSampler = texture unit 0

        // Bind sampler-info UBO (same for all passes).
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, SI_BINDING, uboSamplerInfo);

        // ── Blur passes ──────────────────────────────────────────────
        for (int k = 0; k < max; k++) {
            int radius = requestedRadii.get(k);
            if (radius <= 0) continue;

            ensureOutputFb(w, h, radius);

            // ── Pass 1: horizontal blur ──────────────────────────────
            // Source: main framebuffer → dest: tempFb
            uploadConfig(uboConfigX, 1f, 0f, radius);
            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, CFG_BINDING, uboConfigX);

            tempFb.beginWrite(true);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, main.getColorAttachment());

            GL30.glBindVertexArray(quadVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

            // ── Pass 2: vertical blur ────────────────────────────────
            // Source: tempFb → dest: outputByRadius[radius]
            uploadConfig(uboConfigY, 0f, 1f, radius);
            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, CFG_BINDING, uboConfigY);

            Framebuffer output = outputByRadius.get(radius);
            output.beginWrite(true);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tempFb.getColorAttachment());

            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        }

        GL30.glBindVertexArray(prevVao);

        // ── Restore GL state ─────────────────────────────────────────
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture0);
        GL13.glActiveTexture(prevActiveTexture);
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        if (depthWasOn) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasOn) GL11.glEnable(GL11.GL_BLEND);
    }

    /**
     * Returns the blurred output {@link Framebuffer} for the given radius,
     * or {@code null} if that radius has not been computed yet.
     */
    public Framebuffer getBlurredViewForRadius(int radius) {
        return outputByRadius.get(radius);
    }
}
