package shit.nilore.render.shader;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;

public class BufferUtil {
    public static void fill(FloatBuffer buffer, float value) {
        buffer.clear();
        for (int i = 0; i < buffer.capacity(); ++i) {
            buffer.put(i, value);
        }
        buffer.clear();
    }

    public static FloatBuffer storeMatrix(FloatBuffer buffer, Matrix4f matrix) {
        return matrix.get(buffer);
    }
}