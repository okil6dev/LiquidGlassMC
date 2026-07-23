package restudio.reglass.client;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compiles and caches the liquid_glass_gui shader program using raw OpenGL,
 * and manages Uniform Buffer Objects for its std140 uniform blocks.
 */
public final class LiquidGlassPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("LiquidGlassPipelines");

    private static int GUI_PROGRAM = -1;

    // UBO binding points (2-5 to avoid conflict with PrecomputeRuntime's 0,1)
    public static final int BINDING_SAMPLER_INFO = 2;
    public static final int BINDING_CUSTOM_UNIFORMS = 3;
    public static final int BINDING_WIDGET_INFO = 4;
    public static final int BINDING_BG_CONFIG = 5;

    /** UBO size: SamplerInfo = vec2+vec2 = 16 bytes. */
    private static final int SIZE_SAMPLER_INFO = 16;
    /**
     * UBO size: CustomUniforms is 29 useful floats plus the trailing std140
     * padding needed to round the block size up to a 16-byte boundary.
     */
    private static final int SIZE_CUSTOM_UNIFORMS = 32 * 4;
    /** UBO size: WidgetInfo header(4) + MAX_WIDGETS*12*4 floats = 3076 floats. */
    private static final int SIZE_WIDGET_INFO = 3076 * 4;
    /** UBO size: BgConfig = 4 floats = 16 bytes. */
    private static final int SIZE_BG_CONFIG = 16;

    private static int UBO_SAMPLER_INFO = -1;
    private static int UBO_CUSTOM_UNIFORMS = -1;
    private static int UBO_WIDGET_INFO = -1;
    private static int UBO_BG_CONFIG = -1;

    private LiquidGlassPipelines() {}

    /** @return the raw OpenGL program handle (or -1 on failure). */
    public static synchronized int getGuiProgram() {
        if (GUI_PROGRAM == -1) {
            GUI_PROGRAM = buildProgram(
                    "shaders/core/blit_fullscreen.vsh",
                    "shaders/program/liquid_glass_gui.fsh"
            );
            if (GUI_PROGRAM != -1) {
                initUniformBlockBindings(GUI_PROGRAM);
            }
        }
        return GUI_PROGRAM;
    }

    private static void initUniformBlockBindings(int program) {
        int siIdx = GL31.glGetUniformBlockIndex(program, "SamplerInfo");
        if (siIdx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, siIdx, BINDING_SAMPLER_INFO);
        } else {
            LOGGER.warn("Uniform block 'SamplerInfo' not found in gui program");
        }
        int cuIdx = GL31.glGetUniformBlockIndex(program, "CustomUniforms");
        if (cuIdx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, cuIdx, BINDING_CUSTOM_UNIFORMS);
        } else {
            LOGGER.warn("Uniform block 'CustomUniforms' not found in gui program");
        }
        int wiIdx = GL31.glGetUniformBlockIndex(program, "WidgetInfo");
        if (wiIdx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, wiIdx, BINDING_WIDGET_INFO);
        } else {
            LOGGER.warn("Uniform block 'WidgetInfo' not found in gui program");
        }
        int bgIdx = GL31.glGetUniformBlockIndex(program, "BgConfig");
        if (bgIdx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(program, bgIdx, BINDING_BG_CONFIG);
        } else {
            LOGGER.warn("Uniform block 'BgConfig' not found in gui program");
        }
    }

    private static int createUbo(int sizeBytes) {
        int handle = GL30.glGenBuffers();
        GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, handle);
        GL30.glBufferData(GL31.GL_UNIFORM_BUFFER, sizeBytes, GL30.GL_DYNAMIC_DRAW);
        GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        return handle;
    }

    /** Ensure UBOs exist, return true if ready. */
    public static synchronized boolean ensureUbos() {
        if (GUI_PROGRAM == -1 && getGuiProgram() == -1) return false;
        if (UBO_SAMPLER_INFO == -1) {
            UBO_SAMPLER_INFO = createUbo(SIZE_SAMPLER_INFO);
            UBO_CUSTOM_UNIFORMS = createUbo(SIZE_CUSTOM_UNIFORMS);
            UBO_WIDGET_INFO = createUbo(SIZE_WIDGET_INFO);
            UBO_BG_CONFIG = createUbo(SIZE_BG_CONFIG);
        }
        return true;
    }

    /** Upload a float array to the SamplerInfo UBO. */
    public static void uploadSamplerInfo(float[] data) {
        if (UBO_SAMPLER_INFO == -1) return;
        uploadFloatArrayToUbo(UBO_SAMPLER_INFO, data, Math.min(data.length, 4) * 4);
    }

    /** Upload a float array to the CustomUniforms UBO. */
    public static void uploadCustomUniforms(float[] data) {
        if (UBO_CUSTOM_UNIFORMS == -1) return;
        int len = Math.min(data.length, 29) * 4;
        uploadFloatArrayToUbo(UBO_CUSTOM_UNIFORMS, data, len);
    }

    /** Upload a float array to the WidgetInfo UBO. */
    public static void uploadWidgetInfo(float[] data, int length) {
        if (UBO_WIDGET_INFO == -1) return;
        int bytes = Math.min(length * 4, SIZE_WIDGET_INFO);
        uploadFloatArrayToUbo(UBO_WIDGET_INFO, data, bytes);
    }

    /** Upload a float array to the BgConfig UBO. */
    public static void uploadBgConfig(float[] data) {
        if (UBO_BG_CONFIG == -1) return;
        uploadFloatArrayToUbo(UBO_BG_CONFIG, data, Math.min(data.length, 4) * 4);
    }

    private static void uploadFloatArrayToUbo(int ubo, float[] data, int bytes) {
        GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buf = stack.malloc(bytes);
            buf.asFloatBuffer().put(data, 0, bytes / 4);
            buf.limit(bytes);
            GL30.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, buf);
        }
        GL30.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    /** Bind all UBOs to their binding points. Must call ensureUbos() first. */
    public static void bindUbos() {
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING_SAMPLER_INFO, UBO_SAMPLER_INFO);
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING_CUSTOM_UNIFORMS, UBO_CUSTOM_UNIFORMS);
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING_WIDGET_INFO, UBO_WIDGET_INFO);
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING_BG_CONFIG, UBO_BG_CONFIG);
    }

    private static String loadSource(String path) {
        var id = Identifier.of("reglass", path);
        try {
            var opt = MinecraftClient.getInstance().getResourceManager().getResource(id);
            if (opt.isPresent()) {
                try (InputStream is = opt.get().getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load shader {}", path, e);
        }
        return "";
    }

    private static int compile(int type, String source) {
        int handle = GL20.glCreateShader(type);
        GL20.glShaderSource(handle, source);
        GL20.glCompileShader(handle);
        if (GL20.glGetShaderi(handle, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Shader compile error: {}", GL20.glGetShaderInfoLog(handle));
            GL20.glDeleteShader(handle);
            return -1;
        }
        return handle;
    }

    private static int buildProgram(String vsPath, String fsPath) {
        String vsSource = loadSource(vsPath);
        String fsSource = loadSource(fsPath);
        if (vsSource.isEmpty() || fsSource.isEmpty()) return -1;

        int vs = compile(GL20.GL_VERTEX_SHADER, vsSource);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fsSource);
        if (vs == -1 || fs == -1) return -1;

        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glBindAttribLocation(prog, 0, "Position");
        GL20.glLinkProgram(prog);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Program link error: {}", GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            return -1;
        }

        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return prog;
    }
}
