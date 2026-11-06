package client.nilore.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public final class RoundedRectShader {
    private static final String VERTEX_SOURCE = "#version 150\nin vec2 Position;\nin vec2 LocalPos;\nin vec2 UV;\nuniform mat4 ModelViewMat;\nuniform mat4 ProjMat;\nout vec2 localPos;\nout vec2 uvCoord;\nvoid main() {\n    gl_Position = ProjMat * ModelViewMat * vec4(Position, 0.0, 1.0);\n    localPos = LocalPos;\n    uvCoord = UV;\n}\n";
    private static final String FRAGMENT_SOURCE = "#version 150\nuniform vec2 HalfSize;\nuniform vec4 Radii;\nuniform vec4 Color1;\nuniform vec4 Color2;\nuniform int UseGradient;\nuniform int UseTexture;\nuniform sampler2D Sampler0;\nuniform float StrokeWidth;\nin vec2 localPos;\nin vec2 uvCoord;\nout vec4 fragColor;\nvoid main() {\n    vec2 p = localPos;\n    float r;\n    if (p.x < 0.0) {\n        r = (p.y < 0.0) ? Radii.x : Radii.w;\n    } else {\n        r = (p.y < 0.0) ? Radii.y : Radii.z;\n    }\n    vec2 q = abs(p) - HalfSize + r;\n    float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n    float aa = max(fwidth(d), 0.0001) * 0.5;\n    float alpha;\n    if (StrokeWidth > 0.0) {\n        float halfStroke = StrokeWidth * 0.5;\n        alpha = 1.0 - smoothstep(halfStroke - aa, halfStroke + aa, abs(d));\n    } else {\n        alpha = 1.0 - smoothstep(-aa, aa, d);\n    }\n    vec4 col;\n    if (UseTexture == 1) {\n        col = texture(Sampler0, uvCoord) * Color1;\n    } else if (UseGradient == 1) {\n        float t = clamp((p.y + HalfSize.y) / max(2.0 * HalfSize.y, 0.0001), 0.0, 1.0);\n        col = mix(Color1, Color2, t);\n    } else {\n        col = Color1;\n    }\n    fragColor = vec4(col.rgb, col.a * alpha);\n}\n";
    private static final String GLOW_FRAGMENT_SOURCE = "#version 150\nuniform vec2 HalfSize;\nuniform vec4 Radii;\nuniform vec4 Color;\nuniform float GlowRadius;\nin vec2 localPos;\nout vec4 fragColor;\nvoid main() {\n    vec2 p = localPos;\n    float r;\n    if (p.x < 0.0) {\n        r = (p.y < 0.0) ? Radii.x : Radii.w;\n    } else {\n        r = (p.y < 0.0) ? Radii.y : Radii.z;\n    }\n    vec2 q = abs(p) - HalfSize + r;\n    float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n    float glow = (1.0 - smoothstep(0.0, max(GlowRadius, 0.0001), d)) * smoothstep(-max(fwidth(d), 0.0001), 0.0, d);\n    glow = glow * glow;\n    fragColor = vec4(Color.rgb, Color.a * glow);\n}\n";

    private int programId = 0;
    private int glowProgramId = 0;
    private int uModelViewMat = -1;
    private int uProjMat = -1;
    private int uHalfSize = -1;
    private int uRadii = -1;
    private int uColor1 = -1;
    private int uColor2 = -1;
    private int uUseGradient = -1;
    private int uUseTexture = -1;
    private int uSampler0 = -1;
    private int uStrokeWidth = -1;
    private int gModelViewMat = -1;
    private int gProjMat = -1;
    private int gHalfSize = -1;
    private int gRadii = -1;
    private int gColor = -1;
    private int gGlowRadius = -1;
    private int vboId = 0;
    private int vaoId = 0;
    /** Unit-quad corners, reused by every draw so a rounded rect stops allocating. */
    private static final float[] QUAD_OFFSETS = {-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f};
    private final Vector4f scratchPos = new Vector4f();
    private final float[] vertexData = new float[36];

    public void init() {
        if (this.programId != 0) {
            return;
        }
        int vertexShader = RoundedRectShader.compileShader(35633, VERTEX_SOURCE);
        int fragmentShader = RoundedRectShader.compileShader(35632, FRAGMENT_SOURCE);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "LocalPos");
        GL20.glBindAttribLocation(program, 2, "UV");
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, 35714) == 0) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);
            throw new IllegalStateException("RoundedRectShader link failed: " + log);
        }
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        this.programId = program;
        this.uModelViewMat = GL20.glGetUniformLocation(this.programId, "ModelViewMat");
        this.uProjMat = GL20.glGetUniformLocation(this.programId, "ProjMat");
        this.uHalfSize = GL20.glGetUniformLocation(this.programId, "HalfSize");
        this.uRadii = GL20.glGetUniformLocation(this.programId, "Radii");
        this.uColor1 = GL20.glGetUniformLocation(this.programId, "Color1");
        this.uColor2 = GL20.glGetUniformLocation(this.programId, "Color2");
        this.uUseGradient = GL20.glGetUniformLocation(this.programId, "UseGradient");
        this.uUseTexture = GL20.glGetUniformLocation(this.programId, "UseTexture");
        this.uSampler0 = GL20.glGetUniformLocation(this.programId, "Sampler0");
        this.uStrokeWidth = GL20.glGetUniformLocation(this.programId, "StrokeWidth");
        this.initGlowProgram();
        this.vaoId = GL30.glGenVertexArrays();
        this.vboId = GL15.glGenBuffers();
        int prevVao = GL11.glGetInteger(34229);
        int prevVbo = GL11.glGetInteger(34964);
        GL30.glBindVertexArray(this.vaoId);
        GL15.glBindBuffer(34962, this.vboId);
        GL15.glBufferData(34962, 144L, 35048);
        int stride = 24;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, 5126, false, stride, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, 5126, false, stride, 8L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 2, 5126, false, stride, 16L);
        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(34962, prevVbo);
    }

    private void initGlowProgram() {
        if (this.glowProgramId != 0) {
            return;
        }
        int vertexShader = RoundedRectShader.compileShader(35633, VERTEX_SOURCE);
        int fragmentShader = RoundedRectShader.compileShader(35632, GLOW_FRAGMENT_SOURCE);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "LocalPos");
        GL20.glBindAttribLocation(program, 2, "UV");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        if (GL20.glGetProgrami(program, 35714) == 0) {
            GL20.glDeleteProgram(program);
            return;
        }
        this.glowProgramId = program;
        this.gModelViewMat = GL20.glGetUniformLocation(program, "ModelViewMat");
        this.gProjMat = GL20.glGetUniformLocation(program, "ProjMat");
        this.gHalfSize = GL20.glGetUniformLocation(program, "HalfSize");
        this.gRadii = GL20.glGetUniformLocation(program, "Radii");
        this.gColor = GL20.glGetUniformLocation(program, "Color");
        this.gGlowRadius = GL20.glGetUniformLocation(program, "GlowRadius");
    }

    public void draw(Matrix4f pose, float x1, float y1, float x2, float y2, float tlRadius, float trRadius, float brRadius, float blRadius, int color1, int color2, boolean useGradient, float strokeWidth) {
        // Immediate draw: anything already queued must land first to keep the frame order.
        flushBatch();
        this.drawInternal(pose, x1, y1, x2, y2, tlRadius, trRadius, brRadius, blRadius, color1, color2, useGradient, strokeWidth, -1, 0.0f, 0.0f, 1.0f, 1.0f);
    }

    public void drawTextured(Matrix4f pose, float x1, float y1, float x2, float y2, float tlRadius, float trRadius, float brRadius, float blRadius, int color, int textureId, float u1, float v1, float u2, float v2) {
        // Immediate draw: anything already queued must land first to keep the frame order.
        flushBatch();
        this.drawInternal(pose, x1, y1, x2, y2, tlRadius, trRadius, brRadius, blRadius, color, color, false, 0.0f, textureId, u1, v1, u2, v2);
    }

    /**
     * Draws an outer glow ring hugging the rounded-rect border. The interior of the
     * rectangle is never filled, so the glow can sit behind a translucent background
     * without brightening its centre.
     */
    public void drawGlow(Matrix4f pose, float x1, float y1, float x2, float y2, float tlRadius, float trRadius, float brRadius, float blRadius, float glowRadius, int color) {
        flushBatch();
        this.init();
        if (this.glowProgramId == 0 || glowRadius <= 0.0f) {
            return;
        }
        float centerX = (x1 + x2) * 0.5f;
        float centerY = (y1 + y2) * 0.5f;
        float halfWidth = (x2 - x1) * 0.5f;
        float halfHeight = (y2 - y1) * 0.5f;
        if (halfWidth <= 0.0f || halfHeight <= 0.0f) {
            return;
        }
        float maxRadius = Math.min(halfWidth, halfHeight);
        tlRadius = Math.min(Math.max(tlRadius, 0.0f), maxRadius);
        trRadius = Math.min(Math.max(trRadius, 0.0f), maxRadius);
        brRadius = Math.min(Math.max(brRadius, 0.0f), maxRadius);
        blRadius = Math.min(Math.max(blRadius, 0.0f), maxRadius);
        float expandedHalfW = halfWidth + glowRadius + 1.0f;
        float expandedHalfH = halfHeight + glowRadius + 1.0f;
        org.joml.Vector4f vertexPos = new org.joml.Vector4f();
        float[] vertexData = new float[36];
        float[][] cornerOffsets = new float[][]{{-expandedHalfW, -expandedHalfH}, {expandedHalfW, -expandedHalfH}, {expandedHalfW, expandedHalfH}, {-expandedHalfW, -expandedHalfH}, {expandedHalfW, expandedHalfH}, {-expandedHalfW, expandedHalfH}};
        for (int i = 0; i < 6; ++i) {
            float localX = cornerOffsets[i][0];
            float localY = cornerOffsets[i][1];
            vertexPos.set(centerX + localX, centerY + localY, 0.0f, 1.0f).mul(pose);
            int base = i * 6;
            vertexData[base] = vertexPos.x;
            vertexData[base + 1] = vertexPos.y;
            vertexData[base + 2] = localX;
            vertexData[base + 3] = localY;
            vertexData[base + 4] = 0.0f;
            vertexData[base + 5] = 0.0f;
        }
        int prevProgram = GL11.glGetInteger(35725);
        int prevVao = GL11.glGetInteger(34229);
        int prevVbo = GL11.glGetInteger(34964);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        GL20.glUseProgram(this.glowProgramId);
        GL30.glBindVertexArray(this.vaoId);
        GL15.glBindBuffer(34962, this.vboId);
        try (MemoryStack memoryStack = MemoryStack.stackPush()) {
            FloatBuffer vertexBuffer = memoryStack.mallocFloat(vertexData.length);
            vertexBuffer.put(vertexData).flip();
            GL15.glBufferSubData(34962, 0L, vertexBuffer);
            FloatBuffer modelViewBuffer = memoryStack.mallocFloat(16);
            RenderSystem.getModelViewMatrix().get(modelViewBuffer);
            GL20.glUniformMatrix4fv(this.gModelViewMat, false, modelViewBuffer);
            FloatBuffer projBuffer = memoryStack.mallocFloat(16);
            RenderSystem.getProjectionMatrix().get(projBuffer);
            GL20.glUniformMatrix4fv(this.gProjMat, false, projBuffer);
        }
        GL20.glUniform2f(this.gHalfSize, halfWidth, halfHeight);
        GL20.glUniform4f(this.gRadii, tlRadius, trRadius, brRadius, blRadius);
        GL20.glUniform4f(this.gColor, (float)(color >> 16 & 0xFF) / 255.0f, (float)(color >> 8 & 0xFF) / 255.0f, (float)(color & 0xFF) / 255.0f, (float)(color >>> 24 & 0xFF) / 255.0f);
        GL20.glUniform1f(this.gGlowRadius, glowRadius);
        GL11.glDrawArrays(4, 0, 6);
        GL15.glBindBuffer(34962, prevVbo);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProgram);
    }

    private void drawInternal(Matrix4f pose, float x1, float y1, float x2, float y2, float tlRadius, float trRadius, float brRadius, float blRadius, int color1, int color2, boolean useGradient, float strokeWidth, int textureId, float u1, float v1, float u2, float v2) {
        int i;
        this.init();
        float centerX = (x1 + x2) * 0.5f;
        float centerY = (y1 + y2) * 0.5f;
        float halfWidth = (x2 - x1) * 0.5f;
        float halfHeight = (y2 - y1) * 0.5f;
        if (halfWidth <= 0.0f || halfHeight <= 0.0f) {
            return;
        }
        float maxRadius = Math.min(halfWidth, halfHeight);
        tlRadius = Math.min(Math.max(tlRadius, 0.0f), maxRadius);
        trRadius = Math.min(Math.max(trRadius, 0.0f), maxRadius);
        brRadius = Math.min(Math.max(brRadius, 0.0f), maxRadius);
        blRadius = Math.min(Math.max(blRadius, 0.0f), maxRadius);
        float expandedHalfW = halfWidth + 1.0f;
        float expandedHalfH = halfHeight + 1.0f;
        Vector4f vertexPos = this.scratchPos;
        float[] vertexData = this.vertexData;
        for (i = 0; i < 6; ++i) {
            float localX = QUAD_OFFSETS[i * 2] * expandedHalfW;
            float localY = QUAD_OFFSETS[i * 2 + 1] * expandedHalfH;
            vertexPos.set(centerX + localX, centerY + localY, 0.0f, 1.0f).mul(pose);
            float u = u1 + (localX + halfWidth) / (2.0f * halfWidth) * (u2 - u1);
            float v = v1 + (localY + halfHeight) / (2.0f * halfHeight) * (v2 - v1);
            int base = i * 6;
            vertexData[base] = vertexPos.x;
            vertexData[base + 1] = vertexPos.y;
            vertexData[base + 2] = localX;
            vertexData[base + 3] = localY;
            vertexData[base + 4] = u;
            vertexData[base + 5] = v;
        }
        i = GL11.glGetInteger(35725);
        int prevVao = GL11.glGetInteger(34229);
        int prevVbo = GL11.glGetInteger(34964);
        int prevActiveTex = GL11.glGetInteger(34016);
        GL13.glActiveTexture(33984);
        int prevTex2d = GL11.glGetInteger(32873);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        GL20.glUseProgram(this.programId);
        GL30.glBindVertexArray(this.vaoId);
        GL15.glBindBuffer(34962, this.vboId);
        try (MemoryStack memoryStack = MemoryStack.stackPush()){
            FloatBuffer vertexBuffer = memoryStack.mallocFloat(vertexData.length);
            vertexBuffer.put(vertexData).flip();
            GL15.glBufferSubData(34962, 0L, vertexBuffer);
            FloatBuffer modelViewBuffer = memoryStack.mallocFloat(16);
            RenderSystem.getModelViewMatrix().get(modelViewBuffer);
            GL20.glUniformMatrix4fv(this.uModelViewMat, false, modelViewBuffer);
            FloatBuffer projBuffer = memoryStack.mallocFloat(16);
            RenderSystem.getProjectionMatrix().get(projBuffer);
            GL20.glUniformMatrix4fv(this.uProjMat, false, projBuffer);
        }
        GL20.glUniform2f(this.uHalfSize, halfWidth, halfHeight);
        GL20.glUniform4f(this.uRadii, tlRadius, trRadius, brRadius, blRadius);
        GL20.glUniform4f(this.uColor1, (float)(color1 >> 16 & 0xFF) / 255.0f, (float)(color1 >> 8 & 0xFF) / 255.0f, (float)(color1 & 0xFF) / 255.0f, (float)(color1 >>> 24 & 0xFF) / 255.0f);
        GL20.glUniform4f(this.uColor2, (float)(color2 >> 16 & 0xFF) / 255.0f, (float)(color2 >> 8 & 0xFF) / 255.0f, (float)(color2 & 0xFF) / 255.0f, (float)(color2 >>> 24 & 0xFF) / 255.0f);
        GL20.glUniform1i(this.uUseGradient, useGradient ? 1 : 0);
        GL20.glUniform1f(this.uStrokeWidth, Math.max(0.0f, strokeWidth));
        if (textureId > 0) {
            GL20.glUniform1i(this.uUseTexture, 1);
            GL20.glUniform1i(this.uSampler0, 0);
            GL11.glBindTexture(3553, textureId);
            GL11.glTexParameteri(3553, 10241, 9728);
            GL11.glTexParameteri(3553, 10240, 9728);
        } else {
            GL20.glUniform1i(this.uUseTexture, 0);
        }
        GL11.glDrawArrays(4, 0, 6);
        GL11.glBindTexture(3553, prevTex2d);
        GL13.glActiveTexture(prevActiveTex);
        GL15.glBindBuffer(34962, prevVbo);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(i);
    }

    // ---------------------------------------------------------------------------------
    // Batched path
    // ---------------------------------------------------------------------------------
    //
    // The per-rect path below saves six GL state values, uploads 36 floats and issues one
    // draw call per rectangle. A GUI screen easily reaches a few hundred rounded rects, so
    // that is a few hundred draws and a few thousand state queries per frame.
    //
    // The batch keeps the exact same vertex maths (pose baked on the CPU) but moves the
    // per-rect uniforms into per-vertex attributes, so a run of consecutive rectangles
    // becomes one buffer upload and one draw call. Textured rects still take the old path
    // because a single draw cannot sample several textures.

    private static final String BATCH_VERTEX_SOURCE = "#version 150\n"
            + "in vec2 Position;\n"
            + "in vec2 LocalPos;\n"
            + "in vec2 UV;\n"
            + "in vec2 HalfSize;\n"
            + "in vec4 Radii;\n"
            + "in vec4 Color1;\n"
            + "in vec4 Color2;\n"
            + "in vec3 Params;\n"
            + "uniform mat4 ModelViewMat;\n"
            + "uniform mat4 ProjMat;\n"
            + "out vec2 vLocal;\n"
            + "out vec2 vHalf;\n"
            + "out vec4 vRadii;\n"
            + "out vec4 vColor1;\n"
            + "out vec4 vColor2;\n"
            + "out vec3 vParams;\n"
            + "out vec2 vUV;\n"
            + "void main() {\n"
            + "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 0.0, 1.0);\n"
            + "    vLocal = LocalPos;\n"
            + "    vHalf = HalfSize;\n"
            + "    vRadii = Radii;\n"
            + "    vColor1 = Color1;\n"
            + "    vColor2 = Color2;\n"
            + "    vParams = Params;\n"
            + "    vUV = UV;\n"
            + "}\n";

    private static final String BATCH_FRAGMENT_SOURCE = "#version 150\n"
            + "uniform sampler2D Sampler0;\n"
            + "in vec2 vLocal;\n"
            + "in vec2 vHalf;\n"
            + "in vec4 vRadii;\n"
            + "in vec4 vColor1;\n"
            + "in vec4 vColor2;\n"
            + "in vec3 vParams;\n"
            + "in vec2 vUV;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "    vec2 p = vLocal;\n"
            + "    float r;\n"
            + "    if (p.x < 0.0) {\n"
            + "        r = (p.y < 0.0) ? vRadii.x : vRadii.w;\n"
            + "    } else {\n"
            + "        r = (p.y < 0.0) ? vRadii.y : vRadii.z;\n"
            + "    }\n"
            + "    vec2 q = abs(p) - vHalf + r;\n"
            + "    float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n"
            + "    float aa = max(fwidth(d), 0.0001) * 0.5;\n"
            + "    float alpha;\n"
            + "    if (vParams.y > 0.0) {\n"
            + "        float halfStroke = vParams.y * 0.5;\n"
            + "        alpha = 1.0 - smoothstep(halfStroke - aa, halfStroke + aa, abs(d));\n"
            + "    } else {\n"
            + "        alpha = 1.0 - smoothstep(-aa, aa, d);\n"
            + "    }\n"
            + "    vec4 col;\n"
            + "    if (vParams.x > 0.5) {\n"
            + "        float t = clamp((p.y + vHalf.y) / max(2.0 * vHalf.y, 0.0001), 0.0, 1.0);\n"
            + "        col = mix(vColor1, vColor2, t);\n"
            + "    } else {\n"
            + "        col = vColor1;\n"
            + "    }\n"
            + "    fragColor = vec4(col.rgb, col.a * alpha);\n"
            + "}\n";

    /** Position(2) LocalPos(2) UV(2) HalfSize(2) Radii(4) Color1(4) Color2(4) Params(3). */
    private static final int BATCH_FLOATS_PER_VERTEX = 23;
    private static final int BATCH_VERTICES_PER_RECT = 6;
    private static final int BATCH_FLOATS_PER_RECT = BATCH_FLOATS_PER_VERTEX * BATCH_VERTICES_PER_RECT;
    private static final int BATCH_INITIAL_RECTS = 256;

    private static final Vector4f BATCH_SCRATCH = new Vector4f();

    private static int batchProgramId;
    private static int batchVaoId;
    private static int batchVboId;
    private static int batchModelViewMat = -1;
    private static int batchProjMat = -1;
    private static FloatBuffer batchBuffer;
    private static int batchRects;

    /** Returns false when the batched program is unavailable, so callers can fall back. */
    public static boolean supportsBatch() {
        initBatch();
        return batchProgramId != 0;
    }

    private static void initBatch() {
        if (batchProgramId != 0 || batchBuffer != null) {
            return;
        }
        try {
            int vertexShader = RoundedRectShader.compileShader(35633, BATCH_VERTEX_SOURCE);
            int fragmentShader = RoundedRectShader.compileShader(35632, BATCH_FRAGMENT_SOURCE);
            int program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertexShader);
            GL20.glAttachShader(program, fragmentShader);
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glBindAttribLocation(program, 1, "LocalPos");
            GL20.glBindAttribLocation(program, 2, "UV");
            GL20.glBindAttribLocation(program, 3, "HalfSize");
            GL20.glBindAttribLocation(program, 4, "Radii");
            GL20.glBindAttribLocation(program, 5, "Color1");
            GL20.glBindAttribLocation(program, 6, "Color2");
            GL20.glBindAttribLocation(program, 7, "Params");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, 35714) == 0) {
                String log = GL20.glGetProgramInfoLog(program);
                GL20.glDeleteProgram(program);
                GL20.glDeleteShader(vertexShader);
                GL20.glDeleteShader(fragmentShader);
                System.err.println("[Nilore] RoundedRect batch link failed: " + log);
                return;
            }
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);
            batchProgramId = program;
            batchModelViewMat = GL20.glGetUniformLocation(program, "ModelViewMat");
            batchProjMat = GL20.glGetUniformLocation(program, "ProjMat");

            batchVaoId = GL30.glGenVertexArrays();
            batchVboId = GL15.glGenBuffers();
            int prevVao = GL11.glGetInteger(34229);
            int prevVbo = GL11.glGetInteger(34964);
            GL30.glBindVertexArray(batchVaoId);
            GL15.glBindBuffer(34962, batchVboId);
            int stride = BATCH_FLOATS_PER_VERTEX * 4;
            GL15.glBufferData(34962, (long) BATCH_INITIAL_RECTS * BATCH_FLOATS_PER_RECT * 4L, 35048);
            int[] sizes = {2, 2, 2, 2, 4, 4, 4, 3};
            int offset = 0;
            for (int i = 0; i < sizes.length; i++) {
                GL20.glEnableVertexAttribArray(i);
                GL20.glVertexAttribPointer(i, sizes[i], 5126, false, stride, (long) offset * 4L);
                offset += sizes[i];
            }
            GL30.glBindVertexArray(prevVao);
            GL15.glBindBuffer(34962, prevVbo);
            batchBuffer = BufferUtils.createFloatBuffer(BATCH_INITIAL_RECTS * BATCH_FLOATS_PER_RECT);
        } catch (Throwable throwable) {
            batchProgramId = 0;
            throwable.printStackTrace();
        }
    }

    /** Appends one solid or gradient rounded rectangle to the batch. */
    public static void addBatchRect(Matrix4f pose, float x1, float y1, float x2, float y2,
                                    float tlRadius, float trRadius, float brRadius, float blRadius,
                                    int color1, int color2, boolean useGradient, float strokeWidth) {
        if (!supportsBatch()) {
            return;
        }
        float centerX = (x1 + x2) * 0.5f;
        float centerY = (y1 + y2) * 0.5f;
        float halfWidth = (x2 - x1) * 0.5f;
        float halfHeight = (y2 - y1) * 0.5f;
        if (halfWidth <= 0.0f || halfHeight <= 0.0f) {
            return;
        }
        if (batchRects >= batchBuffer.capacity() / BATCH_FLOATS_PER_RECT) {
            // The staging buffer is full: submit what we have before overflowing it.
            flushBatch();
        }
        float maxRadius = Math.min(halfWidth, halfHeight);
        tlRadius = Math.min(Math.max(tlRadius, 0.0f), maxRadius);
        trRadius = Math.min(Math.max(trRadius, 0.0f), maxRadius);
        brRadius = Math.min(Math.max(brRadius, 0.0f), maxRadius);
        blRadius = Math.min(Math.max(blRadius, 0.0f), maxRadius);
        float expandedHalfW = halfWidth + 1.0f;
        float expandedHalfH = halfHeight + 1.0f;

        float r1 = (float) (color1 >> 16 & 0xFF) / 255.0f;
        float g1 = (float) (color1 >> 8 & 0xFF) / 255.0f;
        float b1 = (float) (color1 & 0xFF) / 255.0f;
        float a1 = (float) (color1 >>> 24 & 0xFF) / 255.0f;
        float r2 = (float) (color2 >> 16 & 0xFF) / 255.0f;
        float g2 = (float) (color2 >> 8 & 0xFF) / 255.0f;
        float b2 = (float) (color2 & 0xFF) / 255.0f;
        float a2 = (float) (color2 >>> 24 & 0xFF) / 255.0f;
        float gradient = useGradient ? 1.0f : 0.0f;
        float stroke = Math.max(0.0f, strokeWidth);

        FloatBuffer buffer = batchBuffer;
        Vector4f vertexPos = BATCH_SCRATCH;
        for (int i = 0; i < BATCH_VERTICES_PER_RECT; i++) {
            float localX = QUAD_OFFSETS[i * 2] * expandedHalfW;
            float localY = QUAD_OFFSETS[i * 2 + 1] * expandedHalfH;
            vertexPos.set(centerX + localX, centerY + localY, 0.0f, 1.0f).mul(pose);
            buffer.put(vertexPos.x).put(vertexPos.y);
            buffer.put(localX).put(localY);
            buffer.put(0.0f).put(0.0f);
            buffer.put(halfWidth).put(halfHeight);
            buffer.put(tlRadius).put(trRadius).put(brRadius).put(blRadius);
            buffer.put(r1).put(g1).put(b1).put(a1);
            buffer.put(r2).put(g2).put(b2).put(a2);
            buffer.put(gradient).put(stroke).put(0.0f);
        }
        batchRects++;
    }

    /** Uploads and draws everything accumulated by {@link #addBatchRect}. */
    public static void flushBatch() {
        if (batchRects == 0 || batchProgramId == 0) {
            batchRects = 0;
            if (batchBuffer != null && batchBuffer.position() != 0) {
                batchBuffer.clear();
            }
            return;
        }
        int rects = batchRects;
        batchRects = 0;
        FloatBuffer buffer = batchBuffer;
        buffer.flip();

        int prevProgram = GL11.glGetInteger(35725);
        int prevVao = GL11.glGetInteger(34229);
        int prevVbo = GL11.glGetInteger(34964);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        GL20.glUseProgram(batchProgramId);
        GL30.glBindVertexArray(batchVaoId);
        GL15.glBindBuffer(34962, batchVboId);
        GL15.glBufferData(34962, buffer, 35048);
        try (MemoryStack memoryStack = MemoryStack.stackPush()) {
            FloatBuffer modelViewBuffer = memoryStack.mallocFloat(16);
            RenderSystem.getModelViewMatrix().get(modelViewBuffer);
            GL20.glUniformMatrix4fv(batchModelViewMat, false, modelViewBuffer);
            FloatBuffer projBuffer = memoryStack.mallocFloat(16);
            RenderSystem.getProjectionMatrix().get(projBuffer);
            GL20.glUniformMatrix4fv(batchProjMat, false, projBuffer);
        }
        GL11.glDrawArrays(4, 0, rects * BATCH_VERTICES_PER_RECT);
        GL15.glBindBuffer(34962, prevVbo);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProgram);
        buffer.clear();
    }

    private static void disposeBatch() {
        if (batchVboId != 0) {
            GL15.glDeleteBuffers(batchVboId);
            batchVboId = 0;
        }
        if (batchVaoId != 0) {
            GL30.glDeleteVertexArrays(batchVaoId);
            batchVaoId = 0;
        }
        if (batchProgramId != 0) {
            GL20.glDeleteProgram(batchProgramId);
            batchProgramId = 0;
        }
        batchRects = 0;
    }

    public void dispose() {
        RoundedRectShader.disposeBatch();
        if (this.programId != 0) {
            GL20.glDeleteProgram(this.programId);
            this.programId = 0;
        }
        if (this.glowProgramId != 0) {
            GL20.glDeleteProgram(this.glowProgramId);
            this.glowProgramId = 0;
        }
        if (this.vboId != 0) {
            GL15.glDeleteBuffers(this.vboId);
            this.vboId = 0;
        }
        if (this.vaoId != 0) {
            GL30.glDeleteVertexArrays(this.vaoId);
            this.vaoId = 0;
        }
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, 35713) == 0) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("RoundedRectShader shader compile failed: " + log);
        }
        return shader;
    }
}
