package shit.lizz.utils.math;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class Vector2f {
    @Getter @Setter
    public float x;
    @Getter @Setter
    public float y;

    public Vector2f(Vector2f other) {
        this(other.x, other.y);
    }

    public Vector2f add(float x, float y) {
        return new Vector2f(this.x + x, this.y + y);
    }

    public Vector2f fill(float value) {
        this.x = value;
        this.y = value;
        return this;
    }

    public Vector2f set(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public Vector2f fillD(double value) {
        this.x = (float)value;
        this.y = (float)value;
        return this;
    }

    public Vector2f setD(double x, double y) {
        this.x = (float)x;
        this.y = (float)y;
        return this;
    }

    public Vector2f fromArray(float[] values) {
        this.x = values[0];
        this.y = values[1];
        return this;
    }

}
