package client.nilore.modules.impl.combat.antikb;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.NiloreClient;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.EntityHurtEvent;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.UseItemRayTraceEvent;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.NoSlow;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.render.RenderUtil;

public class NoXZMode
extends AntiKBMode {
    public static NoXZMode INSTANCE;
    public static boolean handlingVelocity;

    /**
     * 延迟队列。元素类型放宽到 {@code Packet<?>} 是为了能塞进 {@link #TICK_MARKER} —— 它是
     * 出站包, 不是 {@code ClientGamePacketListener} 的入站包, 永远只当分隔符用, 不会 handle。
     */
    private final ConcurrentLinkedQueue<Packet<?>> packetQueue = new ConcurrentLinkedQueue<>();
    /**
     * 每拍的队列边界标记, 对齐对方 {@code ServerPacketManager.postTick()} 里压一个
     * {@code KeepAliveC2SPacket(-1337L)} 的做法。
     *
     * <p>原来的实现拿"这一包击退包"当 marker, 问题在于击退包本身就是延迟队列的第一个元素:
     * 释放时它已经被 poll 掉, 之后所有比较都落空, 于是一次 {@code releaseUntil} 会把整条队列
     * 一口气放干净 —— 想要"只放到本拍为止"根本表达不出来。改成每拍末尾压一个独立标记后,
     * "释放到本拍边界"变成一个纯粹的队列操作, 与包的内容无关, 也和击退包是哪一个无关。
     */
    private static final ServerboundKeepAlivePacket TICK_MARKER = new ServerboundKeepAlivePacket(-1337L);
    private final Map<Entity, Vec3> entityPositions = new HashMap<>();

    public static boolean compensating;
    public static boolean inDelayWindow;
    public static int counterRemaining;

    private boolean positionCorrection;
    private int coolDownTicks;
    private int delayTicks;
    /**
     * 上一次看到的世界引用。切换维度 / 重连后 {@code mc.level} 会换成另一个实例, 用它当
     * "世界已经变了"的判据, 比盯 screen 更可靠 —— 有些服务端的维度切换根本不会让你看到
     * 加载屏, 但旧队列里的包一旦被 handle 到新世界, 就是卡加载界面 / 实体错位。
     */
    private Object lastLevel;
    private long lastFireballTick = Long.MIN_VALUE;
    private int noAimTicks;
    private int noSprintTicks;
    /** 反打阶段已经等了多少拍; 冷却/瞄准迟迟不满足时用它兜底收尾。 */
    private int counterTicks;
    private static final int COUNTER_TIMEOUT_TICKS = 20;
    private int jumpTicks;
    private boolean jumpKeyForced;
    private float barProgress;
    private float barAlpha;
    private long lastFrameNanos;
    private long compensateUntilMs = -1L;
    private Entity target;
    /**
     * 最近一次真正打到我的实体。
     *
     * <p>进入 delay window 的那一包（自己的 {@code ClientboundSetEntityMotionPacket}）只要求
     * {@link #shouldNotEngage()} 为 false，而它里面唯一的"软"条件就是 {@code target != null}。
     * 可 {@link #target} 每拍都被 {@code KillAura.target} 覆盖，而 KillAura 的
     * {@code isValidAttack} 会被 hurtTime / FoV / 距离过滤 —— 刚砍中的人 hurtTime 是 10，
     * 跳砍转身又会掉出 FoV，于是那一拍 {@code KillAura.target} 就是 null，"跳着砍人被打"
     * 永远进不了 delay window。受击来源本身是确定的，用它兜底。
     */
    private Entity lastAttacker;
    private int lastAttackerTick = Integer.MIN_VALUE / 2;

    private static final int WINDUP_TICKS = 2;

    @Override
    public boolean isActive() {
        return inDelayWindow || counterRemaining > 0;
    }

    public static boolean isCountering() {
        return counterRemaining > 0;
    }

    public static boolean isInAttackWindow() {
        return isCountering();
    }

    public static boolean isInDelayWindow() {
        return inDelayWindow;
    }

    public static Entity getLockedTarget() {
        NoXZMode mode = INSTANCE;
        if (mode == null || AntiKB.INSTANCE == null || !AntiKB.INSTANCE.isEnabled()) {
            return null;
        }
        if (!inDelayWindow && counterRemaining <= 0) {
            return null;
        }
        return mode.target != null && mode.target.isAlive() ? mode.target : null;
    }

    public NoXZMode() {
        super("NoXZ");
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.resetAll();
    }

    @Override
    public void onDisable() {
        this.resetAll();
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public void onRotation(RotationEvent rotationEvent) {
    }

    @Override
    public void onMotion(MotionEvent motionEvent) {
    }

    @Override
    public void onPreMotion(PreMotionEvent preMotionEvent) {
    }

    @Override
    public void onSprint(SprintEvent sprintEvent) {
    }

    @Override
    public void onAttack(EntityRemoveEvent entityRemoveEvent) {
    }

    @Override
    public void onEntityHurt(EntityHurtEvent event) {
        if (mc.player == null || event.entity() != mc.player) {
            return;
        }
        Entity attacker = event.damageSource().getDirectEntity();
        if (attacker == null) {
            attacker = event.damageSource().getEntity();
        }
        if (attacker != null && attacker != mc.player && attacker.isAlive()) {
            // 记下受击来源：onTick 每拍会把 target 改成 KillAura.target，可能为 null，
            // 这条记录是"被打了却没索敌目标"时唯一还能进 delay window 的依据。
            this.lastAttacker = attacker;
            this.lastAttackerTick = mc.player.tickCount;
            if (this.target == null) {
                this.target = attacker;
            }
        }
    }

    @Override
    public void onGameTick(GameTickEvent gameTickEvent) {
        if (mc.player == null) {
            return;
        }
        if (!AntiKB.INSTANCE.jumpReset.getValue() || this.jumpTicks <= 0) {
            this.restoreJumpKey();
            return;
        }
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            return;
        }
        if (mc.player.isOnFire()) {
            this.jumpTicks = 0;
            this.restoreJumpKey();
            return;
        }
        // Same rule as JumpResetMode: only press the key on a tick where vanilla would accept it,
        // otherwise the pending jump is spent while airborne and nothing happens.
        if (!canJumpNow(mc.player)) {
            this.jumpTicks = 0;
            this.restoreJumpKey();
            return;
        }
        this.jumpKeyForced = true;
        mc.options.keyJump.setDown(true);
        --this.jumpTicks;
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 切世界 / 重连时队列里的包属于旧连接: 再 handle 就是把旧维度的实体和坐标写进新世界,
        // 表现就是卡在加载界面。Southside 在 ServerPacketManager.onPacket 里判 ConnectScreen
        // 后直接 stop(); 这里把 ReceivingLevelScreen(落地前接收区块) 一起判, 并且直接丢包
        // 而不是 flush —— 那些包在新连接上已经没有任何应用价值。
        if (mc.screen instanceof ReceivingLevelScreen || mc.screen instanceof ConnectScreen) {
            this.abortAll();
            return;
        }
        // 包可能是先于 onTick 到达的: 世界已经换了就必须在这里先丢掉旧队列, 否则新旧包混排。
        if (mc.level != this.lastLevel) {
            this.lastLevel = mc.level;
            this.abortAll();
            return;
        }
        if (this.shouldNotEngage()) {
            return;
        }
        Packet<ClientGamePacketListener> packet = receivePacketEvent.getPacket();

        if (packet instanceof ClientboundPlayerPositionPacket) {
            this.positionCorrection = true;
            return;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()) {
            if (!inDelayWindow) {
                if (this.shouldNotEngage()) {
                    return;
                }
                double horizontal = Math.hypot(motion.getXa(), motion.getZa()) / 8000.0;
                if (this.coolDownTicks > 0 || horizontal < 0.13) {
                    return;
                }
                inDelayWindow = true;
                this.delayTicks = 0;
                handlingVelocity = true;
            } else if (counterRemaining > 0) {
                counterRemaining = 0;
            }
            receivePacketEvent.setCancelled(true);
            this.packetQueue.add(packet);
            return;
        }

        // 位置记录必须放在放行判断之前: 换成对方 canDelay 清单后, MoveEntity / Teleport 属于
        // "可直接处理"的包, 根本走不到入队那一段, 而 Target ESP 正是靠这里缓存的坐标画框。
        if (packet instanceof ClientboundMoveEntityPacket move) {
            Entity entity = move.getEntity(mc.level);
            if (entity != null) {
                Vec3 base = this.entityPositions.getOrDefault(entity,
                        new Vec3(entity.getX(), entity.getY(), entity.getZ()));
                if (move.hasPosition()) {
                    this.entityPositions.put(entity, base.add(
                            move.getXa() / 4096.0,
                            move.getYa() / 4096.0,
                            move.getZa() / 4096.0
                    ));
                }
            }
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            Entity entity = mc.level.getEntity(teleport.getId());
            if (entity != null) {
                this.entityPositions.put(entity,
                        new Vec3(teleport.getX(), teleport.getY(), teleport.getZ()));
            }
        }
        if (this.isAllowedPacket(packet)) {
            return;
        }
        if (!inDelayWindow) {
            return;
        }

        receivePacketEvent.setCancelled(true);
        this.packetQueue.add(packet);
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 加载/重连期间什么都不能做: 窗口如果跨世界存活, 恢复后第一拍就会拿着旧队列去 handle。
        if (mc.screen instanceof ReceivingLevelScreen || mc.screen instanceof ConnectScreen) {
            this.abortAll();
            return;
        }
        // 兜底: 世界实例换了(跨维度/重连)就直接丢队列, 不依赖加载屏是否出现过。
        if (mc.level != this.lastLevel) {
            this.lastLevel = mc.level;
            this.abortAll();
            return;
        }

        // postTick(): 延迟窗口内每拍在队尾压一个边界标记。releaseUntilKb 只放到这个标记为止,
        // 也就是"释放本拍之前到达的包", 与具体是哪一包击退无关。
        if (inDelayWindow) {
            this.packetQueue.add(TICK_MARKER);
        }

        boolean auraOn = KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled();
        this.target = auraOn && AntiKB.INSTANCE.requireKillAura.getValue()
                ? KillAura.target
                : this.crosshairTarget();
        if (this.target == null && auraOn) {
            // 4 格内最近玩家这个兜底只对 aura 有意义 (它会静默把视角转过去)。无 aura 时
            // 锁一个身后的玩家, 只会让窗口空转: 压包 → 射线打不到 → 复位, 纯 lag。
            this.target = this.nearestPlayer(4.0);
        }
        if (this.target == null && AntiKB.INSTANCE.requireKillAura.getValue()
                && this.lastAttacker != null && this.lastAttacker.isAlive()
                && this.lastAttacker.distanceToSqr(mc.player) <= 64.0
                && mc.player.tickCount - this.lastAttackerTick <= 40) {
            this.target = this.lastAttacker;
        }

        if (this.compensateUntilMs != -1L
                && (System.currentTimeMillis() >= this.compensateUntilMs || mc.options.keyUp.isDown())) {
            compensating = false;
            this.compensateUntilMs = -1L;
        }

        if (this.coolDownTicks > 0) {
            --this.coolDownTicks;
        }

        if (this.shouldNotEngage() || this.noAimTicks >= 3) {
            this.resetAll();
            return;
        }

        if (inDelayWindow && counterRemaining <= 0) {
            compensating = true;
            if (this.delayTicks > AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()) {
                this.resetAll();
                return;
            }
            if (this.canRelease()) {
                this.releaseUntilKb();
                counterRemaining = Math.max(1, AntiKB.INSTANCE.attackAmount.getValue().intValue());
                this.noAimTicks = 0;
                this.noSprintTicks = 0;
                this.counterTicks = 0;
                if (AntiKB.INSTANCE.jumpReset.getValue()) {
                    this.jumpTicks = 1;
                }
            } else {
                ++this.delayTicks;
            }
        }

        if (counterRemaining > 0) {
            if (this.isManualAttacking()) {
                this.finishWindow();
                return;
            }
            if (++this.counterTicks > COUNTER_TIMEOUT_TICKS) {
                this.debug("Failed (Timeout)");
                this.resetAll();
                return;
            }
            if (!this.auraLockedOnTarget() && !this.isAimingAtTarget()) {
                ++this.noAimTicks;
                this.debug("Failed (RayCast)");
                return;
            }
            this.noAimTicks = 0;
            if (!mc.player.isSprinting()) {
                ++this.noSprintTicks;
                this.debug("Failed (Sprint)");
                if (this.noSprintTicks > COUNTER_TIMEOUT_TICKS) {
                    this.resetAll();
                }
                return;
            }
            this.noSprintTicks = 0;
            if (!this.attackReduce(this.target)) {
                return;
            }
            if (--counterRemaining <= 0) {
                this.finishWindow();
            }
        } else if (compensating) {
            this.compensateUntilMs = System.currentTimeMillis() + 10L;
        }
    }

    /** 收窗: 把压住的包按序放回, 状态复位。 */
    private void finishWindow() {
        this.flushQueue();
        inDelayWindow = false;
        handlingVelocity = false;
        counterRemaining = 0;
        this.delayTicks = 0;
    }

    @Override
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null) {
            return;
        }
        if (compensating
                && (!inDelayWindow || KillAura.shouldKeepSprintInDelayWindow())
                && AntiKB.INSTANCE.autoForwards.getValue()) {
            strafeEvent.setForward(1.0f);
        }
    }

    @Override
    public void onItemUseRayTrace(UseItemRayTraceEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!mc.player.getMainHandItem().is(Items.FIRE_CHARGE)
                && !mc.player.getOffhandItem().is(Items.FIRE_CHARGE)) {
            return;
        }
        long tick = mc.level.getGameTime();
        if (tick == this.lastFireballTick) {
            return;
        }
        this.lastFireballTick = tick;
        this.coolDownTicks = 10;
    }

    @Override
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        // 断开时连接已经没了, 队列里的包 handle 不出去, 直接丢。
        this.lastLevel = null;
        this.abortAll();
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (!AntiKB.INSTANCE.renderBar.getValue() || !AntiKB.INSTANCE.isEnabled()) {
            return;
        }
        float maximum = Math.max(1, AntiKB.INSTANCE.maxDelayTicks.getValue().intValue());
        float target = inDelayWindow ? Math.min(1.0f, this.delayTicks / maximum) : 0.0f;
        float targetAlpha = inDelayWindow || this.barProgress > 0.01f ? 1.0f : 0.0f;
        long now = System.nanoTime();
        float dt = this.lastFrameNanos == 0L
                ? 0.016666668f
                : Math.min((now - this.lastFrameNanos) / 1.0E9f, 0.1f);
        this.lastFrameNanos = now;
        if (target >= 1.0f) {
            this.barProgress = 1.0f;
        } else {
            float speed = target > this.barProgress ? 9.75f : 21.4f;
            this.barProgress += (target - this.barProgress) * (1.0f - (float) Math.exp(-speed * dt));
        }
        this.barAlpha += (targetAlpha - this.barAlpha) * (1.0f - (float) Math.exp(-9.75f * dt));
        if (Math.abs(target - this.barProgress) < 0.001f) {
            this.barProgress = target;
        }
        if (this.barAlpha <= 0.01f || this.barProgress <= 0.001f) {
            return;
        }
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        float barWidth = 100.0f;
        float barHeight = 2.0f;
        float barX = width / 2.0f - barWidth / 2.0f;
        float barY = height / 2.0f + height * 0.10f;

        RenderUtil.drawFilledRect(event.poseStack(), barX, barY, barWidth, barHeight,
                new Color(30, 30, 36, (int) (180 * this.barAlpha)).getRGB());
        RenderUtil.drawFilledRect(event.poseStack(), barX, barY, barWidth * this.barProgress, barHeight,
                new Color(0, 180, 255, (int) (230 * this.barAlpha)).getRGB());
    }

    @Override
    public void onRender(RenderEvent renderEvent) {
        if (!AntiKB.INSTANCE.targetEsp.getValue() || this.target == null || !inDelayWindow) {
            return;
        }
        if (mc.gameRenderer == null) {
            return;
        }
        Vec3 position = this.entityPositions.get(this.target);
        if (position == null) {
            return;
        }
        double halfWidth = this.target.getBbWidth() / 2.0;
        double height = this.target.getBbHeight();
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
        AABB box = new AABB(-halfWidth, 0.0, -halfWidth, halfWidth, height, halfWidth);
        Color fill = new Color(255, 255, 255, 64);
        RenderUtil.drawFilledColoredBox(box, poseStack, fill, fill);
        Color outline = new Color(255, 255, 255, 204);
        RenderUtil.drawColoredBox(box, poseStack, outline, outline);
        poseStack.popPose();
    }

    /**
     * 反打一次。返回 {@code false} 表示"这一拍打不出去"（冷却没满 / 射线没打到 / 本 tick
     * 已经出过手），调用方不该扣减计数 —— 否则次数被烧掉，实际一个有效攻击都没发出去。
     *
     * <p>为什么必须自己查冷却：KillAura 开着时它有 APS 蓄力引擎在管节奏，NoXZ 只是搭车；
     * 没有 aura 时这里是唯一的出手点，连着 5 拍甩 5 个冷却不足的攻击包，服务端会当成
     * 无效攻击 / 超频，紧接着就是位置修正 —— 也就是"不用杀戮打人反而更卡、被拉回"。
     */
    private boolean attackReduce(Entity entity) {
        if (entity == null || mc.player == null || mc.getConnection() == null) {
            return true;
        }
        // 1.9 冷却闸门: 和 KillAura 的 delayMode "1.9" 用同一个阈值。
        if (mc.player.getAttackStrengthScale(0.5f) < 0.9f) {
            return false;
        }
        // 没有 aura 的静默旋转兜着时, 攻击包算不算数取决于玩家真实准星: 打不到就别发。
        if (!this.auraLockedOnTarget() && !this.isAimingAtTarget()) {
            return false;
        }
        if (!KillAura.attackAndLock()) {
            return false;
        }
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(new EntityRemoveEvent(false, entity));
        }
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, mc.player.isShiftKeyDown()));
        mc.player.swing(InteractionHand.MAIN_HAND);
        Vec3 movement = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(movement.x * 0.6, movement.y, movement.z * 0.6);
        return true;
    }

    private Entity crosshairTarget() {
        return mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
    }

    private Entity nearestPlayer(double range) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Entity best = null;
        double bestDistance = range * range;
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            double distance = player.distanceToSqr(mc.player);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    /**
     * aura 是否正锁着当前目标。
     *
     * <p>锁着时不再复检射线: 朝向在 tick 末尾才交接给 {@code ClientBase}, 而这里的判定跑在
     * TickEvent 里, 读到的是上一拍的朝向 —— 复检会把"明明已经锁着人"的拍判成
     * {@code Failed (RayCast)}。
     */
    private boolean auraLockedOnTarget() {
        return KillAura.INSTANCE != null
                && KillAura.INSTANCE.isEnabled()
                && this.target != null
                && this.target == KillAura.target;
    }

    private boolean isAimingAtTarget() {
        if (this.target == null || mc.player == null) {
            return false;
        }
        // 静默瞄准只有在 aura 真的跑着的时候才存在。没有 aura 时命中目标的就是玩家自己的
        // 准星 (hitResult) —— 老版本 requireKillAura=false 走的就是这条路径, 所以手动打
        // 同样能进窗口。这里不再要求用户先把 Require KillAura 关掉, 而是看 aura 是否在跑。
        boolean auraOn = KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled();
        if (!auraOn || !AntiKB.INSTANCE.requireKillAura.getValue()) {
            return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == this.target;
        }
        double range = Math.max(3.0, AntiKB.INSTANCE.releaseDistance.getValue().doubleValue() + 1.0);
        // 容差从 0.3 放到 0.6: 目标来自 KillAura.target, 判定用的是静默瞄准的朝向, 而
        // rayTraceForAim 的盒子是 inflate 后的 AABB。0.3 在快转身 / 高延迟下会把"其实已经
        // 对着"的那一拍判掉, 表现就是 aura 明明锁着人, 窗口却一直进不去。
        return RotationUtil.isLookingAt(this.target, range, 0.6f);
    }

    /** 释放闸门: 疾跑 + 落地 + 目标在 Release Distance 内; aura 锁着时不再复检射线。 */
    private boolean canRelease() {
        if (!this.ready() || this.target == null) {
            return false;
        }
        if (!this.isWithinReleaseDistance()) {
            return false;
        }
        return this.auraLockedOnTarget() || this.isAimingAtTarget();
    }

    private boolean isWithinReleaseDistance() {
        if (this.target == null || mc.player == null) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        double limit = AntiKB.INSTANCE.releaseDistance.getValue().doubleValue();
        return RotationUtil.closestPoint(eye, this.target.getBoundingBox()).distanceTo(eye) <= limit;
    }

    private boolean shouldNotEngage() {
        if (mc.player == null || mc.getConnection() == null) {
            return true;
        }
        if (mc.player.isInLava() || mc.player.isInWater()) {
            return true;
        }
        if (mc.level != null && mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.COBWEB)) {
            return true;
        }
        if (this.positionCorrection) {
            return true;
        }
        if (mc.screen instanceof InventoryScreen) {
            return true;
        }
        if (this.target == null || !this.target.isAlive()) {
            return true;
        }
        if (mc.player.isDeadOrDying() || mc.player.isSpectator()) {
            return true;
        }
        if (NoSlow.isHandling()) {
            return true;
        }
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            return true;
        }
        Stuck stuck = Stuck.INSTANCE;
        return stuck != null && stuck.isEnabled();
    }

    /**
     * 这一包是否"不进延迟队列"、直接交给原版处理。
     *
     * <p>清单完整对齐对方 {@code PacketUtils.canDelay}: 那边返回 {@code true} 表示"可以延迟",
     * 这里是它的取反, 所以两边放行的包集合完全一致。对方的 {Fabric 名} 对应这里的 {Mojmap 名}:
     * ChatMessage/SystemChat、Scoreboard*、EntityEquipmentUpdate、ClearTitle/Title/Subtitle/
     * TitleFade、BossBar、EntitiesDestroy、HealthUpdate、PlaySound(FromEntity)、EntitySetHeadYaw、
     * EntityS2CPacket、EntityPositionS2CPacket、EntityTrackerUpdate(仅他人)、KeepAlive、
     * EntityDamage、EntityVelocityUpdate(仅他人)、PlayerPositionLook。
     *
     * <p>1.20.1 没有 {@code ClientboundResetScorePacket}(对方那个版本才有), 其余逐条对应。
     * 末尾几条是我们原本就在放行的项 (玩家聊天 / 击杀消息 / 队伍 / 关容器 / 受击动画), 放行
     * 集合更大只会让更多包走原版路径, 不影响"延迟窗口只压可延迟包"的语义。
     */
    private boolean isAllowedPacket(Packet<?> packet) {
        return packet instanceof ClientboundSystemChatPacket                                    // ChatMessageS2CPacket
                || packet instanceof ClientboundSetDisplayObjectivePacket                       // ScoreboardDisplayS2CPacket
                || packet instanceof ClientboundSetEquipmentPacket                              // EntityEquipmentUpdateS2CPacket
                || packet instanceof ClientboundClearTitlesPacket                               // ClearTitleS2CPacket
                || packet instanceof ClientboundSetTitleTextPacket                              // TitleS2CPacket
                || packet instanceof ClientboundBossEventPacket                                 // BossBarS2CPacket
                || packet instanceof ClientboundRemoveEntitiesPacket                            // EntitiesDestroyS2CPacket
                || packet instanceof ClientboundSetHealthPacket                                 // HealthUpdateS2CPacket
                || packet instanceof ClientboundSetObjectivePacket                              // ScoreboardObjectiveUpdateS2CPacket
                || packet instanceof ClientboundSetScorePacket                                  // ScoreboardScoreUpdateS2CPacket
                || packet instanceof ClientboundSetSubtitleTextPacket                           // SubtitleS2CPacket
                || packet instanceof ClientboundSetTitlesAnimationPacket                        // TitleFadeS2CPacket
                || packet instanceof ClientboundSoundPacket                                     // PlaySoundS2CPacket
                || packet instanceof ClientboundSoundEntityPacket                               // PlaySoundFromEntityS2CPacket
                || packet instanceof ClientboundRotateHeadPacket                                // EntitySetHeadYawS2CPacket
                || packet instanceof ClientboundMoveEntityPacket                                // EntityS2CPacket
                || packet instanceof ClientboundTeleportEntityPacket                            // EntityPositionS2CPacket
                || packet instanceof ClientboundSetEntityDataPacket data                        // EntityTrackerUpdateS2CPacket
                        && (mc.player == null || data.id() != mc.player.getId())
                || packet instanceof ClientboundKeepAlivePacket                                 // KeepAliveS2CPacket
                || packet instanceof ClientboundDamageEventPacket                               // EntityDamageS2CPacket
                || packet instanceof ClientboundSetEntityMotionPacket motion                    // EntityVelocityUpdateS2CPacket
                        && (mc.player == null || motion.getId() != mc.player.getId())
                || packet instanceof ClientboundPlayerPositionPacket                            // PlayerPositionLookS2CPacket
                || packet instanceof ClientboundDisconnectPacket
                || packet instanceof ClientboundPlayerChatPacket
                || packet instanceof ClientboundPlayerCombatKillPacket
                || packet instanceof ClientboundSetPlayerTeamPacket
                || packet instanceof ClientboundContainerClosePacket
                || packet instanceof ClientboundHurtAnimationPacket
                || packet instanceof ClientboundAnimatePacket animation
                        && animation.getId() != mc.player.getId();
    }

    /**
     * 释放条件, 取对方 {@code Velocity.ready()} 且把 {@code Delay Till Ground} 一路写死为 true:
     * 没用物品 + 在疾跑 + 已经落地。
     *
     * <p>落地这一条不是可有可无的: 空中释放时服务端仍按"玩家在空中"处理这一包的位移补偿,
     * 击退等于白吞。落地那一拍释放, 位移补偿才落在脚踩实的这一 tick 上。
     */
    /**
     * 释放条件: 没用物品 + 这一拍确实是能结算的时机。
     *
     * <p>不再在这里要求 {@code isSprinting()}: 这段跑在 TickEvent, 比 SprintEvent 还早,
     * 读到的是"上一拍被攻击停掉之后"的疾跑状态 —— 落地那一拍 Sprint 还没把疾跑恢复回来,
     * 于是判定总是差一拍, 表现就是"落地了、也明明在疾跑, 却一直不放行"。疾跑的要求放在
     * 反打阶段 (attackReduce), 那时 SprintEvent 已经跑过, 状态是准的。
     *
     * <p>落地那一条只对自动反打有意义; 玩家手动出刀时他自己那一刀就是服务端重算击退的
     * 触发点, 空中释放才是对的 (跳打), 硬等落地只会让延迟包拖着, 变成 lag。
     */
    /**
     * 对齐 Southside 的 {@code Velocity.ready()}: 没用物品 + 在疾跑。
     *
     * <p>那边 {@code delayTillGround} 默认是 false, 也就是不要求落地; 之前把落地写死成
     * 必要条件, 结果空中被击退、或者落地那一拍 {@code onGround} 还没翻过来时窗口一直不放,
     * 表现就是"落地了也不释放"。
     */
    private boolean ready() {
        return mc.player != null
                && !mc.player.isUsingItem()
                && mc.player.isSprinting()
                && mc.player.onGround();
    }

    /**
     * 玩家这一拍是否会自己砍出去: 按住左键且攻击冷却已经好。
     *
     * <p>手动攻击与 NoXZ 的反打用的是同一个出口 ({@code ServerboundInteractPacket} /
     * {@code Player.attack}), 同一 tick 出现两个 ATTACK 服务端就判非法并修正位置。所以
     * 手动优先: NoXZ 看到玩家在打就让开, 而不是去抢 {@code attackAndLock}。
     */
    private boolean isManualAttacking() {
        return mc.options != null
                && mc.player != null
                && mc.options.keyAttack.isDown()
                && mc.player.getAttackStrengthScale(0.5f) >= 0.9f;
    }

    /**
     * 释放到"本拍边界"为止 —— 对齐对方 {@code releaseUntil(Objects::isNull, false)}。
     *
     * <p>队列里每拍被压入一个 {@link #TICK_MARKER}, 所以第一个标记之前的所有包, 都是"这一拍
     * 开始之前就已经到了、还在等释放"的包; 放到标记就停, 标记之后的包继续留在队列里。取不到
     * 标记 (队列里还没有 postTick 打点, 比如进窗口当拍就释放) 就退化成整队放行, 与对方在
     * channel 为空时的收尾行为一致。
     */
    private void releaseUntilKb() {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        Packet<?> packet;
        while ((packet = this.packetQueue.poll()) != null) {
            if (packet == TICK_MARKER) {
                break;
            }
            try {
                this.handleDelayed(packet);
            } catch (Exception exception) {
                this.packetQueue.clear();
                break;
            }
        }
    }

    private void flushQueue() {
        mc.execute(() -> {
            if (mc.getConnection() == null) {
                this.packetQueue.clear();
                return;
            }
            Packet<?> packet;
            while ((packet = this.packetQueue.poll()) != null) {
                if (packet == TICK_MARKER) {
                    continue;
                }
                try {
                    this.handleDelayed(packet);
                } catch (Exception exception) {
                    this.packetQueue.clear();
                    break;
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleDelayed(Packet<?> packet) {
        ((Packet<ClientGamePacketListener>) packet).handle(mc.getConnection());
    }

    private void restoreJumpKey() {
        if (!this.jumpKeyForced) {
            return;
        }
        this.jumpKeyForced = false;
        if (mc.player == null || Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            return;
        }
        boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
        mc.options.keyJump.setDown(down);
    }

    private void resetAll() {
        this.resetAll(true);
    }

    /**
     * @param releaseQueued true  = 正常收尾, 队列里的包还要按顺序 handle 掉;
     *                      false = 转世界/重连, 队列属于旧连接, 直接丢弃。
     */
    private void resetAll(boolean releaseQueued) {
        if (releaseQueued) {
            this.flushQueue();
        } else {
            this.packetQueue.clear();
        }
        inDelayWindow = false;
        compensating = false;
        this.positionCorrection = false;
        this.delayTicks = 0;
        counterRemaining = 0;
        this.noAimTicks = 0;
        this.noSprintTicks = 0;
        this.counterTicks = 0;
        this.compensateUntilMs = -1L;
        this.jumpTicks = 0;
        this.restoreJumpKey();
        this.entityPositions.clear();
        this.target = null;
        handlingVelocity = false;
    }

    /** 切换世界 / 重连: 旧连接上的包一个都不能再 handle, 直接丢弃并复位。 */
    private void abortAll() {
        this.resetAll(false);
    }

    private void debug(String message) {
        if (AntiKB.INSTANCE.debugLog.getValue()) {
            ChatUtil.print(message);
        }
    }

    static {
        handlingVelocity = false;
        compensating = false;
        inDelayWindow = false;
        counterRemaining = 0;
    }
}
