package client.nilore.modules.impl.combat.antikb;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
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

    private final ConcurrentLinkedQueue<Packet<?>> packetQueue = new ConcurrentLinkedQueue<>();
    private final Map<Entity, Vec3> entityPositions = new HashMap<>();

    /**
     * 每拍压在队尾的出站 KeepAlive, 只当"这一拍"的分隔符, 永远不 handle。
     *
     * <p>有了它, 释放就能精确停在"本拍之前到达的包"这个边界上, 而不是把队列里所有东西
     * 一次倒出去。Grim 的 Simulation 是按拍的位移差记账的, 一次性倒出几十个包 = 客户端位置
     * 在那一拍瞬移, 下一拍就被记一笔。
     */
    private static final ServerboundKeepAlivePacket TICK_MARKER = new ServerboundKeepAlivePacket(-1337L);

    /** 释放阶段每拍最多放回的包数。 */
    private static final int RELEASE_PER_TICK = 3;

    /** 已经开窗放包, 剩下的积压正在按拍排空。 */
    private boolean releasing;

    public static boolean compensating;
    public static boolean inDelayWindow;
    public static int counterRemaining;

    private boolean positionCorrection;
    private int delayTicks;
    private int noAimTicks;
    private int noSprintTicks;
    private int jumpTicks;
    private boolean jumpKeyForced;
    private float barProgress;
    private float barAlpha;
    private long lastFrameNanos;
    private long compensateUntilMs = -1L;
    private Entity target;

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

    /**
     * 不接管疾跑。
     *
     * <p>当前 Sprint 模块是"每拍现算"的基线实现: 它在 SprintEvent 里问一次
     * {@link KillAura#canCritUnderVelocity()}, 成立就松键掉疾跑 (为了下一刀暴击), 不成立就
     * 立刻把疾跑还给玩家。那套判定已经把 NoXZ 算进去了 —— 窗口攥着输入
     * ({@code isInDelayWindow() && shouldKeepSprintInDelayWindow()}) 和反打
     * ({@code isCountering()}) 两种情况它都返回 false, 也就是窗口/反打期间由 Sprint 维持
     * 疾跑, 窗口交出控制权之后才轮到它掉疾跑。
     *
     * <p>所以这里再写一遍"什么时候该疾跑"只会和 Sprint 抢同一拍: 两边一个 setSprinting(false)
     * 一个 setSprinting(true), 谁后跑谁赢, 顺序随模块启用顺序漂移。保持空实现, 单一真相在
     * Sprint / KillAura 那边。
     */
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
        if (attacker != null && attacker != mc.player && attacker.isAlive() && this.target == null) {
            this.target = attacker;
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
        Packet<ClientGamePacketListener> packet = receivePacketEvent.getPacket();

        if (packet instanceof ClientboundPlayerPositionPacket) {
            this.positionCorrection = true;
            return;
        }

        if (this.shouldNotEngage()) {
            return;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()) {
            if (!inDelayWindow) {
                inDelayWindow = true;
                this.delayTicks = 0;
                handlingVelocity = true;
            }
            receivePacketEvent.setCancelled(true);
            this.packetQueue.add(packet);
        }

        if (this.isAllowedPacket(packet)) {
            return;
        }
        // 与 deobf 的 VelocityModule 一致: 队列只在窗口打开期间攒包, 释放当拍窗口就关掉,
        // 之后不再拦截 —— 反打阶段没有额外的压包相位, 否则压着的位移包会在释放时集中倒出,
        // 那一段位移和 Grim 的预测差好几拍, 直接记 Simulation。
        if (!inDelayWindow) {
            return;
        }

        // 仅在压包期间追踪: 这一包的位移还没有被 apply, 实体仍在旧位置, 增量叠上去才是
        // 服务端侧的真实坐标。放行时实体位置由 handle 自己更新, 不需要这里再算一遍。
        this.trackEntityPosition(packet);

        receivePacketEvent.setCancelled(true);
        this.packetQueue.add(packet);
    }

    /**
     * 记下实体在服务端侧的最新位置。
     *
     * <p>{@code ClientboundMoveEntityPacket} 只带增量, 必须叠在上一次的基准上; 传送包给的是
     * 绝对值。调用点限定在"这一包已经被压住、还没 apply"的时刻, 基准才等价于服务端位置。
     */
    private void trackEntityPosition(Packet<?> packet) {
        if (mc.level == null) {
            return;
        }
        if (packet instanceof ClientboundMoveEntityPacket move) {
            Entity entity = move.getEntity(mc.level);
            if (entity != null && move.hasPosition()) {
                Vec3 base = this.entityPositions.getOrDefault(entity,
                        new Vec3(entity.getX(), entity.getY(), entity.getZ()));
                this.entityPositions.put(entity, base.add(
                        move.getXa() / 4096.0,
                        move.getYa() / 4096.0,
                        move.getZa() / 4096.0
                ));
            }
        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            Entity entity = mc.level.getEntity(teleport.getId());
            if (entity != null) {
                this.entityPositions.put(entity,
                        new Vec3(teleport.getX(), teleport.getY(), teleport.getZ()));
            }
        }
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        // 每拍在队尾压一个边界标记。releaseUntilKb 只放到这个标记为止, 也就是"本拍之前
        // 到达的包", 剩下的继续攒到下一拍, 客户端的位置是一次一小段地补回去的。
        if (inDelayWindow) {
            this.packetQueue.add(TICK_MARKER);
        }

        this.target = AntiKB.INSTANCE.requireKillAura.getValue()
                ? KillAura.target
                : this.crosshairTarget();

        if (this.compensateUntilMs != -1L
                && (System.currentTimeMillis() >= this.compensateUntilMs || mc.options.keyUp.isDown())) {
            compensating = false;
            this.compensateUntilMs = -1L;
        }

        if (this.shouldNotEngage() || this.noAimTicks >= 3) {
            this.resetAll();
            return;
        }

        // 已经开窗放过的包: 剩下的一拍放几个, 直到排空。
        if (this.releasing) {
            this.drainQueue(RELEASE_PER_TICK);
            if (this.packetQueue.isEmpty()) {
                this.releasing = false;
            }
        }

        if (inDelayWindow) {
            compensating = true;
            counterRemaining = 0;
            if (this.delayTicks >= AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()) {
                this.resetAll();
                return;
            }
            ++this.delayTicks;
            if (this.canRelease()) {
                counterRemaining = AntiKB.INSTANCE.attackAmount.getValue().intValue();
                this.noAimTicks = 0;
                inDelayWindow = false;
                handlingVelocity = false;
                // 只放到自己的击退包那一拍, marker 之后的残包交给上面的 drainQueue 按拍排空。
                this.releaseUntilKb();
                this.releasing = true;
                if (AntiKB.INSTANCE.jumpReset.getValue()) {
                    this.jumpTicks = 1;
                }
            }
        }

        if (counterRemaining > 0) {
            if (!this.isAimingAtTarget()) {
                ++this.noAimTicks;
                this.debug("Failed (RayCast)");
                return;
            }
            // 这里必须读"当前"疾跑状态, 不能换成"本拍将要恢复到疾跑"的预测:
            // ATTACK 包就在下面这一行发出去, 它携带的是此刻客户端的疾跑标记, 服务端按
            // sprint-hit 结算的也是这个值。预测成 true 但实际还在停疾跑, 打出来的就是
            // 普通命中, 0.6 自减速叠不上去 —— 表现为"反打打了但不减击退"。
            //
            // 掉疾跑只跳过这一刀, 不收窗: 对齐 deobf 的 {@code if (!isSprinting()) return;}
            // —— 那边不数拍数。数到 3 就 resetAll 会把窗口里压着的包一次性 flush 出去,
            // 客户端位置在那一拍直接跳一段, 就是 Simulation 的 VL 来源。
            if (!mc.player.isSprinting()) {
                this.debug("Failed (Sprint)");
                return;
            }
            this.attackReduce(this.target);
            --counterRemaining;
        } else if (compensating) {
            this.compensateUntilMs = System.currentTimeMillis() + 10L;
        }
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
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.resetAll();
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

    private void attackReduce(Entity entity) {
        if (entity == null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (!KillAura.attackAndLock()) {
            return;
        }
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(new EntityRemoveEvent(false, entity));
        }
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, mc.player.isShiftKeyDown()));
        mc.player.swing(InteractionHand.MAIN_HAND);
        // 原版 {@code Player.attack} 的疾跑攻击分支会 setSprinting(false); 这里直接构造
        // interact 包绕过了它, 所以必须自己补上。否则服务端按"被攻击停疾跑"结算, 客户端
        // 还在疾跑速度上, 两边每 tick 差一个 .029 级的 Simulation offset —— 一直差到有人
        // 手动松键为止。
        if (mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }
        Vec3 movement = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(movement.x * 0.6, movement.y, movement.z * 0.6);
    }

    private Entity crosshairTarget() {
        return mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
    }

    private boolean isAimingAtTarget() {
        if (this.target == null || mc.player == null) {
            return false;
        }
        if (!AntiKB.INSTANCE.requireKillAura.getValue()) {
            return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == this.target;
        }
        return RotationUtil.isLookingAt(this.target,
                Math.max(3.0, RotationUtil.getReachLimit()), 0.1f);
    }

    /**
     * 释放闸门: 射线锁着 + 落地 + 疾跑。
     *
     * <p>疾跑这里问的是 {@link #sprintThisTick()}, 不是 {@code mc.player.isSprinting()} —— 原因见
     * 那个方法。落地那一条不能省: 空中释放时服务端仍按"玩家在空中"结算这一包的位移补偿,
     * 击退等于白吞, 必须等脚踩实的那一拍。
     */
    private boolean canRelease() {
        if (mc.player == null || !mc.player.onGround()) {
            return false;
        }
        return this.isAimingAtTarget() && this.sprintThisTick();
    }

    /**
     * 本拍客户端实际会不会处于疾跑: 已疾跑, 或者这一拍 Sprint 模块就会把它恢复回来。
     *
     * <p>顺序问题: {@code TickEvent} (本方法的调用点) 在 {@code Minecraft.tick()} 顶部发出,
     * 而 {@code SprintEvent} 要等 {@code LocalPlayer.tick()} 走到 {@code super.tick()} 之前才发。
     * 落地那一拍读 {@code isSprinting()} 读到的是"上一拍空中被停掉之后"的值, 于是明明落地、
     * 明明在疾跑、Sprint 这一拍马上就会把疾跑还回来, 这里却始终不放行, 表现为"落地了也不
     * 释放, 一直拖到超时"。
     *
     * <p>Sprint 是每拍现算的基线实现: 只要它这一拍不 hold (Stuck / 暴击门成立), 就会
     * {@code keySprint.setDown(true)} 并在有前进输入时 {@code setSprinting(true)}。所以
     * "已疾跑 || (键没松 && 有前进输入 && Sprint 不 hold)" 就是这一拍结束时的疾跑状态。
     *
     * <p>反打阶段不用这个预测: 那里发的是 ATTACK 包, 要的是发包瞬间的真实状态。
     */
    private boolean sprintThisTick() {
        if (mc.player == null) {
            return false;
        }
        if (mc.player.isSprinting()) {
            return true;
        }
        // Sprint 这一拍会不会 hold: 与 Sprint.shouldHoldSprint() 同一组条件。
        boolean sprintHeld = Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()
                || KillAura.INSTANCE != null && KillAura.INSTANCE.canCritUnderVelocity();
        if (sprintHeld) {
            return false;
        }
        boolean hasForwardInput = mc.player.input != null && mc.player.input.forwardImpulse > 0.0f;
        return mc.options.keySprint.isDown() && hasForwardInput;
    }

    private boolean shouldNotEngage() {
        if (mc.player == null || mc.getConnection() == null) {
            return true;
        }
        if (mc.player.isUsingItem() || mc.player.isInLava() || mc.player.isInWater()) {
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

    private boolean isAllowedPacket(Packet<?> packet) {
        return packet instanceof ClientboundSetEntityMotionPacket
                || packet instanceof ClientboundSetHealthPacket
                || packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundSoundPacket
                || packet instanceof ClientboundPlayerChatPacket
                || packet instanceof ClientboundPlayerCombatKillPacket
                || packet instanceof ClientboundContainerClosePacket
                || packet instanceof ClientboundHurtAnimationPacket
                || packet instanceof ClientboundSetTitleTextPacket
                || packet instanceof ClientboundSetPlayerTeamPacket
                || packet instanceof ClientboundSystemChatPacket
                || packet instanceof ClientboundDisconnectPacket
                // 对账包, 压不得。Grim 的 Post 是这么判的: 收到 flying 时把 sentFlying 置位,
                // 之后收到的动作包(ATTACK / INTERACT_ENTITY / ANIMATION ...)都进 post 队列,
                // 等收到 transaction(pong) 时若队列非空就记一条。压住 KeepAlive / Ping 等于
                // 把客户端的 pong 往后拖, 服务端那边 sentFlying 一直是 true —— 这期间反打的
                // ATTACK 就全被算进 post, pong 一到就报 "Post"。
                || packet instanceof ClientboundKeepAlivePacket
                || packet instanceof ClientboundPingPacket
                // 白名单其余部分与 deobf 的 {@code һсxјаs} 一致: 实体移动(EntityS2C)、传送
                // (EntityPositionS2C)、头部朝向、他人 tracker update、伤害事件都在压包范围内,
                // 只有上面两个对账包必须放行 —— 压住它们会让 pong 晚发, 反打的 ATTACK 落进
                // 服务端的 post 窗口, 就是 Grim 的 Post。
                || packet instanceof ClientboundAnimatePacket animation
                && animation.getId() != mc.player.getId();
    }

    /**
     * 按拍把队列放回, 每拍最多 {@code budget} 个包。
     *
     * <p>一次性清空会在同一拍往主线程塞几十个 handle: 服务端看到的是"瞬间位移 + 瞬时补齐",
     * 客户端下一拍的位移和 Grim 的预测差出好几拍的量, 记的就是 Simulation。摊到多拍之后
     * 每拍只补一点, 位移差始终压在一个小范围里。
     */
    private void drainQueue(int budget) {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        mc.execute(() -> {
            if (mc.getConnection() == null) {
                this.packetQueue.clear();
                return;
            }
            for (int i = 0; i < budget; i++) {
                Packet<?> packet = this.packetQueue.poll();
                if (packet == null) {
                    break;
                }
                if (packet == TICK_MARKER) {
                    continue;
                }
                // 反打已经出手, 服务端会按那一刀重算击退另发一包; 队列里这条旧击退再 apply
                // 只会和新击退叠在一起 —— 表现就是"释放之后 KB 异常大"。
                if (this.isOwnKnockback(packet)) {
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

    /**
     * 释放到"自己的击退包所在那一拍"为止。
     *
     * <p>{@link #TICK_MARKER} 只当分隔符: 遇到 marker 时若还没命中击退包就跳过继续放,
     * 命中之后的下一个 marker 才 break。这样放出去的是"截止本拍、且包含这次击退"的那一段,
     * 后面的残包留给 {@link #drainQueue}。
     */
    private void releaseUntilKb() {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        Packet<?> packet;
        boolean stopRelease = false;
        while ((packet = this.packetQueue.poll()) != null) {
            if (packet == TICK_MARKER) {
                if (stopRelease) {
                    break;
                }
                continue;
            }
            try {
                this.handleDelayed(packet);
            } catch (Exception exception) {
                this.packetQueue.clear();
                break;
            }
            if (this.isOwnKnockback(packet)) {
                stopRelease = true;
            }
        }
    }

    /** 这一包是不是自己的击退 (EntityVelocityUpdate 且 id 是自己)。 */
    private boolean isOwnKnockback(Packet<?> packet) {
        return packet instanceof ClientboundSetEntityMotionPacket motion
                && mc.player != null
                && motion.getId() == mc.player.getId();
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
        // 收尾也不整队倒: 只放到本拍边界, 残包（含 marker）直接丢弃 —— 那些包对应的
        // 时刻已经过去了, 补 apply 只会让位置再跳一次。
        this.releaseUntilKb();
        this.packetQueue.clear();
        inDelayWindow = false;
        compensating = false;
        this.releasing = false;
        this.positionCorrection = false;
        this.delayTicks = 0;
        counterRemaining = 0;
        this.noAimTicks = 0;
        this.noSprintTicks = 0;
        this.compensateUntilMs = -1L;
        this.jumpTicks = 0;
        this.restoreJumpKey();
        this.entityPositions.clear();
        this.target = null;
        handlingVelocity = false;
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
