package client.nilore.modules.impl.movement;

public interface SpeedAccess {

    record Collision(int contacts, double motionX, double motionZ) {
        public static Collision of(int contacts, double motionX, double motionZ) {
            return new Collision(contacts, motionX, motionZ);
        }
    }
}
