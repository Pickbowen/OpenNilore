package shit.nilore.utils.animation;

import lombok.Getter;
import lombok.Setter;

public class SpringAnimation {
    private final float stiffness;
    private final float mass;
    private final float damping;
    @Getter @Setter
    private float targetValue;
    private float currentValue;
    private float velocity;

    public SpringAnimation(float stiffness, float mass, float damping, float initialValue) {
        this.stiffness = stiffness;
        this.mass = mass;
        this.damping = damping;
        this.currentValue = initialValue;
        this.targetValue = initialValue;
    }

    public void reset(float value) {
        this.currentValue = value;
        this.targetValue = value;
        this.velocity = 0.0f;
    }

    public void update(float deltaTime) {
        if (deltaTime <= 0.0f) {
            return;
        }
        float force = -this.stiffness * (this.currentValue - this.targetValue) - this.damping * this.velocity;
        float acceleration = force / this.mass;
        this.velocity += acceleration * deltaTime;
        this.currentValue += this.velocity * deltaTime;
    }

    public float getValue() {
        return this.currentValue;
    }

    public void setValue(float value) {
        this.currentValue = value;
    }

    }