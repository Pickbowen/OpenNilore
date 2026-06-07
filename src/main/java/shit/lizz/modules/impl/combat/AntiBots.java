package shit.lizz.modules.impl.combat;

import com.mojang.authlib.GameProfile;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.WorldChangeEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.misc.ChatUtil;
import shit.lizz.event.EventTarget;

public class AntiBots
extends Module {
    public static AntiBots INSTANCE;
    private final NumberSetting newPlayerTimeout = new NumberSetting("Respawn Time", 2500.0, 0.0, 10000.0, 100.0);
    private final BooleanSetting debug = new BooleanSetting("Debug", true);
    private static final Map<UUID, String> suspectNames;
    private static final Map<Integer, String> confirmedBotNames;
    private static final Map<UUID, Long> suspectJoinTimes;
    private static final Set<Integer> confirmedBotIds;
    private static final Map<UUID, Long> playerAddTimes;

    public AntiBots() {
        super("AntiBots", Category.COMBAT);
        INSTANCE = this;
    }

    public static boolean isBedWarsBot(Entity entity) {
        AntiBots antiBots = INSTANCE;
        if (entity.getId() >= 1000000000 || entity.getId() <= -1) {
            return true;
        }
        if (entity.getName() == null) {
            return true;
        }
        if (entity.getScoreboardName().isEmpty()) {
            return true;
        }
        if (antiBots.newPlayerTimeout.getValue().floatValue() < 1.0f) {
            return false;
        }
        if (!playerAddTimes.containsKey(entity.getUUID())) {
            return false;
        }
        return (float)(System.currentTimeMillis() - playerAddTimes.get(entity.getUUID())) < antiBots.newPlayerTimeout.getValue().floatValue();
    }

    public static boolean isBot(Entity entity) {
        return confirmedBotIds.contains(entity.getId());
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (packetEvent.isIncoming() && mc.level != null) {
            Packet<?> packet = packetEvent.getPacket();
            if (packet instanceof ClientboundPlayerInfoUpdatePacket clientboundPlayerInfoUpdatePacket) {
                if (clientboundPlayerInfoUpdatePacket.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                    for (ClientboundPlayerInfoUpdatePacket.Entry entry : clientboundPlayerInfoUpdatePacket.entries()) {
                        if (entry.displayName() != null) {
                            if (entry.profile() != null && entry.profile().getName().contains("Sky_Yuanxiao") && this.debug.getValue()) {
                                ChatUtil.print("汤圆来了11");
                            }
                            if (entry.displayName().getString().contains("Sky_Yuanxiao") && this.debug.getValue()) {
                                ChatUtil.print("汤圆来了11");
                            }
                        }
                        GameProfile gameProfile = entry.profile();
                        UUID uUID = gameProfile.getId();
                        playerAddTimes.put(uUID, System.currentTimeMillis());
                    }
                }
            } else {
                packet = packetEvent.getPacket();
                if (packet instanceof ClientboundAnimatePacket clientboundAnimatePacket) {
                    net.minecraft.world.entity.Entity entity = mc.level.getEntity(clientboundAnimatePacket.getId());
                    if (entity != null && clientboundAnimatePacket.getAction() == 0) {
                        playerAddTimes.remove(entity.getUUID());
                    }
                }
            }
        }
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent worldChangeEvent) {
        suspectNames.clear();
        confirmedBotNames.clear();
        confirmedBotIds.clear();
        suspectJoinTimes.clear();
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (motionEvent.isPost()) {
            for (Map.Entry<UUID, Long> entry : suspectJoinTimes.entrySet()) {
                if (System.currentTimeMillis() - entry.getValue() <= 500L) continue;
                if (this.debug.getValue()) {
                    ChatUtil.print("Fake Staff Detected! (" + suspectNames.get(entry.getKey()) + ")");
                }
                suspectJoinTimes.remove(entry.getKey());
            }
        }
    }

    @EventTarget
    public void onPacketBot(PacketEvent packetEvent) {
        if (!packetEvent.isIncoming()) {
            return;
        }
        Object packet = packetEvent.getPacket();
        if (packet instanceof ClientboundPlayerInfoUpdatePacket infoUpdate) {
            if (!infoUpdate.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                return;
            }
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : infoUpdate.entries()) {
                if (entry.displayName() != null) {
                    if (entry.profile() != null && entry.profile().getName().contains("Sky_Yuanxiao") && this.debug.getValue()) {
                        ChatUtil.print("汤圆来了1");
                    }
                    if (entry.displayName().getString().contains("Sky_Yuanxiao") && this.debug.getValue()) {
                        ChatUtil.print("汤圆来了1");
                    }
                }
                if (entry.displayName() == null
                        || !entry.displayName().getSiblings().isEmpty()
                        || entry.gameMode() != GameType.SURVIVAL) {
                    continue;
                }
                UUID uuid = entry.profile().getId();
                suspectJoinTimes.put(uuid, System.currentTimeMillis());
                suspectNames.put(uuid, entry.displayName().getString());
            }
            return;
        }
        if (packet instanceof ClientboundAddPlayerPacket addPlayer) {
            if (!suspectJoinTimes.containsKey(addPlayer.getPlayerId())) {
                return;
            }
            String botName = suspectNames.get(addPlayer.getPlayerId());
            if (this.debug.getValue()) {
                ChatUtil.print("Bot Detected! (" + botName + ")");
            }
            confirmedBotNames.put(addPlayer.getEntityId(), botName);
            suspectJoinTimes.remove(addPlayer.getPlayerId());
            confirmedBotIds.add(addPlayer.getEntityId());
            return;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket removePacket) {
            for (Integer entityId : removePacket.getEntityIds()) {
                if (!confirmedBotIds.contains(entityId)) continue;
                String name = confirmedBotNames.get(entityId);
                if (this.debug.getValue()) {
                    ChatUtil.print("Bot Removed! (" + name + ")");
                }
                confirmedBotIds.remove(entityId);
            }
        }
    }

    static {
        suspectNames = new ConcurrentHashMap<>();
        confirmedBotNames = new ConcurrentHashMap<>();
        suspectJoinTimes = new ConcurrentHashMap<>();
        confirmedBotIds = new HashSet<>();
        playerAddTimes = new ConcurrentHashMap<>();
    }
}