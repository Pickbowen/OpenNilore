package shit.nilore.render.shader;

import lombok.Getter;
import lombok.Generated;
import org.lwjgl.opengl.GL20;

public abstract class Uniform<T extends Uniform<?>> {
    @Getter
    private final String name;
    @Getter
    private int programId;
    @Getter
    private int location;

    public T bindToProgram(int programId) {
        this.programId = programId;
        this.location = GL20.glGetUniformLocation(this.programId, this.name);
        return (T)this;
    }

    @Generated
    public Uniform(String name) {
        this.name = name;
    }
}