package shit.nilore.modules.impl.combat;

import java.util.Optional;
import java.util.stream.StreamSupport;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.PreMotionEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.misc.PacketUtil;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.utils.rotation.RotationHandler;
import shit.nilore.event.EventTarget;

public class CrystalAura
extends Module {
    public static CrystalAura INSTANCE;
    public static Rotation aimRotation;
    private Entity crystalTarget;
    public final BooleanSetting attackOnPacket = new BooleanSetting("Attack on Packet (Danger)", false);

    public CrystalAura() {
        super("CrystalAura", Category.COMBAT);
        INSTANCE = this;
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Object rawPacket = packetEvent.getPacket();
        if (rawPacket instanceof ClientboundAddEntityPacket addEntityPacket) {
            if (this.attackOnPacket.getValue() && addEntityPacket.getType() == EntityType.END_CRYSTAL) {
                EndCrystal endCrystal = new EndCrystal(mc.level, addEntityPacket.getX(), addEntityPacket.getY(), addEntityPacket.getZ());
                endCrystal.setId(addEntityPacket.getId());
                if (mc.player.distanceTo(endCrystal) <= 4.0f) {
                    Rotation rotation = RotationUtil.entityRotation(endCrystal);
                    mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY(), mc.player.getZ(), rotation.getYaw(), rotation.getPitch(), mc.player.onGround()));
                    PacketUtil.sendPredictive(seq -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, seq));
                    float prevYaw = mc.player.getYRot();
                    float prevPitch = mc.player.getXRot();
                    mc.player.setYRot(RotationHandler.targetRotation.getYaw());
                    mc.player.setXRot(RotationHandler.targetRotation.getPitch());
                    mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(endCrystal, false));
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    mc.player.setYRot(prevYaw);
                    mc.player.setXRot(prevPitch);
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player != null && mc.level != null) {
            Rotation rotation;
            Entity crystalEntity;
            double hitDistance;
            Optional<Entity> crystalOpt = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), true).filter(entity -> entity instanceof EndCrystal).findAny();
            aimRotation = null;
            if (crystalOpt.isPresent() && (hitDistance = RotationUtil.getMinHitDistance(crystalEntity = crystalOpt.get(), rotation = RotationUtil.entityRotation(crystalEntity))) <= 3.0) {
                aimRotation = rotation;
                this.crystalTarget = crystalEntity;
            }
        }
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent preMotionEvent) {
        if (this.crystalTarget != null && aimRotation != null && mc.player != null && mc.getConnection() != null) {
            float prevYaw = mc.player.getYRot();
            float prevPitch = mc.player.getXRot();
            mc.player.setYRot(aimRotation.getYaw());
            mc.player.setXRot(aimRotation.getPitch());
            mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(this.crystalTarget, false));
            mc.player.swing(InteractionHand.MAIN_HAND);
            mc.player.setYRot(prevYaw);
            mc.player.setXRot(prevPitch);
            this.crystalTarget = null;
        }
    }
}