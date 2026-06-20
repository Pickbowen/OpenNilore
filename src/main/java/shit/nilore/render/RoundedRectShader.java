package shit.nilore.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public final class RoundedRectShader {
    private int programId = 0;
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
    private int vboId = 0;
    private int vaoId = 0;

    public void init() {
        if (this.programId != 0) {
            return;
        }
        int vertexShader = RoundedRectShader.compileShader(35633, "#version 150\nin vec2 Position;\nin vec2 LocalPos;\nin vec2 UV;\nuniform mat4 ModelViewMat;\nuniform mat4 ProjMat;\nout vec2 localPos;\nout vec2 uvCoord;\nvoid main() {\n    gl_Position = ProjMat * ModelViewMat * vec4(Position, 0.0, 1.0);\n    localPos = LocalPos;\n    uvCoord = UV;\n}\n");
        int fragmentShader = RoundedRectShader.compileShader(35632, "#version 150\nuniform vec2 HalfSize;\nuniform vec4 Radii;\nuniform vec4 Color1;\nuniform vec4 Color2;\nuniform int UseGradient;\nuniform int UseTexture;\nuniform sampler2D Sampler0;\nuniform float StrokeWidth;\nin vec2 localPos;\nin vec2 uvCoord;\nout vec4 fragColor;\nvoid main() {\n    vec2 p = localPos;\n    float r;\n    if (p.x < 0.0) {\n        r = (p.y < 0.0) ? Radii.x : Radii.w;\n    } else {\n        r = (p.y < 0.0) ? Radii.y : Radii.z;\n    }\n    vec2 q = abs(p) - HalfSize + r;\n    float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n    float alpha;\n    if (StrokeWidth > 0.0) {\n        float halfStroke = StrokeWidth * 0.5;\n        alpha = 1.0 - smoothstep(halfStroke - 0.5, halfStroke + 0.5, abs(d));\n    } else {\n        alpha = 1.0 - smoothstep(-0.5, 0.5, d);\n    }\n    vec4 col;\n    if (UseTexture == 1) {\n        col = texture(Sampler0, uvCoord) * Color1;\n    } else if (UseGradient == 1) {\n        float t = clamp((p.y + HalfSize.y) / max(2.0 * HalfSize.y, 0.0001), 0.0, 1.0);\n        col = mix(Color1, Color2, t);\n    } else {\n        col = Color1;\n    }\n    fragColor = vec4(col.rgb, col.a * alpha);\n}\n");
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

    public void draw(Matrix4f pose, float x1, float y1, float x2, float y2, float tlRadius, float trRadius, float brRadius, float blRadius, int color1, int color2, boolean useGradient, float strokeWidth) {
        this.drawInternal(pose, x1, y1, x2, y2, tlRadius, trRadius, brRadius, blRadius, color1, color2, useGradient, strokeWidth, -1, 0.0f, 0.0f, 1.0f, 1.0f);
    }

    public void drawTextured(Matrix4f pose, float x1, float y1, float x2, float y2, float tlRadius, float trRadius, float brRadius, float blRadius, int color, int textureId, float u1, float v1, float u2, float v2) {
        this.drawInternal(pose, x1, y1, x2, y2, tlRadius, trRadius, brRadius, blRadius, color, color, false, 0.0f, textureId, u1, v1, u2, v2);
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
        Vector4f vertexPos = new Vector4f();
        float[] vertexData = new float[36];
        float[][] cornerOffsets = new float[][]{{-expandedHalfW, -expandedHalfH}, {expandedHalfW, -expandedHalfH}, {expandedHalfW, expandedHalfH}, {-expandedHalfW, -expandedHalfH}, {expandedHalfW, expandedHalfH}, {-expandedHalfW, expandedHalfH}};
        for (i = 0; i < 6; ++i) {
            vertexPos.set(centerX + cornerOffsets[i][0], centerY + cornerOffsets[i][1], 0.0f, 1.0f).mul(pose);
            float localX = cornerOffsets[i][0];
            float localY = cornerOffsets[i][1];
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

    public void dispose() {
        if (this.programId != 0) {
            GL20.glDeleteProgram(this.programId);
            this.programId = 0;
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