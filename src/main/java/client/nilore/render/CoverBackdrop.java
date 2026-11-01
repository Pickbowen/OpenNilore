package client.nilore.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import client.nilore.ClientBase;

public final class CoverBackdrop {

    private static final int PALETTE_SIZE = 32;

    private static final int PALETTE_BLUR_RADIUS = 2;
    private static final int PALETTE_BLUR_PASSES = 4;

    private static int programId = 0;
    private static int vaoId = 0;
    private static int vboId = 0;
    private static int coverUniform = -1;
    private static int prevCoverUniform = -1;
    private static int resolutionUniform = -1;
    private static int timeUniform = -1;
    private static int fadeUniform = -1;
    private static int beatUniform = -1;
    private static int panelRectUniform = -1;
    private static int panelRadiusUniform = -1;
    private static int panelEnabledUniform = -1;

    private static boolean failed = false;

    private CoverBackdrop() {
    }

    private static final String VERTEX_SOURCE = """
            #version 150
            in vec3 Position;
            in vec2 UV0;
            out vec2 TexCoord;
            void main() {
                gl_Position = vec4(Position, 1.0);
                TexCoord = UV0;
            }
            """;

    private static final String FRAGMENT_SOURCE = """
            #version 150

            in vec2 TexCoord;

            uniform sampler2D Cover;
            uniform sampler2D PrevCover;
            uniform vec2 Resolution;
            uniform float Time;
            uniform float Fade;
            uniform float Beat;

            uniform vec4 PanelRect;      // x0, y0, x1, y1
            uniform float PanelRadius;
            uniform float PanelEnabled;

            out vec4 fragColor;

            float roundedBoxSDF(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            void main() {
                vec2 uv = TexCoord;

                vec2 panelUv = uv;
                vec2 panelSize = Resolution;
                float alpha = 1.0;

                if (PanelEnabled > 0.5) {
                    vec2 fragPx = uv * Resolution;
                    vec2 rect0 = PanelRect.xy;
                    vec2 rect1 = PanelRect.zw;
                    vec2 size = max(rect1 - rect0, vec2(1.0));
                    panelUv = clamp((fragPx - rect0) / size, 0.0, 1.0);
                    panelSize = size;

                    vec2 center = (rect0 + rect1) * 0.5;
                    float sdf = roundedBoxSDF(fragPx - center, size * 0.5, PanelRadius);
                    alpha = 1.0 - smoothstep(-0.75, 0.75, sdf);
                    if (alpha <= 0.002) discard;
                }

                vec2 correction = vec2(min(1.0, panelSize.x / panelSize.y),
                                       min(1.0, panelSize.y / panelSize.x));
                vec2 p = (panelUv - 0.5) * correction;
                p *= 1.0 - 0.10 * Beat;

                float t = Time;

                vec2 q = p;
                q += 0.14 * vec2(sin(t * 0.42 + p.y * 3.7), cos(t * 0.37 + p.x * 3.1));
                q += 0.08 * vec2(cos(t * 0.29 + p.x * 2.3), sin(t * 0.51 + p.y * 4.4));
                q += 0.04 * vec2(sin(t * 0.77 - p.y * 6.1), cos(t * 0.71 - p.x * 5.3));

                float a = t * 0.09;
                float cs = cos(a);
                float sn = sin(a);
                q = mat2(cs, -sn, sn, cs) * q;

                vec2 texUV = clamp(q + 0.5, 0.01, 0.99);
                vec3 col = mix(texture(PrevCover, texUV).rgb,
                               texture(Cover, texUV).rgb,
                               Fade);

                float dist = length(panelUv - 0.5) * 1.42;
                float core = 1.0 - smoothstep(0.0, 1.05, dist);
                col *= mix(0.60, 1.0, core);
                col += col * core * core * 0.08;

                float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                col += (dither - 0.5) / 255.0;

                fragColor = vec4(clamp(col, 0.0, 1.0), alpha);
            }
            """;

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    /**
     *
     * <ol>
     * </ol>
     *
     *
     */
    public static NativeImage prepareFluidPalette(NativeImage source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        int sw = source.getWidth();
        int sh = source.getHeight();
        int size = PALETTE_SIZE;
        float[] rgb = new float[size * size * 3];

        for (int y = 0; y < size; y++) {
            int y0 = y * sh / size;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / size);
            int stepY = Math.max(1, (y1 - y0) / 4);
            for (int x = 0; x < size; x++) {
                int x0 = x * sw / size;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / size);
                int stepX = Math.max(1, (x1 - x0) / 4);
                int count = 0;
                int sumR = 0;
                int sumG = 0;
                int sumB = 0;
                for (int sy = y0; sy < y1; sy += stepY) {
                    for (int sx = x0; sx < x1; sx += stepX) {
                        int abgr = source.getPixelRGBA(sx, sy);
                        sumR += FastColor.ABGR32.red(abgr);
                        sumG += FastColor.ABGR32.green(abgr);
                        sumB += FastColor.ABGR32.blue(abgr);
                        count++;
                    }
                }
                int offset = (y * size + x) * 3;
                float inv = 1.0f / Math.max(1, count);
                rgb[offset] = sumR * inv;
                rgb[offset + 1] = sumG * inv;
                rgb[offset + 2] = sumB * inv;
            }
        }

        saturate(rgb);
        boxBlur(rgb, size, size, PALETTE_BLUR_RADIUS, PALETTE_BLUR_PASSES);

        NativeImage out = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        for (int i = 0; i < size * size; i++) {
            int r = toByte(rgb[i * 3]);
            int g = toByte(rgb[i * 3 + 1]);
            int b = toByte(rgb[i * 3 + 2]);
            out.setPixelRGBA(i % size, i / size, 0xFF000000 | b << 16 | g << 8 | r);
        }
        return out;
    }

    private static void saturate(float[] rgb) {
        for (int i = 0; i < rgb.length; i += 3) {
            float r = (rgb[i] - 128.0f) * 0.4f + 128.0f;
            float g = (rgb[i + 1] - 128.0f) * 0.4f + 128.0f;
            float b = (rgb[i + 2] - 128.0f) * 0.4f + 128.0f;

            float gray = r * 0.3f + g * 0.59f + b * 0.11f;

            r = gray * -2.0f + r * 3.0f;
            g = gray * -2.0f + g * 3.0f;
            b = gray * -2.0f + b * 3.0f;

            rgb[i] = ((r - 128.0f) * 1.7f + 128.0f) * 0.75f;
            rgb[i + 1] = ((g - 128.0f) * 1.7f + 128.0f) * 0.75f;
            rgb[i + 2] = ((b - 128.0f) * 1.7f + 128.0f) * 0.75f;
        }
    }

    private static void boxBlur(float[] rgb, int width, int height, int radius, int passes) {
        float[] tmp = new float[rgb.length];
        for (int pass = 0; pass < passes; pass++) {
            boxBlurHorizontal(rgb, tmp, width, height, radius);
            boxBlurVertical(tmp, rgb, width, height, radius);
        }
    }

    private static void boxBlurHorizontal(float[] src, float[] dst, int width, int height, int radius) {
        float divisor = 2.0f * radius + 1.0f;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float r = 0.0f;
                float g = 0.0f;
                float b = 0.0f;
                for (int k = -radius; k <= radius; k++) {
                    int sx = Math.max(0, Math.min(width - 1, x + k));
                    int index = (y * width + sx) * 3;
                    r += src[index];
                    g += src[index + 1];
                    b += src[index + 2];
                }
                int out = (y * width + x) * 3;
                dst[out] = r / divisor;
                dst[out + 1] = g / divisor;
                dst[out + 2] = b / divisor;
            }
        }
    }

    private static void boxBlurVertical(float[] src, float[] dst, int width, int height, int radius) {
        float divisor = 2.0f * radius + 1.0f;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                float r = 0.0f;
                float g = 0.0f;
                float b = 0.0f;
                for (int k = -radius; k <= radius; k++) {
                    int sy = Math.max(0, Math.min(height - 1, y + k));
                    int index = (sy * width + x) * 3;
                    r += src[index];
                    g += src[index + 1];
                    b += src[index + 2];
                }
                int out = (y * width + x) * 3;
                dst[out] = r / divisor;
                dst[out + 1] = g / divisor;
                dst[out + 2] = b / divisor;
            }
        }
    }

    private static int toByte(float value) {
        if (value <= 0.0f) {
            return 0;
        }
        return value >= 255.0f ? 255 : (int) (value + 0.5f);
    }

    public static Texture uploadPalette(NativeImage image) {
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        DynamicTexture texture = new DynamicTexture(image);
        int id = texture.getId();
        GlStateManager._bindTexture(id);
        GL11.glTexParameteri(3553, 10241, 9729);
        GL11.glTexParameteri(3553, 10240, 9729);
        GL11.glTexParameteri(3553, 10242, 33071);
        GL11.glTexParameteri(3553, 10243, 33071);
        GlStateManager._bindTexture(0);
        return new Texture(id, width, height);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    public record PanelRect(float x, float y, float width, float height, float radius) {
    }

    public static void render(int coverTexture, int prevTexture, float fade,
                              float width, float height, float beat) {
        render(coverTexture, prevTexture, fade, width, height, beat, null);
    }

    public static void render(int coverTexture, int prevTexture, float fade,
                              float width, float height, float beat, PanelRect panel) {
        if (failed || coverTexture <= 0) {
            return;
        }
        if (!ensureInitialized()) {
            return;
        }

        float time = (System.nanoTime() % 1_000_000_000_000L) / 1_000_000_000.0f;

        boolean blendWasEnabled = GL11.glIsEnabled(3042);
        boolean depthWasEnabled = GL11.glIsEnabled(2929);
        GlStateManager._disableDepthTest();
        if (panel != null) {
            GlStateManager._enableBlend();
            GlStateManager._blendFuncSeparate(770, 771, 1, 771);
        } else {
            GlStateManager._disableBlend();
        }

        int previousProgram = GL11.glGetInteger(35725);
        int previousVao = GL11.glGetInteger(34229);
        int previousActiveTexture = GL11.glGetInteger(34016);
        GL13.glActiveTexture(33984);
        int previousTexture0 = GL11.glGetInteger(32873);
        GL13.glActiveTexture(33985);
        int previousTexture1 = GL11.glGetInteger(32873);

        GL20.glUseProgram(programId);
        GL20.glUniform2f(resolutionUniform, Math.max(width, 1.0f), Math.max(height, 1.0f));
        GL20.glUniform1f(timeUniform, time);
        GL20.glUniform1f(fadeUniform, Mth.clamp(fade, 0.0f, 1.0f));
        GL20.glUniform1f(beatUniform, Mth.clamp(beat, 0.0f, 1.0f));
        if (panel != null) {
            GL20.glUniform4f(panelRectUniform, panel.x, panel.y,
                    panel.x + panel.width, panel.y + panel.height);
            GL20.glUniform1f(panelRadiusUniform, Math.max(0.0f, panel.radius));
            GL20.glUniform1f(panelEnabledUniform, 1.0f);
        } else {
            GL20.glUniform1f(panelEnabledUniform, 0.0f);
        }

        GL13.glActiveTexture(33984);
        GL11.glBindTexture(3553, coverTexture);
        GL20.glUniform1i(coverUniform, 0);
        GL13.glActiveTexture(33985);
        GL11.glBindTexture(3553, prevTexture > 0 ? prevTexture : coverTexture);
        GL20.glUniform1i(prevCoverUniform, 1);

        GL30.glBindVertexArray(vaoId);
        GL11.glDrawArrays(4, 0, 6);

        GL13.glActiveTexture(33984);
        GL11.glBindTexture(3553, previousTexture0);
        GL13.glActiveTexture(33985);
        GL11.glBindTexture(3553, previousTexture1);
        GL13.glActiveTexture(previousActiveTexture);
        GL30.glBindVertexArray(previousVao);
        GL20.glUseProgram(previousProgram);

        if (blendWasEnabled) {
            GlStateManager._enableBlend();
        }
        if (depthWasEnabled) {
            GlStateManager._enableDepthTest();
        }
    }

    private static boolean ensureInitialized() {
        if (programId != 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        int vertexShader = compile(35633, VERTEX_SOURCE);
        int fragmentShader = compile(35632, FRAGMENT_SOURCE);
        if (vertexShader == 0 || fragmentShader == 0) {
            if (vertexShader != 0) GL20.glDeleteShader(vertexShader);
            if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader);
            failed = true;
            return false;
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "UV0");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        if (GL20.glGetProgrami(program, 35714) == 0) {
            ClientBase.logger.warn("cover backdrop shader link failed: " + GL20.glGetProgramInfoLog(program));
            GL20.glDeleteProgram(program);
            failed = true;
            return false;
        }
        programId = program;
        coverUniform = GL20.glGetUniformLocation(programId, "Cover");
        prevCoverUniform = GL20.glGetUniformLocation(programId, "PrevCover");
        resolutionUniform = GL20.glGetUniformLocation(programId, "Resolution");
        timeUniform = GL20.glGetUniformLocation(programId, "Time");
        fadeUniform = GL20.glGetUniformLocation(programId, "Fade");
        beatUniform = GL20.glGetUniformLocation(programId, "Beat");
        panelRectUniform = GL20.glGetUniformLocation(programId, "PanelRect");
        panelRadiusUniform = GL20.glGetUniformLocation(programId, "PanelRadius");
        panelEnabledUniform = GL20.glGetUniformLocation(programId, "PanelEnabled");

        vaoId = GL30.glGenVertexArrays();
        vboId = GL15.glGenBuffers();
        int previousVao = GL11.glGetInteger(34229);
        int previousVbo = GL11.glGetInteger(34964);
        GL30.glBindVertexArray(vaoId);
        GL15.glBindBuffer(34962, vboId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(30);
            putVertex(buffer, -1.0f, -1.0f, 0.0f, 0.0f, 1.0f);
            putVertex(buffer, 1.0f, -1.0f, 0.0f, 1.0f, 1.0f);
            putVertex(buffer, 1.0f, 1.0f, 0.0f, 1.0f, 0.0f);
            putVertex(buffer, -1.0f, -1.0f, 0.0f, 0.0f, 1.0f);
            putVertex(buffer, 1.0f, 1.0f, 0.0f, 1.0f, 0.0f);
            putVertex(buffer, -1.0f, 1.0f, 0.0f, 0.0f, 0.0f);
            buffer.flip();
            GL15.glBufferData(34962, buffer, 35044);
        }
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, 5126, false, 20, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, 5126, false, 20, 12L);
        GL30.glBindVertexArray(previousVao);
        GL15.glBindBuffer(34962, previousVbo);
        return true;
    }

    private static void putVertex(FloatBuffer buffer, float x, float y, float z, float u, float v) {
        buffer.put(x).put(y).put(z).put(u).put(v);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, 35713) == 0) {
            ClientBase.logger.warn("cover backdrop shader compile failed (type " + type + "): "
                    + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public static void delete() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        if (vboId != 0) {
            GL15.glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (vaoId != 0) {
            GL30.glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        failed = false;
    }
}
