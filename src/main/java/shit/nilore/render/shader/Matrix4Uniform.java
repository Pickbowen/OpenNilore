package shit.nilore.render.shader;

import java.nio.FloatBuffer;
import lombok.Getter;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;
import shit.nilore.render.shader.BufferUtil;
import shit.nilore.render.shader.Uniform;

public class Matrix4Uniform
extends Uniform<Matrix4Uniform> {
    @Getter
    private boolean transpose;
    @Getter
    private final FloatBuffer dataBuffer = MemoryUtil.memAllocFloat(16);
    @Getter
    private final FloatBuffer stagingBuffer = MemoryUtil.memAllocFloat(16);

    public Matrix4Uniform(String name) {
        super(name);
    }

    public void upload(Matrix4f matrix) {
        this.stagingBuffer.clear();
        BufferUtil.storeMatrix(this.stagingBuffer, matrix);
        this.uploadRaw(false, this.stagingBuffer);
    }

    public void uploadRaw(boolean transpose, FloatBuffer buffer) {
        this.transpose = transpose;
        buffer.mark();
        this.dataBuffer.clear();
        this.dataBuffer.put(buffer);
        this.dataBuffer.rewind();
        buffer.reset();
        int location = this.getLocation();
        if (location >= 0) {
            GL20.glUniformMatrix4fv(location, transpose, this.dataBuffer);
        }
    }

    public float getElement(int row, int col) {
        int index = this.transpose ? col * 4 + row : row * 4 + col;
        return this.dataBuffer.get(index);
    }

    protected void clear() {
        BufferUtil.fill(this.dataBuffer, 0.0f);
    }

    }