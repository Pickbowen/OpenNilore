package client.nilore.modules.impl.combat;

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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.event.EventTarget;

public class AntiBots
extends Module {
    public static AntiBots INSTANCE;
    private final NumberSetting newPlayerTimeout = new NumberSetting("Respawn Time", 2500.2, 0.0, 10000.0, 100.0);
    private final BooleanSetting debug = new BooleanSetting("Debug", true);
    private static final Map<UUID, String> suspectNames;
    private static final Map<Integer, String> confirmedBotNames;
    private static final Map<UUID, Long> suspectJoinTimes;
    private static final Set<Integer> confirmedBotIds;
    private static final Map<UUID, Long> playerAddTimes;

    /** 一直查不到 tab 列表条目的实体 id, 由 onMotion 每拍刷新。 */
    private static final Set<Integer> tabMissingIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 首次发现某 UUID 不在 tab 列表里的时间戳, 用来做宽限期。 */
    private static final Map<UUID, Long> unseenInTab;

    /**
     * tab 列表反查: 真实玩家一定在 tab 里, 只有 NPC/假人插件实体不在。
     *
     * <p>这是整套判定里最硬的一条 —— 比"加入时间短"可靠得多, 因为假人通常是服务端
     * 自己 spawn 的实体, 压根不会进 PlayerInfo。宽限期只是防"实体包比 info 包先到"。</p>
     */
    private final BooleanSetting tabCheck = new BooleanSetting("Tab Check", true);

    /** tab 反查的宽限时间 (毫秒)。 */
    private final NumberSetting tabGrace = new NumberSetting("Tab Grace", 1500.0, 0.0, 5000.0, 100.0,
            () -> this.tabCheck.getValue());

    /** 名字特征匹配 (NPC / CIT- / 空名等)。 */
    private final BooleanSetting nameFilter = new BooleanSetting("Name Filter", true);

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
        if (entity == null) {
            return false;
        }
        if (confirmedBotIds.contains(entity.getId())) {
            return true;
        }
        AntiBots antiBots = INSTANCE;
        // 模块关掉时保持上游语义: 只有包级确认过的才算假人。
        if (antiBots == null || !antiBots.isEnabled()) {
            return false;
        }
        if (antiBots.nameFilter.getValue() && nameLooksLikeBot(entity)) {
            return true;
        }
        return antiBots.tabCheck.getValue() && tabMissingIds.contains(entity.getId());
    }

    /**
     * 名字特征。只认"真人不可能叫"的形态, 不做子串乱匹配
     * (否则 RobotKing / Botanist 这类正常 ID 会被误杀)。
     */
    private static boolean nameLooksLikeBot(Entity entity) {
        if (entity.getName() == null) {
            return true;
        }
        String name = entity.getName().getString();
        if (name == null || name.isBlank()) {
            return true;
        }
        String plain = name.replaceAll("\u00a7.", "").trim();
        if (plain.isEmpty()) {
            return true;
        }
        String lower = plain.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("npc")
                || lower.equals("bot")
                || lower.equals("citizen")
                || plain.contains("[NPC]")
                || plain.contains("CIT-")
                || plain.startsWith("\u00ab");
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
        // 换世界必须清掉: 否则所有人都被当成"刚加入", 宽限期内整队人都被剔出目标列表。
        playerAddTimes.clear();
        unseenInTab.clear();
        tabMissingIds.clear();
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
            if (this.tabCheck.getValue()) {
                this.scanTab();
            } else {
                tabMissingIds.clear();
                unseenInTab.clear();
            }
        }
    }

    /** 每拍扫一遍玩家实体, 把持续不在 tab 列表里的挑出来。 */
    private void scanTab() {
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.getConnection() == null || mc.hasSingleplayerServer()) {
            tabMissingIds.clear();
            unseenInTab.clear();
            return;
        }
        long now = System.currentTimeMillis();
        double grace = this.tabGrace.getValue().doubleValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player) || entity == mc.player) {
                continue;
            }
            if (mc.getConnection().getPlayerInfo(entity.getUUID()) != null) {
                tabMissingIds.remove(entity.getId());
                unseenInTab.remove(entity.getUUID());
                continue;
            }
            long since = unseenInTab.computeIfAbsent(entity.getUUID(), key -> now);
            if ((double) (now - since) >= grace) {
                tabMissingIds.add(entity.getId());
            } else {
                tabMissingIds.remove(entity.getId());
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
        unseenInTab = new ConcurrentHashMap<>();
    }
}