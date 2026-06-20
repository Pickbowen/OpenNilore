package shit.nilore.render;

import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public final class BlurShader {
    private int programId = 0;
    private int samplerUniform = -1;
    private int blurDirUniform = -1;
    private int texelSizeUniform = -1;
    private int radiusUniform = -1;
    private int vboId = 0;
    private int vaoId = 0;

    public void init() {
        if (this.programId != 0) {
            return;
        }
        int vertexShader = BlurShader.compileShader(35633, "#version 150\nin vec3 Position;\nin vec2 UV0;\nout vec2 texCoord;\nvoid main() {\n    gl_Position = vec4(Position, 1.0);\n    texCoord = UV0;\n}\n");
        int fragmentShader = BlurShader.compileShader(35632, "#version 150\nuniform sampler2D Sampler0;\nuniform vec2 BlurDir;\nuniform vec2 TexelSize;\nuniform float Radius;\nin vec2 texCoord;\nout vec4 fragColor;\nvoid main() {\n    float sigma = max(Radius, 1.0);\n    int halfK = int(ceil(sigma * 2.0));\n    if (halfK > 24) halfK = 24;\n    float twoSigmaSq = 2.0 * sigma * sigma;\n    vec4 sum = vec4(0.0);\n    float weightSum = 0.0;\n    for (int i = -halfK; i <= halfK; i++) {\n        float w = exp(-float(i * i) / twoSigmaSq);\n        vec2 offset = BlurDir * float(i) * TexelSize;\n        sum += texture(Sampler0, texCoord + offset) * w;\n        weightSum += w;\n    }\n    fragColor = sum / max(weightSum, 0.0001);\n}\n");
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "UV0");
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, 35714) == 0) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);
            throw new IllegalStateException("Blur shader link failed: " + log);
        }
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        this.programId = program;
        this.samplerUniform = GL20.glGetUniformLocation(this.programId, "Sampler0");
        this.blurDirUniform = GL20.glGetUniformLocation(this.programId, "BlurDir");
        this.texelSizeUniform = GL20.glGetUniformLocation(this.programId, "TexelSize");
        this.radiusUniform = GL20.glGetUniformLocation(this.programId, "Radius");
        this.vaoId = GL30.glGenVertexArrays();
        this.vboId = GL15.glGenBuffers();
        int prevVao = GL11.glGetInteger(34229);
        int prevVbo = GL11.glGetInteger(34964);
        GL30.glBindVertexArray(this.vaoId);
        GL15.glBindBuffer(34962, this.vboId);
        try (MemoryStack memoryStack = MemoryStack.stackPush()){
            FloatBuffer vertexBuffer = memoryStack.mallocFloat(30);
            BlurShader.putVertex(vertexBuffer, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f);
            BlurShader.putVertex(vertexBuffer, 1.0f, -1.0f, 0.0f, 1.0f, 0.0f);
            BlurShader.putVertex(vertexBuffer, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f);
            BlurShader.putVertex(vertexBuffer, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f);
            BlurShader.putVertex(vertexBuffer, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f);
            BlurShader.putVertex(vertexBuffer, -1.0f, 1.0f, 0.0f, 0.0f, 1.0f);
            vertexBuffer.flip();
            GL15.glBufferData(34962, vertexBuffer, 35044);
        }
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, 5126, false, 20, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, 5126, false, 20, 12L);
        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(34962, prevVbo);
    }

    private static void putVertex(FloatBuffer buffer, float x, float y, float z, float u, float v) {
        buffer.put(x).put(y).put(z).put(u).put(v);
    }

    public void render(int textureId, float dirX, float dirY, int width, int height, float radius) {
        int prevProgram = GL11.glGetInteger(35725);
        int prevVao = GL11.glGetInteger(34229);
        int prevActiveTex = GL11.glGetInteger(34016);
        GL13.glActiveTexture(33984);
        int prevTex2d = GL11.glGetInteger(32873);
        GL20.glUseProgram(this.programId);
        GL20.glUniform2f(this.blurDirUniform, dirX, dirY);
        GL20.glUniform2f(this.texelSizeUniform, 1.0f / (float)width, 1.0f / (float)height);
        GL20.glUniform1f(this.radiusUniform, Math.max(radius, 1.0f));
        GL20.glUniform1i(this.samplerUniform, 0);
        GL11.glBindTexture(3553, textureId);
        GL30.glBindVertexArray(this.vaoId);
        GL11.glDrawArrays(4, 0, 6);
        GL11.glBindTexture(3553, prevTex2d);
        GL13.glActiveTexture(prevActiveTex);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProgram);
    }

    public void delete() {
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
            throw new IllegalStateException("Blur shader " + type + " compile failed: " + log);
        }
        return shader;
    }
}