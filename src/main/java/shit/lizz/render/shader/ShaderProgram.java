package shit.lizz.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import lombok.Getter;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import shit.lizz.render.shader.Matrix4Uniform;
import shit.lizz.render.shader.ShaderFormats;
import shit.lizz.render.shader.ShaderSource;

public class ShaderProgram {
    private static final Logger LOGGER = LogUtils.getLogger();
    @Getter
    private final int programId;
    @Getter
    private final Map<String, Integer> uniformCache = new HashMap<>();
    @Getter
    private final Matrix4Uniform modelViewUniform;
    @Getter
    private final Matrix4Uniform projectionUniform;
    @Getter
    private Matrix4f cachedModelView;
    @Getter
    private Matrix4f cachedProjection;
    private static int prevProgram;
    private final boolean valid;

    public ShaderProgram(String name) {
        this(name, "vertex", ShaderFormats.POSITION_UV, "ModelViewMat", "ProjMat");
    }

    public ShaderProgram(String fragmentName, String vertexName, Supplier<Map<Integer, String>> attributesSupplier) {
        this(fragmentName, vertexName, attributesSupplier, "ModelViewMat", "ProjMat");
    }

    public ShaderProgram(String name, Supplier<Map<Integer, String>> attributesSupplier) {
        this(name, name, attributesSupplier, "ModelViewMat", "ProjMat");
    }

    public ShaderProgram(String name, Supplier<Map<Integer, String>> attributesSupplier, String modelViewName, String projName) {
        this(name, name, attributesSupplier, modelViewName, projName);
    }

    public ShaderProgram(String fragmentName, String vertexName, Supplier<Map<Integer, String>> attributesSupplier, String modelViewName, String projName) {
        this.programId = GL20.glCreateProgram();
        int fragmentShader = ShaderProgram.compileShader(ShaderSource.getByFileName(fragmentName + ".fsh").getSource(), 35632);
        int vertexShader = ShaderProgram.compileShader(ShaderSource.getByFileName(vertexName + ".vsh").getSource(), 35633);
        // Shader compilation can fail on some drivers (notably Intel forward-compatible
        // GL contexts, which reject legacy GLSL such as texture2D). Degrade gracefully.
        if (fragmentShader == 0 || vertexShader == 0) {
            if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader);
            if (vertexShader != 0) GL20.glDeleteShader(vertexShader);
            LOGGER.error("Shader '{}' disabled: compilation failed; the effect will be skipped.", fragmentName);
            this.modelViewUniform = null;
            this.projectionUniform = null;
            this.valid = false;
            return;
        }
        GL20.glAttachShader(this.programId, fragmentShader);
        GL20.glAttachShader(this.programId, vertexShader);
        for (Map.Entry<Integer, String> entry : attributesSupplier.get().entrySet()) {
            GL20.glEnableVertexAttribArray(entry.getKey());
            GL20.glBindAttribLocation(this.programId, entry.getKey(), entry.getValue());
        }
        GL20.glLinkProgram(this.programId);
        if (GL20.glGetProgrami(this.programId, 35714) == 0) {
            LOGGER.error("Shader '{}' disabled: program link failed; the effect will be skipped.", fragmentName);
            GL20.glDeleteShader(fragmentShader);
            GL20.glDeleteShader(vertexShader);
            this.modelViewUniform = null;
            this.projectionUniform = null;
            this.valid = false;
            return;
        }
        GL20.glDeleteShader(fragmentShader);
        GL20.glDeleteShader(vertexShader);
        this.modelViewUniform = new Matrix4Uniform(modelViewName).bindToProgram(this.programId);
        this.projectionUniform = new Matrix4Uniform(projName).bindToProgram(this.programId);
        this.valid = true;
    }

    public boolean isValid() {
        return this.valid;
    }

    public void use() {
        if (!this.valid) {
            return;
        }
        prevProgram = GL20.glGetInteger(35725);
        GL20.glUseProgram(this.programId);
        this.setModelView(RenderSystem.getModelViewMatrix());
        this.setProjection(RenderSystem.getProjectionMatrix());
    }

    public void setModelView(Matrix4f modelView) {
        if (this.cachedModelView != modelView) {
            this.modelViewUniform.upload(modelView);
            this.cachedModelView = modelView;
        }
    }

    public void setProjection(Matrix4f projection) {
        if (this.cachedProjection != projection) {
            this.projectionUniform.upload(projection);
            this.cachedProjection = projection;
        }
    }

    public void stopUsing() {
        GL20.glUseProgram(prevProgram);
    }

    public int getUniformLocation(String uniformName) {
        if (!this.uniformCache.containsKey(uniformName)) {
            this.uniformCache.put(uniformName, GL20.glGetUniformLocation(this.programId, uniformName));
        }
        return this.uniformCache.get(uniformName);
    }

    private static int compileShader(String source, int type) {
        int shader = GL20.glCreateShader(type);
        Matcher matcher = ShaderFormats.IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            boolean isPlainImport = matcher.group(2) == null;
            if (!isPlainImport) continue;
            String importName = matcher.group(3);
            String importSource = ShaderSource.getByFileName(importName).getSource();
            source = source.replaceAll(ShaderFormats.IMPORT_PATTERN.pattern(), importSource);
        }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, 35713) == 0) {
            LOGGER.error("Failed to compile shader (type {}):\n{}", type, GL20.glGetShaderInfoLog(shader, Short.MAX_VALUE));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    }
