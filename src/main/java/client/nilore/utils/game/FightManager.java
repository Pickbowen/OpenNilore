package client.nilore.utils.game;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import client.nilore.ClientBase;

public final class FightManager
extends ClientBase {
    private static int currentTick = -1;
    private static boolean tickAttacked;
    private static boolean tickLocked;

    private FightManager() {
    }

    private static void checkTick() {
        int gameTick = mc.player != null ? mc.player.tickCount : 0;
        if (gameTick != currentTick) {
            currentTick = gameTick;
            tickAttacked = false;
            tickLocked = false;
        }
    }

    public static boolean hasAttackedThisTick() {
        checkTick();
        return tickAttacked;
    }

    public static boolean attackAndLock() {
        checkTick();
        if (tickLocked) {
            return false;
        }
        tickLocked = true;
        return true;
    }

    public static void markAttack() {
        checkTick();
        tickAttacked = true;
        tickLocked = true;
    }

    public static boolean attackByPacket(Entity entity, boolean swingFirst) {
        if (entity == null || mc.player == null || mc.getConnection() == null) {
            return false;
        }
        if (swingFirst) {
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, false));
        } else {
            mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, false));
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
        checkTick();
        tickAttacked = true;
        tickLocked = true;
        return true;
    }

    public static void reset() {
        currentTick = -1;
        tickAttacked = false;
        tickLocked = false;
    }
}
