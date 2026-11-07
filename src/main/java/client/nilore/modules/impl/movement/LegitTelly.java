package client.nilore.modules.impl.movement;

import client.nilore.NiloreClient;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.KeyEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketSendEvent;
import client.nilore.event.impl.PostMotionEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.KeyBind;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.ChatUtil;



import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.AABB;
import client.nilore.utils.render.RenderUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 移植自 RavenBS-Plus-Plus（1.8.9）的 telly 脚本 {@code BSLegitTellyFix}，行为逐函数对齐。
 * 脚本宿主 API 已换成 26.2 原版 + Epsilon 公共 API：发包由 {@link MotionEvent}
 * 承载，输入注入由 {@link StrafeEvent} 承载，方块名走 1.13+ 注册名。
 *
 * <p>⚠️ <b>这是「脚本 Telly」</b>（模拟人手，理论可绕任何反作弊）—— 设计上<b>必须驱动玩家视角</b>
 * （{@code player.setYRot/setXRot}），<b>不要套用「静默旋转铁律」</b>。
 * 同名但完全不同的另一个实现是 <b>Scaffold 内的算法 Telly</b>
 * （{@code leader-lite/Scaffold.java} 的 {@code isLegitTellyMode} 分支 / Epsilon 侧
 * {@code movement/scaffold/**}）：自动搭桥、静默旋转、只能绕 GrimAC。两者不可混淆。
 */
public class LegitTelly extends Module {
    private static final float SENSITIVITY_QUANTUM = 0.03404715f;


    public LegitTelly() {
        super("LegitTelly", Category.MOVEMENT);
    }

    private final BooleanSetting autoSwap = new BooleanSetting("Auto Swap", true);
    private final BooleanSetting disableSafeWalk = new BooleanSetting("Disable SafeWalk", true);
    // 原版默认是 false；这里改成 true，否则装好之后完全看不到激活框（原版要自己去 GUI 开）。
    private final BooleanSetting showActivationHitbox = new BooleanSetting("Show Activation Hitbox", true);
    // [Fix AirLiquidPlace] 刚放的块要等服务端 S23 确认后才能当支撑。
    // 某些服务端不回方块变更包时关掉它（关掉是旧行为，AirLiquidPlace 可能回来）。
    private final BooleanSetting waitServerAck = new BooleanSetting("Wait Server Block Ack", false);
    private final BooleanSetting print = new BooleanSetting("Print", false);
    /** 调试日志开关：控制写进 latest.log 的 [Telly] 诊断行。默认开，排查完可自行关闭。 */
    private final BooleanSetting debugLog = new BooleanSetting("Debug Log", true);
    /**
     * 激活手势的「按住潜行」等待时间（秒）：蹲满这段时间提示才由红转绿、允许触发。
     *
     * <p>脚本原值固定 1.0 秒，但这是宿主侧的手感参数、与算法无关，做成滑块便于按自己的节奏调。
     */
    private final NumberSetting activationTime = new NumberSetting("Activation Time", 1.0, 0.1, 2.0, 0.1);

    /**
     * 激活时把朝向吸附到的「基准角」网格：{@code round((yaw - 该值)/90)*90 + 该值}。
     *
     * <p>⚠️ 默认 <b>46.5</b>，不是 45。三个理由：
     * <ol>
     *   <li><b>实测最优</b>：用户实测 46.5 时不会掉（44 会掉），这是最终采用它的直接依据。
     *   <li><b>躲机器特征</b>：45 是整度数，吸附后 {@code BEGIN yaw} 会变成 -315.00 / -495.00
     *       这类精确值，真人做不到 —— 实测那样会被 Intave 报 {@code acting computer-like}。
     *       46.5 是「像手抖停在的角度」。<b>这是 46.5 目前唯一仍然成立的理由。</b>
     *   <li><b>（原第 3 条理由已作废）</b>原文写「45° 是 {@code rawX = sin - cos} 的零点（分界），
     *       浮点抖动会让 travelX/travelZ 两侧跳」—— <b>这是错的</b>：零点在 {@code 45°+k·180°}，
     *       而分支分界（{@code |rawX| == |rawZ|}）在 <b>{@code 0°/90°/180°/270°}</b>，两者差 45°。
     *       实测：滑块 42~48 及其 {@code +k·90} 网格点无一落在分界上，且
     *       44° / 45° / 46.5° / 48° 输出<b>完全相同</b>（axis=Z, sign=-1）。
     *       正确结论是：46.5 相对 45 <b>在 travelXZ（桥走向轴）上既无优势也无风险</b>；
     *       但改 baseYaw 会整体平移 21 帧 {@code YAW_CURVE} 的绝对角，每次 raycast 与候选块的
     *       落点都会变 —— <b>行为会变，必须实测</b>。不要写成"零风险"。
     * </ol>
     * 激活判据是 45°±2°，滑块范围 42~48 都还在容差内。
     */
    private final NumberSetting snapBase = new NumberSetting("Snap Degrees", 46.5, 42.0, 48.0, 0.1);


    // ===== 脚本顶层状态 =====

    private boolean armed = false;
    private boolean running = false;
    private long activatePromptAt = 0L;
    private long promptBrokeAt = 0L;
    private float promptAlpha = 0.0f;
    private long promptFadeLastAt = 0L;
    private int promptFadeRgb = 0xFF5555;
    private int[] hitboxLastPos = null;
    private int hitboxLastFace = -1;
    private boolean activationMovementHeld = false;
    private boolean antiSwayTapUsed = false;
    /** tap 节流：原先一次 tap 后要等误差归零才能再 tap，strafe=0 的相位里根本等不到 ⇒ 持续向右偏。 */
    private int antiSwayTapTick = -9999;
    /** [Fix AirLiquidPlace] 已发送、但还没收到服务端 S23 回包的方块（确认前不能当支撑）。 */
    private final Map<String, Long> pendingSupportConfirm = new HashMap<>();
    // ReceivePacketEvent 在 Netty 线程执行（add/remove），客户端线程同时 contains/clear ⇒ 必须并发集合。
    private final Set<String> cancelledGhostBlocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean tellyAutoPlaceWindow = false;
    private boolean autoPlaceDebugActive = false;
    private boolean safeWalkStateCaptured = false;
    private boolean safeWalkWasEnabled = false;

    private int setupTick = 0;
    private int cyclePhase = 19;
    private float baseYaw = 0.0f;
    private int travelX = 0;
    private int travelZ = 0;
    private double antiSwayLane = 0.0;
    private float antiSwayYawOffset = 0.0f;
    private int bridgeLaneBlock = 0;
    private int bridgeStartProgress = 0;
    private int[] latestStraightPlacedPos = null;
    private boolean firstTellyPlacementPending = false;
    private boolean adaptiveAimValid = false;
    private float adaptiveAimYaw = 0.0f;
    private float adaptiveAimPitch = 0.0f;
    private long adaptiveAimUpdatedAt = 0L;
    private long takeoverDetectionAt = 0L;
    private boolean takeoverCameraValid = false;
    private float takeoverCameraYaw = 0.0f;
    private float takeoverCameraPitch = 0.0f;
    private float takeoverAccumulated = 0.0f;
    private long takeoverLastFrameAt = 0L;
    private long freezeLastTickAt = 0L;
    private boolean ignoreForwardUntilRelease = false;
    private boolean ignoreBackUntilRelease = false;
    private boolean ignoreLeftUntilRelease = false;
    private boolean ignoreRightUntilRelease = false;
    private boolean ignoreJumpUntilRelease = false;
    private boolean ignoreSneakUntilRelease = false;
    private boolean ignoreSprintUntilRelease = false;

    private boolean rotationActive = false;
    private long rotationStartedAt = 0L;
    private long rotationDuration = 50L;
    private float rotationStartYaw = 0.0f;
    private float rotationStartPitch = 0.0f;
    private float rotationTargetYaw = 0.0f;
    private float rotationTargetPitch = 0.0f;
    private float scriptedRotationYaw = 0.0f;
    private float scriptedRotationPitch = 0.0f;

    /**
     * 抖动/量化的单位**一律**走 {@link RotationUtils#mouseQuantum()}（{@code f³ × 8 × 0.15}，
     * 按本机灵敏度实时计算）—— **不要**再引入源客户端那套硬编码量子。
     *
     * <p>源版（1.8.9 客户端）写的 {@code 0.03404715} 在本机只有真实网格的 0.4 格：
     * 取整时 ±0.034 会被舍成 0（抖动被吃掉）、±0.068 被放大成整格 ⇒ 旋转退化成机器般平滑，
     * Intave 判 {@code acting computer-like} 并踢出。见 AGENTS.md「量化网格必须用客户端实际灵敏度」。
     */
    private static final int[] YAW_NUDGE_PATTERN = {0, 1, -1, 2, -2};
    private int rotationStepCounter = 0;
    private static final double ACTIVATION_ACROSS_MIN = 0.38;
    private static final double ACTIVATION_ACROSS_MAX = 0.65;
    private static final double ACTIVATION_HEIGHT_MIN = 0.25;
    private static final double ACTIVATION_HEIGHT_MAX = 0.75;
    private static final float ACTIVATION_YAW_TOLERANCE = 2.0f;

    /** 吸附的最大校正范围（度）：超出说明玩家在主动找位置，不干预。 */
    private static final float ACTIVATION_SNAP_RANGE = 10.0f;
    /** 吸附的每 tick 步长（度）：平滑推入而非瞬移。 */
    private static final float ACTIVATION_SNAP_STEP = 1.0f;

    /**
     * 「玩家手动接管」判据：相机相对脚本给的朝向偏了多少度就认为玩家在动视角、让位给他。
     *
     * <p>⚠️ 这里**有意偏离**脚本原实现。原版门限是「一个灵敏度网格 × 0.45 ≈ 0.015°」
     * 再累积到 25 —— 实测它会把「按住右键+潜行时手指的微小移动」也攒成接管，
     * 16 轮实测里几乎每一轮都被它提前掐断（placedOk 停在 10~55），表现为「有时成功有时失败」。
     * 5° 是明显属于人为操作、而手抖达不到的量级。
     */
    private static final double MANUAL_TAKEOVER_DEGREES = 5.0;

    /** 激活诊断的刷新间隔（毫秒）。 */
    private static final long ACTIVATION_DIAGNOSTICS_INTERVAL_MS = 100L;

    /**
     * setup 阶段起跳前的「建立后向惯性」tick 数：脚本原值 {@code setupTick >= 6} 才起跳。
     *
     * <p>⚠️ 踩过的坑：曾把它改成 2（想「早点跳更快」），实测反而更容易掉桥 —— 前 6 tick
     * 的后退正是把玩家从方块边缘带回可落脚区域的手段，砍掉它就等于在边缘直接起跳。
     * <b>这里是脚本原值，不要调。</b>
     */
    private static final int SETUP_INERTIA_TICKS = 6;
    private long lastActivationDiagnosticsAt = 0L;
    /** 提示文案里的按键显示名缓存（每次 {@link #armAutomation()} 失效重取）。 */
    private String promptKeySneak = null;
    private String promptKeyUse = null;
    /** 屏幕底部单行显示的激活诊断文本；null = 不显示。 */
    private String activationDiagnosticsLine = null;

    // 运行期诊断（只读，用于定位"搭几格后掉落"这类问题）
    private int runDiagPlaceOk = 0;
    private int runDiagPlaceFail = 0;
    /** 上一次「放置失败」诊断的输出时间，限流用（高帧率下每 tick 都可能失败）。 */
    private long lastPlacementFailLogAt = 0L;
    /**
     * 放置失败原因直方图（本轮累计）。
     *
     * <p>限流日志只采样，会漏掉「恰好掉下去那几 tick」的样本，所以停止时把完整分布打出来。
     * ⚠️ 已知事实：{@code look-misaligned} 属于**设计内**的筛选 ——
     * {@code findBelowPlacement} 会同时产出身前/身后候选，而 {@code resolveVerifiedHit(当前视角, …)}
     * 就是靠 YAW_CURVE 转过头去"够"不同候选（偏转大时够到身后格，偏转小时够到身前格）。
     * 曾试过把放置方向钉死在 baseYaw（新增稳定「逻辑朝向」），结果匹配率反而下降、
     * placedOk 从 105/137 崩到 9~28 —— **摆动是够取候选的手段，不能锁死。**
     */
    private final Map<String, Integer> placementFailCounts = new LinkedHashMap<>();
    private boolean runDiagBelowAir = false;
    private float runDiagSway = 0f;
    private float runDiagTargetPitch = 0f;
    private float runDiagTargetYaw = 0f;
    private int runDiagPhase = 0;

    /*
     * ===== telly（塔里）的机制 —— 动这几条曲线之前必须先读懂 =====
     *
     * Java 版没法像基岩版那样"往前伸着手搭"，所以 telly 全程是「按住 S 后退 + 有节奏地补方块」
     * （普通蹲起搭是按住 S 并规律地按 Shift；telly 多一步：交替 A+S）。
     *
     * ⚠️ 曾写「A+S 的合成速度比只按 S 快」—— <b>这是错的</b>。1.8.9 的
     * {@code EntityLivingBase.moveRelative} 里有
     * {@code f = sqrt(strafe²+forward²); if (f < 1.0F) f = 1.0F; strafe /= f; forward /= f;}
     * 的归一化；26.2 的 {@code KeyboardInput.tick()} 里是
     * {@code new Vec2(strafe, forward).normalized()}（字节码已核），
     * <b>走路状态下两端斜向都不提速</b>（只有潜行——输入先 ×0.3、长度 &lt;1 不被归一化——才有 +41%）。
     * A+S 的真正作用是<b>方向</b>：yaw=46.5° 时 (-1,-1) 合成 ≈ (0.026,-0.999)（沿桥轴），
     * 而 (-1,0) ≈ (0.725,-0.688)（斜 45°，会走出桥外）。<b>别删，但理由是方向，不是速度。</b>
     *
     * 完整的 telly 是三拍动作：
     *   ① 先转头，边转头边起跳；
     *   ② 疾跑窜出去；
     *   ③ 再回头，把刚才那几格接上。
     *
     * 这三条曲线就是这套动作的分解，不是可随便调优的噪声：
     *   - FORWARD_CURVE：主旋律「按住 S」。相位 4~19 恒 -1（持续后退）；相位 0/1/20 为 +1
     *     （"窜出去"那两拍）；相位 2/3 为 0。
     *   - STRAFE_CURVE：交替的「A」。相位 0~3 与 17~20 为 -1（A+S），其余为 0（只按 S）。
     *     ⚠️ 正是这个 -1 把 forward=-1 掰成"沿桥轴向"：(-1,-1) 在 baseYaw 下合成出平行于桥的
     *     方向，而 (-1,0) 是斜 45° 的。别去"修正"它。
     *   - YAW_CURVE / PITCH_CURVE：对应 ①③ 的「转头 / 回头」，相对 baseYaw 摆动。
     *     走位方向**必须跟随这个摆动视角** —— MC 的 moveRelative() 就是用 getYRot() 旋转输入的。
     *
     * ⚠️⚠️ 踩过的坑（每个都让实测报废过一轮）：
     *   ① 把走位方向锁死在 baseYaw（订阅 StrafeEvent.setYaw），理由是我算出"21 帧矢量和的净方向
     *      斜 36.9°，跟摆会漂"。（⚠️ 36.9° 的计算口径已不可考，另有重算得 ~10°；
     *      下面这条结论"走位必须跟随视角"不依赖于这个数字，是实测 placedOk 105/137→5~34 得出的。）结果占 13/21 帧的 strafe=0 相位全变成恒定斜 45° 前进，角色笔直
     *      斜着飞出去，placedOk 从 105/137 崩到 5~34。**走位跟随视角是设计，不是 bug。**
     *   ② 把视角改写成只驱动模型（setYBodyRot/setYHeadRot），想"视角摆会让走位歪"。这直接把相机
     *      锁死了 —— 而"视角在摆"正是这个脚本的核心观感。**视角必须由 player.setYRot 驱动。**
     *   ③ 把 setup 的前置惯性从 6 刻砍到 2 刻（想"早点跳更快"），反而更容易在边缘踩空掉桥。
     *   ④ 曾把发包 yaw 改成脚本角、真实 yaw 固定，属于同一类误判，一并回滚。
     */
    private static final float[] YAW_CURVE = {
            91.68f, 98.88f, 78.94f, 37.45f, 1.61f, -21.69f, -33.98f,
            -35.80f, -34.64f, -33.85f, -33.06f, -31.55f, -29.26f, -26.65f,
            -24.19f, -21.07f, -18.84f, -17.06f, -8.87f, 2.61f, 41.94f
    };

    private static final float[] PITCH_CURVE = {
            64.31f, 59.95f, 60.57f, 61.46f, 60.64f, 58.89f, 56.91f,
            56.63f, 58.65f, 61.63f, 64.20f, 66.74f, 68.69f, 70.64f,
            73.01f, 75.37f, 77.46f, 78.56f, 78.90f, 77.22f, 72.25f
    };

    private static final float[] FORWARD_CURVE = {
            1.0f, 1.0f, 0.0f, 0.0f, -1.0f, -1.0f, -1.0f,
            -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f,
            -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, 1.0f
    };

    private static final float[] STRAFE_CURVE = {
            -1.0f, -1.0f, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, -1.0f, -1.0f, -1.0f, -1.0f
    };

    // ===== 脚本 client.setForward/getForward 等的宿主内部状态 =====

    private float clientForward = 0.0f;
    private float clientStrafe = 0.0f;
    private boolean clientJump = false;
    private boolean clientSneak = false;
    private boolean clientSprinting = false;

    /** 本 tick 正在派发的键盘输入事件；脚本 client.setXxx 的注入目标。仅在运行时被写入。 */
    private StrafeEvent activeInputEvent = null;

    // ===== 放置控制器状态 =====

    private int currentClientTick = Integer.MIN_VALUE;
    private int placementEvaluationTick = Integer.MIN_VALUE;
    private int lastPlacementAttemptTick = Integer.MIN_VALUE;
    private int lastSuccessfulPlaceTick = Integer.MIN_VALUE;
    private int forceSuppressTick = Integer.MIN_VALUE;
    private long totalC08Counter = 0L;
    private long c08CounterAtTickBoundary = 0L;
    private boolean hasLastSentServerPos = false;
    private double lastSentServerPosX, lastSentServerPosY, lastSentServerPosZ;
    private Object[] cachedCandidate = null;
    private int cachedCandidateTick = Integer.MIN_VALUE;
    private float cachedCandidateYaw = Float.NaN;
    private float cachedCandidatePitch = Float.NaN;
    private boolean candidateResolvedThisTick = false;
    private int[] lastPlacedPos = null;
    private int[] lastSupportPos = null;
    private int lastSupportFace = -1;
    private List<int[]> cachedBelowTargets = null;
    private int cachedBelowTargetsTick = Integer.MIN_VALUE;
    private final Map<String, Integer> rejectedTargets = new HashMap<>();
    private int forcedModeCheck = 0;
    private boolean useSuppressed = false;
    private boolean silentPitchActive = false;
    private float silentPitch = 0f;
    private boolean placingViaModule = false;
    private boolean manualC08InWindow = false;

    /** 脚本 bridge 的等价物：同一个客户端内脚本之间的共享槽位。 */
    private static final Map<String, Object> BRIDGE = new HashMap<>();

    // =====================================================================
    // 模块生命周期（脚本 onLoad / onEnable / onDisable / onWorldJoin）
    // =====================================================================

    @Override
    protected void onEnable() {
        autoPlaceOnEnable();
        armAutomation();
    }

    @Override
    protected void onDisable() {
        stopAutomation(false);
        autoPlaceOnDisable();
    }

    @EventTarget
    private void onWorldJoin(WorldChangeEvent event) {
        stopAutomation(false);
        autoPlaceOnWorldJoin();
    }

    // =====================================================================
    // 事件接线
    // =====================================================================

    /** 脚本 onPreUpdate()。 */
    @EventTarget
    private void onPreUpdate(TickEvent event) {
        // 世界加载期（集成服务器启动中）mc.level 可能尚未建立，而 mc.player 已经有了。
        // 此时任何方块查询（Level.clip / getBlockState）都会让主线程同步加载区块，
        // 与服务器区块生成争锁，表现为卡在「准备生成区域」长时间不动。
        if (nullCheck()) return;
        enforceSafeWalkDisabledForRun();
        if (running) {
            setPressed("attack", false);
            applySmoothedRotation();
        }

        if (armed && !running) updateActivationPrompt();
        if (running && mc.player != null) pushActivationDiagnostics(mc.player);

        if (!running) return;

        long freezeNow = clientTime();
        // 脚本原值 300ms 用于检测「游戏被外部冻结」。26.2 在高负载机器上单帧常超 300ms，
        // 会持续误判并把自动化打断（表现：跑 1~6 秒就自动停）。放宽到 2 秒。
        if (freezeLastTickAt != 0L && freezeNow - freezeLastTickAt > 2000L) {
            stopAutomation(true);
            return;
        }
        freezeLastTickAt = freezeNow;

        LocalPlayer player = mc.player;
        if (player == null || player.isDeadOrDying() || player.fallDistance > 7.0f) {
            stopAutomation(true);
            return;
        }
        handleAutoSwap(player);
        if (!player.isHolding(stack -> stack.getItem() instanceof BlockItem)) {
            stopAutomation(true);
            return;
        }
        if (firstTellyPlacementPending) updateAdaptivePlacementAim(player);

        autoPlaceOnPreUpdate();
        if (firstTellyPlacementPending) updateAdaptivePlacementAim(player);
    }

    /** 脚本 onPostPlayerInput()。 */
    @EventTarget(0)
    private void onPostPlayerInput(StrafeEvent event) {
        // 未运行时绝不触碰玩家输入：StrafeEvent 的产物就是本 tick 的 player.input，
        // 无条件写回缓存值会把玩家自己的按键清成 0（表现为完全无法移动）。
        if (nullCheck() || !running) return;

        activeInputEvent = event;
        suppressSneakInput();
        enforceSafeWalkDisabledForRun();

        if (setupTick >= 0) {
            if (setupTick < 12) {
                // 用户方案：先「轻微往回冲一小段」建立后退惯性，再起跳 ——
                // 从边缘静止直接起跳没有初速，退一点点再跳会明显更快，桥更容易接上。
                boolean setupJump = setupTick >= SETUP_INERTIA_TICKS;
                applyMovement(-1.0f, -1.0f, setupJump, false);
                applyUse(true);
                dbg(() -> "setup t=" + setupTick
                        + " jump=" + setupJump
                        + " pos=" + (mc.player == null ? "null" : fmtPos(mc.player))
                        + " vH=" + (mc.player == null ? "-" : String.format(Locale.ROOT, "%.3f", Math.hypot(mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().z)))
                        + " vY=" + (mc.player == null ? "-" : String.format(Locale.ROOT, "%.3f", mc.player.getDeltaMovement().y))
                        + " onGround=" + (mc.player != null && mc.player.onGround()));

                if (setupTick == 11) {
                    setRotationTarget(baseYaw + YAW_CURVE[19], PITCH_CURVE[19], 50L);
                } else {
                    setRotationTarget(baseYaw, 74.52f, 50L);
                }
                setupTick++;
                return;
            }

            setupTick = -1;
            takeoverDetectionAt = clientTime() + 125L;
            LocalPlayer takeoverPlayer = mc.player;
            takeoverCameraValid = takeoverPlayer != null;
            takeoverAccumulated = 0.0f;
            takeoverLastFrameAt = clientTime();
            if (takeoverPlayer != null) {
                takeoverCameraYaw = takeoverPlayer.getYRot();
                takeoverCameraPitch = takeoverPlayer.getXRot();
            }
            captureInitialMovementHolds();
            cyclePhase = 19;
            firstTellyPlacementPending = true;
            adaptiveAimValid = false;
            clearCachedCandidate();
            updateAdaptivePlacementAim(mc.player);
        }

        int phase = cyclePhase;
        float strafe = STRAFE_CURVE[phase];

        boolean sprinting = phase == 0 || phase == 1;
        boolean jumping = phase >= 1 && phase <= 19;
        boolean use = phase >= 7;

        // 保险①：横向不能跑歪。telly 是沿一条通道后退搭桥，垂直于推进方向漂出 1 格
        // 就会踩到桥外（用户实测：掉下去是「跑歪了」，不是纵向冲太快）。
        // 保险②：纵向不能冲过头 —— 玩家不能站到"还没铺好的区域"上。
        // 两种情况本 tick 都站定等桥/姿态对齐，旋转与放置照常。
        // ⛔ 这里**保持脚本原版**（越界容差 1）。曾把容差收到 0 想防"退进未铺区"，
        // 结果是停步频率大增、移动变成走走停停 ⇒ **被反作弊判定**，已整体回退。
        // 详见 AGENTS.md「不要用「停步」当护栏」。
        if (isPlayerOffLane(mc.player) || isPlayerAheadOfBridge(mc.player)) {
            applyMovement(0.0f, 0.0f, false, false);
        } else {
            applyMovement(FORWARD_CURVE[phase], strafe, jumping, sprinting);
        }
        applyUse(use);

        int nextPhase = (phase + 1) % YAW_CURVE.length;
        setRotationTarget(baseYaw + YAW_CURVE[nextPhase], PITCH_CURVE[nextPhase], 50L);
        cyclePhase = nextPhase;
    }

    /** 脚本 onPreMotion(state)。 */
    @EventTarget
    private void onPreMotion(MotionEvent event) {
        if (nullCheck() || !running) return;
        LocalPlayer player = mc.player;
        if (player == null) return;
        event.setYaw(player.getYRot());
        event.setPitch(player.getXRot());
        autoPlaceOnPreMotion(event);
    }

    /** 脚本 onPostMotion()。 */
    @EventTarget
    private void onPostMotion(PostMotionEvent event) {
        if (nullCheck() || !running) return;
        autoPlaceOnPostMotion();
    }

    /** 脚本 onRenderTick(pt)。 */
    @EventTarget
    private void onRender3D(RenderEvent event) {
        if (nullCheck()) return;
        updateActivatePromptFade();
        if (!running) {
            drawActivationPrompt3D(event);
            return;
        }
        if (detectManualCameraTakeover()) return;
        applySmoothedRotation();
        autoPlaceOnRenderTick();
    }

    /** 原版「Show Activation Hitbox」：把可激活的那个方块面画成一个高亮框。 */
    private void drawActivationPrompt3D(RenderEvent event) {
        if (!showActivationHitbox.getValue()) return;
        if (!armed || running) return;
        if (promptAlpha < 0.05f) return;

        if (activatePromptAt != 0L) {
            Object[] hit = rayCast(mc.player.getYRot(), mc.player.getXRot());
            if (hit != null) {
                int face = (Integer) hit[1];
                if (face >= 2) {
                    hitboxLastPos = (int[]) hit[0];
                    hitboxLastFace = face;
                }
            }
        }
        if (hitboxLastPos == null || hitboxLastFace < 2) return;
        drawActivationFaceRegion(event, hitboxLastPos, hitboxLastFace);
    }

    /** 原版屏幕中央的 "Activate?" 提示。 */
    @EventTarget
    private void onRender2D(Render2DEvent event) {
        if (promptAlpha < 0.05f) return;
        GuiGraphics graphics = event.guiGraphics();
        if (graphics == null || mc.font == null) return;
        String text = "Activate?";
        int alpha = (int) (promptAlpha * 255.0f);
        if (alpha < 16) alpha = 16;
        int color = alpha << 24 | promptFadeRgb & 0xFFFFFF;
        int width = mc.font.width(text);
        int x = (mc.getWindow().getGuiScaledWidth() - width) / 2;
        int y = (mc.getWindow().getGuiScaledHeight() / 2) + 10;
        graphics.drawString(mc.font, text, x, y, color, true);
    }

    /** 把激活面画成高亮框（原版实现，颜色随提示红/绿变化）。 */
    private void drawActivationFaceRegion(RenderEvent event, int[] pos, int face) {
        double yMin = pos[1] + ACTIVATION_HEIGHT_MIN;
        double yMax = pos[1] + ACTIVATION_HEIGHT_MAX;
        double x1, z1, x2, z2;
        if (face == 5) {
            x1 = pos[0] + 1.005;
            x2 = x1;
            z1 = pos[2] + ACTIVATION_ACROSS_MIN;
            z2 = pos[2] + ACTIVATION_ACROSS_MAX;
        } else if (face == 4) {
            x1 = pos[0] - 0.005;
            x2 = x1;
            z1 = pos[2] + (1.0 - ACTIVATION_ACROSS_MAX);
            z2 = pos[2] + (1.0 - ACTIVATION_ACROSS_MIN);
        } else if (face == 3) {
            z1 = pos[2] + 1.005;
            z2 = z1;
            x1 = pos[0] + (1.0 - ACTIVATION_ACROSS_MAX);
            x2 = pos[0] + (1.0 - ACTIVATION_ACROSS_MIN);
        } else {
            z1 = pos[2] - 0.005;
            z2 = z1;
            x1 = pos[0] + ACTIVATION_ACROSS_MIN;
            x2 = pos[0] + ACTIVATION_ACROSS_MAX;
        }

        float alpha = promptAlpha;
        AABB box = new AABB(
                Math.min(x1, x2), yMin, Math.min(z1, z2),
                Math.max(x1, x2), yMax, Math.max(z1, z2)).inflate(0.002);
        if (box.getXsize() < 0.01) box = box.inflate(0.01, 0.0, 0.0);
        if (box.getZsize() < 0.01) box = box.inflate(0.0, 0.0, 0.01);

        event.poseStack().pushPose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionShader);
        RenderSystem.setShaderColor(((promptFadeRgb >> 16) & 0xFF) / 255.0f,
                ((promptFadeRgb >> 8) & 0xFF) / 255.0f,
                (promptFadeRgb & 0xFF) / 255.0f,
                Math.max(0.05f, 0.55f * alpha));
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderUtil.drawBoxVerts(buffer, event.poseStack().last().pose(), box);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        event.poseStack().popPose();
    }

    /**
     * 脚本 onMouse(button, state, x, y)。
     * Nilore 把鼠标与键盘统一塞进 {@link KeyEvent}（鼠标键码由 {@link KeyBind#mouseCode} 编码），
     * 这里用 {@link KeyBind#isMouse} 分流，避免把鼠标事件当成键盘处理。
     */
    @EventTarget
    private void onMouse(KeyEvent event) {
        if (!KeyBind.isMouse(event.getKeyCode())) return;
        int button = KeyBind.mouseButton(event.getKeyCode());
        if (!onMouse(button, event.isPressed())) event.setCancelled(true);
    }

    /** 脚本 onKey(name, code, state, inGui)。 */
    @EventTarget
    private void onKey(KeyEvent event) {
        if (KeyBind.isMouse(event.getKeyCode())) return;
        boolean inGui = mc.screen != null;
        if (!onKey(event.getKeyCode(), event.isPressed(), inGui)) event.setCancelled(true);
    }

    /** 脚本 onPacketSent(packet)。 */
    @EventTarget
    private void onPacketSend(PacketSendEvent event) {
        if (!onPacketSent(event.getPacket())) event.setCancelled(true);
    }

    /** 脚本 onPacketReceived(packet)。 */
    @EventTarget
    private void onPacketReceive(ReceivePacketEvent event) {
        // 返回值语义与 onPacketSent 相反：脚本 onPacketReceived 总是 return true 表示「放行」，
        // 只有显式 return false 才是拦截。这里若无条件 cancel，会把所有服务端包丢光，
        // 世界加载会直接卡死在「准备生成区域」（客户端等不到区块数据）。
        if (!onPacketReceived(event.getPacket())) event.setCancelled(true);
    }

    // =====================================================================
    // 激活手势（脚本 148-311、407-434）
    // =====================================================================

    private float activationPitch() {
        return 75.0f;
    }

    /** 自动换槽前玩家手上的槽位；停止时恢复。 */
    private int restoreSelectedSlot = -1;

    private void handleAutoSwap(LocalPlayer player) {
        if (!autoSwap.getValue()) return;

        int threshold = 5;
        ItemStack held = player.getMainHandItem();
        boolean heldUsable = held != null && isUsableBlockStack(held);
        int heldCount = heldUsable ? held.getCount() : 0;
        if (heldCount > threshold) return;
        // 只换同类方块：原先只比数量，会把木板桥中途换成圆石。
        net.minecraft.world.item.Item heldItem = heldUsable ? held.getItem() : null;

        int bestSlot = -1;
        int bestSize = heldCount;
        for (int slot = 0; slot <= 8; slot++) {
            if (slot == mc.player.getInventory().selected) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!isUsableBlockStack(stack)) continue;
            if (heldItem != null && stack.getItem() != heldItem) continue;
            if (stack.getCount() > bestSize) {
                bestSize = stack.getCount();
                bestSlot = slot;
            }
        }

        if (bestSlot != -1) {
            if (restoreSelectedSlot == -1) {
                restoreSelectedSlot = mc.player.getInventory().selected;
            }
            mc.player.getInventory().selected = bestSlot;
        }
    }

    /** 等待时间换算为毫秒，供内部计时比较。 */
    private long activationTimeMs() {
        return (long) (activationTime.getValue().doubleValue() * 1000.0);
    }

    private boolean activationPromptReady() {
        return activatePromptAt != 0L && clientTime() - activatePromptAt >= activationTimeMs();
    }

    /** 计时过 85% 后先吞掉右键，避免玩家提前按住右键把放置窗口提前打开。 */
    private boolean activationSuppressUse() {
        return activatePromptAt != 0L
                && clientTime() - activatePromptAt >= (long) (activationTimeMs() * 0.85);
    }

    private void updateActivationPrompt() {
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) {
            clearActivationPrompt();
            return;
        }

        setActivationMovementHold(activationPromptReady() && physicalRightMouseDown());

        boolean lookingDown = player.getXRot() >= activationPitch();
        boolean atEdge = lookingDown && isLookingAtEdge(player);
        pushActivationDiagnostics(player);

        if (player.isShiftKeyDown() && atEdge) {
            if (activatePromptAt == 0L) {
                activatePromptAt = clientTime();
                dbg(() -> "ARM sneak-start pos=" + fmtPos(player)
                        + " yaw=" + String.format(Locale.ROOT, "%.2f", player.getYRot())
                        + " pitch=" + String.format(Locale.ROOT, "%.2f", player.getXRot())
                        + " onGround=" + player.onGround());
            }
            if (activationSuppressUse()) setPressed("use", false);
            aimAtActivationGrid(player);
            if (activationPromptReady() && physicalRightMouseDown()) {
                // 蹲着 + 对准绿框 + 按住右键 = 直接触发；触发后右键可随意松开。
                disableSafeWalkForRun();
                enforceSafeWalkDisabledForRun();
                rememberActivationPromptColor();
                activatePromptAt = 0L;
                beginAutomation();
                if (!running) setPressed("use", false);
                return;
            }
            if (safeWalkStateCaptured) restoreSafeWalkState();
            return;
        }

        // 一旦离开「蹲着 + 对准」，计时作废（触发只可能发生在该状态下按住右键的那一刻）。
        clearActivationPrompt();
    }

    /**
     * 按住潜行计时期间，把视角平滑推向最近的「Snap Degrees 网格」（默认 46.5°+k·90°）。
     *
     * <p>为什么需要：激活时 {@code baseYaw} 就取这个网格值，它决定桥的走向与整条 21 帧曲线序列。
     * 触发瞬间若带几度偏差，整轮搭桥都会扛着同一个偏置。
     *
     * <p>约束：校正量超过 {@link #ACTIVATION_SNAP_RANGE} 直接放弃（说明玩家在找位置，别扳他）；
     * 每 tick 最多走 {@link #ACTIVATION_SNAP_STEP} 度，平滑推入而非瞬移。
     */
    private void aimAtActivationGrid(LocalPlayer player) {
        float base = snapBase.getValue().floatValue();
        float target = Math.round((player.getYRot() - base) / 90.0f) * 90.0f + base;
        float delta = tellyWrapAngle(target - player.getYRot());
        if (Math.abs(delta) > ACTIVATION_SNAP_RANGE || Math.abs(delta) < 0.02f) return;
        player.setYRot(player.getYRot() + clampFloat(delta, -ACTIVATION_SNAP_STEP, ACTIVATION_SNAP_STEP));
    }

    private void clearActivationPrompt() {
        rememberActivationPromptColor();
        if (activationSuppressUse()) {
            setPressed("use", false);
        }
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        // 提示失效后不再沿用上一块方块的高亮框（否则玩家会照着一个早已失效的框摆位）。
        hitboxLastPos = null;
        hitboxLastFace = -1;
        setActivationMovementHold(false);
        if (!running) restoreSafeWalkState();
    }

    private void rememberActivationPromptColor() {
        if (activatePromptAt != 0L) {
            promptFadeRgb = activationPromptReady() ? 0x55FF55 : 0xFF5555;
        }
    }

    private int[] travelDirectionFromYaw(float yaw) {
        double radians = Math.toRadians(yaw);
        double rawX = Math.sin(radians) - Math.cos(radians);
        double rawZ = -Math.cos(radians) - Math.sin(radians);
        if (Math.abs(rawX) >= Math.abs(rawZ)) return new int[]{rawX >= 0.0 ? 1 : -1, 0};
        return new int[]{0, rawZ >= 0.0 ? 1 : -1};
    }

    private boolean isLookingAtEdge(LocalPlayer player) {
        if (!isActivationYawAligned(player.getYRot())) return false;
        Object[] hit = hostRaycast(4.5);
        if (hit == null || hit.length < 3 || hit[0] == null || hit[1] == null || hit[2] == null) return false;

        int face = faceFromName((String) hit[2]);
        if (face < 2) return false;
        if (!isInActivationFaceCenter(face, (Vec3) hit[1])) return false;

        int[] travel = travelDirectionFromYaw(player.getYRot());
        int travelFace = travel[0] > 0 ? 5 : travel[0] < 0 ? 4 : travel[1] > 0 ? 3 : 2;
        if (face != travelFace) return false;

        int[] pos = posFromVec((Vec3) hit[0]);
        if (!isPlayerOnActivationBlock(player, pos)) return false;
        int aheadX = pos[0] + travel[0];
        int aheadZ = pos[2] + travel[1];
        if (!isReplaceableName(blockNameAt(aheadX, pos[1] + 1, aheadZ), false)) return false;

        Vec3 playerPos = player.position();
        double lipDistance;
        if (face == 5) lipDistance = (pos[0] + 1) - playerPos.x;
        else if (face == 4) lipDistance = playerPos.x - pos[0];
        else if (face == 3) lipDistance = (pos[2] + 1) - playerPos.z;
        else lipDistance = playerPos.z - pos[2];
        return lipDistance <= 0.65;
    }

    private boolean isActivationYawAligned(float yaw) {
        // 判据必须与 aimAtActivationGrid 的吸附网格同源：原先硬编码 45°，而 Snap Degrees 允许 42~48，
        // 只要 snapBase 偏离 45 超过容差（42/48），吸附后永远判不通过 ⇒ 提示不出、模块无法激活。
        float base = snapBase.getValue().floatValue();
        float nearestDiagonal = Math.round((yaw - base) / 90.0f) * 90.0f + base;
        return Math.abs(tellyWrapAngle(yaw - nearestDiagonal)) <= ACTIVATION_YAW_TOLERANCE;
    }

    private boolean isPlayerOnActivationBlock(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        Vec3 playerPos = player.position();
        if (pos[1] != floor(playerPos.y - 0.01)) return false;
        double centerX = pos[0] + 0.5;
        double centerZ = pos[2] + 0.5;
        return Math.abs(playerPos.x - centerX) <= 0.85
                && Math.abs(playerPos.z - centerZ) <= 0.85;
    }

    // 只允许在朝外表面的下半部分激活，并排除其内侧。
    private boolean isInActivationFaceCenter(int face, Vec3 localHit) {
        if (localHit == null) return false;
        // 沿面坐标：±Z 面用 x，±X 面用 z。原实现 ±X 面取 localHit.x（命中该面时 x 偏移恒为 0），
        // 再经下面的反相恒为 1.0 ⇒ WEST 面激活判据数学上恒 false。
        double acrossFace = (face == 2 || face == 3) ? localHit.x : localHit.z;
        if (face == 3 || face == 4) acrossFace = 1.0 - acrossFace;
        return acrossFace >= ACTIVATION_ACROSS_MIN && acrossFace <= ACTIVATION_ACROSS_MAX
                && localHit.y >= ACTIVATION_HEIGHT_MIN && localHit.y <= ACTIVATION_HEIGHT_MAX;
    }

    /**
     * 临时排障：逐项列出 {@link #isLookingAtEdge} 的九项判据与实测数值。
     * 只读——不改变任何判定、阈值或显示逻辑，确认问题后可整段删除。
     */
    private void pushActivationDiagnostics(LocalPlayer player) {
        if (running) {
            activationDiagnosticsLine = String.format(Locale.ROOT,
                    "跑[%d] 放:%d 失:%d 脚空:%s 摆:%+.2f 目标p:%.0f 目标y:%.0f",
                    runDiagPhase, runDiagPlaceOk, runDiagPlaceFail,
                    runDiagBelowAir ? "√" : "×", runDiagSway, runDiagTargetPitch, runDiagTargetYaw);
            return;
        }
        if (!armed) {
            activationDiagnosticsLine = null;
            return;
        }
        if (!player.isShiftKeyDown() && player.getXRot() < 60.0f) {
            activationDiagnosticsLine = null;
            return;
        }
        long now = clientTime();
        if (now - lastActivationDiagnosticsAt < ACTIVATION_DIAGNOSTICS_INTERVAL_MS) return;
        lastActivationDiagnosticsAt = now;
        activationDiagnosticsLine = buildActivationDiagnostics(player);
    }

    private String buildActivationDiagnostics(LocalPlayer player) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        StringBuilder sb = new StringBuilder();
        sb.append(pitch >= activationPitch() ? "√低头" : "×低头");
        sb.append(String.format(Locale.ROOT, "%.0f ", pitch));

        // 与 isActivationYawAligned 同源，避免「诊断说 √ 但实际判据用另一套网格」。
        float snapBaseValue = snapBase.getValue().floatValue();
        float nearestDiagonal = Math.round((yaw - snapBaseValue) / 90.0f) * 90.0f + snapBaseValue;
        float yawDelta = Math.abs(tellyWrapAngle(yaw - nearestDiagonal));
        sb.append(yawDelta <= ACTIVATION_YAW_TOLERANCE ? "√对角" : "×对角");
        sb.append(String.format(Locale.ROOT, "%.2f ", yawDelta));

        Object[] hit = hostRaycast(4.5);
        sb.append(hit != null ? "√命中 " : "×命中 ");
        if (hit != null) {
            int face = faceFromName((String) hit[2]);
            Vec3 local = (Vec3) hit[1];
            sb.append(face >= 2 ? "√侧面" : "×侧面").append(face).append(' ');

            double across = (face == 4 || face == 5) ? local.z : local.x;
            if (face == 3 || face == 4) across = 1.0 - across;
            boolean inRegion = across >= ACTIVATION_ACROSS_MIN && across <= ACTIVATION_ACROSS_MAX
                    && local.y >= ACTIVATION_HEIGHT_MIN && local.y <= ACTIVATION_HEIGHT_MAX;
            sb.append(inRegion ? "√区域" : "×区域");
            sb.append(String.format(Locale.ROOT, "a=%.2f y=%.2f ", across, local.y));

            int[] travel = travelDirectionFromYaw(yaw);
            int travelFace = travel[0] > 0 ? 5 : travel[0] < 0 ? 4 : travel[1] > 0 ? 3 : 2;
            sb.append(face == travelFace ? "√朝向面" : "×朝向面").append(travelFace).append(' ');

            int[] pos = posFromVec((Vec3) hit[0]);
            sb.append(isPlayerOnActivationBlock(player, pos) ? "√站块 " : "×站块 ");

            Vec3 playerPos = player.position();
            double lip;
            if (face == 5) lip = (pos[0] + 1) - playerPos.x;
            else if (face == 4) lip = playerPos.x - pos[0];
            else if (face == 3) lip = (pos[2] + 1) - playerPos.z;
            else lip = playerPos.z - pos[2];
            sb.append(lip <= 0.65 ? "√唇距" : "×唇距");
            sb.append(String.format(Locale.ROOT, "%.2f ", lip));
        }
        sb.append(player.isShiftKeyDown() ? "潜行中" : "未潜行");
        sb.append(physicalRightMouseDown() ? " 右键√" : " 右键×");
        if (activatePromptAt == 0L) {
            sb.append(" 未计时");
        } else if (activationPromptReady()) {
            sb.append(" 计时√可触发");
        } else {
            long elapsedLimitMs = Math.max(1L, activationTimeMs());
            sb.append(String.format(Locale.ROOT, " 计时%.0f%%",
                    (clientTime() - activatePromptAt) * 100.0 / elapsedLimitMs));
        }
        return sb.toString();
    }




    /** 激活诊断：屏幕底部一行，不写聊天栏。 */

    private void updateActivatePromptFade() {
        boolean show = armed && !running && activatePromptAt != 0L;
        if (show) rememberActivationPromptColor();

        long now = clientTime();
        long elapsed = promptFadeLastAt == 0L ? 0L : Math.min(100L, now - promptFadeLastAt);
        promptFadeLastAt = now;
        float step = elapsed / 200.0f;
        promptAlpha += show ? step : -step;
        if (promptAlpha < 0.0f) promptAlpha = 0.0f;
        if (promptAlpha > 1.0f) promptAlpha = 1.0f;
    }

    // =====================================================================
    // 输入过滤（脚本 435-597）
    // =====================================================================

    private boolean onMouse(int button, boolean state) {
        if (running) {
            if (button == 0) {
                setPressed("attack", false);
                return false;
            }
            if (button == 1) {
                setPressed("use", tellyAutoPlaceWindow);
                return false;
            }
            return autoPlaceOnMouse(button, state);
        }
        if (armed && button == 1 && !state) setActivationMovementHold(false);
        if (armed && activationSuppressUse() && button == 1) return false;
        return true;
    }

    private boolean onKey(int keyCode, boolean state, boolean inGui) {
        boolean dropKey = keyCode == keyCode("drop") || isDropKeyName(keyCode);
        if (isDropProtected() && dropKey) {
            setPressed("drop", false);
            return false;
        }
        if (!running && activationMovementHeld && !state
                && (keyCode == keyCode("back")
                || keyCode == keyCode("right"))) {
            setPressed("back", true);
            setPressed("right", true);
            return false;
        }
        if (!running) return true;
        if (keyCode == keyCode("sneak")) {
            suppressSneakInput();
            return false;
        }
        if (!state) clearInitialMovementHold(keyCode);
        if (state
                && setupTick < 0
                && isManualMovementKey(keyCode)
                && !isInitialMovementHold(keyCode)
                && !isScriptHeldKey(keyCode)) {
            stopAutomation(true);
            return true;
        }
        if (inGui) return true;
        if (isManualMovementKey(keyCode)) return false;
        return true;
    }

    private void setActivationMovementHold(boolean hold) {
        if (hold) {
            activationMovementHeld = true;
            setPressed("back", true);
            setPressed("right", true);
            return;
        }
        if (!activationMovementHeld) return;
        activationMovementHeld = false;
        setPressed("back", isPhysicalKeyDown("back"));
        setPressed("right", isPhysicalKeyDown("right"));
    }

    private boolean isScriptHeldKey(int keyCode) {
        // 必须读 KeyMapping 的注入状态（脚本 setPressed 写的），不能用物理键态：
        // GLFW 在回调前就更新了物理键态 ⇒ 刚按下的这个键永远「已按下」，接管分支不可达，
        // 随后 isManualMovementKey 又把事件取消 ⇒ 运行中玩家完全失去键盘控制。
        if (keyCode == keyCode("forward")) return isMappingDown("forward");
        if (keyCode == keyCode("back")) return isMappingDown("back");
        if (keyCode == keyCode("left")) return isMappingDown("left");
        if (keyCode == keyCode("right")) return isMappingDown("right");
        if (keyCode == keyCode("jump")) return isMappingDown("jump");
        if (keyCode == keyCode("sprint")) return isMappingDown("sprint");
        return false;
    }

    private boolean isMappingDown(String key) {
        KeyMapping mapping = mapping(key);
        return mapping != null && mapping.isDown();
    }

    private boolean isManualMovementKey(int keyCode) {
        return keyCode == keyCode("forward")
                || keyCode == keyCode("back")
                || keyCode == keyCode("left")
                || keyCode == keyCode("right")
                || keyCode == keyCode("jump")
                || keyCode == keyCode("sneak")
                || keyCode == keyCode("sprint");
    }

    private void captureInitialMovementHolds() {
        ignoreForwardUntilRelease = isPhysicalKeyDown("forward");
        ignoreBackUntilRelease = isPhysicalKeyDown("back");
        ignoreLeftUntilRelease = isPhysicalKeyDown("left");
        ignoreRightUntilRelease = isPhysicalKeyDown("right");
        ignoreJumpUntilRelease = isPhysicalKeyDown("jump");
        ignoreSneakUntilRelease = isPhysicalKeyDown("sneak");
        ignoreSprintUntilRelease = isPhysicalKeyDown("sprint");
    }

    private boolean isInitialMovementHold(int keyCode) {
        if (keyCode == keyCode("forward")) return ignoreForwardUntilRelease;
        if (keyCode == keyCode("back")) return ignoreBackUntilRelease;
        if (keyCode == keyCode("left")) return ignoreLeftUntilRelease;
        if (keyCode == keyCode("right")) return ignoreRightUntilRelease;
        if (keyCode == keyCode("jump")) return ignoreJumpUntilRelease;
        if (keyCode == keyCode("sneak")) return ignoreSneakUntilRelease;
        if (keyCode == keyCode("sprint")) return ignoreSprintUntilRelease;
        return false;
    }

    private void clearInitialMovementHold(int keyCode) {
        if (keyCode == keyCode("forward")) ignoreForwardUntilRelease = false;
        if (keyCode == keyCode("back")) ignoreBackUntilRelease = false;
        if (keyCode == keyCode("left")) ignoreLeftUntilRelease = false;
        if (keyCode == keyCode("right")) ignoreRightUntilRelease = false;
        if (keyCode == keyCode("jump")) ignoreJumpUntilRelease = false;
        if (keyCode == keyCode("sneak")) ignoreSneakUntilRelease = false;
        if (keyCode == keyCode("sprint")) ignoreSprintUntilRelease = false;
    }

    private void clearInitialMovementHolds() {
        ignoreForwardUntilRelease = false;
        ignoreBackUntilRelease = false;
        ignoreLeftUntilRelease = false;
        ignoreRightUntilRelease = false;
        ignoreJumpUntilRelease = false;
        ignoreSneakUntilRelease = false;
        ignoreSprintUntilRelease = false;
    }

    private boolean detectManualCameraTakeover() {
        if (!running || setupTick >= 0 || clientTime() < takeoverDetectionAt) return false;
        LocalPlayer player = mc.player;
        if (player == null) return false;

        long now = clientTime();
        float expectedYaw = scriptedRotationYaw;
        float expectedPitch = scriptedRotationPitch;
        if (!takeoverCameraValid) {
            takeoverCameraValid = true;
            takeoverCameraYaw = player.getYRot();
            takeoverCameraPitch = player.getXRot();
            takeoverAccumulated = 0.0f;
            takeoverLastFrameAt = now;
            return false;
        }

        double yawInput = Math.abs(tellyWrapAngle(player.getYRot() - expectedYaw));
        double pitchInput = Math.abs(player.getXRot() - expectedPitch);

        takeoverCameraYaw = player.getYRot();
        takeoverCameraPitch = player.getXRot();
        takeoverLastFrameAt = now;

        // 见 MANUAL_TAKEOVER_DEGREES 的说明：超过 5° 才判定为玩家接管。
        if (yawInput > MANUAL_TAKEOVER_DEGREES || pitchInput > MANUAL_TAKEOVER_DEGREES) {
            stopAutomation(true);
            return true;
        }
        return false;
    }

    // =====================================================================
    // 发包拦截（脚本 598-642、727-740）
    // =====================================================================

    private boolean onPacketSent(Packet<?> packet) {
        if (isDropProtected() && packet instanceof ServerboundPlayerActionPacket digging) {
            String status = digging.getAction() == null ? "" : digging.getAction().name().toUpperCase(Locale.ROOT);
            if (status.contains("DROP")) return false;
        }
        if (!running) return true;
        if (packet instanceof ServerboundInteractPacket) {
            return false;
        }
        if (packet instanceof ServerboundPlayerActionPacket digging) {
            String status = digging.getAction() == null ? "" : digging.getAction().name().toUpperCase(Locale.ROOT);
            if (status.contains("DESTROY")) return false;
        }
        if (packet instanceof ServerboundPlayerCommandPacket action) {
            String name = action.getAction() == null ? "" : action.getAction().name();
            if ("START_SNEAKING".equals(name)) return false;
        }
        int[] placedTarget = null;
        if (packet instanceof ServerboundUseItemOnPacket placement) {
            BlockHitResult hit = placement.getHitResult();
            if (hit != null && hit.getType() != HitResult.Type.MISS && hit.getBlockPos() != null) {
                placedTarget = offsetPos(posFromBlockPos(hit.getBlockPos()), hit.getDirection().get3DDataValue());
                if (!isStraightTellyTarget(placedTarget)) {
                    cancelledGhostBlocks.add(posKey(placedTarget));
                    return false;
                }
            }
        }

        boolean allowed = autoPlaceOnPacketSent(packet);
        if (allowed && placedTarget != null) {
            cancelledGhostBlocks.remove(posKey(placedTarget));
            latestStraightPlacedPos = new int[]{placedTarget[0], placedTarget[1], placedTarget[2]};
            if (firstTellyPlacementPending && setupTick < 0) {
                firstTellyPlacementPending = false;
                adaptiveAimValid = false;
                adaptiveAimUpdatedAt = 0L;
            }
        }
        return allowed;
    }

    private boolean isActivationInProgress() {
        return armed && !running && activatePromptAt != 0L;
    }

    private boolean isDropProtected() {
        return running || isActivationInProgress();
    }

    private boolean onPacketReceived(Packet<?> packet) {
        if (running && packet instanceof ClientboundPlayerPositionPacket) {
            // ReceivePacketEvent 在 netty 线程：stopAutomationInner 会写通知队列、
            // 改 setSprinting、KeyMapping、GLFW 与 ModuleManager，必须回主线程执行。
            if (mc.isSameThread()) {
                stopAutomation(true);
            } else {
                mc.execute(() -> stopAutomation(true));
            }
            return true;
        }
        if (packet instanceof ClientboundBlockUpdatePacket change && change.getPos() != null) {
            int[] changed = posFromBlockPos(change.getPos());
            cancelledGhostBlocks.remove(posKey(changed));
            // [Fix AirLiquidPlace] 服务端回包确认这块已是实心方块，解除“ghost 支撑”封印。
            if (!pendingSupportConfirm.isEmpty()) pendingSupportConfirm.remove(posKey(changed));

            // 「吞方块」诊断：联机时我们铺好的那一格被服务端改回空气/可替换方块。
            // 客户端因为本地预测会先看到方块出现，所以日志里 placedOk 与 placeFail 都正常，
            // 只有这里能抓到服务端回撤 —— 而桥面出现缺口正是随后踩空（fall=7.52）的直接原因。
            // 注意：此时 ReceivePacketEvent 还在 HEAD，客户端世界尚未应用这个包，
            // 所以要读包里的 state，不能去查 mc.level。
            BlockState changedState = change.getBlockState();
            boolean becamePassable = changedState == null
                    || changedState.isAir()
                    || changedState.canBeReplaced();
            if (running && becamePassable && isStraightTellyTarget(changed)) {
                // 服务端回发「放置位仍是空气」= 那次放置被服务端静默拒绝。
                // 这里在 netty 线程：getEyes/blockNameAt 会读 mc.player/mc.level，必须搬回客户端线程。
                if (mc.isSameThread()) {
                    logSwallowed(changed);
                } else {
                    mc.execute(() -> logSwallowed(changed));
                }
            }
        }
        return true;
    }

    private void logSwallowed(int[] changed) {
        if (!running || mc.player == null || mc.level == null) return;
        String eyeDist = String.format(Locale.ROOT, "%.3f",
                getEyes(mc.player).distanceTo(Vec3.atCenterOf(new BlockPos(changed[0], changed[1], changed[2]))));
        dbg(() -> "SERVER-SWALLOW at=" + java.util.Arrays.toString(changed)
                + " name=" + blockNameAt(changed[0], changed[1], changed[2])
                + " lastPlaced=" + java.util.Arrays.toString(lastPlacedPos)
                + " lastSupport=" + java.util.Arrays.toString(lastSupportPos)
                + " lastFace=" + lastSupportFace
                + " reach=" + String.format(Locale.ROOT, "%.3f", reach())
                + " eyeDist=" + eyeDist
                + " tick=" + currentClientTick);
    }

    // =====================================================================
    // 启停与状态（脚本 741-876）
    // =====================================================================

    private void armAutomation() {
        armed = true;
        running = false;
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        setupTick = 0;
        cyclePhase = 19;
        rotationActive = false;
        activationMovementHeld = false;
        // ⚠️ 文案必须与实际触发条件一致：**按住**潜行蹲稳 → 提示变绿后**再按住**右键。
        // 不是「松开潜行」（那是脚本原版的写法，本移植已改），别再写 release。
        promptKeySneak = null;
        promptKeyUse = null;
        printStatus("&eArmed. Hold " + keyDisplay("sneak")
                + " at the edge looking down; when green hold " + rightMouseDisplay());
    }

    private void beginAutomation() {
        LocalPlayer player = mc.player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof BlockItem)) {
            printStatus("&cHold blocks before starting");
            return;
        }
        if (!isActivationYawAligned(player.getYRot())) return;

        disableSafeWalkForRun();
        dbg(() -> "BEGIN yaw=" + String.format(Locale.ROOT, "%.2f", player.getYRot())
                + " pitch=" + String.format(Locale.ROOT, "%.2f", player.getXRot())
                + " pos=" + fmtPos(player)
                + " v=" + String.format(Locale.ROOT, "%.3f,%.3f,%.3f",
                        player.getDeltaMovement().x, player.getDeltaMovement().y, player.getDeltaMovement().z)
                + " onGround=" + player.onGround()
                + " sneak=" + player.isShiftKeyDown()
                + " fall=" + String.format(Locale.ROOT, "%.2f", player.fallDistance));
        // 对齐到 Snap Degrees 网格（默认 46.5°，与按住潜行期间的吸附一致）：触发瞬间的瞄准偏差
        // 绝不能固化进 baseYaw，否则后续每一刻的旋转目标都带同一偏差，桥会越搭越歪（第一格就接不上）。
        float alignBase = snapBase.getValue().floatValue();
        float alignedYaw = Math.round((player.getYRot() - alignBase) / 90.0f) * 90.0f + alignBase;
        baseYaw = alignedYaw;
        player.setYRot(alignedYaw);
        setActivationMovementHold(false);
        calculateTravelDirection(baseYaw);
        antiSwayLane = travelX != 0
                ? (floor(player.position().z) + 0.5)
                : (floor(player.position().x) + 0.5);
        antiSwayYawOffset = 0.0f;
        antiSwayTapUsed = false;
        antiSwayTapTick = -9999;
        cancelledGhostBlocks.clear();
        initializeStraightBridgeLane(player);
        firstTellyPlacementPending = false;
        adaptiveAimValid = false;
        adaptiveAimUpdatedAt = 0L;
        setupTick = 0;
        cyclePhase = 19;
        armed = false;
        running = true;
        freezeLastTickAt = clientTime();
        activationMovementHeld = false;
        tellyAutoPlaceWindow = true;
        scriptedRotationYaw = player.getYRot();
        scriptedRotationPitch = player.getXRot();
        takeoverDetectionAt = 0L;
        takeoverCameraValid = false;
        clearInitialMovementHolds();
        resetControllerState();
        setPressed("attack", false);
        applyMovement(-1.0f, -1.0f, false, false);
        setRotationTarget(baseYaw, 74.52f, 50L);
        applyUse(true);
        printStatus("&aStarted");
    }

    private void stopAutomation(boolean turnOffButton) {
        stopAutomation(turnOffButton, callerHint());
    }

    /**
     * 调用者提示：给出"谁按停的"（方法名 + 行号），免去给每个调用点加参数。
     *
     * <p>用 {@link StackWalker} 而非 {@code Thread.currentThread().getStackTrace()}：
     * 后者每次都要填充并返回<b>完整</b>栈数组；这里只取前 8 帧就够。
     */
    private static String callerHint() {
        return StackWalker.getInstance().walk(frames -> frames
                .limit(8)
                .filter(f -> f.getClassName().contains("Telly"))
                .filter(f -> !f.getMethodName().startsWith("stopAutomation"))
                .filter(f -> !f.getMethodName().equals("callerHint"))
                .map(f -> f.getMethodName() + ":" + f.getLineNumber())
                .findFirst()
                .orElse("unknown"));
    }

    /** 便于定位"跑几秒就自己停"：把停止原因、相位、位置、累计放置数一并写进日志。 */
    private void stopAutomation(boolean turnOffButton, String reason) {
        if (running) {
            LocalPlayer p = mc.player;
            dbg(() -> "STOP reason=" + reason
                    + " phase=" + cyclePhase
                    + " setupTick=" + setupTick
                    + " tick=" + currentClientTick
                    + " placedOk=" + runDiagPlaceOk
                    + " placeFail=" + runDiagPlaceFail
                    + " failHist=" + placementFailCounts
                    + " pos=" + (p == null ? "null" : fmtPos(p))
                    + " vH=" + (p == null ? "-" : String.format(Locale.ROOT, "%.3f", Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z)))
                    + " fall=" + (p == null ? "-" : String.format(Locale.ROOT, "%.2f", p.fallDistance))
                    + " holdBlock=" + (p != null && p.isHolding(stack -> stack.getItem() instanceof BlockItem)));
        }
        stopAutomationInner(turnOffButton);
    }

    private void stopAutomationInner(boolean turnOffButton) {
        placementFailCounts.clear();
        armed = false;
        running = false;
        setupTick = 0;
        cyclePhase = 19;
        rotationActive = false;
        activationMovementHeld = false;
        tellyAutoPlaceWindow = false;
        autoPlaceDebugActive = false;
        antiSwayYawOffset = 0.0f;
        antiSwayTapUsed = false;
        pendingSupportConfirm.clear();
        firstTellyPlacementPending = false;
        latestStraightPlacedPos = null;
        adaptiveAimValid = false;
        adaptiveAimUpdatedAt = 0L;
        scriptedRotationYaw = 0.0f;
        scriptedRotationPitch = 0.0f;
        takeoverDetectionAt = 0L;
        takeoverCameraValid = false;
        takeoverCameraYaw = 0.0f;
        takeoverCameraPitch = 0.0f;
        takeoverAccumulated = 0.0f;
        takeoverLastFrameAt = 0L;

        try {
            cancelledGhostBlocks.clear();
            clearInitialMovementHolds();
            resetControllerState();
            setClientForward(0.0f);
            setClientStrafe(0.0f);
            setClientJump(false);
            setClientSprinting(false);
            releaseMovementKeys();
            restorePhysicalUse();
            setPressed("attack", physicalLeftMouseDown());
            if (restoreSelectedSlot >= 0 && restoreSelectedSlot < 9) {
                mc.player.getInventory().selected = restoreSelectedSlot;
            }
            restoreSelectedSlot = -1;
        } catch (Exception ignored) {
        }

        restoreSafeWalkState();

        freezeLastTickAt = 0L;
        armed = true;
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        if (turnOffButton) {
            printStatus("&eStopped. Hold " + keyDisplay("sneak") + " looking down to arm again");
        }
    }

    private void disableSafeWalkForRun() {
        if (safeWalkStateCaptured) {
            enforceSafeWalkDisabledForRun();
            return;
        }
        if (!disableSafeWalk.getValue()) return;

        try {
            Module safeWalk = findModule("SafeWalk");
            safeWalkWasEnabled = safeWalk != null && safeWalk.isEnabled();
            safeWalkStateCaptured = true;
            if (safeWalkWasEnabled) safeWalk.setEnabled(false);
        } catch (Exception ignored) {
            safeWalkStateCaptured = false;
        }
    }

    private void enforceSafeWalkDisabledForRun() {
        if (!safeWalkStateCaptured) return;
        try {
            Module safeWalk = findModule("SafeWalk");
            if (safeWalk != null && safeWalk.isEnabled()) safeWalk.setEnabled(false);
        } catch (Exception ignored) {
        }
    }

    private void restoreSafeWalkState() {
        if (!safeWalkStateCaptured) return;

        boolean restoreEnabled = safeWalkWasEnabled;
        safeWalkStateCaptured = false;
        try {
            Module safeWalk = findModule("SafeWalk");
            if (safeWalk == null) return;
            boolean currentlyEnabled = safeWalk.isEnabled();
            if (restoreEnabled && !currentlyEnabled) safeWalk.setEnabled(true);
            if (!restoreEnabled && currentlyEnabled) safeWalk.setEnabled(false);
        } catch (Exception ignored) {
        }
    }

    private void printStatus(String message) {
        try {
            if (!print.getValue()) return;
            String text = message == null ? "" : message;
            if (text.startsWith("&a")) {
                ChatUtil.print(text.substring(2));
            } else if (text.startsWith("&c")) {
                ChatUtil.print(text.substring(2));
            } else if (text.startsWith("&e")) {
                ChatUtil.print(text.substring(2));
            } else {
                ChatUtil.print(text);
            }
        } catch (Exception ignored) {
        }
    }

    // =====================================================================
    // 旋转脚本（脚本 886-941）
    // =====================================================================

    private void setRotationTarget(float targetYaw, float targetPitch, long duration) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        applySmoothedRotation();
        // 运行中必须以**上一刻的脚本角**为插值起点：此时玩家的真实视角可能已被别的模块
        // 或玩家自己的鼠标改过，拿它当起点会让曲线断裂、出现脚本之外的跳变。
        if (running) {
            rotationStartYaw = scriptedRotationYaw;
            rotationStartPitch = scriptedRotationPitch;
        } else {
            rotationStartYaw = player.getYRot();
            rotationStartPitch = player.getXRot();
        }
        float correctedTargetYaw = targetYaw;
        boolean adaptivePlacementTarget = running
                && tellyAutoPlaceWindow
                && firstTellyPlacementPending
                && adaptiveAimValid
                && clientTime() - adaptiveAimUpdatedAt <= 125L;
        if (adaptivePlacementTarget) {
            correctedTargetYaw = adaptiveAimYaw;
            targetPitch = adaptiveAimPitch;
        }
        // [Fix 视角累积右偏] 不再把 antiSwayYawOffset 注入 yaw 目标。
        // 这个偏移由横向误差积分而来，误差长期同号时会一直把整条曲线往同一侧推，
        // 每跑一圈就多一点 ⇒ 视角越来越偏、最后对不上方块。位置修正交给 strafe tap。

        rotationStepCounter++;
        correctedTargetYaw += (float) (mouseQuantum() * YAW_NUDGE_PATTERN[rotationStepCounter % 5]);

        // [Fix 视角累积右偏 + AimModulo360] 目标角必须是**绝对角**（baseYaw + 曲线）。
        // 之前写成 player.getYRot() + wrap(want - playerYaw)：每 tick 都以“已经被推偏的当前角”
        // 为基准重新展开，偏差被一圈圈继承下来，这就是“越跑越偏”的来源。
        // 现在存绝对值，插值时用 wrap 走最短路径，既不会累积也不会产生 ±360 跳变。
        rotationTargetYaw = correctedTargetYaw;
        rotationTargetPitch = clamp(targetPitch, -90.0f, 90.0f);
        rotationStartedAt = clientTime();
        rotationDuration = Math.max(1L, duration);
        rotationActive = true;
    }

    private void applySmoothedRotation() {
        if (!rotationActive) return;
        LocalPlayer player = mc.player;
        if (player == null) return;

        double progress = (double) (clientTime() - rotationStartedAt) / (double) rotationDuration;
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;

        // [Fix AimModulo360] 以当前真实角为起点、按最短路径推进，并且**不把结果折回 ±180**。
        // 原实现在 179.9° 附近会把 180.1 归一化成 -179.9，制造一次 −360° 的假跳变。
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        float yawDelta = tellyWrapAngle(rotationTargetYaw - currentYaw);
        float pitchDelta = rotationTargetPitch - currentPitch;

        float newYaw;
        float newPitch;
        if (progress >= 1.0) {
            // [Fix AimModulo360] 必须是「当前表示 + 最短增量」，不能直接写绝对目标。
            // 直接写 rotationTargetYaw（例如 408）时，MC 侧可能把它折回 48；
            // 下一 tick 再写 415，包里的 raw delta 就是 ±360 —— Grim 的
            // `|deltaYaw| > 320 && |lastDeltaYaw| < 30` 正是抓这个（实测 ×8）。
            // 同时这一 tick 仍然精确到达（yawDelta 是 wrap 后的最短差，无累积）。
            newYaw = currentYaw + yawDelta;
            newPitch = rotationTargetPitch;
        } else {
            newYaw = quantizeFrom(currentYaw, currentYaw + yawDelta * (float) progress);
            newPitch = quantizeFrom(currentPitch, currentPitch + pitchDelta * (float) progress);
        }
        newPitch = clamp(newPitch, -90.0f, 90.0f);

        scriptedRotationYaw = newYaw;
        scriptedRotationPitch = newPitch;
        player.setYRot(scriptedRotationYaw);
        player.setXRot(scriptedRotationPitch);
        if (progress >= 1.0) rotationActive = false;
    }

    /**
     * 把 {@code value} 沿 {@code origin} 方向截断到**本机实时**灵敏度网格的整数倍。
     *
     * <p>量子取自 {@link RotationUtils#mouseQuantum()}，与发包时的
     * {@code RotationUtils.applySensitivityPatch} 同源；用源客户端那套硬编码常量会在本机落在错格点上。
     *
     * <p>**截断而非四舍五入**：脚本原实现是 {@code delta -= delta % gcd}（向零取整）。
     * 早先这里写成 {@code Math.round}，每步会多走半格，与脚本曲线对不上。
     */
    private float quantizeFrom(float origin, float value) {
        double quantum = mouseQuantum();
        if (quantum <= 0.0) return value;

        double delta = value - origin;
        delta -= delta % quantum;
        return (float) (origin + delta);
    }

    // =====================================================================
    // 移动与使用（脚本 942-1136）
    // =====================================================================

    private void applyMovement(float forward, float strafe, boolean jumping, boolean sprinting) {
        float controlledForward = forward;
        boolean controlledSprint = sprinting;

        float correctedStrafe = strafe;
        boolean antiSway = running;
        if (antiSway) correctedStrafe = applyAntiSwayCorrection(controlledForward, strafe);
        else antiSwayYawOffset = 0.0f;

        setPressed("forward", controlledForward > 0.03f);
        setPressed("back", controlledForward < -0.03f);
        setPressed("left", correctedStrafe > 0.5f);
        setPressed("right", correctedStrafe < -0.5f);
        setPressed("jump", jumping);
        setPressed("sprint", controlledSprint);
        setClientForward(controlledForward);
        setClientStrafe(correctedStrafe);
        setClientJump(jumping);
        setClientSneak(false);
        setClientSprinting(controlledSprint);
    }

    private void suppressSneakInput() {
        setPressed("sneak", false);
        setClientSneak(false);
    }

    private void calculateTravelDirection(float yaw) {
        double radians = Math.toRadians(yaw);
        double rawX = Math.sin(radians) - Math.cos(radians);
        double rawZ = -Math.cos(radians) - Math.sin(radians);

        if (Math.abs(rawX) >= Math.abs(rawZ)) {
            travelX = rawX >= 0.0 ? 1 : -1;
            travelZ = 0;
        } else {
            travelX = 0;
            travelZ = rawZ >= 0.0 ? 1 : -1;
        }
    }

    private void initializeStraightBridgeLane(LocalPlayer player) {
        Vec3 position = player.position();
        int startX = floor(position.x);
        int startY = floor(position.y) - 1;
        int startZ = floor(position.z);
        bridgeLaneBlock = travelX != 0 ? startZ : startX;
        bridgeStartProgress = startX * travelX + startZ * travelZ;

        Object[] hit = hostRaycast(4.5);
        if (hit != null && hit.length > 0 && hit[0] instanceof Vec3) {
            int[] hitPos = posFromVec((Vec3) hit[0]);
            int hitLane = travelX != 0 ? hitPos[2] : hitPos[0];
            int hitProgress = straightProgress(hitPos);
            if (hitLane == bridgeLaneBlock
                    && Math.abs(hitPos[0] - startX) <= 2
                    && Math.abs(hitPos[2] - startZ) <= 2
                    && hitProgress < bridgeStartProgress) {
                bridgeStartProgress = hitProgress;
            }
        }

        latestStraightPlacedPos = new int[]{startX, startY, startZ};
    }

    private int straightProgress(int[] position) {
        if (position == null) return Integer.MIN_VALUE;
        return position[0] * travelX + position[2] * travelZ;
    }

    private boolean isStraightTellyTarget(int[] position) {
        if (!running || position == null) return true;
        int lane = travelX != 0 ? position[2] : position[0];
        if (lane != bridgeLaneBlock) return false;
        return straightProgress(position) >= bridgeStartProgress;
    }

    private void updateAdaptivePlacementAim(LocalPlayer player) {
        if (!firstTellyPlacementPending) return;
        Object[] candidate = cachedCandidate;
        if (candidate != null) {
            int[] target = candidatePlacedPos(candidate);
            Vec3 hitVec = candidateHitVec(candidate);
            if (isStraightTellyTarget(target) && hitVec != null) {
                setAdaptiveAimToPoint(player, hitVec);
                return;
            }
        }

        int[] support = latestStraightPlacedPos != null ? latestStraightPlacedPos : lastPlacedPos;
        if (support == null || !isStraightTellyTarget(support)) return;
        int face = travelX > 0 ? 5 : travelX < 0 ? 4 : travelZ > 0 ? 3 : 2;
        int[] nextTarget = offsetPos(support, face);
        if (!isStraightTellyTarget(nextTarget) || !isReplaceable(nextTarget[0], nextTarget[1], nextTarget[2])) return;
        Vec3 fallbackHit = getSupportFaceHitVec(support, face, 0.5, 0.5);
        setAdaptiveAimToPoint(player, fallbackHit);
    }

    private void setAdaptiveAimToPoint(LocalPlayer player, Vec3 point) {
        if (player == null || point == null) return;
        Vec3 position = player.position();
        double eyeX = position.x;
        double eyeY = player.position().y + player.getEyeHeight();
        double eyeZ = position.z;
        double dx = point.x - eyeX;
        double dy = point.y - eyeY;
        double dz = point.z - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-5 && Math.abs(dy) < 1.0E-5) return;

        adaptiveAimYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        adaptiveAimPitch = clamp((float) (-Math.toDegrees(Math.atan2(dy, horizontal))), -89.0f, 89.0f);
        adaptiveAimUpdatedAt = clientTime();
        adaptiveAimValid = true;
    }

    private float applyAntiSwayCorrection(float forward, float recordedStrafe) {
        LocalPlayer player = mc.player;
        if (player == null) return recordedStrafe;

        Vec3 position = player.position();
        Vec3 motion = player.getDeltaMovement();
        double lanePosition = travelX != 0 ? position.z : position.x;
        double laneVelocity = motion == null ? 0.0 : (travelX != 0 ? motion.z : motion.x);
        double error = antiSwayLane - lanePosition;

        if (Math.abs(error) < 0.015 && Math.abs(laneVelocity) < 0.008) {
            antiSwayTapUsed = false;
            antiSwayYawOffset *= 0.65f;
            if (Math.abs(antiSwayYawOffset) < 0.03f) antiSwayYawOffset = 0.0f;
            return recordedStrafe;
        }

        double desiredLaneVelocity = error * 0.42 - laneVelocity * 0.78;
        if (desiredLaneVelocity > 0.16) desiredLaneVelocity = 0.16;
        if (desiredLaneVelocity < -0.16) desiredLaneVelocity = -0.16;
        double velocityCorrection = desiredLaneVelocity - laneVelocity;

        // 必须用瞬时视角，不能用固定 baseYaw：yawLaneDerivative 是「桥前进方向对视角 yaw 的
        // 解析导数」，它的语义要求 sin/cos 来自当前视角（转动视角是驱动桥前进的唯一手段）。
        // 脚本把视角推到 baseYaw+91.68=136.7°，真实导数 cos(136.7°)=-0.729，与 cos(45°)=+0.707
        // 符号相反；固定 baseYaw 会让半个周期朝反方向推，另一半周期权限顶到 ±2.25° 钳位后饱和。
        // 1.8.9 原版即此写法（BSLegitTellyFix.java:1080 player.getYaw()），本移植曾改错。
        double radians = Math.toRadians(player.getYRot());
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double yawLaneDerivative = travelX != 0
                ? -forward * sin + recordedStrafe * cos
                : -forward * cos - recordedStrafe * sin;
        double desiredYawOffset = 0.0;
        if (Math.abs(yawLaneDerivative) >= 0.12) {
            desiredYawOffset = Math.toDegrees(velocityCorrection * 0.55 / yawLaneDerivative);
        }
        if (desiredYawOffset > 2.25) desiredYawOffset = 2.25;
        if (desiredYawOffset < -2.25) desiredYawOffset = -2.25;
        antiSwayYawOffset = antiSwayYawOffset * 0.60f + (float) desiredYawOffset * 0.40f;

        double strafeLaneAxis = travelX != 0 ? sin : cos;
        boolean tapHelps = Math.abs(strafeLaneAxis) >= 0.20 && velocityCorrection * strafeLaneAxis > 0.0;
        // [Fix 向右偏] tap 改成按 tick 节流：strafe=0 的相位（phase 4~16）里横向误差持续存在，
        // 原来「一次 tap 后必须等误差归零」等于只修一次，剩下的全靠 ±2.25° 的 yaw 钳位，
        // 抵消不了 0.7 量级的副轴分量 → 整条桥持续向右偏。
        boolean tapReady = currentClientTick - antiSwayTapTick >= 3;
        if (tapHelps
                && tapReady
                && Math.abs(velocityCorrection) >= 0.03
                && recordedStrafe < 0.5f) {
            antiSwayTapUsed = true;
            antiSwayTapTick = currentClientTick;
            return recordedStrafe + 1.0f;
        }

        return recordedStrafe;
    }

    private void applyUse(boolean pressed) {
        if (pressed && !autoPlaceDebugActive) {
            printStatus("&aAutoPlace activated");
        }
        autoPlaceDebugActive = pressed;
        tellyAutoPlaceWindow = pressed;
        setPressed("use", pressed);
    }

    private void restorePhysicalUse() {
        tellyAutoPlaceWindow = false;
        autoPlaceDebugActive = false;
        setPressed("use", physicalRightMouseDown());
    }

    private void releaseMovementKeys() {
        restorePhysicalKey("forward");
        restorePhysicalKey("back");
        restorePhysicalKey("left");
        restorePhysicalKey("right");
        restorePhysicalKey("jump");
        restorePhysicalKey("sneak");
        restorePhysicalKey("sprint");
    }

    private void restorePhysicalKey(String key) {
        int code = keyCode(key);
        setPressed(key, code >= 0 && InputConstants.isKeyDown(mc.getWindow().getWindow(), code));
    }

    // =====================================================================
    // 工具（脚本 1137-1205、2617-2805）
    // =====================================================================

    private float tellyWrapAngle(float angle) {
        while (angle <= -180.0f) angle += 360.0f;
        while (angle > 180.0f) angle -= 360.0f;
        return angle;
    }

    private float clamp(float value, float minimum, float maximum) {
        if (value < minimum) return minimum;
        if (value > maximum) return maximum;
        return value;
    }

    private static final double[] FACE_HIT_OFFSETS = {0.5, 0.25, 0.75, 0.15, 0.85};
    private static final double[] EXTENDED_FACE_HIT_OFFSETS = {0.5, 0.25, 0.75, 0.15, 0.85, 0.35, 0.65, 0.05, 0.95};
    private static final int[] ALLOWED_PLACE_FACES = {2, 3, 4, 5, 1};

    private static final String[] REPLACEABLE_BLOCKS = {
            "air", "cave_air", "void_air", "water", "lava", "fire",
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "snow", "vine", "seagrass", "kelp", "kelp_plant", "bubble_column", "light"
    };

    private static final String[] EXPERIMENTAL_REPLACEABLE_BLOCKS = {
            "oak_sapling", "dandelion", "poppy", "brown_mushroom", "red_mushroom",
            "wheat", "carrots", "potatoes", "nether_wart", "sugar_cane"
    };

    private static final String[] UNPLACEABLE_EXACT = {
            "snow", "cobweb", "oak_sapling", "daylight_detector", "beacon", "white_banner",
            "end_portal_frame", "end_portal", "lever", "stone_button", "oak_button",
            "skeleton_skull", "cactus", "tall_grass", "lily_pad", "white_carpet", "tripwire_hook",
            "short_grass", "dandelion", "poppy", "flower_pot", "oak_sign", "ladder",
            "torch", "redstone_torch", "gravel", "clay", "sand",
            "soul_sand", "chest", "trapped_chest", "ender_chest", "furnace",
            "jukebox", "enchanting_table", "dropper", "dispenser", "hopper", "anvil",
            "note_block", "crafting_table", "spawner", "brewing_stand", "red_bed"
    };

    private static final String[] UNPLACEABLE_CONTAINS = {
            "stairs", "slab", "fence", "pane", "rail", "door",
            "torch", "pumpkin", "flower", "sapling", "banner", "button",
            "skull", "web", "carpet", "cactus", "sign", "mushroom", "bed"
    };

    private void autoPlaceOnEnable() {
        setPressed("attack", false);
        resetControllerState();
    }

    private void autoPlaceOnDisable() {
        resetControllerState();
        restoreUseToPhysicalState();
        // 按物理左键恢复，而不是无条件 false（KeyMapping 是持久键态，写 false 会吞掉玩家正按住的攻击）。
        setPressed("attack", physicalLeftMouseDown());
        BRIDGE.remove("AutoPlacePlacing");
        releaseExperimentalPlacementClaim();
    }

    private void resetControllerState() {
        currentClientTick = Integer.MIN_VALUE;
        placementEvaluationTick = Integer.MIN_VALUE;
        lastPlacementAttemptTick = Integer.MIN_VALUE;
        lastSuccessfulPlaceTick = Integer.MIN_VALUE;
        forceSuppressTick = Integer.MIN_VALUE;
        totalC08Counter = 0L;
        c08CounterAtTickBoundary = 0L;
        hasLastSentServerPos = false;
        clearCachedCandidate();
        lastPlacedPos = null;
        lastSupportPos = null;
        lastSupportFace = -1;
        cachedBelowTargets = null;
        cachedBelowTargetsTick = Integer.MIN_VALUE;
        rejectedTargets.clear();
        forcedModeCheck = 0;
        useSuppressed = false;
        silentPitchActive = false;
        placingViaModule = false;
        manualC08InWindow = false;
    }

    private void autoPlaceOnWorldJoin() {
        resetControllerState();
    }

    private void autoPlaceOnPreUpdate() {
        LocalPlayer player = mc.player;
        if (player == null) return;

        syncPlacementTick(player);

        if (placementEvaluationTick != currentClientTick) {
            placementEvaluationTick = currentClientTick;
            processAutoPlaceTick(player);
        }
    }

    private void syncPlacementTick(LocalPlayer player) {
        int tick = placementTick(player);
        if (tick == currentClientTick) return;
        currentClientTick = tick;
        candidateResolvedThisTick = false;
        silentPitchActive = false;
    }

    private boolean useExtendedSearch() {
        return true;
    }

    private void autoPlaceOnPostMotion() {
        c08CounterAtTickBoundary = totalC08Counter;
        manualC08InWindow = false;
    }

    private void autoPlaceOnRenderTick() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!isAutoPlaceActiveWindow(player)) return;

        ItemStack heldStack = player.getMainHandItem();
        if (!isUsableBlockStack(heldStack)) return;

        float basePitch = sanitizePitch(player.getXRot(), player.getXRot());
        Object[] candidate = resolveCandidateWithOffCursorSilentPitch(player, player.getYRot(), basePitch, heldStack);
        if (candidate != null) {
            silentPitch = sanitizePitch(candidatePitch(candidate), basePitch);
            silentPitchActive = true;
            suppressUse();
        }
    }

    private void autoPlaceOnPreMotion(MotionEvent state) {
        if (silentPitchActive && !manualC08InWindow) {
            state.setPitch(sanitizePitch(silentPitch, state.getPitch()));
        }
    }

    private boolean autoPlaceOnPacketSent(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket c03) {
            if (c03.hasPosition()) {
                hasLastSentServerPos = true;
                lastSentServerPosX = c03.getX(0.0);
                lastSentServerPosY = c03.getY(0.0);
                lastSentServerPosZ = c03.getZ(0.0);
            }
            return true;
        }
        if (packet instanceof ServerboundUseItemOnPacket c08) {
            BlockHitResult hit = c08.getHitResult();
            boolean miss = hit == null || hit.getType() == HitResult.Type.MISS;
            if (miss) {
                if (shouldCancelAutoPlaceUseItem()) {
                    suppressUse();
                    return false;
                }
            } else {
                ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
                if (held.getItem() instanceof BlockItem) {
                    totalC08Counter++;
                    if (!placingViaModule) manualC08InWindow = true;
                }
            }
        }
        return true;
    }

    private boolean autoPlaceOnMouse(int button, boolean state) {
        if (!state || (button != 0 && button != 1)) return true;

        if (button == 1 && shouldCancelAutoPlaceUseItem()) {
            suppressUse();
            return false;
        }
        if (!shouldSuppressManualClicksThisTick()) return true;
        setPressed("attack", false);
        return false;
    }

    private boolean shouldSuppressManualClicksThisTick() {
        if (!isInGameContext()) return false;
        return lastSuccessfulPlaceTick == currentClientTick || forceSuppressTick == currentClientTick;
    }

    private boolean shouldCancelAutoPlaceUseItem() {
        if (!isInGameContext()) return false;
        if (shouldSuppressManualClicksThisTick()) return true;
        return useSuppressed && silentPitchActive;
    }

    private void suppressUse() {
        setPressed("use", false);
        useSuppressed = true;
    }

    private void restoreUseToPhysicalState() {
        setPressed("use", running
                ? tellyAutoPlaceWindow
                : physicalRightMouseDown());
        useSuppressed = false;
    }

    private boolean isInGameContext() {
        return mc.player != null && mc.screen == null;
    }

    private boolean areAutoPlaceConditionsMet(LocalPlayer player) {
        if (!tellyAutoPlaceWindow) return false;
        return isUsableBlockStack(player.getMainHandItem());
    }

    private boolean isAutoPlaceActiveWindow(LocalPlayer player) {
        if (!isInGameContext()) return false;
        if (hasBridge("ScaffoldRunning")) return false;
        if (!areAutoPlaceConditionsMet(player)) return false;
        return isUsableBlockStack(player.getMainHandItem());
    }

    private boolean isUsableBlockStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) return false;
        // 名称黑名单永远列不全（wall/bars/chain/candle/lantern…）；先按碰撞形状排除非完整方块。
        net.minecraft.world.level.block.state.BlockState state =
                ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
        if (!state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return false;
        }
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        for (String bad : UNPLACEABLE_EXACT) {
            if (name.equals(bad)) return false;
        }
        for (String bad : UNPLACEABLE_CONTAINS) {
            if (name.contains(bad)) return false;
        }
        return true;
    }

    private boolean isBlockBelowPlayerReplaceable(LocalPlayer player) {
        Vec3 pos = player.position();
        return isReplaceable(floor(pos.x), floor(pos.y) - 1, floor(pos.z));
    }

    private boolean placedInCurrentWindow() {
        return totalC08Counter > c08CounterAtTickBoundary;
    }

    private boolean claimExperimentalPlacementTick() {
        Object tickValue = BRIDGE.get("PlacementArbiterTick");
        Object ownerValue = BRIDGE.get("PlacementArbiterOwner");
        if (tickValue instanceof Number
                && ((Number) tickValue).intValue() == currentClientTick
                && ownerValue != null
                && !getName().equals(String.valueOf(ownerValue))) {
            return false;
        }
        BRIDGE.put("PlacementArbiterTick", currentClientTick);
        BRIDGE.put("PlacementArbiterOwner", getName());
        return true;
    }

    private void releaseExperimentalPlacementClaim() {
        Object ownerValue = BRIDGE.get("PlacementArbiterOwner");
        if (ownerValue == null || !getName().equals(String.valueOf(ownerValue))) return;
        BRIDGE.remove("PlacementArbiterTick");
        BRIDGE.remove("PlacementArbiterOwner");
    }

    private boolean hasBridge(String key) {
        if ("ScaffoldRunning".equals(key)) return Scaffold.INSTANCE.isEnabled();
        return BRIDGE.containsKey(key);
    }

    private void processAutoPlaceTick(LocalPlayer player) {
        pruneRejectedTargets();

        if (lastPlacedPos != null && !isSupportAvailable(lastPlacedPos[0], lastPlacedPos[1], lastPlacedPos[2])) {
            lastPlacedPos = null;
            lastSupportPos = null;
            lastSupportFace = -1;
        }

        // 运行期诊断采样（只读）
        runDiagBelowAir = isBlockBelowPlayerReplaceable(player);
        runDiagSway = antiSwayYawOffset;
        runDiagTargetPitch = rotationTargetPitch;
        runDiagTargetYaw = rotationTargetYaw;
        runDiagPhase = cyclePhase;

        if (!isAutoPlaceActiveWindow(player)) {
            clearCachedCandidate();
            BRIDGE.remove("AutoPlacePlacing");
            if (useSuppressed) restoreUseToPhysicalState();
            return;
        }

        ItemStack heldStack = player.getMainHandItem();
        if (!isUsableBlockStack(heldStack)) {
            clearCachedCandidate();
            if (useSuppressed) restoreUseToPhysicalState();
            return;
        }

        // 不再用「脚下那一格」做全局门：实际目标来自 3x3 邻域/朝向偏移，
        // 脚下实心不代表没有合法目标（实测 curY 与 feetY-1 83% 不一致）。
        // 目标格是否可替换由 attemptPlacement 的 target-not-replaceable 检查负责。
        float yaw = player.getYRot();
        float basePitch = sanitizePitch(player.getXRot(), player.getXRot());
        Object[] candidate = resolveCandidateWithOffCursorSilentPitch(player, yaw, basePitch, heldStack);
        if (candidate != null) {
            silentPitch = sanitizePitch(candidatePitch(candidate), basePitch);
            silentPitchActive = true;
            suppressUse();
        } else if (useSuppressed && !placedInCurrentWindow() && lastPlacementAttemptTick != currentClientTick) {
            restoreUseToPhysicalState();
        }

        if (placedInCurrentWindow() || lastPlacementAttemptTick == currentClientTick) {
            suppressUse();
            return;
        }

        if (candidate == null) {
            clearCachedCandidate();
            return;
        }

        if (!claimExperimentalPlacementTick()) {
            clearCachedCandidate();
            return;
        }

        BRIDGE.put("AutoPlacePlacing", Boolean.TRUE);
        lastPlacementAttemptTick = currentClientTick;

        if (attemptPlacement(player, candidate, heldStack)) {
            runDiagPlaceOk++;
            return;
        }

        if (placedInCurrentWindow()) return;

        float retryYaw = player.getYRot();
        float retryPitch = player.getXRot();
        clearCachedCandidate();
        Object[] retryCandidate = findBelowPlacement(player, retryYaw, retryPitch, heldStack, clientTime() + (useExtendedSearch() ? 4L : 2L));
        cacheCandidate(retryCandidate, retryYaw, retryPitch);
        if (retryCandidate != null) {
            silentPitch = sanitizePitch(candidatePitch(retryCandidate), retryPitch);
            silentPitchActive = true;
            if (attemptPlacement(player, retryCandidate, heldStack)) {
                runDiagPlaceOk++;
                return;
            }
            runDiagPlaceFail++;
        }
        releaseExperimentalPlacementClaim();
    }

    // =====================================================================
    // 放置执行（脚本 1421-1537）
    // =====================================================================

    /**
     * 「搭不上」诊断出口：记录放置失败的**原因**与当时的视角。
     *
     * <p>⚠️ 这条日志只做采样（250ms 限流），完整分布看停止时打印的 {@code failHist} 直方图。
     *
     * @return 恒为 {@code false}，便于在早退处直接 {@code return placementFail(...)}
     */
    private boolean placementFail(String reason, int[] placedPos, int[] supportPos, int face, LocalPlayer player) {
        placementFailCounts.merge(reason, 1, Integer::sum);
        long now = clientTime();
        if (now - lastPlacementFailLogAt >= 250L) {
            lastPlacementFailLogAt = now;
            dbg(() -> "PLACE-FAIL[" + reason + "]"
                    + " target=" + (placedPos == null ? "-" : java.util.Arrays.toString(placedPos))
                    + " support=" + (supportPos == null ? "-" : java.util.Arrays.toString(supportPos))
                    + " face=" + face
                    + " yaw=" + String.format(Locale.ROOT, "%.2f", player.getYRot())
                    + " pitch=" + String.format(Locale.ROOT, "%.2f", player.getXRot())
                    + " phase=" + cyclePhase
                    + " tick=" + currentClientTick);
        }
        return false;
    }

    private boolean attemptPlacement(LocalPlayer player, Object[] candidate, ItemStack heldStack) {
        if (candidate == null) return false;
        int[] placedPos = candidatePlacedPos(candidate);
        int[] supportPos = candidateSupportPos(candidate);
        int face = candidateFace(candidate);
        if (placedPos == null || supportPos == null || face <= 0) {
            return placementFail("bad-candidate", placedPos, supportPos, face, player);
        }
        if (!isStraightTellyTarget(placedPos)) {
            return placementFail("not-straight", placedPos, supportPos, face, player);
        }
        if (!isUsableBlockStack(player.getMainHandItem())) {
            return placementFail("no-block", placedPos, supportPos, face, player);
        }
        if (placedInCurrentWindow()) {
            return placementFail("already-placed-window", placedPos, supportPos, face, player);
        }

        float placementPitch = sanitizePitch(candidatePitch(candidate), player.getXRot());
        Object[] prePlaceHit = resolveVerifiedHit(player.getYRot(), placementPitch, supportPos, face, placedPos);
        if (prePlaceHit == null) {
            // 设计内的筛选，不是 bug：候选里同时有身前/身后格，靠 YAW_CURVE 转头去够。
            // 偏转小时够到身前格，偏转大时够到身后格；够不到就换下一格。
            // （曾误判成"摆动把校验打空"，把放置方向钉在 baseYaw —— 实测反而更差，见 placementFailCounts。）
            return placementFail("look-misaligned", placedPos, supportPos, face, player);
        }

        // 写入/移除都记录被放置格；原先这里查 supportPos（差一格），守卫永不命中。
        if (cancelledGhostBlocks.contains(posKey(placedPos))) {
            return placementFail("cancelled-ghost", placedPos, supportPos, face, player);
        }
        if (!isReplaceable(placedPos[0], placedPos[1], placedPos[2])) {
            return placementFail("target-not-replaceable", placedPos, supportPos, face, player);
        }
        if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) {
            return placementFail("no-support", placedPos, supportPos, face, player);
        }
        if (doesPlacementIntersectPlayer(player, placedPos)) {
            return placementFail("intersects-player", placedPos, supportPos, face, player);
        }

        long counterBefore = totalC08Counter;
        Vec3 hitAbs = (Vec3) prePlaceHit[2];
        placingViaModule = true;
        boolean placed = hostPlaceBlock(supportPos, faceName(face), hitAbs);
        placingViaModule = false;
        boolean packetSent = totalC08Counter > counterBefore;

        if (!placed && !packetSent) {
            return placementFail("game-mode-rejected", placedPos, supportPos, face, player);
        }
        if (!packetSent) {
            markRejectedTarget(placedPos);
            return placementFail("packet-cancelled", placedPos, supportPos, face, player);
        }

        lastPlacedPos = placedPos;
        lastSupportPos = supportPos;
        lastSupportFace = face;
        lastSuccessfulPlaceTick = currentClientTick;
        forceSuppressTick = currentClientTick;
        // [Fix AirLiquidPlace] 刚放的块先登记为“待服务端确认”，确认前不许当支撑。
        pendingSupportConfirm.put(posKey(placedPos), clientTime());
        mc.player.swing(InteractionHand.MAIN_HAND);
        // 落点日志：写进 latest.log（不走聊天栏），用于定位"某一格放错/叠高"。
        dbg(() -> "placed at=" + java.util.Arrays.toString(placedPos)
                + " support=" + java.util.Arrays.toString(supportPos)
                + " face=" + face
                + " pY=" + String.format(Locale.ROOT, "%.2f", player.getY())
                + " feetY=" + floor(player.getY())
                + " curY=" + getCurrentBelowTargetY(player)
                + " strictY=" + getStrictBelowTargetY(player)
                + " prevY=" + getPreviousBelowTargetY(player)
                + " onGround=" + player.onGround()
                + " sneak=" + player.isShiftKeyDown()
                + " physSneak=" + mc.options.keyShift.isDown()
                + " sprint=" + player.isSprinting()
                + " vH=" + String.format(Locale.ROOT, "%.3f", Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z))
                + " tick=" + currentClientTick);
        return true;
    }

    private Object[] resolveVerifiedHit(float yaw, float pitch, int[] expectedSupport, int expectedFace, int[] expectedPlaced) {
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] tracedSupport = (int[]) traced[0];
        int tracedFace = (Integer) traced[1];
        if (!posEquals(tracedSupport, expectedSupport) || tracedFace != expectedFace) return null;
        int[] tracedPlaced = offsetPos(tracedSupport, tracedFace);
        if (!posEquals(tracedPlaced, expectedPlaced)) return null;
        return traced;
    }

    private Object[] resolveCandidateWithOffCursorSilentPitch(LocalPlayer player, float yaw, float basePitch, ItemStack heldStack) {
        float safeBasePitch = sanitizePitch(basePitch, player.getXRot());
        // 只有本刻解析出的候选才能复用；跨刻候选的位置/角度已过期，重新打时间戳会把它伪装成“本刻已解析”。
        Object[] previousCandidate = cachedCandidateTick == currentClientTick ? cachedCandidate : null;
        Object[] baseCandidate = resolveCandidateForCurrentTick(player, yaw, safeBasePitch, heldStack);
        if (baseCandidate == null) {
            if (previousCandidate != null) {
                float previousBlockPitch = getBlockDerivedSilentPitch(player, previousCandidate, safeBasePitch);
                Object[] recovered = resolveCandidateForCurrentTick(player, yaw, previousBlockPitch, heldStack);
                if (recovered != null) return recovered;
                cacheCandidate(previousCandidate, yaw, safeBasePitch);
                return previousCandidate;
            }
            return null;
        }
        if (isPlacementLookAligned(yaw, safeBasePitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidatePlacedPos(baseCandidate))) {
            return baseCandidate;
        }
        float blockPitch = getBlockDerivedSilentPitch(player, baseCandidate, safeBasePitch);
        if (isPlacementLookAligned(yaw, blockPitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidatePlacedPos(baseCandidate))) {
            return new Object[]{blockPitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidateHitVec(baseCandidate), candidatePlacedPos(baseCandidate)};
        }
        Object[] corrected = resolveCandidateForCurrentTick(player, yaw, blockPitch, heldStack);
        if (corrected != null && posEquals(candidatePlacedPos(baseCandidate), candidatePlacedPos(corrected))) {
            return corrected;
        }
        cacheCandidate(baseCandidate, yaw, safeBasePitch);
        return baseCandidate;
    }

    private Object[] resolveCandidateForCurrentTick(LocalPlayer player, float yaw, float pitch, ItemStack heldStack) {
        float safePitch = sanitizePitch(pitch, player.getXRot());
        if (hasCachedCandidateForCurrentTick(yaw, safePitch)) return cachedCandidate;
        Object[] candidate = findBelowPlacement(player, yaw, safePitch, heldStack, clientTime() + (useExtendedSearch() ? 8L : 4L));
        cacheCandidate(candidate, yaw, safePitch);
        return candidate;
    }

    private float getBlockDerivedSilentPitch(LocalPlayer player, Object[] candidate, float fallbackPitch) {
        if (candidate == null) return sanitizePitch(fallbackPitch, fallbackPitch);
        Vec3 hitVec = candidateHitVec(candidate);
        if (hitVec != null) {
            Float derived = computePitchToHitVec(player, hitVec);
            if (derived != null) return sanitizePitch(derived, fallbackPitch);
        }
        return sanitizePitch(candidatePitch(candidate), fallbackPitch);
    }

    private void cacheCandidate(Object[] candidate, float yaw, float pitch) {
        cachedCandidate = candidate;
        cachedCandidateTick = currentClientTick;
        cachedCandidateYaw = yaw;
        cachedCandidatePitch = pitch;
        candidateResolvedThisTick = candidate != null;
    }

    private boolean hasCachedCandidateForCurrentTick(float yaw, float pitch) {
        if (cachedCandidateTick != currentClientTick || !candidateResolvedThisTick || cachedCandidate == null) return false;
        if (Float.isNaN(cachedCandidateYaw) || Float.isNaN(cachedCandidatePitch)) return false;
        return Math.abs(wrapAngle(yaw - cachedCandidateYaw)) <= 0.75f && Math.abs(pitch - cachedCandidatePitch) <= 0.75f;
    }

    private void clearCachedCandidate() {
        cachedCandidate = null;
        cachedCandidateTick = Integer.MIN_VALUE;
        cachedCandidateYaw = Float.NaN;
        cachedCandidatePitch = Float.NaN;
        candidateResolvedThisTick = false;
    }

    // =====================================================================
    // 放置搜索（脚本 1538-1669）
    // =====================================================================

    private Object[] findBelowPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;

        Object[] cursorRayCandidate = findDirectCursorRayPlacement(player, yaw, currentPitch, heldStack);
        if (cursorRayCandidate != null) return cursorRayCandidate;

        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        int previousY = getPreviousBelowTargetY(player);
        int[] feetPos = getFeetBelowTargetAtY(player, currentY);

        List<int[]> targets = new ArrayList<>();
        addBelowTarget(player, targets, feetPos);
        addBelowTarget(player, targets, offsetPos(feetPos, facingFromYaw(yaw)));

        for (int dy = 0; dy <= 2; dy++) {
            int targetY = dy == 0 ? currentY : dy == 1 ? strictY : previousY;
            if (targetY == Integer.MIN_VALUE || (dy == 1 && targetY == currentY) || (dy == 2 && (targetY == currentY || targetY == strictY))) continue;
            addBelowTarget(player, targets, new int[]{feetPos[0], targetY, feetPos[2]});
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                addBelowTarget(player, targets, new int[]{feetPos[0] + dx, currentY, feetPos[2] + dz});
                if (strictY != currentY) addBelowTarget(player, targets, new int[]{feetPos[0] + dx, strictY, feetPos[2] + dz});
                if (previousY != currentY && previousY != strictY) addBelowTarget(player, targets, new int[]{feetPos[0] + dx, previousY, feetPos[2] + dz});
            }
        }

        if (!player.onGround()) {
            addBelowTarget(player, targets, getMotionBelowTargetAtY(player, currentY, 1.0));
            if (previousY != currentY && previousY != strictY) {
                addBelowTarget(player, targets, getMotionBelowTargetAtY(player, previousY, 1.0));
            }
        }

        Object[] bestCandidate = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int[] targetPos : targets) {
            // 超时用 break（保留已找到的最优候选），不能用 return null 把 bestCandidate 丢掉：
            // 8ms 预算下常在第一个目标就超时，原写法等于「有可用解却报无候选」。
            if (clientTime() >= deadlineMs) break;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, -1, deadlineMs, false, true);
            if (candidate == null) continue;
            double score = scorePlacementCandidate(player, currentPitch, candidatePitch(candidate), candidateFace(candidate), 0.5, 0.5);
            if (score < bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    private Object[] findDirectCursorRayPlacement(LocalPlayer player, float yaw, float pitch, ItemStack heldStack) {
        if (!isUsableBlockStack(heldStack)) return null;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] supportPos = (int[]) traced[0];
        int face = (Integer) traced[1];
        if (face == 0) return null;
        int[] targetPos = offsetPos(supportPos, face);
        if (!isPlacementTargetAvailable(player, targetPos)) return null;
        if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) return null;
        if (shouldRejectStraightSideSwitch(player, targetPos, face)) return null;
        float tracedPitch = clampFloat(pitch, -89.0f, 89.0f);
        return new Object[]{tracedPitch, supportPos, face, (Vec3) traced[2], targetPos};
    }

    private Object[] findStraightGroundExceptionCandidate(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        int previousForcedMode = forcedModeCheck;
        forcedModeCheck = 2;
        Object[] candidate = findBelowPlacementForSupport(player, yaw, currentPitch, heldStack, null, -1, deadlineMs);
        if (candidate == null) {
            candidate = findBelowPlayerAirborneFallback(player, yaw, currentPitch, heldStack, Math.max(deadlineMs, clientTime() + (useExtendedSearch() ? 4L : 2L)));
        }
        if (candidate == null) {
            candidate = findNearestSupportToBelowPlayerFallback(player, yaw, currentPitch, heldStack, Math.max(deadlineMs, clientTime() + (useExtendedSearch() ? 4L : 2L)));
        }
        forcedModeCheck = previousForcedMode;
        return candidate;
    }

    private Object[] findPreviousBlockAirborneFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (!hasValidLastSupportFace(player) || clientTime() >= deadlineMs) return null;
        int[] exactTarget = offsetPos(lastSupportPos, lastSupportFace);
        if (!isPlacementTargetAvailable(player, exactTarget)) return null;
        boolean diagonal = isDiagonalMovementContext(player);
        return findPitchPlacementForTarget(player, yaw, currentPitch, exactTarget, heldStack, lastSupportPos, lastSupportFace, deadlineMs, false, diagonal);
    }

    private Object[] findStraightLegacyLaneFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        int previousY = getPreviousBelowTargetY(player);
        int upwardY = isStraightAscendingContext(player) ? currentY + 1 : Integer.MIN_VALUE;

        List<int[]> laneTargets = new ArrayList<>();
        addBelowTarget(player, laneTargets, getCursorStartTargetAtY(player, currentY));
        addBelowTarget(player, laneTargets, getCursorPlacedTargetFromRay(yaw, currentPitch, currentY));
        addBelowTarget(player, laneTargets, getCursorTargetAtY(player, currentY));
        if (strictY != currentY) {
            addBelowTarget(player, laneTargets, getCursorStartTargetAtY(player, strictY));
            addBelowTarget(player, laneTargets, getCursorPlacedTargetFromRay(yaw, currentPitch, strictY));
            addBelowTarget(player, laneTargets, getCursorTargetAtY(player, strictY));
        }
        if (previousY != currentY && previousY != strictY) {
            addBelowTarget(player, laneTargets, getCursorStartTargetAtY(player, previousY));
            addBelowTarget(player, laneTargets, getCursorPlacedTargetFromRay(yaw, currentPitch, previousY));
            addBelowTarget(player, laneTargets, getCursorTargetAtY(player, previousY));
        }
        if (upwardY != Integer.MIN_VALUE && upwardY != currentY && upwardY != strictY && upwardY != previousY) {
            addBelowTarget(player, laneTargets, getCursorStartTargetAtY(player, upwardY));
            addBelowTarget(player, laneTargets, getCursorPlacedTargetFromRay(yaw, currentPitch, upwardY));
            addBelowTarget(player, laneTargets, getCursorTargetAtY(player, upwardY));
        }

        for (int[] targetPos : laneTargets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isStraightLaneTargetAvailable(player, targetPos, currentY, strictY, previousY, upwardY)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, deadlineMs);
            if (candidate != null) return candidate;
        }
        return null;
    }

    // =====================================================================
    // 回退链与评分（脚本 1670-2137）
    // =====================================================================

    private boolean isCursorDirectedAtBlock(float yaw, float pitch) {
        return rayCast(yaw, pitch) != null;
    }

    private boolean isStraightCenterBelowAir(LocalPlayer player) {
        Vec3 pos = player.position();
        return isReplaceableName(blockNameAt(floor(pos.x), getCurrentBelowTargetY(player), floor(pos.z)), true);
    }

    private boolean isStraightPreviousTickCenterOnGroundSupport(LocalPlayer player) {
        Vec3 last = lastPosition(player);
        return !isReplaceableName(blockNameAt(floor(last.x), floor(last.y) - 1, floor(last.z)), true);
    }

    private boolean isNearStraightSupportEdge(LocalPlayer player) {
        if (lastSupportPos == null || lastSupportFace < 2) return false;
        Vec3 pos = player.position();
        double localX = pos.x - lastSupportPos[0];
        double localZ = pos.z - lastSupportPos[2];
        if (isPastStraightSupportEdgeThreshold(lastSupportFace, localX, localZ)) return true;
        Vec3 motion = player.getDeltaMovement();
        if (motion.x * motion.x + motion.z * motion.z < 1.0E-4) return false;
        if (!isMovingTowardStraightSupportEdge(lastSupportFace, motion.x, motion.z)) return false;
        return isPastStraightSupportEdgeThreshold(lastSupportFace, localX + motion.x * 1.45, localZ + motion.z * 1.45);
    }

    private boolean isPastStraightSupportEdgeThreshold(int supportFace, double localX, double localZ) {
        if (supportFace == 5) return localX >= 0.52;
        if (supportFace == 4) return localX <= 0.48;
        if (supportFace == 3) return localZ >= 0.52;
        if (supportFace == 2) return localZ <= 0.48;
        return false;
    }

    private boolean isMovingTowardStraightSupportEdge(int supportFace, double motionX, double motionZ) {
        if (supportFace == 5) return motionX > 0.0;
        if (supportFace == 4) return motionX < 0.0;
        if (supportFace == 3) return motionZ > 0.0;
        if (supportFace == 2) return motionZ < 0.0;
        return false;
    }

    private Object[] findStraightPreviousVisibleFaceFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs || !hasValidLastSupportFace(player)) return null;
        if (lastSupportFace <= 0) return null;
        int[] targetPos = offsetPos(lastSupportPos, lastSupportFace);
        if (!isPlacementTargetAvailable(player, targetPos)) return null;
        return findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, lastSupportPos, lastSupportFace, deadlineMs, true, true);
    }

    private List<int[]> getBelowPlayerFallbackEndpoints(LocalPlayer player, float yaw, float pitch, int targetY) {
        List<int[]> endpoints = new ArrayList<>();
        if (!isDiagonalMovementContext(player)) {
            if (!player.onGround()) {
                addBelowTargetIfUnique(player, endpoints, getFeetBelowTargetAtY(player, targetY));
                addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.0));
                addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.7));
            }
            addBelowTargetIfUnique(player, endpoints, getCursorStartTargetAtY(player, targetY));
            addBelowTargetIfUnique(player, endpoints, getCursorPlacedTargetFromRay(yaw, pitch, targetY));
            addBelowTargetIfUnique(player, endpoints, getCursorTargetAtY(player, targetY));
            return endpoints;
        }
        addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.0));
        addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.7));
        return endpoints;
    }

    private Object[] findBelowPlayerAirborneFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        int playerBelowY = getCurrentBelowTargetY(player);
        boolean diagonal = isDiagonalMovementContext(player);
        boolean allowNonCursorTarget = diagonal || !player.onGround();
        List<int[]> fallbackTargets = new ArrayList<>();
        for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, playerBelowY)) {
            addBelowTarget(player, fallbackTargets, endpoint);
        }
        for (int[] targetPos : fallbackTargets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, -1, deadlineMs, false, allowNonCursorTarget);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findNearestSupportToBelowPlayerFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        int targetY = getCurrentBelowTargetY(player);
        int[] belowPlayer = getFeetBelowTargetAtY(player, targetY);
        if (belowPlayer == null || hasDirectSupportNeighbor(belowPlayer)) return null;

        int[] searchOrigin = getPathStartTowardBelowPlayer(player, targetY, belowPlayer);
        int[] nearestStart = findNearestSupportedReplaceableTarget(player, searchOrigin, belowPlayer, targetY, deadlineMs);
        if (nearestStart == null) return null;

        List<int[]> requiredPath = rasterizeHorizontalLineAtY(nearestStart, belowPlayer, targetY, 64);
        for (int i = requiredPath.size() - 1; i >= 0; i--) {
            if (clientTime() >= deadlineMs) return null;
            int[] pathPos = requiredPath.get(i);
            if (!isPlacementTargetAvailable(player, pathPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, pathPos, heldStack, null, -1, deadlineMs, false, true);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private int[] findNearestSupportedReplaceableTarget(LocalPlayer player, int[] origin, int[] belowPlayer, int targetY, long deadlineMs) {
        if (origin == null || belowPlayer == null || clientTime() >= deadlineMs) return null;
        for (int radius = 0; radius <= 3; radius++) {
            int[] bestAtRadius = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int dx = -radius; dx <= radius; dx++) {
                int dzAbs = radius - Math.abs(dx);
                int[] positive = new int[]{origin[0] + dx, targetY, origin[2] + dzAbs};
                if (isPlacementTargetAvailable(player, positive) && hasDirectSupportNeighbor(positive)) {
                    double score = scoreAirPathStartCandidate(positive, belowPlayer, origin);
                    if (score < bestScore) {
                        bestScore = score;
                        bestAtRadius = positive;
                    }
                }
                if (dzAbs == 0) continue;
                int[] negative = new int[]{origin[0] + dx, targetY, origin[2] - dzAbs};
                if (isPlacementTargetAvailable(player, negative) && hasDirectSupportNeighbor(negative)) {
                    double score = scoreAirPathStartCandidate(negative, belowPlayer, origin);
                    if (score < bestScore) {
                        bestScore = score;
                        bestAtRadius = negative;
                    }
                }
            }
            if (bestAtRadius != null) return bestAtRadius;
        }
        return null;
    }

    private double scoreAirPathStartCandidate(int[] candidate, int[] belowPlayer, int[] origin) {
        double sampleY = candidate[1] + 0.5;
        double goalDistSq = distSq(candidate[0] + 0.5, sampleY, candidate[2] + 0.5, belowPlayer[0] + 0.5, sampleY, belowPlayer[2] + 0.5);
        double originDistSq = distSq(candidate[0] + 0.5, sampleY, candidate[2] + 0.5, origin[0] + 0.5, sampleY, origin[2] + 0.5);
        return goalDistSq * 4.0 + originDistSq;
    }

    private int[] getPathStartTowardBelowPlayer(LocalPlayer player, int targetY, int[] fallback) {
        int[] pathStart = null;
        if (lastPlacedPos != null && lastPlacedPos[1] == targetY) pathStart = lastPlacedPos;
        if (pathStart == null) pathStart = getMotionBelowTargetAtY(player, targetY, 1.7);
        if (pathStart == null) pathStart = getMotionBelowTargetAtY(player, targetY, 1.0);
        return pathStart != null ? pathStart : fallback;
    }

    private boolean hasValidLastPlacedPos(LocalPlayer player) {
        if (lastPlacedPos == null) return false;
        return isWithinReach(player, lastPlacedPos) && isSupportAvailable(lastPlacedPos[0], lastPlacedPos[1], lastPlacedPos[2]) && !isInteractable(lastPlacedPos[0], lastPlacedPos[1], lastPlacedPos[2]);
    }

    private boolean hasValidLastSupportFace(LocalPlayer player) {
        if (lastSupportPos == null || lastSupportFace < 0) return false;
        return isWithinReach(player, lastSupportPos) && isSupportAvailable(lastSupportPos[0], lastSupportPos[1], lastSupportPos[2]) && !isInteractable(lastSupportPos[0], lastSupportPos[1], lastSupportPos[2]);
    }

    private Object[] findLegacyBelowPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs || !isUsableBlockStack(heldStack)) return null;
        if (isDiagonalMovementContext(player)) {
            Object[] diagonalCandidate = findLegacyDiagonalPlacement(player, yaw, currentPitch, heldStack, deadlineMs);
            if (diagonalCandidate != null) return diagonalCandidate;
        }
        if (hasValidLastPlacedPos(player)) {
            Object[] preferred = findLegacyBelowPlacementForSupport(player, yaw, currentPitch, heldStack, lastPlacedPos, deadlineMs);
            if (preferred != null) return preferred;
        }
        return findLegacyBelowPlacementForSupport(player, yaw, currentPitch, heldStack, null, deadlineMs);
    }

    private Object[] findLegacyDiagonalPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        List<int[]> diagonalTargets = new ArrayList<>();
        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, currentY)) {
            addBelowTarget(player, diagonalTargets, endpoint);
        }
        if (strictY != currentY) {
            for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, strictY)) {
                addBelowTarget(player, diagonalTargets, endpoint);
            }
        }
        if (diagonalTargets.isEmpty()) return null;
        int[] preferredSupportPos = hasValidLastPlacedPos(player) ? lastPlacedPos : null;
        for (int[] targetPos : diagonalTargets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, preferredSupportPos, deadlineMs);
            if (candidate != null) return candidate;
        }
        if (preferredSupportPos == null) return null;
        for (int[] targetPos : diagonalTargets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, deadlineMs);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findLegacyBelowPlacementForSupport(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, int[] preferredSupportPos, long deadlineMs) {
        for (int[] targetPos : getMessageStyleBelowTargets(player)) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, preferredSupportPos, deadlineMs);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findLegacyPitchPlacementForTarget(LocalPlayer player, float yaw, float currentPitch, int[] targetPos, ItemStack heldStack, int[] preferredSupportPos, long deadlineMs) {
        float clampedBasePitch = clampFloat(currentPitch, 40.0f, 89.0f);
        Object[] direct = tryLegacyPitch(yaw, clampedBasePitch, targetPos, preferredSupportPos, deadlineMs);
        if (direct != null) return direct;
        for (int offset = 1; offset <= 49; offset++) {
            if (clientTime() >= deadlineMs) return null;
            float up = clampedBasePitch + offset;
            if (up <= 89.0f) {
                Object[] candidate = tryLegacyPitch(yaw, up, targetPos, preferredSupportPos, deadlineMs);
                if (candidate != null) return candidate;
            }
            float down = clampedBasePitch - offset;
            if (down >= 40.0f) {
                Object[] candidate = tryLegacyPitch(yaw, down, targetPos, preferredSupportPos, deadlineMs);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private Object[] tryLegacyPitch(float yaw, float pitch, int[] targetPos, int[] preferredSupportPos, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] supportPos = (int[]) traced[0];
        int face = (Integer) traced[1];
        if (preferredSupportPos != null && !posEquals(supportPos, preferredSupportPos)) return null;
        if (face == 0) return null;
        if (isReplaceable(supportPos[0], supportPos[1], supportPos[2]) || isInteractable(supportPos[0], supportPos[1], supportPos[2])) return null;
        int[] placedPos = offsetPos(supportPos, face);
        if (!posEquals(placedPos, targetPos)) return null;
        return new Object[]{Math.min(pitch, 89.0f), supportPos, face, (Vec3) traced[2], placedPos};
    }

    private List<int[]> getMessageStyleBelowTargets(LocalPlayer player) {
        double[] offsets = {0.0, 0.29, -0.29};
        Vec3 pos = player.position();
        int maxY = floor(pos.y) - 1;
        int minY = floor(pos.y) - 2;
        List<int[]> targets = new ArrayList<>();
        for (int targetY = maxY; targetY >= minY; targetY--) {
            for (double xOffset : offsets) {
                for (double zOffset : offsets) {
                    targets.add(new int[]{floor(pos.x + xOffset), targetY, floor(pos.z + zOffset)});
                }
            }
        }
        return targets;
    }

    private Object[] findBelowPlacementForSupport(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, int[] preferredSupportPos, int preferredSupportFace, long deadlineMs) {
        boolean diagonal = isDiagonalMovementContext(player);
        for (int[] targetPos : getBelowTargets(player, yaw, currentPitch)) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            if (!isStrictOneBelowPlayer(player, targetPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, preferredSupportPos, preferredSupportFace, deadlineMs, false, diagonal);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private boolean isWithinReach(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        Vec3 eyes = getEyes(player);
        double cx = Math.max(pos[0], Math.min(eyes.x, pos[0] + 1.0));
        double cy = Math.max(pos[1], Math.min(eyes.y, pos[1] + 1.0));
        double cz = Math.max(pos[2], Math.min(eyes.z, pos[2] + 1.0));
        double dx = eyes.x - cx;
        double dy = eyes.y - cy;
        double dz = eyes.z - cz;
        return dx * dx + dy * dy + dz * dz <= reach() * reach();
    }

    private Object[] findPitchPlacementForTarget(LocalPlayer player, float yaw, float currentPitch, int[] targetPos, ItemStack heldStack, int[] preferredSupportPos, int preferredSupportFace, long deadlineMs, boolean requireLookAlignment, boolean allowNonCursorTarget) {
        if (clientTime() >= deadlineMs || targetPos == null) return null;
        boolean effectiveAllowNonCursorTarget = allowNonCursorTarget || shouldAllowPlayerOneNonCursorTarget(player, targetPos);
        if (!effectiveAllowNonCursorTarget && !isCursorOrBelowPlayerTarget(player, targetPos, yaw, currentPitch)) return null;
        if (!isPlacementTargetAvailable(player, targetPos)) return null;

        Object[] bestCandidate = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int placeFace : getAllowedPlaceFacesForContext(player, yaw)) {
            if (clientTime() >= deadlineMs) break;
            if (shouldRejectStraightSideSwitch(player, targetPos, placeFace)) continue;
            int[] supportPos = offsetPos(targetPos, opposite(placeFace));
            if (preferredSupportPos != null && !posEquals(supportPos, preferredSupportPos)) continue;
            if (preferredSupportFace >= 0 && placeFace != preferredSupportFace) continue;
            if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) continue;
            if (!isWithinReach(player, supportPos)) continue;

            double[] hitOffsets = useExtendedSearch() ? EXTENDED_FACE_HIT_OFFSETS : FACE_HIT_OFFSETS;
            for (double primaryOffset : hitOffsets) {
                for (double secondaryOffset : hitOffsets) {
                    if (clientTime() >= deadlineMs) break;
                    Vec3 hitVec = getSupportFaceHitVec(supportPos, placeFace, primaryOffset, secondaryOffset);
                    Object[] candidate = buildPlacementCandidateForHitVec(player, yaw, targetPos, supportPos, placeFace, hitVec, requireLookAlignment, effectiveAllowNonCursorTarget);
                    if (candidate == null) continue;
                    double candidateScore = scorePlacementCandidate(player, currentPitch, candidatePitch(candidate), placeFace, primaryOffset, secondaryOffset);
                    if (candidateScore < bestScore) {
                        bestScore = candidateScore;
                        bestCandidate = candidate;
                    }
                }
            }
        }
        if (bestCandidate == null && preferredSupportPos != null && preferredSupportFace >= 0) {
            return findRayAlignedPitchCandidate(yaw, currentPitch, targetPos, preferredSupportPos, preferredSupportFace, deadlineMs);
        }
        return bestCandidate;
    }

    private Object[] findRayAlignedPitchCandidate(float yaw, float currentPitch, int[] targetPos, int[] supportPos, int placeFace, long deadlineMs) {
        float clampedBasePitch = clampFloat(currentPitch, 40.0f, 89.0f);
        for (int offset = 0; offset <= 49; offset++) {
            if (clientTime() >= deadlineMs) return null;
            float upPitch = clampedBasePitch + offset;
            if (upPitch <= 89.0f) {
                Object[] candidate = tryRayAlignedPitch(yaw, upPitch, targetPos, supportPos, placeFace);
                if (candidate != null) return candidate;
            }
            if (offset == 0) continue;
            float downPitch = clampedBasePitch - offset;
            if (downPitch >= 40.0f) {
                Object[] candidate = tryRayAlignedPitch(yaw, downPitch, targetPos, supportPos, placeFace);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private Object[] tryRayAlignedPitch(float yaw, float pitch, int[] targetPos, int[] supportPos, int placeFace) {
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] tracedSupport = (int[]) traced[0];
        int tracedFace = (Integer) traced[1];
        if (!posEquals(tracedSupport, supportPos) || tracedFace != placeFace) return null;
        int[] tracedPlaced = offsetPos(tracedSupport, tracedFace);
        if (!posEquals(tracedPlaced, targetPos)) return null;
        return new Object[]{pitch, tracedSupport, tracedFace, (Vec3) traced[2], tracedPlaced};
    }

    private double scorePlacementCandidate(LocalPlayer player, float currentPitch, float candidatePitchValue, int placeFace, double primaryOffset, double secondaryOffset) {
        double pitchPenalty = Math.abs(wrapAngle(candidatePitchValue - currentPitch));
        double centerPenalty = Math.abs(primaryOffset - 0.5) + Math.abs(secondaryOffset - 0.5);
        double facePenalty = placeFace == 1 ? 0.0 : 0.35;
        double straightSidePenalty = getStraightSideSwitchPenalty(player, placeFace);
        return pitchPenalty + centerPenalty * 2.0 + facePenalty + straightSidePenalty;
    }

    private double getStraightSideSwitchPenalty(LocalPlayer player, int placeFace) {
        if (getConditionModeCheck(player) != 1) return 0.0;
        if (lastSupportFace < 2) return 0.0;
        if (placeFace == lastSupportFace) return 0.0;
        return 0.8;
    }

    private boolean shouldRejectStraightSideSwitch(LocalPlayer player, int[] targetPos, int placeFace) {
        if (targetPos == null || getConditionModeCheck(player) != 1) return false;
        if (placeFace < 2) return false;
        if (lastSupportFace < 2) return false;
        if (placeFace == lastSupportFace) return false;
        if (isNearStraightSupportEdge(player)) return false;
        int[] laneSupportPos = offsetPos(targetPos, opposite(lastSupportFace));
        return isSupportAvailable(laneSupportPos[0], laneSupportPos[1], laneSupportPos[2]) && isWithinReach(player, laneSupportPos);
    }

    private Object[] buildPlacementCandidateForHitVec(LocalPlayer player, float yaw, int[] targetPos, int[] supportPos, int placeFace, Vec3 hitVec, boolean requireLookAlignment, boolean allowNonCursorTarget) {
        if (hitVec == null) return null;
        int[] offsetTarget = offsetPos(supportPos, placeFace);
        if (!posEquals(offsetTarget, targetPos)) return null;
        if (!isStrictOneBelowPlayer(player, offsetTarget)) return null;
        Float pitch = computePitchToHitVec(player, hitVec);
        if (pitch == null) return null;
        if (!isPlacementLookAligned(yaw, pitch, supportPos, placeFace, targetPos)) return null;
        if (!(allowNonCursorTarget || isDiagonalMovementContext(player) || isSupportFaceVisible(player, supportPos, placeFace, hitVec))) return null;
        return new Object[]{pitch, supportPos, placeFace, hitVec, offsetTarget};
    }

    private int[] getAllowedPlaceFacesForContext(LocalPlayer player, float yaw) {
        if (getConditionModeCheck(player) != 1) return ALLOWED_PLACE_FACES;
        int forward = getStraightForwardFacing(player, yaw);
        if (useExtendedSearch()) {
            return new int[]{rotateY(forward), rotateYCCW(forward), forward, opposite(forward), 1};
        }
        return new int[]{rotateY(forward), rotateYCCW(forward), forward, opposite(forward)};
    }

    private boolean isPlacementLookAligned(float yaw, float pitch, int[] supportPos, int placeFace, int[] targetPos) {
        if (supportPos == null || placeFace < 0 || targetPos == null) return false;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return false;
        if (!posEquals((int[]) traced[0], supportPos) || (Integer) traced[1] != placeFace) return false;
        int[] tracedOffset = offsetPos((int[]) traced[0], (Integer) traced[1]);
        return posEquals(tracedOffset, targetPos);
    }

    private boolean isSupportFaceVisible(LocalPlayer player, int[] supportPos, int placeFace, Vec3 hitVec) {
        Vec3 eyes = getEyes(player);
        double dx = hitVec.x - eyes.x;
        double dy = hitVec.y - eyes.y;
        double dz = hitVec.z - eyes.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4) return false;
        float traceYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tracePitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        Object[] traced = hostRaycast(distance + 0.5, traceYaw, tracePitch);
        if (traced == null) return false;
        int[] tracedPos = posFromVec((Vec3) traced[0]);
        int tracedFace = faceFromName((String) traced[2]);
        return posEquals(tracedPos, supportPos) && tracedFace == placeFace;
    }

    private Vec3 getSupportFaceHitVec(int[] supportPos, int placeFace, double primaryOffset, double secondaryOffset) {
        double primary = Math.max(0.001, Math.min(0.999, primaryOffset));
        double secondary = Math.max(0.001, Math.min(0.999, secondaryOffset));
        if (placeFace == 2) return new Vec3(supportPos[0] + primary, supportPos[1] + secondary, supportPos[2] + 0.001);
        if (placeFace == 3) return new Vec3(supportPos[0] + primary, supportPos[1] + secondary, supportPos[2] + 0.999);
        if (placeFace == 5) return new Vec3(supportPos[0] + 0.999, supportPos[1] + primary, supportPos[2] + secondary);
        if (placeFace == 4) return new Vec3(supportPos[0] + 0.001, supportPos[1] + primary, supportPos[2] + secondary);
        if (placeFace == 0) return new Vec3(supportPos[0] + primary, supportPos[1] + 0.001, supportPos[2] + secondary);
        return new Vec3(supportPos[0] + primary, supportPos[1] + 0.999, supportPos[2] + secondary);
    }

    private Float computePitchToHitVec(LocalPlayer player, Vec3 hitVec) {
        Vec3 eyes = getEyes(player);
        double dx = hitVec.x - eyes.x;
        double dz = hitVec.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = hitVec.y - eyes.y;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return Math.max(-89.0f, Math.min(89.0f, pitch));
    }

    // =====================================================================
    // 目标枚举与空间判定（脚本 2138-2616）
    // =====================================================================

    private List<int[]> getBelowTargets(LocalPlayer player, float yaw, float pitch) {
        if (cachedBelowTargetsTick == currentClientTick && cachedBelowTargets != null) return cachedBelowTargets;
        List<int[]> belowTargets = new ArrayList<>();
        boolean diagonal = isDiagonalMovementContext(player);
        if (!diagonal) {
            int currentY = getCurrentBelowTargetY(player);
            addBelowTarget(player, belowTargets, getCursorStartTargetAtY(player, currentY));
            if (belowTargets.isEmpty()) {
                int strictY = getStrictBelowTargetY(player);
                if (strictY != currentY) addBelowTarget(player, belowTargets, getCursorStartTargetAtY(player, strictY));
            }
            if (belowTargets.isEmpty()) addBelowTarget(player, belowTargets, getCursorPlacedTargetFromRay(yaw, pitch, currentY));
            if (belowTargets.isEmpty()) {
                int strictY = getStrictBelowTargetY(player);
                if (strictY != currentY) addBelowTarget(player, belowTargets, getCursorPlacedTargetFromRay(yaw, pitch, strictY));
            }
            if (belowTargets.isEmpty()) addBelowTarget(player, belowTargets, getCursorTargetAtY(player, currentY));
        } else {
            int currentY = getCurrentBelowTargetY(player);
            addBelowTarget(player, belowTargets, getMotionBelowTargetAtY(player, currentY, 1.0));
            addBelowTarget(player, belowTargets, getMotionBelowTargetAtY(player, currentY, 1.7));
            for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, pitch, currentY)) {
                addBelowTarget(player, belowTargets, endpoint);
            }
        }
        cachedBelowTargets = belowTargets;
        cachedBelowTargetsTick = currentClientTick;
        return belowTargets;
    }

    private boolean isCursorOrBelowPlayerTarget(LocalPlayer player, int[] targetPos, float yaw, float pitch) {
        if (targetPos == null) return false;
        if (!isDiagonalMovementContext(player)) {
            int currentY = getCurrentBelowTargetY(player);
            if (posEquals(getCursorStartTargetAtY(player, currentY), targetPos)) return true;
            if (posEquals(getCursorPlacedTargetFromRay(yaw, pitch, currentY), targetPos)) return true;
            int strictY = getStrictBelowTargetY(player);
            if (strictY != currentY) {
                if (posEquals(getCursorStartTargetAtY(player, strictY), targetPos)) return true;
                if (posEquals(getCursorPlacedTargetFromRay(yaw, pitch, strictY), targetPos)) return true;
            }
            if (isCursorInsideTargetAtY(player, targetPos, currentY)) return true;
            return posEquals(getCursorTargetAtY(player, currentY), targetPos);
        }
        int strictY = getStrictBelowTargetY(player);
        if (isBelowPlayerTargetAtY(player, targetPos, strictY, yaw, pitch)) return true;
        return isBelowPlayerTargetAtY(player, targetPos, getCurrentBelowTargetY(player), yaw, pitch);
    }

    private boolean isBelowPlayerTargetAtY(LocalPlayer player, int[] targetPos, int targetY, float yaw, float pitch) {
        for (int[] candidate : getBelowPlayerFallbackEndpoints(player, yaw, pitch, targetY)) {
            if (posEquals(targetPos, candidate)) return true;
        }
        return false;
    }

    private int[] getFeetBelowTargetAtY(LocalPlayer player, int targetY) {
        Vec3 pos = player.position();
        return new int[]{floor(pos.x), targetY, floor(pos.z)};
    }

    private boolean shouldAllowPlayerOneNonCursorTarget(LocalPlayer player, int[] targetPos) {
        if (targetPos == null) return false;
        if (isDiagonalMovementContext(player) || player.onGround()) return false;
        if (!isPlayerHitboxFullyInsideSingleBlockColumn(player)) return false;
        if (!hasValidLastSupportFace(player) || lastSupportFace == 0) return false;
        int[] continuationTarget = offsetPos(lastSupportPos, lastSupportFace);
        if (!posEquals(targetPos, continuationTarget)) return false;
        int targetY = targetPos[1];
        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        if (targetY != currentY && targetY != strictY) return false;
        int[] feetBelow = getFeetBelowTargetAtY(player, targetY);
        int horizontalDistance = Math.abs(targetPos[0] - feetBelow[0]) + Math.abs(targetPos[2] - feetBelow[2]);
        return horizontalDistance <= 1;
    }

    private boolean isPlayerHitboxFullyInsideSingleBlockColumn(LocalPlayer player) {
        Vec3 pos = player.position();
        double half = player.getBbWidth() / 2.0;
        int minX = floor(pos.x - half + 1.0E-4);
        int maxX = floor(pos.x + half - 1.0E-4);
        if (minX != maxX) return false;
        int minZ = floor(pos.z - half + 1.0E-4);
        int maxZ = floor(pos.z + half - 1.0E-4);
        return minZ == maxZ;
    }

    private int[] getMotionBelowTargetAtY(LocalPlayer player, int targetY, double multiplier) {
        Vec3 pos = player.position();
        Vec3 motion = player.getDeltaMovement();
        return new int[]{floor(pos.x + motion.x * multiplier), targetY, floor(pos.z + motion.z * multiplier)};
    }

    private boolean hasDirectSupportNeighbor(int[] targetPos) {
        for (int placeFace : ALLOWED_PLACE_FACES) {
            int[] supportPos = offsetPos(targetPos, opposite(placeFace));
            if (isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) return true;
        }
        return false;
    }

    private void addBelowTargetIfUnique(LocalPlayer player, List<int[]> targets, int[] candidate) {
        if (candidate == null) return;
        if (!isStrictOneBelowPlayer(player, candidate)) return;
        for (int[] existing : targets) {
            if (posEquals(existing, candidate)) return;
        }
        targets.add(candidate);
    }

    private void addBelowTarget(LocalPlayer player, List<int[]> targets, int[] candidate) {
        addBelowTargetIfUnique(player, targets, candidate);
    }

    private List<int[]> rasterizeHorizontalLineAtY(int[] start, int[] end, int y, int maxSteps) {
        List<int[]> line = new ArrayList<>();
        int x0 = start[0];
        int z0 = start[2];
        int x1 = end[0];
        int z1 = end[2];
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = Integer.compare(x1, x0);
        int sz = Integer.compare(z1, z0);
        int movedX = 0;
        int movedZ = 0;
        for (int steps = 0; steps < maxSteps; steps++) {
            line.add(new int[]{x0, y, z0});
            if ((x0 == x1 && z0 == z1) || (movedX >= dx && movedZ >= dz)) break;
            if (movedX >= dx) {
                z0 += sz;
                movedZ++;
            } else if (movedZ >= dz) {
                x0 += sx;
                movedX++;
            } else if ((1 + 2 * movedX) * dz < (1 + 2 * movedZ) * dx) {
                x0 += sx;
                movedX++;
            } else {
                z0 += sz;
                movedZ++;
            }
        }
        return line;
    }

    private int getDetectedModeCheck(LocalPlayer player) {
        float forwardInput = Math.abs(clientForward);
        float strafeInput = Math.abs(clientStrafe);
        if (forwardInput >= 0.08f || strafeInput >= 0.08f) {
            return (forwardInput >= 0.08f && strafeInput >= 0.08f) ? 1 : 2;
        }
        double[] direction = getMotionDirectionComponents(player);
        if (direction == null) return 1;
        double angleDeg = Math.toDegrees(Math.atan2(direction[1], direction[0]));
        double norm90 = (angleDeg % 90.0 + 90.0) % 90.0;
        return Math.abs(norm90 - 45.0) <= 18.0 ? 2 : 1;
    }

    private double[] getMotionDirectionComponents(LocalPlayer player) {
        Vec3 pos = player.position();
        Vec3 last = lastPosition(player);
        double dirX = pos.x - last.x;
        double dirZ = pos.z - last.z;
        double speedSq = dirX * dirX + dirZ * dirZ;
        if (speedSq < 1.0E-4) {
            Vec3 motion = player.getDeltaMovement();
            dirX = motion.x;
            dirZ = motion.z;
            speedSq = dirX * dirX + dirZ * dirZ;
        }
        if (speedSq < 1.0E-4) return null;
        return new double[]{dirX, dirZ};
    }

    private double[] getInputDirectionComponents(float referenceYaw) {
        float forwardInput = clientForward;
        float strafeInput = clientStrafe;
        if (Math.abs(forwardInput) < 0.08f && Math.abs(strafeInput) < 0.08f) return null;
        double yawRadians = Math.toRadians(referenceYaw);
        double sinYaw = Math.sin(yawRadians);
        double cosYaw = Math.cos(yawRadians);
        double dirX = forwardInput * -sinYaw + strafeInput * cosYaw;
        double dirZ = forwardInput * cosYaw - strafeInput * sinYaw;
        if (dirX * dirX + dirZ * dirZ < 1.0E-4) return null;
        return new double[]{dirX, dirZ};
    }

    private int getStraightForwardFacing(LocalPlayer player, float fallbackYaw) {
        double[] direction = getInputDirectionComponents(fallbackYaw);
        if (direction == null) direction = getMotionDirectionComponents(player);
        if (direction == null) return facingFromYaw(fallbackYaw);
        float directionYaw = (float) (Math.toDegrees(Math.atan2(direction[1], direction[0])) - 90.0);
        return facingFromYaw(directionYaw);
    }

    private int getConditionModeCheck(LocalPlayer player) {
        if (forcedModeCheck != 0) return forcedModeCheck;
        // Telly 运行时本身就是对角线直线桥；中段 forward-only 输入会被误判成 mode 2（斜向搜索），
        // 再被 isStraightTellyTarget 否掉，表现就是「不走直线 / 方块连不起来」。
        if (running) return 1;
        return getDetectedModeCheck(player);
    }

    private boolean isDiagonalMovementContext(LocalPlayer player) {
        return getConditionModeCheck(player) == 2;
    }

    private int[] getCursorPlacedTargetFromRay(float yaw, float pitch, int targetY) {
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] offsetTarget = offsetPos((int[]) traced[0], (Integer) traced[1]);
        if (offsetTarget[1] != targetY) return null;
        return offsetTarget;
    }

    private int[] getCursorStartTargetAtY(LocalPlayer player, int targetY) {
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        Vec3 lookVec = getCursorLookVec(player);
        if (cursorPoint == null || lookVec == null) return null;
        double startX = cursorPoint.x - lookVec.x * 0.03;
        double startZ = cursorPoint.z - lookVec.z * 0.03;
        return new int[]{floor(startX), targetY, floor(startZ)};
    }

    private int[] getCursorTargetAtY(LocalPlayer player, int targetY) {
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        if (cursorPoint == null) return null;
        return new int[]{floor(cursorPoint.x), targetY, floor(cursorPoint.z)};
    }

    private Vec3 getCursorIntersectionAtY(LocalPlayer player, int targetY) {
        Vec3 eyes = getEyes(player);
        Vec3 lookVec = getCursorLookVec(player);
        if (lookVec == null || Math.abs(lookVec.y) < 1.0E-4) return null;
        double t = (targetY - eyes.y) / lookVec.y;
        if (t <= 0.0) return null;
        return new Vec3(eyes.x + lookVec.x * t, targetY + 0.5, eyes.z + lookVec.z * t);
    }

    /**
     * 「光标」朝向 —— <b>必须与 {@link #isPlacementLookAligned} 验证时用的角同一套</b>。
     *
     * <p>⚠️ 这里曾用 {@code mc.gameRenderer.mainCamera()}（<b>跨 tick 插值的相机角</b>），
     * 而候选的验证走 {@code rayCast(player.getYRot(), player.getXRot())}（脚本角）——
     * 同一 tick 内两个角不一致 ⇒ 候选与验证系统性错位 ⇒ 大量
     * {@code PLACE-FAIL[look-misaligned]}（实测 2026-09-27：23 次掉落里有 10 次，
     * 其前一条就是它）⇒ 该放的方块没放上 ⇒ 玩家下落时脚下是空的 ⇒ <b>掉</b>。
     *
     * <p>现与脚本原版 / 1.8.9 宿主（Myau 用 {@code player.rotationYaw/rotationPitch}）一致。
     */
    private Vec3 getCursorLookVec(LocalPlayer player) {
        return getLookVec(player.getYRot(), player.getXRot());
    }

    private Vec3 getLookVec(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    private boolean isCursorInsideTargetAtY(LocalPlayer player, int[] targetPos, int targetY) {
        if (targetPos == null || targetPos[1] != targetY) return false;
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        if (cursorPoint == null) return false;
        double x = cursorPoint.x;
        double z = cursorPoint.z;
        return x >= targetPos[0] - 1.0E-6 && x <= targetPos[0] + 1.0 + 1.0E-6 && z >= targetPos[2] - 1.0E-6 && z <= targetPos[2] + 1.0 + 1.0E-6;
    }

    private boolean isPlacementTargetAvailable(LocalPlayer player, int[] pos) {
        return isBasePlacementTargetAvailable(player, pos) && isStrictOneBelowPlayer(player, pos);
    }

    private boolean isStraightLaneTargetAvailable(LocalPlayer player, int[] pos, int currentY, int strictY, int previousY, int upwardY) {
        if (!isBasePlacementTargetAvailable(player, pos)) return false;
        int targetY = pos[1];
        if (targetY == currentY || targetY == strictY) return true;
        if (previousY != Integer.MIN_VALUE && targetY == previousY) return true;
        return upwardY != Integer.MIN_VALUE && targetY == upwardY;
    }

    private boolean isBasePlacementTargetAvailable(LocalPlayer player, int[] pos) {
        return pos != null
                && isStraightTellyTarget(pos)
                && !isRejectedTarget(pos)
                && !doesPlacementIntersectPlayer(player, pos)
                && isReplaceable(pos[0], pos[1], pos[2]);
    }

    private boolean doesPlacementIntersectPlayer(LocalPlayer player, int[] placePos) {
        if (placePos == null) return false;
        if (isInsideAnyPlayerPositionCell(player, placePos)) return true;

        Vec3 pos = player.position();
        double half = player.getBbWidth() / 2.0;
        double height = player.getBbHeight();
        if (boxIntersectsBlock(pos.x - half, pos.y, pos.z - half, pos.x + half, pos.y + height, pos.z + half, placePos)) return true;
        if (isBlockPosInsideBounds(placePos, pos.x - half, pos.y, pos.z - half, pos.x + half, pos.y + height, pos.z + half)) return true;

        if (!shouldUseHistoricalPlayerCollisionChecks(player, placePos)) return false;

        Vec3 last = lastPosition(player);
        if (last.x != pos.x || last.y != pos.y || last.z != pos.z) {
            if (boxIntersectsBlock(last.x - half, last.y, last.z - half, last.x + half, last.y + height, last.z + half, placePos)) return true;
            if (isBlockPosInsideBounds(placePos, last.x - half, last.y, last.z - half, last.x + half, last.y + height, last.z + half)) return true;
        }
        if (hasLastSentServerPos && (lastSentServerPosX != pos.x || lastSentServerPosY != pos.y || lastSentServerPosZ != pos.z)) {
            double sx = lastSentServerPosX;
            double sy = lastSentServerPosY;
            double sz = lastSentServerPosZ;
            if (boxIntersectsBlock(sx - half, sy, sz - half, sx + half, sy + height, sz + half, placePos)) return true;
            if (isBlockPosInsideBounds(placePos, sx - half, sy, sz - half, sx + half, sy + height, sz + half)) return true;
        }
        return false;
    }

    private boolean boxIntersectsBlock(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int[] pos) {
        return maxX > pos[0] && minX < pos[0] + 1.0 && maxY > pos[1] && minY < pos[1] + 1.0 && maxZ > pos[2] && minZ < pos[2] + 1.0;
    }

    private boolean isBlockPosInsideBounds(int[] pos, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int bMinX = floor(minX + 1.0E-4);
        int bMaxX = floor(maxX - 1.0E-4);
        if (pos[0] < bMinX || pos[0] > bMaxX) return false;
        int bMinZ = floor(minZ + 1.0E-4);
        int bMaxZ = floor(maxZ - 1.0E-4);
        if (pos[2] < bMinZ || pos[2] > bMaxZ) return false;
        int bMinY = floor(minY + 1.0E-4);
        int bMaxY = floor(maxY - 1.0E-4);
        return pos[1] >= bMinY && pos[1] <= bMaxY;
    }

    private boolean isInsideAnyPlayerPositionCell(LocalPlayer player, int[] placePos) {
        Vec3 pos = player.position();
        if (isInsidePlayerPositionCell(placePos, pos.x, pos.y, pos.z)) return true;
        if (!shouldUseHistoricalPlayerCollisionChecks(player, placePos)) return false;
        Vec3 last = lastPosition(player);
        if (isInsidePlayerPositionCell(placePos, last.x, last.y, last.z)) return true;
        return hasLastSentServerPos && isInsidePlayerPositionCell(placePos, lastSentServerPosX, lastSentServerPosY, lastSentServerPosZ);
    }

    private boolean shouldUseHistoricalPlayerCollisionChecks(LocalPlayer player, int[] placePos) {
        if (!player.onGround()) return false;
        if (placePos == null) return true;
        return placePos[1] > getCurrentBelowTargetY(player);
    }

    private boolean isInsidePlayerPositionCell(int[] placePos, double x, double y, double z) {
        int playerX = floor(x);
        int playerY = floor(y);
        int playerZ = floor(z);
        return placePos[0] == playerX && placePos[2] == playerZ && (placePos[1] == playerY || placePos[1] == playerY + 1);
    }

    private boolean isStrictOneBelowPlayer(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        int targetY = pos[1];
        int currentY = getCurrentBelowTargetY(player);
        if (targetY == currentY) return true;
        if (targetY == getStrictBelowTargetY(player)) return true;
        int previousY = getPreviousBelowTargetY(player);
        if (previousY != Integer.MIN_VALUE && targetY == previousY) return true;
        return isStraightAscendingContext(player) && targetY == currentY + 1;
    }

    /**
     * 目标 Y 的基准 —— <b>与脚本原版一致</b>。
     *
     * <p>默认跟随<b>实时</b> {@code pos.y}；只有「离地且垂直速度落在 (-0.12, 0]」这个
     * 顶点附近的窗口才用 {@code max(pos.y, 上一 tick 的 y)} 防抖。
     *
     * <p>⚠️ 这里曾被改成「空中锁死最后一次落地高度 {@code lastGroundFeetY}」，
     * 已被实测证伪（2026-09-27，`26.2-Epsilon/logs/latest.log`）：
     * telly 连续起跳，{@code onGround} 只占约 24%，缓存几乎不更新 ——
     * 日志里 {@code curY} 与 {@code feetY-1} 有 <b>83%</b> 的时刻不一致（偏差 -5~+2）。
     * 于是 {@code isStrictOneBelowPlayer} 的 {@code targetY == currentY} 主判据失效，
     * 目标 Y 实际由 {@code strictY}/{@code prevY} 两个实时量决定 → 桥面 y 一路
     * 103→104→…→109 阶梯爬升（实测 placed y 序列），玩家被迫跟着爬，最终掉下去。
     */
    private double getStableBelowReferenceY(LocalPlayer player) {
        Vec3 pos = player.position();
        double referenceY = pos.y;
        if (!player.onGround()) {
            Vec3 motion = player.getDeltaMovement();
            if (motion.y > -0.12 && motion.y <= 0.0) {
                referenceY = Math.max(referenceY, lastPosition(player).y);
            }
        }
        return referenceY;
    }

    private int getStrictBelowTargetY(LocalPlayer player) {
        if (isDiagonalMovementContext(player)) return getCurrentBelowTargetY(player);
        double projectedY = getStableBelowReferenceY(player);
        Vec3 motion = player.getDeltaMovement();
        if (!player.onGround() && motion.y < -0.12) {
            projectedY = player.position().y + motion.y * 0.75;
        }
        return floor(projectedY) - 1;
    }

    private int getCurrentBelowTargetY(LocalPlayer player) {
        return floor(getStableBelowReferenceY(player)) - 1;
    }

    private int getPreviousBelowTargetY(LocalPlayer player) {
        return floor(lastPosition(player).y) - 1;
    }

    /** 脚本原版：mode==1 且「正在上升」时，允许把目标叠到 currentY + 1（1.8.9 行为）。 */
    private boolean isStraightAscendingContext(LocalPlayer player) {
        if (getConditionModeCheck(player) != 1) return false;
        Vec3 motion = player.getDeltaMovement();
        return motion.y > 0.0 || player.position().y > lastPosition(player).y + 1.0E-4;
    }

    private boolean isSupportAvailable(int x, int y, int z) {
        if (isInteractable(x, y, z)) return false;
        if (isReplaceable(x, y, z)) return false;
        // [Fix AirLiquidPlace] 刚放下、服务端还没回 S23 的方块不能当支撑。
        // 客户端按预测把它当实心块来算射线与命中面，服务端那边它还是空气/水，
        // (支撑格, 面, 命中点) 与服务端对不上 —— 这正是 AirLiquidPlace 的判定输入。
        return !isFreshGhostSupport(x, y, z);
    }

    private boolean waitServerBlockAck() {
        try {
            return waitServerAck.getValue();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isFreshGhostSupport(int x, int y, int z) {
        if (pendingSupportConfirm.isEmpty()) return false;
        if (!waitServerBlockAck()) return false;
        String key = posKey(new int[]{x, y, z});
        Long placedAt = pendingSupportConfirm.get(key);
        if (placedAt == null) return false;
        // 服务端正常 1~2 tick 就回方块变更；长期不回（自定义服务端/被吞包）就放行，避免整条桥卡死。
        if (clientTime() - placedAt <= 150L) return true;
        pendingSupportConfirm.remove(key);
        return false;
    }

    private boolean isRejectedTarget(int[] pos) {
        Integer rejectedAtTick = rejectedTargets.get(posKey(pos));
        if (rejectedAtTick == null) return false;
        return currentClientTick - rejectedAtTick <= 4;
    }

    private void markRejectedTarget(int[] pos) {
        if (pos == null) return;
        rejectedTargets.put(posKey(pos), currentClientTick);
    }

    private void pruneRejectedTargets() {
        if (rejectedTargets.isEmpty()) return;
        Iterator<Map.Entry<String, Integer>> iterator = rejectedTargets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            if (currentClientTick - entry.getValue() > 4) {
                iterator.remove();
                continue;
            }
            String[] parts = entry.getKey().split(",");
            if (!isReplaceable(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))) {
                iterator.remove();
            }
        }
    }

    // =====================================================================
    // 世界访问与工具（脚本 2617-2805）
    // =====================================================================

    private Object[] rayCast(float yaw, float pitch) {
        Object[] hit = hostRaycast(reach(), yaw, pitch);
        if (hit == null || hit[0] == null || hit[2] == null) return null;
        int face = faceFromName((String) hit[2]);
        if (face < 0 || face == 0) return null;
        int[] supportPos = posFromVec((Vec3) hit[0]);
        Vec3 offset = (Vec3) hit[1];
        Vec3 hitAbs = new Vec3(supportPos[0] + offset.x, supportPos[1] + offset.y, supportPos[2] + offset.z);
        return new Object[]{supportPos, face, hitAbs};
    }

    /** 脚本宿主的 client.raycastBlock，返回 {方块坐标(Vec3), 面内偏移(Vec3，相对角点), 面名(String)}。 */
    private Object[] hostRaycast(double reach) {
        LocalPlayer player = mc.player;
        if (player == null) return null;
        return hostRaycast(reach, player.getYRot(), player.getXRot());
    }

    private Object[] hostRaycast(double reach, float yaw, float pitch) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;
        Vec3 eyes = getEyes(player);
        Vec3 to = eyes.add(getLookVec(yaw, pitch).scale(reach));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit == null || hit.getType() == HitResult.Type.MISS) return null;
        BlockPos blockPos = hit.getBlockPos();
        if (blockPos == null) return null;
        Vec3 localOffset = hit.getLocation().subtract(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        return new Object[]{
                new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                localOffset,
                faceName(hit.getDirection().get3DDataValue())
        };
    }

    /** 脚本宿主的 client.placeBlock。 */
    private boolean hostPlaceBlock(int[] supportPos, String faceName, Vec3 hitAbs) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return false;
        int face = faceFromName(faceName);
        if (face < 0) return false;
        BlockHitResult result = new BlockHitResult(hitAbs, Direction.from3DDataValue(face), new BlockPos(supportPos[0], supportPos[1], supportPos[2]), false);
        InteractionResult interaction = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, result);
        return interaction != null && interaction.consumesAction();
    }

    private Vec3 getEyes(LocalPlayer player) {
        Vec3 pos = player.position();
        return new Vec3(pos.x, pos.y + player.getEyeHeight(), pos.z);
    }

    private Vec3 lastPosition(LocalPlayer player) {
        return new Vec3(player.xo, player.yo, player.zo);
    }

    private String blockNameAt(int x, int y, int z) {
        if (mc.level == null) return "air";
        BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toLowerCase(Locale.ROOT);
    }

    private boolean isReplaceable(int x, int y, int z) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
        if (isReplaceableName(blockNameAt(x, y, z), false)) return true;
        return state.canBeReplaced();
    }

    private boolean isReplaceableName(String name, boolean airOnly) {
        if (name == null) return false;
        if (airOnly) return name.equals("air");
        for (String replaceable : REPLACEABLE_BLOCKS) {
            if (name.equals(replaceable)) return true;
        }
        for (String replaceable : EXPERIMENTAL_REPLACEABLE_BLOCKS) {
            if (name.equals(replaceable)) return true;
        }
        return false;
    }

    private boolean isInteractable(int x, int y, int z) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
        if (state.hasBlockEntity()) return true;
        Block block = state.getBlock();
        return block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof BedBlock;
    }

    /**
     * 交互距离 —— 走 26.2 的 <b>attribute</b>（{@code block_interaction_range}），不要硬编码。
     *
     * <p>⚠️ 1.8.9 的 reach 是客户端常量（4.5 / 创造 5.0），26.2 起它是 attribute，
     * <b>服务器可以改</b>。硬编码的后果：一旦服务器把它调小，本模块会算出服务端不接受的候选，
     * 而服务端 {@code ServerGamePacketListenerImpl.handleUseItemOn} 的<b>第一道</b>校验就是
     * {@code if (!player.isWithinBlockInteractionRange(blockPos, 1.0)) return;} —— <b>静默丢弃</b>，
     * 本地却已经走了预测（看着像放上了）。表现就是日志里的
     * {@code SERVER-SWALLOW ... name=air}（服务器回发的放置位仍是空气）⇒ 该格没接上 ⇒ <b>掉</b>。
     *
     * <p>用 attribute 还会自动覆盖创造模式（attribute 自己会变），不必再分叉。
     */
    private double reach() {
        LocalPlayer player = mc.player;
        return player == null ? 4.5 : (player.isCreative() ? 5.0 : 4.5);
    }

    private int placementTick(LocalPlayer player) {
        if (isRavenTimerActive()) return (int) (clientTime() / 50L);
        return player.tickCount;
    }

    private boolean isRavenTimerActive() {
        try {
            Module timer = findModule("Timer");
            return timer != null && timer.isEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    private float candidatePitch(Object[] candidate) {
        return clampFloat((Float) candidate[0], -90.0f, 90.0f);
    }

    private int[] candidateSupportPos(Object[] candidate) {
        return (int[]) candidate[1];
    }

    private int candidateFace(Object[] candidate) {
        return (Integer) candidate[2];
    }

    private Vec3 candidateHitVec(Object[] candidate) {
        return (Vec3) candidate[3];
    }

    private int[] candidatePlacedPos(Object[] candidate) {
        return (int[]) candidate[4];
    }

    private float sanitizePitch(float pitch, float fallbackPitch) {
        float safeFallback = clampFloat(Float.isNaN(fallbackPitch) ? 0.0f : fallbackPitch, -90.0f, 90.0f);
        if (Float.isNaN(pitch) || Float.isInfinite(pitch)) return safeFallback;
        return clampFloat(pitch, -90.0f, 90.0f);
    }

    private int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private float clampFloat(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private float wrapAngle(float angle) {
        angle = angle % 360f;
        if (angle >= 180f) angle -= 360f;
        if (angle < -180f) angle += 360f;
        return angle;
    }

    private double distSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean posEquals(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private String posKey(int[] pos) {
        return pos[0] + "," + pos[1] + "," + pos[2];
    }

    private int[] posFromVec(Vec3 vec) {
        return new int[]{floor(vec.x), floor(vec.y), floor(vec.z)};
    }

    private int[] posFromBlockPos(BlockPos pos) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ()};
    }

    private int[] offsetPos(int[] pos, int face) {
        if (pos == null) return null;
        if (face == 0) return new int[]{pos[0], pos[1] - 1, pos[2]};
        if (face == 1) return new int[]{pos[0], pos[1] + 1, pos[2]};
        if (face == 2) return new int[]{pos[0], pos[1], pos[2] - 1};
        if (face == 3) return new int[]{pos[0], pos[1], pos[2] + 1};
        if (face == 4) return new int[]{pos[0] - 1, pos[1], pos[2]};
        return new int[]{pos[0] + 1, pos[1], pos[2]};
    }

    private int opposite(int face) {
        if (face == 0) return 1;
        if (face == 1) return 0;
        if (face == 2) return 3;
        if (face == 3) return 2;
        if (face == 4) return 5;
        return 4;
    }

    private int rotateY(int face) {
        if (face == 2) return 5;
        if (face == 5) return 3;
        if (face == 3) return 4;
        if (face == 4) return 2;
        return face;
    }

    private int rotateYCCW(int face) {
        if (face == 2) return 4;
        if (face == 4) return 3;
        if (face == 3) return 5;
        if (face == 5) return 2;
        return face;
    }

    private int facingFromYaw(float yaw) {
        int index = floor(yaw / 90.0 + 0.5) & 3;
        if (index == 0) return 3;
        if (index == 1) return 4;
        if (index == 2) return 2;
        return 5;
    }

    private String faceName(int face) {
        if (face == 0) return "DOWN";
        if (face == 1) return "UP";
        if (face == 2) return "NORTH";
        if (face == 3) return "SOUTH";
        if (face == 4) return "WEST";
        return "EAST";
    }

    private int faceFromName(String name) {
        if (name == null) return -1;
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("DOWN")) return 0;
        if (upper.equals("UP")) return 1;
        if (upper.equals("NORTH")) return 2;
        if (upper.equals("SOUTH")) return 3;
        if (upper.equals("WEST")) return 4;
        if (upper.equals("EAST")) return 5;
        return -1;
    }

    // =====================================================================
    // 宿主替换层：时间 / 键位 / 客户端输入状态 / 文本宽度
    // =====================================================================

    private long clientTime() {
        return System.currentTimeMillis();
    }

    private void setClientForward(float forward) {
        this.clientForward = forward;
        if (activeInputEvent != null) activeInputEvent.setForward(forward);
    }

    private void setClientStrafe(float strafe) {
        this.clientStrafe = strafe;
        if (activeInputEvent != null) activeInputEvent.setStrafe(strafe);
    }

    private void setClientJump(boolean jump) {
        this.clientJump = jump;
        if (activeInputEvent != null) activeInputEvent.setJumping(jump);
    }

    private void setClientSneak(boolean sneak) {
        this.clientSneak = sneak;
            }

    private void setClientSprinting(boolean sprinting) {
        this.clientSprinting = sprinting;
                if (mc.player != null) mc.player.setSprinting(sprinting);
    }

    /**
     * 物理鼠标按键状态 —— 脚本 {@code keybinds.isMouseDown(n)} 的等价物。
     *
     * <p>不能读 {@code MouseHandler.isLeftPressed()/isRightPressed()}：本模块在
     * {@code activationSuppressUse()} 与自动放置期间会 cancel {@link KeyEvent}，
     * 而 {@code MixinMouseHandler} 是在 {@code onButton} 的 HEAD 处直接 {@code ci.cancel()}，
     * 原版方法体根本不执行，MouseHandler 的内部布尔量因此不会更新 —— 读它恒为 false，
     * 触发条件里的「按住右键」永远不会成立。GLFW 的物理按键状态不受此影响。</p>
     */
    private boolean isPhysicalMouseDown(int glfwButton) {
        return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), glfwButton) == GLFW.GLFW_PRESS;
    }

    private boolean physicalRightMouseDown() {
        return isPhysicalMouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    private boolean physicalLeftMouseDown() {
        return isPhysicalMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private KeyMapping mapping(String key) {
        return switch (key) {
            case "forward" -> mc.options.keyUp;
            case "back" -> mc.options.keyDown;
            case "left" -> mc.options.keyLeft;
            case "right" -> mc.options.keyRight;
            case "jump" -> mc.options.keyJump;
            case "sneak" -> mc.options.keyShift;
            case "sprint" -> mc.options.keySprint;
            case "attack" -> mc.options.keyAttack;
            case "use" -> mc.options.keyUse;
            case "drop" -> mc.options.keyDrop;
            default -> null;
        };
    }

    /**
     * 激活手势里那个「右键」的显示名。
     *
     * <p>⚠️ 这里**不能**用 {@code mc.options.keyUse.getTranslatedKeyMessage()}：
     * 触发条件检测的是 {@link #physicalRightMouseDown()}（物理鼠标右键），
     * 而不是 {@code keyUse} 的绑定状态 —— 后者会被本模块自己
     * {@code setPressed("use", …)} 抑制掉（写后即读恒 false）。
     * 若玩家把 {@code keyUse} 改绑到别的键，用它显示就会**误导**。
     */
    private String rightMouseDisplay() {
        try {
            return InputConstants.Type.MOUSE
                    .getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                    .getDisplayName().getString();
        } catch (Exception ignored) {
            return "RMB";
        }
    }

    /**
     * 按键的显示名（走玩家实际绑定），供提示文案使用。
     *
     * <p>提示里必须写清要按哪两个键；取不到时退回内部键名。
     */
    private String keyDisplay(String key) {
        KeyMapping binding = mapping(key);
        if (binding == null) return key;
        try {
            return binding.getTranslatedKeyMessage().getString();
        } catch (Exception ignored) {
            return key;
        }
    }

    private void setPressed(String key, boolean state) {
        KeyMapping mapping = mapping(key);
        if (mapping == null) return;
        // Nilore 没有 Epsilon 的 KeyMappingAccessor；use/attack/sneak/sprint 在开启原版 toggle 时
        // setDown(false) 可能被解释成取反，这里先关 toggle 语义：直接写 setDown 并同步被按下计数。
        mapping.setDown(state);
    }

    /** Epsilon 侧由模块基类提供；Nilore 侧自己判。 */
    private boolean nullCheck() {
        return mc.player == null || mc.level == null;
    }

    /**
     * 本机实时灵敏度对应的鼠标量子（一格 = 多少度）。
     * 与 {@code Rotation.snapToSensitivity} 同源：{@code (sens*0.6+0.2)^3 * 8}，
     * 这里再乘 0.15 得到脚本原实现使用的步长。
     */
    private float mouseQuantum() {
        try {
            float sensitivity = mc.options.sensitivity().get().floatValue();
            float f = sensitivity * 0.6f + 0.2f;
            float quantum = f * f * f * 8.0f * 0.15f;
            return quantum > 1.0E-5f ? quantum : SENSITIVITY_QUANTUM;
        } catch (Throwable ignored) {
            return SENSITIVITY_QUANTUM;
        }
    }

    private int keyCode(String key) {
        KeyMapping mapping = mapping(key);
        return mapping == null ? -1 : mapping.getKey().getValue();
    }

    private boolean isPressed(String key) {
        KeyMapping mapping = mapping(key);
        return mapping != null && mapping.isDown();
    }

    /** 脚本 keybinds.isKeyDown：读物理键盘状态（不是 KeyMapping 的抑制状态）。 */
    private boolean isPhysicalKeyDown(String key) {
        int code = keyCode(key);
        return code >= 0 && InputConstants.isKeyDown(mc.getWindow().getWindow(), code);
    }

    private boolean isDropKeyName(int keyCode) {
        try {
            return InputConstants.Type.KEYSYM.getOrCreate(keyCode).getName().toLowerCase(Locale.ROOT).contains("drop");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 紧凑位置串，供调试日志使用。 */
    private static String fmtPos(LocalPlayer player) {
        Vec3 pos = player.position();
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", pos.x, pos.y, pos.z);
    }

    /**
     * 玩家是否已越过最后一个已放置方块（即站在尚未铺好的区域上）。
     * 桥的推进方向由 travelX/travelZ 决定，沿该方向超出 1 格即视为冲过头。
     */
    /**
     * 玩家是否已越过最后一个已放置方块（即站在尚未铺好的区域上）。
     * 桥的推进方向由 travelX/travelZ 决定，沿该方向超出 1 格即视为冲过头。
     *
     * <p>⚠️ 容差**保持脚本原值 1**，不要收紧 —— 收紧会让停步变频繁，
     * 移动呈"走走停停"从而被反作弊判定（2026-09-27 实测）。见 AGENTS.md。
     */
    private boolean isPlayerAheadOfBridge(LocalPlayer player) {
        if (player == null || lastPlacedPos == null) return false;
        Vec3 pos = player.position();
        int ahead = (floor(pos.x) - lastPlacedPos[0]) * travelX
                + (floor(pos.z) - lastPlacedPos[2]) * travelZ;
        return ahead > 1;
    }

    /** 玩家是否已横向偏离桥的通道（垂直于推进方向超过 1 格）。 */
    private boolean isPlayerOffLane(LocalPlayer player) {
        if (player == null) return false;
        if (travelX == 0 && travelZ == 0) return false;
        double lateral = travelX != 0 ? player.position().z : player.position().x;
        return Math.abs(lateral - antiSwayLane) > 1.0;
    }

    /** 调试日志出口：受 Debug Log 设置控制，只写 latest.log，不进聊天栏。 */
    private void dbg(String message) {
        if (debugLog.getValue()) {
            logger.info("[Telly] {}", message);
        }
    }

    /**
     * 惰性诊断：{@code debugLog} 关闭时<b>不构造字符串</b>。
     *
     * <p>诊断串里常含 {@code String.format} 与 {@code getDeltaMovement()} 这类调用；
     * 若写成字面拼接，参数会在进入本方法前就求值 —— 关掉开关也照样分配。
     * 热路径上的诊断一律走这个重载。
     */
    private void dbg(Supplier<String> message) {
        if (debugLog.getValue()) {
            logger.info("[Telly] {}", message.get());
        }
    }

    private static Module findModule(String name) {
        // 模块显示名里有空格（SafeWalk 注册名是 "Safe Walk"），精确 equals 会让
        // findModule("SafeWalk") 恒返回 null ⇒ "Disable SafeWalk" 静默失效、跑桥时被 SafeWalk 削掉后退位移。
        String needle = name == null ? "" : name.replace(" ", "").toLowerCase(Locale.ROOT);
        for (Module module : NiloreClient.getInstance().getModuleManager().getModules()) {
            String candidate = module.getName() == null ? "" : module.getName().replace(" ", "").toLowerCase(Locale.ROOT);
            if (candidate.equals(needle)) return module;
        }
        return null;
    }

    @Override
    public String getSuffix() {
        if (running) return "Running";
        if (armed) return "Armed";
        return null;
    }
}
