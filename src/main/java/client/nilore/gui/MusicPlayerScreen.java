package client.nilore.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.modules.impl.misc.MusicPlayer;
import client.nilore.modules.impl.misc.music.AudioPlayer;
import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.LyricSprings;
import client.nilore.modules.impl.misc.music.MusicHttp;
import client.nilore.modules.impl.misc.music.NeteaseApi;
import client.nilore.modules.impl.misc.music.SongInfo;
import client.nilore.modules.impl.misc.music.provider.MusicSource;
import client.nilore.modules.impl.misc.music.provider.MusicSources;
import client.nilore.modules.impl.misc.music.provider.NeteaseOfficialApi;
import client.nilore.modules.impl.misc.music.provider.QrCode;
import client.nilore.modules.impl.render.LyricsModule;
import client.nilore.render.CoverBackdrop;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.LyricGlowFbo;
import client.nilore.render.Paint;
import client.nilore.render.Rectangle;
import client.nilore.render.Renderer;
import client.nilore.render.RoundedRectangle;
import client.nilore.render.Texture;
import client.nilore.utils.animation.SmoothAnimationTimer;
import client.nilore.utils.animation.SpringSolver;
import client.nilore.utils.math.Easings;
import client.nilore.utils.render.ColorUtil;
import client.nilore.utils.render.MonetPalette;

public class MusicPlayerScreen extends Screen {
    private static final float DESIGN_W = 760.32f;
    private static final float DESIGN_H = 459.36f;
    private static final float SIDEBAR_W = 80.96f;
    private static final float BOTTOM_H = 72.16f;
    private static final float PAD = 22.88f;
    private static final float RADIUS = 19.36f;

    // 配色不再是写死的常量：每首歌按封面动态提取（Material You / Monet），换歌时平滑过渡。
    // 这里从 static final 改成可变静态字段，是为了让下面上百处引用一行都不用动。
    // 字面值只是占位，静态块里会用兜底配色覆盖一遍（见本类末尾的 static 块）。
    private static int SCRIM = 0xC30B0807;
    private static int SHELL = 0xFF1B1215;
    private static int SIDEBAR = 0xFF24171C;
    private static int SURFACE = 0xFF2D1E23;
    private static int RAISED = 0xFF3A272E;
    private static int BERRY = 0xFF81344F;
    private static int BERRY_HOVER = 0xFF98405F;
    private static int ACCENT = 0xFFFFA6C3;
    private static int ACCENT_STRONG = 0xFFFF8FB5;
    private static int ACCENT_HOVER = 0xFFFFB9CE;
    // 文字色不参与主题派生：恒为白色系，免得跟着专辑封面来回变色。
    // 三个值只差明度，对应「标题 / 次要 / 最弱」三层信息，不是三种颜色。
    // 进度条滑块也用 CREAM，所以它一起变成白的。
    private static final int CREAM = 0xFFFFFFFF;
    private static final int MUTED = 0xFFD5D5D5;
    private static final int DIM = 0xFF9A9A9A;
    private static int NAV_ACTIVE = 0xFF60404B;
    private static int ROW_ACTIVE = 0xFF52313D;
    private static int DIVIDER = 0xFF3B2B31;

    // --- 动态配色状态 ---
    // 配色是全局共享的（上面那些字段是 static），所以状态也放 static。
    // 同一时刻只会开一个播放器界面，不会互相干扰。
    private static MonetPalette.Scheme themeCurrent = MonetPalette.fallback();
    private static MonetPalette.Scheme themeFrom = themeCurrent;
    private static MonetPalette.Scheme themeTarget = themeCurrent;
    private static float themeProgress = 1.0f;
    /** 换歌时配色过渡时长（秒）。 */
    private static final float THEME_FADE_SECONDS = 0.85f;

    private static long frameLastNanos = 0L;

    /**
     * 把一套 Monet 配色套到上面那组字段上。
     *
     * <p>tone 按 Material 3 深色方案取：表面层级 surfaceLowest(5) → surfaceHighest(21)，
     * 主色块 primaryContainer(30)，强调色 primary(80)。
     *
     * <p>注意这里**不碰文字色**（CREAM / MUTED / DIM）——那三个是固定的白色系常量，
     * 只有背景、卡片、强调色/图标才跟随封面变化。
     */
    private static void applyScheme(MonetPalette.Scheme scheme) {
        SCRIM = MonetPalette.withAlphaOf(scheme.surfaceLowest(), 0xC3);
        SHELL = scheme.surfaceLowest();
        SIDEBAR = scheme.surfaceContainer();
        SURFACE = scheme.surfaceHigh();
        RAISED = scheme.surfaceHighest();
        BERRY = scheme.primaryContainer();
        BERRY_HOVER = scheme.primary.at(36);
        ACCENT = scheme.primary();
        ACCENT_STRONG = scheme.primary.at(88);
        ACCENT_HOVER = scheme.primary.at(92);
        NAV_ACTIVE = scheme.secondaryContainer();
        ROW_ACTIVE = scheme.secondaryContainer();
        DIVIDER = scheme.outlineVariant();
    }

    /** 本帧与上一帧的间隔（秒），夹在 [0, 0.1] 内，避免卡顿后动画整段跳过去。 */
    private static float frameDelta() {
        long now = System.nanoTime();
        float delta = frameLastNanos == 0L ? 0.0f : (now - frameLastNanos) / 1.0e9f;
        frameLastNanos = now;
        return Math.min(Math.max(delta, 0.0f), 0.1f);
    }

    /** 推进配色过渡。过渡结束后直接返回，不做事。 */
    private static void tickTheme(float delta) {
        if (themeProgress >= 1.0f) {
            return;
        }
        themeProgress = Math.min(1.0f, themeProgress + delta / THEME_FADE_SECONDS);
        themeCurrent = MonetPalette.lerp(themeFrom, themeTarget, themeProgress);
        applyScheme(themeCurrent);
    }

    /**
     * 切到新配色。
     *
     * <p>起点用「当前显示的颜色」而不是上一个目标色，这样连续换歌时不会跳变。
     */
    private static void setTheme(MonetPalette.Scheme next) {
        if (next == null) {
            return;
        }
        themeFrom = themeCurrent;
        themeTarget = next;
        themeProgress = 0.0f;
    }

    static {
        // 上面那些字面色值只是占位。开局统一走一遍取色管线，
        // 保证「打开界面时」和「换歌后」走的是同一套逻辑，避免第一首歌加载完时颜色突跳。
        applyScheme(themeCurrent);
    }

    private static final FontRenderer DISPLAY_FONT = FontPresets.poppinsBold(33.0f);
    private static final FontRenderer TITLE_FONT = FontPresets.pingfang(29.0f);
    private static final FontRenderer HEADING_FONT = FontPresets.pingfang(25.0f);
    private static final FontRenderer BODY_FONT = FontPresets.pingfang(23.0f);
    private static final FontRenderer SMALL_FONT = FontPresets.pingfang(21.0f);
    private static final FontRenderer NAV_FONT = FontPresets.productSans(20.0f);
    private static final FontRenderer ICON_FONT = FontPresets.materialIcons(28.0f);
    private static final FontRenderer ICON_LARGE = FontPresets.materialIcons(38.0f);
    private static final FontRenderer USERNAME_FONT = FontPresets.pingfang(33.0f);

    // 歌词泛光：光晕半径（逻辑像素）和送进离屏纹理的强度。
    // 半径偏大才是「氛围」，太小就成了一圈描边。
    private static final float GLOW_RADIUS = 6.0f;
    private static final float GLOW_ALPHA = 0.55f;

    /** 从第几行开始压低亮度。更远的不再继续降，免得一行比一行暗。 */
    private static final int LYRIC_FADE_DISTANCE = 2;

    // --- 沉浸式播放页：Apple 风格固定配色 ---
    // 这一页刻意不参与 Monet 主题派生，配色写死。背景仍然是封面流体（那是内容不是配色），
    // 但文字、进度条、控件都是中性白，换歌时不会跟着封面一起变。
    private static final int AP_TEXT = 0xFFFFFFFF;      // 主文字 / 图标
    private static final int AP_TEXT_DIM = 0xB3FFFFFF;  // 次要文字
    private static final int AP_TEXT_FAINT = 0x73FFFFFF;// 时间戳 / 未激活
    private static final int AP_HOVER = 0x1FFFFFFF;     // 悬停垫底
    private static final int AP_TRACK = 0x33FFFFFF;     // 进度条轨道
    private static final int AP_BAR = 0xFFE6E6E6;       // 进度条已播放（比纯白灰一档）
    private static final int AP_BAR_GLOW = 0x4DFFFFFF;  // 进度条泛光，比歌词那层弱得多
    private static final int AP_PLACEHOLDER = 0x14FFFFFF;// 封面占位

    // 沉浸式页专用字体：Product Sans（Google Sans 的近亲）+ 中文自动落到苹方。
    // 单独一组，不动上面那些被其它页面共用的字体。
    private static final FontRenderer AP_TITLE_FONT = FontPresets.googleSansBold(30.0f);
    private static final FontRenderer AP_ARTIST_FONT = FontPresets.googleSansBold(22.0f);
    private static final FontRenderer AP_TIME_FONT = FontPresets.googleSans(16.0f);
    private static final FontRenderer AP_LYRIC_FONT = FontPresets.googleSansBold(26.0f);
    private static final FontRenderer AP_LYRIC_ACTIVE_FONT = FontPresets.googleSansBold(33.0f);

    private static final String ICON_PREV = "";
    private static final String ICON_PLAY = "";
    private static final String ICON_PAUSE = "";
    private static final String ICON_NEXT = "";
    private static final String ICON_VOLUME = "";
    private static final String ICON_SEARCH = "";
    private static final String ICON_QUEUE = "";
    private static final String ICON_PLAYLIST = "";
    private static final String ICON_ADD = "";
    private static final String ICON_REMOVE = "";
    private static final String ICON_HOME = "";
    private static final String ICON_BACK = "";
    private static final String ICON_MUSIC = "";
    private static final String ICON_INFO = "";
    private static final String ICON_CLOSE = "";

    private Page page = Page.HOME;
    private Page playerReturnPage = Page.HOME;
    private final List<ClickArea> clickAreas = new ArrayList<>();
    private final Map<Long, List<LyricLine>> lyricsCache = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> lyricsRequested = new ConcurrentHashMap<>();
    private final AtomicLong searchSeq = new AtomicLong();
    private final AtomicLong playRequestSeq = new AtomicLong();

    private String searchText = "";
    private boolean searchFocused;
    private boolean searchDirty;
    private boolean searchSelectAll;
    private volatile List<SongInfo> searchResults = List.of();
    private volatile boolean searching;

    private final List<SongInfo> playQueue = new ArrayList<>();
    private int queueIndex = -1;
    private boolean playlistAutoAdvance;

    private float searchScroll;
    private float maxSearchScroll;
    private float queueScroll;
    private float maxQueueScroll;
    private float playlistScroll;
    private float maxPlaylistScroll;
    private long lyricSongId = -1;

    // --- 歌词动画 ---
    // 每行一个独立弹簧，而不是整块一起 lerp：换行时是「新行滑上来、旧行走完自己那一段」，
    // 而不是整个列表硬邦邦地一起位移。区域位置、尺寸全部沿用原来那套，只换动画。
    private SpringSolver[] lyricLineY = new SpringSolver[0];
    private SpringSolver[] lyricLineScale = new SpringSolver[0];
    /** 每行静止时的屏幕 y（行顶部），由当前行位置推出来。 */
    private float[] lyricRestY = new float[0];
    /** 级联延迟：离锚点越远的行越晚起步，形成波浪。 */
    private float[] lyricCascade = new float[0];
    /** 上次的锚点行 / 整块偏移，变了才重设弹簧目标。 */
    private int lyricAnchor = -1;
    private float lyricLastBlockTop = Float.NaN;
    private boolean lyricSpringsReady;
    private long lyricFrameNs;
    private long lyricLastPosMs = -1L;

    private Bounds searchViewport;
    private Bounds queueViewport;
    private Bounds playlistViewport;
    private DragTarget dragTarget = DragTarget.NONE;
    private Rectangle lastProgressRect;
    private Rectangle lastVolumeRect;
    private float pendingVolume = -1.0f;
    private float pendingProgress = -1.0f;

    // --- 沉浸式页的交互反馈 ---
    // 悬停要平滑淡入、按下要弹簧回弹、图标还要跟着微缩放。这三样是「手感」的来源，
    // 缺一样控件看起来就是死的——点下去毫无回应，再好的静态外观也救不回来。
    /** 顺序：音量 / 上一首 / 播放 / 下一首 / 关闭。 */
    private static final int PLAYER_BTN_COUNT = 5;
    private final float[] playerBtnHover = new float[PLAYER_BTN_COUNT];
    private final SpringSolver[] playerBtnPress = new SpringSolver[PLAYER_BTN_COUNT];
    /** 每个按钮上一帧的矩形，用来在 mouseClicked/Released 里判断按到了哪个。 */
    private final Rectangle[] playerBtnRects = new Rectangle[PLAYER_BTN_COUNT];
    private int playerPressedButton = -1;
    private long playerMotionNs;
    /** 进度条的悬停展开程度：0 是常态，1 是拉粗。 */
    private float playerBarEngage;
    /** 音量滑块是否展开（点音量按钮切换）。 */
    private boolean volumeBarOpen;
    /** 滑块的展开进度 0..1，用它做「从中间长出来」的动画。 */
    private float volumeBarReveal;
    /** 封面呼吸：播放中满尺寸，暂停时略收。 */
    private final SmoothAnimationTimer coverBreath = new SmoothAnimationTimer();

    {
        for (int i = 0; i < PLAYER_BTN_COUNT; i++) {
            // 和 deobfmusic 的按压弹簧同一组参数，阻尼比约 0.62，按下去有轻微回弹
            playerBtnPress[i] = new SpringSolver(1.0, 21.5, 300.0);
        }
    }

    private float layoutOriginX;
    private float layoutOriginY;
    private float layoutScale = 1.0f;

    /** 流体色雾是否已就绪。true 时 renderRoot 会把流体画进圆角背景板。 */
    private boolean fluidActive;

    private final SmoothAnimationTimer openAnim = new SmoothAnimationTimer();
    private volatile Texture albumTexture;
    private long albumSongId = -1;
    private volatile boolean albumLoading;
    private volatile byte[] albumBytes;
    private int albumRetryCount;
    // 背景用的是封面压出来的 32×32 色雾（见 CoverBackdrop.prepareFluidPalette），不是封面本身。
    // 单独记一份：albumTexture 在换歌加载期间会被清空，背景不能跟着闪回原版背景。
    private volatile Texture fluidTexture;
    private volatile Texture fluidPrev;
    /** 背景在新旧色雾之间的插值，0 = 旧，1 = 新。 */
    private float backdropFade = 1.0f;
    private static final float BACKDROP_FADE_SECONDS = 0.7f;
    /** 有流体背景时的遮罩透明度。默认的 0xC3 是给世界画面兜底用的，压在自发光背景上会把光压没。 */
    private static final int SCRIM_ALPHA_FLUID = 0x66;

    // 持久化：网易官方登录态（Cookie 串）、当前播放渠道。
    // 路径放在 .minecraft/nilore/ 下，和其它 mod 配置区分开。
    private final Path sessionFile = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("nilore").resolve("netease_session.txt");
    private final Path sourceFile = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("nilore").resolve("music_source.txt");

    // 二维码登录弹层状态。点 About 页里的登录卡片打开；用户扫码成功后自动关闭。
    private boolean loginDialogOpen = false;
    private QrCode.RenderableQr loginQr;
    // loginQrKey / loginQrStatus 在 CompletableFuture 回调线程里赋值、渲染线程读，
    // 不加 volatile 渲染线程可能一直读到旧值（表现为扫码成功后界面毫无反应）。
    private volatile String loginQrKey;
    private volatile NeteaseOfficialApi.QrStatus loginQrStatus = NeteaseOfficialApi.QrStatus.WAITING;
    private long loginQrNextPollMs;
    /** CONFIRMED 那一刻的时间戳，用于延迟 1.2 秒再自动关弹层。0 表示不在确认流程里。 */
    private long lastLoginConfirmMs;

    // 弹层自己的点击区。必须和主界面的 clickAreas 分开：
    // 主界面走设计坐标（有 translate/scale 变换），弹层在变换之外、用屏幕像素定位，
    // 混在同一个列表里会因为坐标系不同而永远匹配不上（表现为关闭按钮点不掉）。
    private final List<ClickArea> dialogClickAreas = new ArrayList<>();

    public MusicPlayerScreen() {
        super(Component.literal("Music Player"));
        MusicPlayer.AUDIO_PLAYER.setNearEndListener(this::requestPreloadForNext);
        MusicPlayer.AUDIO_PLAYER.setOnCrossfadeTrackListener(this::onCrossfadeTrack);
        // 启动恢复：上次选的播放渠道 + 网易官方登录态（如果登录过）。
        // 两边都在 catch 里吞异常，缺文件/坏数据都不影响正常使用。
        MusicSources.load(sourceFile);
        NeteaseOfficialApi.loadSession(sessionFile);
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().level == null) {
            MusicPlayer.AUDIO_PLAYER.stop();
        }
        // 二维码登录轮询：每 1.5 秒问一次。状态变 CONFIRMED/EXPIRED/FAILED 后不再轮询。
        if (loginDialogOpen && loginQrKey != null
                && loginQrStatus != NeteaseOfficialApi.QrStatus.CONFIRMED
                && loginQrStatus != NeteaseOfficialApi.QrStatus.EXPIRED
                && loginQrStatus != NeteaseOfficialApi.QrStatus.FAILED
                && System.currentTimeMillis() >= loginQrNextPollMs) {
            loginQrNextPollMs = System.currentTimeMillis() + 1500L;
            NeteaseOfficialApi.pollQr(loginQrKey, sessionFile).thenAccept(s -> loginQrStatus = s);
        }
    }

    @Override
    public void onClose() {
        // 退出时落盘当前播放渠道。Cookie 文件由 NeteaseOfficialApi 自己在登录成功时存。
        MusicSources.save(sourceFile);
        releaseLoginQr();
        // 泛光那三张离屏纹理占的是显存，页面关掉就还回去
        LyricGlowFbo.release();
        super.onClose();
    }

    private void releaseLoginQr() {
        if (loginQr != null) {
            // 必须从 TextureManager 注销，否则 GL 纹理 ID 泄漏。
            // TextureManager.release 只清掉引用，DynamicTexture 的 NativeImage 靠 GC finalize 释放。
            Minecraft.getInstance().getTextureManager().release(loginQr.location());
            loginQr = null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        clickAreas.clear();
        dialogClickAreas.clear();
        searchViewport = null;
        queueViewport = null;
        playlistViewport = null;
        lastProgressRect = null;
        lastVolumeRect = null;

        float delta = frameDelta();
        tickTheme(delta);
        if (backdropFade < 1.0f) {
            backdropFade = Math.min(1.0f, backdropFade + delta / BACKDROP_FADE_SECONDS);
        }
        // 流体是否就绪。真正的绘制在 renderRoot 里做 —— 要裁剪到圆角背景板内。
        // 色雾还没算出来时只有主菜单退回原版背景；游戏内退回原版背景会把世界画面糊掉，
        // 所以那种情况直接不画，让世界照常透出来（和原来的行为一致）。
        Texture fluid = fluidTexture;
        fluidActive = fluid != null && fluid.getGlId() > 0;
        if (!fluidActive && Minecraft.getInstance().level == null) {
            renderBackground(graphics);
        }

        openAnim.animate(1.0, 0.3, Easings.EASE_OUT_QUAD);
        openAnim.tick();
        LayoutTransform transform = calculateTransform();
        layoutOriginX = transform.originX();
        layoutOriginY = transform.originY();
        layoutScale = transform.scale();
        float designMouseX = toDesignX(mouseX);
        float designMouseY = toDesignY(mouseY);

        // 有流体背景时用更轻的遮罩，否则自发光背景会被自己的遮罩压成一坨死黑。
        final int scrimColor = fluidActive
                ? MonetPalette.withAlphaOf(SCRIM, SCRIM_ALPHA_FLUID)
                : SCRIM;

        Renderer.render(graphics, ctx -> {
            float alpha = openAnim.getValueF();
            ctx.drawRectXYWH(0, 0, width, height, new Paint().setColor(withAlpha(scrimColor, alpha)));
            ctx.save();
            ctx.translate(layoutOriginX, layoutOriginY);
            ctx.scale(layoutScale, layoutScale);
            renderRoot(ctx, designMouseX, designMouseY, alpha);
            ctx.restore();
            // 登录弹层在 ctx.restore() 之后画 —— 栈已经退回到屏幕坐标，弹层用屏幕像素定位。
            if (loginDialogOpen) {
                renderLoginDialog(ctx, mouseX, mouseY);
            }
        });
    }

    private LayoutTransform calculateTransform() {
        float margin = 15.84f;
        float scale = Math.min(1.0f, Math.min((width - margin * 2.0f) / DESIGN_W, (height - margin * 2.0f) / DESIGN_H));
        scale = Math.max(0.45f, scale);
        return new LayoutTransform((width - DESIGN_W * scale) * 0.5f, (height - DESIGN_H * scale) * 0.5f, scale);
    }

    private void renderRoot(DrawContext ctx, float mouseX, float mouseY, float alpha) {
        // 背景板（不透明底色）。流体要画在它上面、被它裁出圆角。
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(0, 0, DESIGN_W, DESIGN_H, RADIUS),
                new Paint().setColor(withAlpha(SHELL, alpha)));

        // 发光流体：只填在背景板这一块圆角矩形里，不是铺满整屏。
        // CoverBackdrop 是直接在裁剪空间画全屏四边形的、不走 DrawContext，
        // 所以 GUI 层的 clipRect 对它无效 —— 把面板的屏幕坐标矩形传进去，
        // 由着色器自己做圆角 SDF 裁剪。
        if (fluidActive) {
            Texture fluid = fluidTexture;
            Texture previous = fluidPrev;
            CoverBackdrop.render(fluid.getGlId(),
                    previous == null ? fluid.getGlId() : previous.getGlId(),
                    backdropFade, width, height, 0.0f,
                    new CoverBackdrop.PanelRect(
                            layoutOriginX, layoutOriginY,
                            DESIGN_W * layoutScale, DESIGN_H * layoutScale,
                            RADIUS * layoutScale));
        }

        if (page == Page.PLAYER) {
            renderPlayerPage(ctx, mouseX, mouseY);
            return;
        }

        float contentH = DESIGN_H - BOTTOM_H;
        // 侧栏不铺底色了：那层半透明面会把封面流体挡住，去掉之后流体能一直透到这一栏来。
        // 导航项的 hover / 选中底还留着，所以可读性不靠底下这层。
        renderSidebar(ctx, 0, 0, SIDEBAR_W, contentH, mouseX, mouseY);
        renderCurrentPage(ctx, SIDEBAR_W, 0, DESIGN_W - SIDEBAR_W, contentH, mouseX, mouseY);
        renderPlaybackBar(ctx, 0, contentH, DESIGN_W, BOTTOM_H, mouseX, mouseY);
    }

    private void renderCurrentPage(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        switch (page) {
            case HOME -> renderHomePage(ctx, x, y, w, h, mouseX, mouseY);
            case SEARCH -> renderSearchPage(ctx, x, y, w, h, mouseX, mouseY);
            case QUEUE -> renderQueue(ctx, x, y, w, h, mouseX, mouseY);
            case PLAYLIST -> renderPlaylist(ctx, x, y, w, h, mouseX, mouseY);
            case ABOUT -> renderAbout(ctx, x, y, w, h, mouseX, mouseY);
            default -> { }
        }
    }

    private void renderSidebar(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x + 15.84f, y + 20.84f, 49.28f, 42.24f, 14.08f), new Paint().setColor(BERRY));
        drawCentered(ICON_MUSIC, x + 15.84f, y + 37.56f, 49.28f, ICON_LARGE, ACCENT);

        float navY = y + 85.08f;
        navItem(ctx, x + 8.8f, navY, w - 17.6f, Page.HOME, ICON_HOME, "Home", mouseX, mouseY);
        navItem(ctx, x + 8.8f, navY + 63.36f, w - 17.6f, Page.SEARCH, ICON_SEARCH, "Search", mouseX, mouseY);
        navItem(ctx, x + 8.8f, navY + 126.72f, w - 17.6f, Page.QUEUE, ICON_QUEUE, "Queue", mouseX, mouseY);
        navItem(ctx, x + 8.8f, navY + 190.08f, w - 17.6f, Page.PLAYLIST, ICON_PLAYLIST, "Library", mouseX, mouseY);
        navItem(ctx, x + 8.8f, h - 53.08f, w - 17.6f, Page.ABOUT, ICON_INFO, "About", mouseX, mouseY);
    }

    private void navItem(DrawContext ctx, float x, float y, float w, Page target, String icon, String label,
                         float mouseX, float mouseY) {
        float h = 50.16f;
        boolean active = page == target;
        boolean hover = contains(mouseX, mouseY, x, y, w, h);
        if (active || hover) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y - 3.0f, w, h, 15.84f),
                    new Paint().setColor(active ? NAV_ACTIVE : withAlpha(RAISED, 0.82f)));
        }
        drawCentered(icon, x, y + 14.08f, w, ICON_FONT, active ? ACCENT : hover ? CREAM : MUTED);
        drawCentered(label, x, y + 31.68f, w, NAV_FONT, active ? CREAM : MUTED);
        clickAreas.add(new ClickArea(x, y, w, h, () -> openPage(target)));
    }

    private void renderHomePage(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        float innerX = x + PAD;
        float innerW = w - PAD * 2.2f;
        String username = Minecraft.getInstance().player == null
                ? "Player"
                : Minecraft.getInstance().player.getGameProfile().getName();
        String greetingLine = greeting() + ", ";
        GlHelper.drawText(greetingLine, innerX, y + 22.88f, DISPLAY_FONT, CREAM);
        GlHelper.drawText(ellipsize(username, USERNAME_FONT, 376), innerX + measure(greetingLine, DISPLAY_FONT),
                y + 22.88f, USERNAME_FONT, CREAM);
        GlHelper.drawText("Music picked for this moment", innerX, y + 51.04f, BODY_FONT, MUTED);
        renderHeaderActions(ctx, x + w - PAD - 130.24f, y + 23.36f, mouseX, mouseY);

        float heroY = y + 73.04f;
        float heroH = 138.16f;
        renderDailyMix(ctx, innerX, heroY, innerW, heroH, mouseX, mouseY);

        float cardsTitleY = heroY + heroH + 16;
        GlHelper.drawText("Made for you", innerX, cardsTitleY, TITLE_FONT, CREAM);
        String seeAll = "See all";
        float seeAllW = measure(seeAll, BODY_FONT);
        GlHelper.drawText(seeAll, innerX + innerW - seeAllW, cardsTitleY + 2, BODY_FONT, CREAM);
        clickAreas.add(new ClickArea(innerX + innerW - seeAllW - 8, cardsTitleY - 5, seeAllW + 16, 25,
                () -> openPage(Page.PLAYLIST)));

        float gridY = cardsTitleY + 26.0f;
        renderRecommendationGrid(ctx, recommendations(), innerX, gridY, innerW,
                h - gridY - 14.0f, mouseX, mouseY);
    }

    private void renderHeaderActions(DrawContext ctx, float x, float y, float mouseX, float mouseY) {
        float w = 130.24f;
        float h = 36.96f;
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 18.48f), new Paint().setColor(SURFACE));
        float actionW = 31.68f;
        float gap = 9.6f;
        float startX = x + (w - actionW * 3.0f - gap * 2.0f) * 0.5f;
        headerAction(ctx, startX, y + 7, ICON_SEARCH, mouseX, mouseY, () -> openPage(Page.SEARCH));
        headerAction(ctx, startX + actionW + gap, y + 7, ICON_INFO, mouseX, mouseY, () -> openPage(Page.ABOUT));
        headerAction(ctx, startX + (actionW + gap) * 2.0f, y + 7, ICON_CLOSE, mouseX, mouseY, this::onClose);
    }

    private void headerAction(DrawContext ctx, float x, float y, String icon, float mouseX, float mouseY, Runnable action) {
        boolean hover = contains(mouseX, mouseY, x, y, 31.68f, 28.16f);
        if (hover) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y - 3.0f, 31.68f, 28.16f, 14.08f), new Paint().setColor(RAISED));
        }
        drawCentered(icon, x, y + 12, 31.68f, ICON_FONT, hover ? ACCENT : MUTED);
        clickAreas.add(new ClickArea(x, y, 31.68f, 28.16f, action));
    }

    private void renderDailyMix(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        List<SongInfo> songs = recommendations();
        SongInfo hero = songs.isEmpty() ? null : songs.get(0);
        // 背景和下面 "Made for you" 推荐卡片同款：SURFACE 半透明，hover 时 RAISED。
        boolean cardHover = contains(mouseX, mouseY, x, y, w, h);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 25),
                new Paint().setColor(cardHover ? RAISED : withAlpha(SURFACE, 0.682f)));

        float art = h - 33.44f;
        float artX = x + 19.36f;
        float artY = y + 16.72f;
        drawAlbum(ctx, hero, artX, artY, art, 14.96f);
        float textX = artX + art + 24.64f;
        GlHelper.drawText("YOUR DAILY SOUNDTRACK", textX, y + 24, SMALL_FONT, 0xFFFFC4D4);
        GlHelper.drawText(hero == null ? "Build your mix" : "Daily Mix", textX, y + 48, DISPLAY_FONT, CREAM);
        String description = hero == null ? "Search for music to create your first mix"
                : ellipsize(hero.name + " · " + hero.artist, BODY_FONT, w - (textX - x) - 29.92f);
        GlHelper.drawText(description, textX, y + 74.96f, BODY_FONT, 0xFFF0C1CF);

        float buttonY = y + h - 41.36f;
        float buttonW = hero == null ? 109.12f : 98.56f;
        boolean hover = contains(mouseX, mouseY, textX, buttonY, buttonW, 28.16f);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(textX, buttonY, buttonW, 28.16f, 14.08f),
                new Paint().setColor(hover ? ACCENT_HOVER : ACCENT));
        GlHelper.drawText(hero == null ? ICON_SEARCH : ICON_PLAY, textX + 12.32f, buttonY + 15, ICON_FONT, 0xFF431A28);
        GlHelper.drawText(hero == null ? "Find music" : "Listen now", textX + 32,
                buttonY + (hero == null ? 9.68f : 11.68f), SMALL_FONT, 0xFF431A28);

        if (hero == null) {
            clickAreas.add(new ClickArea(textX, buttonY, buttonW, 32, () -> openPage(Page.SEARCH)));
        } else {
            clickAreas.add(new ClickArea(x, y, w, h, () -> playSongAndOpen(hero, songs, 0, true, Page.HOME)));
        }
    }

    private void renderRecommendationGrid(DrawContext ctx, List<SongInfo> songs, float x, float y, float w, float h,
                                          float mouseX, float mouseY) {
        if (songs.isEmpty()) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, Math.max(96, h), 18), new Paint().setColor(SURFACE));
            GlHelper.drawText("Your saved music will appear here.", x + 20, y + 24, BODY_FONT, MUTED);
            GlHelper.drawText("Open Search to add your first track.", x + 20, y + 50, SMALL_FONT, DIM);
            clickAreas.add(new ClickArea(x, y, w, Math.max(96, h), () -> openPage(Page.SEARCH)));
            return;
        }

        int columns = Math.max(3, Math.min(5, (int) (w / 130.24f)));
        float gap = 18.48f;
        float cardW = (w - gap * (columns - 1)) / columns;
        float artSize = Math.min(cardW, h - 43.0f);
        int count = Math.min(columns, songs.size());
        for (int i = 0; i < count; i++) {
            SongInfo song = songs.get(i);
            float cardX = x + i * (cardW + gap);
            boolean hover = contains(mouseX, mouseY, cardX, y, cardW, artSize + 38);
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(cardX - 6, y - 6, cardW + 12, artSize + 49, 16),
                    new Paint().setColor(hover ? RAISED : withAlpha(SURFACE, 0.682f)));
            drawAlbum(ctx, song, cardX, y, artSize, 13);
            GlHelper.drawText(ellipsize(song.name, SMALL_FONT, cardW), cardX, y + artSize + 9, SMALL_FONT,
                    hover ? CREAM : MUTED);
            GlHelper.drawText(ellipsize(song.artist, SMALL_FONT, cardW), cardX, y + artSize + 25, SMALL_FONT, DIM);
            int index = i;
            clickAreas.add(new ClickArea(cardX, y, cardW, artSize + 38,
                    () -> playSongAndOpen(song, songs, index, true, Page.HOME)));
        }
    }

    private void renderSearchPage(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        float innerX = x + PAD;
        float innerW = w - PAD * 2.2f;
        GlHelper.drawText("Search", innerX, y + 24.64f, DISPLAY_FONT, CREAM);
        GlHelper.drawText("Find tracks, artists and albums", innerX, y + 53.68f, BODY_FONT, MUTED);
        renderSearchInput(ctx, innerX, y + 80.08f, innerW, mouseX, mouseY);
        renderSearchResults(ctx, innerX, y + 132.88f, innerW, h - 150.48f, mouseX, mouseY);
    }

    private void renderSearchInput(DrawContext ctx, float x, float y, float w, float mouseX, float mouseY) {
        float h = 38.72f;
        boolean hover = contains(mouseX, mouseY, x, y, w, h);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 16),
                new Paint().setColor(searchFocused ? RAISED : hover ? withAlpha(RAISED, 0.902f) : SURFACE));
        GlHelper.drawText(ICON_SEARCH, x + 15, y + 20, ICON_FONT, searchFocused ? ACCENT : MUTED);

        float textX = x + 45;
        float textW = w - 86;
        float textY = y + 17;
        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(textX, y + 4, textW, h - 8), true);
        if (searchSelectAll && !searchText.isEmpty()) {
            float selectionW = Math.min(textW, measure(searchText, BODY_FONT) + 5);
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(textX - 2, y + 10, selectionW, 22, 5),
                    new Paint().setColor(withAlpha(BERRY_HOVER, 0.792f)));
        }
        String shown = searchText.isEmpty() ? "Search music..." : searchText;
        GlHelper.drawText(shown, textX, textY, BODY_FONT, searchText.isEmpty() ? DIM : CREAM);
        if (searchFocused && !searchSelectAll) {
            float cursorX = textX + Math.min(textW - 1, measure(searchText, BODY_FONT) + 1);
            float blink = (float) Math.abs(Math.sin(System.currentTimeMillis() / 220.0));
            ctx.drawLine(cursorX, y + 10, cursorX, y + 33,
                    new Paint().setColor(withAlpha(ACCENT, blink)).setStrokeWidth(1.54f));
        }
        ctx.restore();
        GlHelper.drawText("Enter", x + w - 49, y + 17, SMALL_FONT, searchText.isEmpty() ? DIM : CREAM);
        clickAreas.add(new ClickArea(x, y, w, h, () -> {
            searchFocused = true;
            searchSelectAll = false;
        }));
    }

    private void renderSearchResults(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        searchViewport = new Bounds(x, y, w, h);
        if (searching) {
            GlHelper.drawText("Searching...", x + 8, y + 18, BODY_FONT, MUTED);
            return;
        }
        if (searchResults.isEmpty()) {
            String title = searchText.isEmpty() ? "Start with a song or artist" : "No results found";
            String detail = searchText.isEmpty() ? "Press Enter to search NetEase Music." : "Try another title or artist.";
            GlHelper.drawText(title, x + 8, y + 18, HEADING_FONT, MUTED);
            GlHelper.drawText(detail, x + 8, y + 47, SMALL_FONT, DIM);
            return;
        }

        float rowH = 60.5f;
        float gap = 4.4f;
        maxSearchScroll = Math.max(0, searchResults.size() * (rowH + gap) - gap - h);
        searchScroll = clamp(searchScroll, 0, maxSearchScroll);
        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(x, y, w, h), true);
        for (int i = 0; i < searchResults.size(); i++) {
            SongInfo song = searchResults.get(i);
            float rowY = y + i * (rowH + gap) - searchScroll;
            if (rowY + rowH <= y || rowY >= y + h) {
                continue;
            }
            boolean fullyInteractive = rowY >= y && rowY + rowH <= y + h;
            boolean hover = fullyInteractive && contains(mouseX, mouseY, x, rowY, w, rowH);
            boolean playing = isCurrentSong(song);
            if (hover || playing) {
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, rowY, w, rowH, 13),
                        new Paint().setColor(playing ? ROW_ACTIVE : RAISED));
            }
            GlHelper.drawText(playing ? "♪" : String.valueOf(i + 1), x + 14, rowY + 19, SMALL_FONT,
                    playing ? CREAM : DIM);
            GlHelper.drawText(ellipsize(song.name, BODY_FONT, w - 185), x + 44, rowY + 11, BODY_FONT,
                    playing ? CREAM : MUTED);
            GlHelper.drawText(ellipsize(song.artist, SMALL_FONT, w - 185), x + 44, rowY + 32, SMALL_FONT, DIM);
            String duration = song.formatDuration();
            GlHelper.drawText(duration, x + w - measure(duration, SMALL_FONT) - 52, rowY + 20, SMALL_FONT, MUTED);
            GlHelper.drawText(ICON_ADD, x + w - 30, rowY + 26, ICON_FONT, hover ? ACCENT : MUTED);
            if (fullyInteractive) {
                int index = i;
                clickAreas.add(new ClickArea(x + w - 43, rowY, 43, rowH,
                        () -> MusicPlayer.PLAYLIST.add(searchResults.get(index))));
                clickAreas.add(new ClickArea(x, rowY, w - 48, rowH,
                        () -> playSong(searchResults.get(index), searchResults, index, true)));
            }
        }
        ctx.restore();
    }

    private void renderQueue(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        queueViewport = renderListPage(ctx, "Queue", "Songs in your current session", playQueue,
                x, y, w, h, mouseX, mouseY, false, false);
    }

    private void renderPlaylist(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        playlistViewport = renderListPage(ctx, "Library", "Tracks saved to General Playlist", MusicPlayer.PLAYLIST.getSongs(),
                x, y, w, h, mouseX, mouseY, true, true);
    }

    private Bounds renderListPage(DrawContext ctx, String title, String subtitle, List<SongInfo> songs,
                                  float x, float y, float w, float h, float mouseX, float mouseY,
                                  boolean removable, boolean playlist) {
        float innerX = x + PAD;
        float innerW = w - PAD * 2.2f;
        GlHelper.drawText(title, innerX, y + 25.52f, DISPLAY_FONT, CREAM);
        GlHelper.drawText(subtitle, innerX, y + 53.68f, BODY_FONT, MUTED);
        if (songs.isEmpty()) {
            GlHelper.drawText(removable ? "Add songs from Search to build your library." : "Play a song to create a queue.",
                    innerX, y + 99.44f, BODY_FONT, DIM);
            return null;
        }

        float listY = y + 82.72f;
        float listH = h - 100.32f;
        float rowH = 50.16f;
        float gap = 3.52f;
        float maxScroll = Math.max(0, songs.size() * (rowH + gap) - gap - listH);
        float scroll = playlist ? clamp(playlistScroll, 0, maxScroll) : clamp(queueScroll, 0, maxScroll);
        if (playlist) {
            maxPlaylistScroll = maxScroll;
            playlistScroll = scroll;
        } else {
            maxQueueScroll = maxScroll;
            queueScroll = scroll;
        }

        Bounds viewport = new Bounds(innerX, listY, innerW, listH);
        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(innerX, listY, innerW, listH), true);
        for (int i = 0; i < songs.size(); i++) {
            SongInfo song = songs.get(i);
            float rowY = listY + i * (rowH + gap) - scroll;
            if (rowY + rowH <= listY || rowY >= listY + listH) {
                continue;
            }
            boolean fullyInteractive = rowY >= listY && rowY + rowH <= listY + listH;
            boolean hover = fullyInteractive && contains(mouseX, mouseY, innerX, rowY, innerW, rowH);
            boolean playing = isCurrentSong(song);
            if (hover || playing) {
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX, rowY, innerW, rowH, 13),
                        new Paint().setColor(playing ? ROW_ACTIVE : RAISED));
            }
            if (playing) {
                ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX + 4, rowY + 12, 3, rowH - 24, 1.65f),
                        new Paint().setColor(ACCENT));
            }
            GlHelper.drawText(String.valueOf(i + 1), innerX + 16, rowY + 20, SMALL_FONT,
                    playing ? CREAM : DIM);
            GlHelper.drawText(ellipsize(song.name, BODY_FONT, innerW - 150), innerX + 48, rowY + 12, BODY_FONT,
                    playing ? CREAM : MUTED);
            GlHelper.drawText(ellipsize(song.artist, SMALL_FONT, innerW - 150), innerX + 48, rowY + 33, SMALL_FONT, DIM);
            if (removable) {
                GlHelper.drawText(ICON_REMOVE, innerX + innerW - 30, rowY + 27, ICON_FONT, hover ? ACCENT : MUTED);
            }
            if (fullyInteractive) {
                int index = i;
                if (removable) {
                    clickAreas.add(new ClickArea(innerX + innerW - 44, rowY, 44, rowH,
                            () -> MusicPlayer.PLAYLIST.remove(index)));
                }
                clickAreas.add(new ClickArea(innerX, rowY, innerW - (removable ? 49 : 0), rowH,
                        () -> playSongAndOpen(songs.get(index), songs, index, true,
                                playlist ? Page.PLAYLIST : Page.QUEUE)));
            }
        }
        ctx.restore();
        return viewport;
    }

    private void renderAbout(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        float innerX = x + PAD;
        GlHelper.drawText("About", innerX, y + 25.52f, DISPLAY_FONT, CREAM);
        GlHelper.drawText("A focused player for your Minecraft sessions", innerX, y + 54.56f, BODY_FONT, MUTED);

        float cardY = y + 82.72f;

        // 第一张：可点击的播放渠道切换器。点击循环切到下一个 source。
        MusicSource current = MusicSources.current();
        MusicSource next = nextSource(current);
        boolean sourceHover = contains(mouseX, mouseY, innerX, cardY, w - PAD * 2, 63.36f);
        aboutCard(ctx, innerX, cardY, w - PAD * 2, 63.36f,
                "Music source",
                current.displayName() + "   →   click to switch to " + next.displayName(),
                sourceHover ? RAISED : SURFACE);
        clickAreas.add(new ClickArea(innerX, cardY, w - PAD * 2, 63.36f, this::cycleMusicSource));

        // 第二张：网易官方登录卡片。
        // 只有当前 source 是官方时才显示 —— gdstudio 不需要登录、登录也没用。
        float secondCardY = cardY + 68;
        if ("NetEase Official".equals(current.displayName())) {
            String loginText = NeteaseOfficialApi.isLoggedIn()
                    ? "Signed in. Open login dialog to re-authorize on another device."
                    : "Sign in to play VIP-only tracks. QR-code login, no password leaves this device.";
            boolean loginHover = contains(mouseX, mouseY, innerX, secondCardY, w - PAD * 2, 63.36f);
            aboutCard(ctx, innerX, secondCardY, w - PAD * 2, 63.36f, "NetEase Account", loginText,
                    loginHover ? RAISED : SURFACE);
            clickAreas.add(new ClickArea(innerX, secondCardY, w - PAD * 2, 63.36f, this::openLoginDialog));
            secondCardY += 68;
        }

        aboutCard(ctx, innerX, secondCardY, w - PAD * 2, 63.36f, "Library",
                "Saved tracks are stored locally in your Nilore config.");
        aboutCard(ctx, innerX, secondCardY + 68, w - PAD * 2, 63.36f, "Playback",
                "Music keeps playing after this screen is closed.");

        boolean hover = contains(mouseX, mouseY, innerX, h - 50.16f, 116, 29.92f);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(innerX, h - 50.16f, 116, 29.92f, 14.96f),
                new Paint().setColor(hover ? BERRY_HOVER : BERRY));
        GlHelper.drawText(ICON_SEARCH, innerX + 12.32f, h - 35.08f, ICON_FONT, ACCENT);
        GlHelper.drawText("Search music", innerX + 36.96f, h - 39, SMALL_FONT, CREAM);
        clickAreas.add(new ClickArea(innerX, h - 50.16f, 116, 29.92f, () -> openPage(Page.SEARCH)));
    }

    private void aboutCard(DrawContext ctx, float x, float y, float w, float h, String title, String text, int bg) {
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, 17), new Paint().setColor(bg));
        GlHelper.drawText(title, x + 18, y + 18, HEADING_FONT, CREAM);
        GlHelper.drawText(ellipsize(text, BODY_FONT, w - 36), x + 18, y + 45, BODY_FONT, MUTED);
    }

    // 老的双参重载：内部默认 SURFACE 颜色
    private void aboutCard(DrawContext ctx, float x, float y, float w, float h, String title, String text) {
        aboutCard(ctx, x, y, w, h, title, text, SURFACE);
    }

    /** 当前 source 列表里下一个 source，用于 "click to switch to ..." 提示。 */
    private static MusicSource nextSource(MusicSource current) {
        List<MusicSource> all = MusicSources.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).displayName().equals(current.displayName())) {
                return all.get((i + 1) % all.size());
            }
        }
        return all.get(0);
    }

    private void cycleMusicSource() {
        MusicSources.setCurrent(nextSource(MusicSources.current()).displayName());
        MusicSources.save(sourceFile);
    }

    private void openLoginDialog() {
        loginDialogOpen = true;
        loginQrStatus = NeteaseOfficialApi.QrStatus.WAITING;
        loginQrNextPollMs = 0L; // 立刻拉一次
        NeteaseOfficialApi.requestQrKey().thenAccept(key -> {
            if (key == null) {
                loginQrStatus = NeteaseOfficialApi.QrStatus.FAILED;
                return;
            }
            loginQrKey = key;
            // 生成二维码 + 上传纹理都是 GL 操作，必须在渲染线程跑。
            // 直接在 CompletableFuture 的回调里做会在 GL 线程外调 upload() 而失败，
            // 且异常被回调吞掉 —— 表现就是二维码永远停在 "Generating QR..."。
            RenderSystem.recordRenderCall(() -> {
                try {
                    releaseLoginQr();
                    QrCode.RenderableQr renderable =
                            QrCode.render(NeteaseOfficialApi.qrContent(key), 240);
                    Minecraft.getInstance().getTextureManager()
                            .register(renderable.location(), renderable.texture());
                    loginQr = renderable;
                } catch (Throwable t) {
                    // 用 Throwable 而不是 Exception：zxing 缺依赖时抛的是
                    // NoClassDefFoundError（Error 不是 Exception），不接住会直接崩游戏。
                    // 接住后只显示 Failed，不再整个客户端崩掉。
                    ClientBase.logger.error("二维码生成失败（多半是 zxing 依赖没进 classpath）", t);
                    loginQrStatus = NeteaseOfficialApi.QrStatus.FAILED;
                }
            });
        }).exceptionally(e -> {
            // 网络层已经把异常吞成 null 了，这里兜住「请求本身抛异常」的情况
            ClientBase.logger.error("申请二维码 key 失败", e);
            loginQrStatus = NeteaseOfficialApi.QrStatus.FAILED;
            return null;
        });
    }

    private void closeLoginDialog() {
        loginDialogOpen = false;
        releaseLoginQr();
    }

    /**
     * 渲染网易云扫码登录弹层。
     *
     * <p>布局：屏幕居中面板（深底 + 圆角），上方标题与关闭按钮，中间二维码（240×240），
     * 下方状态文字跟随 {@code loginQrStatus} 切换。
     */
    private void renderLoginDialog(DrawContext ctx, int mouseX, int mouseY) {
        // 背景遮罩（让背后内容变暗但不完全黑）
        ctx.drawRectXYWH(0, 0, width, height, new Paint().setColor(withAlpha(0xFF000000, 0.55f)));

        float panelW = 304.0f;
        float panelH = 372.0f;
        float px = (width - panelW) * 0.5f;
        float py = (height - panelH) * 0.5f;
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(px, py, panelW, panelH, 18),
                new Paint().setColor(SHELL));

        // ---- 顶部：标题 + 关闭按钮，两者垂直居中对齐 ----
        float headerY = py + 20;
        float closeSize = 28;
        float closeX = px + panelW - closeSize - 20;
        // 标题 y 由按钮中心反推，保证文字垂直居中和按钮同一条中线
        float titleH = HEADING_FONT.getHeight();
        float titleY = headerY + (closeSize - titleH) * 0.5f;
        GlHelper.drawText("Sign in with NetEase Music", px + 24, titleY, HEADING_FONT, CREAM);

        boolean closeHover = contains(mouseX, mouseY, closeX, headerY, closeSize, closeSize);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(closeX, headerY, closeSize, closeSize, closeSize * 0.5f),
                new Paint().setColor(closeHover ? RAISED : SIDEBAR));
        // 关闭图标用 Material Icons 的 close（ICON_CLOSE，和顶部 headerAction 同一个），
        // 之前用 "×" 字符 + HEADING_FONT，苹方里没有这个字形，画出来是空白。
        float crossW = ICON_FONT.getWidth(ICON_CLOSE);
        float crossH = ICON_FONT.getHeight();
        GlHelper.drawText(ICON_CLOSE,
                closeX + (closeSize - crossW) * 0.5f,
                headerY + (closeSize - crossH) * 0.5f,
                ICON_FONT, closeHover ? ACCENT : MUTED);
        // 弹层走独立列表（屏幕坐标），加到主 clickAreas 会因为坐标系不同点不中
        dialogClickAreas.add(new ClickArea(closeX, headerY, closeSize, closeSize, this::closeLoginDialog));

        // ---- 中间：二维码 ----
        int qrSize = 240;
        float qrX = px + (panelW - qrSize) * 0.5f;
        float qrY = py + 72;
        if (loginQr != null) {
            // 二维码是黑白的，不要被主题色染色 —— Paint 用纯白
            Texture tex = new Texture(loginQr.location(), qrSize, qrSize);
            ctx.drawTexture(tex,
                    Rectangle.ofXYWH(0, 0, qrSize, qrSize),
                    Rectangle.ofXYWH(qrX, qrY, qrSize, qrSize),
                    new Paint().setColor(0xFFFFFFFF));
        } else {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(qrX, qrY, qrSize, qrSize, 8),
                    new Paint().setColor(RAISED));
            drawCentered("Generating QR...", qrX, qrY + qrSize * 0.5f - 8, qrSize, BODY_FONT, MUTED);
        }

        // ---- 底部：状态文字 ----
        String statusText;
        int statusColor = MUTED;
        switch (loginQrStatus) {
            case WAITING -> {
                statusText = "Scan with the NetEase Music app";
                statusColor = MUTED;
            }
            case SCANNED -> {
                statusText = "Confirm sign-in on your phone";
                statusColor = ACCENT;
            }
            case CONFIRMED -> {
                statusText = "Signed in";
                statusColor = ACCENT_STRONG;
            }
            case EXPIRED -> {
                statusText = "QR expired — close & reopen to refresh";
                statusColor = 0xFFFF8080;
            }
            default -> {
                statusText = "Failed — close & reopen to retry";
                statusColor = 0xFFFF8080;
            }
        }
        drawCentered(statusText, px, qrY + qrSize + 18, panelW, BODY_FONT, statusColor);

        // 登录成功后 1.2 秒自动关弹层，给用户看到 ✓ 的时间
        if (loginQrStatus == NeteaseOfficialApi.QrStatus.CONFIRMED) {
            loginQrNextPollMs = Long.MAX_VALUE; // 停掉轮询
            long since = System.currentTimeMillis() - lastLoginConfirmMs;
            if (lastLoginConfirmMs == 0L) {
                lastLoginConfirmMs = System.currentTimeMillis();
            } else if (since > 1200L) {
                closeLoginDialog();
                lastLoginConfirmMs = 0L;
            }
        }
    }

    private void renderPlaybackBar(DrawContext ctx, float x, float y, float w, float h, float mouseX, float mouseY) {
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        SongInfo song = player.getCurrentSong();
        ensureAlbum(song);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, w, h, new float[]{0, 0, RADIUS, RADIUS}),
                new Paint().setColor(withAlpha(DIVIDER, 0.9f)));
        ctx.drawRectXYWH(x, y, w, 1, new Paint().setColor(RAISED));

        float progress = dragTarget == DragTarget.PROGRESS && pendingProgress >= 0
                ? pendingProgress : player.getProgress();
        drawSplitProgress(ctx, x, y, w, 3.3f, progress, false);
        lastProgressRect = Rectangle.ofXYWH(x, y - 7, w, 17);
        clickAreas.add(new ClickArea(x, y - 7, w, 17, () -> dragTarget = DragTarget.PROGRESS));

        float art = 40;
        float artX = x + 15.84f;
        float artY = y + 16;
        drawAlbum(ctx, song, artX, artY, art, 7.92f);
        float textX = artX + art + 12.32f;
        float textW = 184;
        GlHelper.drawText(ellipsize(song == null ? "No track playing" : song.name, HEADING_FONT, textW),
                textX, y + 20.24f, HEADING_FONT, CREAM);
        GlHelper.drawText(ellipsize(song == null ? "Open Search to start" : song.artist, SMALL_FONT, textW),
                textX, y + 44.88f, SMALL_FONT, MUTED);
        clickAreas.add(new ClickArea(artX, artY, art + textW + 12.32f, art, () -> {
            if (MusicPlayer.AUDIO_PLAYER.getCurrentSong() != null) {
                openPlayer(page);
            }
        }));

        float center = x + w * 0.5f;
        float sideOffset = 50.0f;
        drawControl(ctx, center - sideOffset - 13.64f, y + 20.24f, 27.28f, 29.92f, ICON_PREV, false, mouseX, mouseY, this::prevSong);
        drawControl(ctx, center - 19.36f, y + 14.96f, 38.72f, 38.72f,
                player.getState() == AudioPlayer.State.PLAYING ? ICON_PAUSE : ICON_PLAY,
                true, mouseX, mouseY, player.getState() == AudioPlayer.State.LOADING ? null : player::togglePause);
        drawControl(ctx, center + sideOffset - 13.64f, y + 20.24f, 27.28f, 29.92f, ICON_NEXT, false, mouseX, mouseY, this::nextSong);

        String current = timestamp(player.getCurrentPositionMs());
        GlHelper.drawText(current, x + w - 131, y + 34, SMALL_FONT, MUTED);
        GlHelper.drawText(ICON_VOLUME, x + w - 93, y + 37, ICON_FONT, MUTED);
        float volumeX = x + w - 70;
        float volume = dragTarget == DragTarget.VOLUME && pendingVolume >= 0 ? pendingVolume : player.getVolume();
        drawSlider(ctx, volumeX, y + 35, 54, volume);
        lastVolumeRect = Rectangle.ofXYWH(volumeX, y + 27, 54, 16);
        clickAreas.add(new ClickArea(volumeX, y + 27, 54, 16, () -> dragTarget = DragTarget.VOLUME));
    }

    private void renderPlayerPage(DrawContext ctx, float mouseX, float mouseY) {
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        SongInfo song = player.getCurrentSong();
        ensureAlbum(song);

        float dt = playerMotionDelta();

        // 背景压暗：封面流体本身挺亮，不压一层的话白字会发飘，歌词也「浮」不起来。
        // deobfmusic 的沉浸式页也是整屏盖一层约 60% 的黑。
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(0, 0, DESIGN_W, DESIGN_H, RADIUS),
                new Paint().setColor(0x96000000));

        // 布局照 deobfmusic 的沉浸式页来：左边 40% 一整列竖排，右边 60% 全给歌词。
        // 左右各往内收 30：分栏位置不动，两列的内容区各自窄 30。
        float sideInset = 30.0f;
        float dividerX = DESIGN_W * 0.4f;
        float leftContentW = dividerX - sideInset;
        float cx = sideInset + leftContentW * 0.5f;

        float artD = 182.0f;
        // 封面在左列里水平居中，所以它到面板左边框的距离 = sideInset + 两侧余量的一半。
        // 让「到上边框的距离」等于这个值，四个方向的留白才均衡。
        float top = sideInset + (leftContentW - artD) * 0.5f;
        // 封面横向往左挪
        float coverNudge = 8.0f;
        top -= coverNudge;
        float volW = 30.0f;
        float nameH = 34.0f;
        float artistH = 26.0f;
        float barH = 5.5f;
        float timeH = 16.0f;
        float ctrlH = 42.0f;
        float gapArtName = 22.0f;
        float gapNameArtist = 2.0f;
        float gapArtistBar = 2.0f;
        float gapBarTime = 9.0f;
        float gapTimeCtrl = 12.0f;

        // 封面呼吸：播放时满尺寸，暂停时收一点。静止状态也有层次，不会像贴上去的。
        coverBreath.animate(player.getState() == AudioPlayer.State.PLAYING ? 1.0 : 0.0, 0.4, Easings.EASE_OUT_QUAD);
        coverBreath.tick();
        float coverD = artD * (0.94f + 0.06f * coverBreath.getValueF());
        float artX = cx - artD * 0.5f - coverNudge;
        float coverX = artX + (artD - coverD) * 0.5f;
        float coverY = top + (artD - coverD) * 0.5f;
        // 封面外面一圈白色柔光。压暗的背景上，白光的「托举感」比黑色投影更轻，
        // 也不会在深色底上糊成一团黑边。
        ctx.drawBlurredRoundedRect(RoundedRectangle.ofXYWHR(coverX, coverY, coverD, coverD, 16.0f),
                0.0f, 0.0f, 22.0f, 0.0f, 0x40FFFFFF);
        drawAlbum(ctx, song, coverX, coverY, coverD, 16.0f, AP_PLACEHOLDER, AP_TEXT_FAINT);

        // 歌名和歌手左对齐到进度条左端
        float nameY = top + artD + gapArtName;
        // 右侧要给音量按钮让位
        float textMaxW = artD - volW - 10.0f;
        String title = song == null ? "Nothing playing" : song.name;
        GlHelper.drawText(ellipsize(title, AP_TITLE_FONT, textMaxW), artX, nameY, AP_TITLE_FONT, AP_TEXT);

        float artistY = nameY + nameH + gapNameArtist - 16.0f;
        String artist = song == null ? "Pick a song to start" : song.artist;
        GlHelper.drawText(ellipsize(artist, AP_ARTIST_FONT, textMaxW), artX, artistY, AP_ARTIST_FONT, AP_TEXT_DIM);

        // 音量按钮：和歌曲信息同一行、再上移 21，右端对齐进度条右端。
        // 图标在按钮自身的基础上再上移 1px，悬停矩形保持原位不动。
        playerBtnRects[0] = Rectangle.ofXYWH(artX + artD - volW, artistY - 24.0f, volW, volW);
        boolean muted = player.getVolume() <= 0.001f;
        drawPlayerButton(ctx, 0, playerBtnRects[0], ICON_VOLUME, ICON_FONT,
                mouseX, mouseY, this::toggleVolumeBar, -1.0f, muted ? AP_TEXT_FAINT : AP_TEXT_DIM, dt);

        // 进度条：命中范围比条本身大一圈，悬停（或拖拽）时拉粗 3px。
        // 相对歌手再上移 12（歌手自己已经上移 16，合起来是用户要的 28）。
        float barHitY = artistY + artistH + gapArtistBar - 12.0f;
        boolean barActive = dragTarget == DragTarget.PROGRESS
                || contains(mouseX, mouseY, artX - 6.0f, barHitY - 10.0f, artD + 12.0f, barH + 20.0f);
        playerBarEngage += ((barActive ? 1.0f : 0.0f) - playerBarEngage) * Math.min(1.0f, dt / 0.09f);
        float barHeight = barH + 3.0f * playerBarEngage;
        float progress = dragTarget == DragTarget.PROGRESS && pendingProgress >= 0
                ? pendingProgress : player.getProgress();
        drawPlayerProgress(ctx, artX, barHitY - (barHeight - barH) * 0.5f, artD, barHeight, progress);
        lastProgressRect = Rectangle.ofXYWH(artX, barHitY - 8.0f, artD, barH + 16.0f);
        clickAreas.add(new ClickArea(artX, barHitY - 8.0f, artD, barH + 16.0f, () -> dragTarget = DragTarget.PROGRESS));

        float timeY = barHitY + barH + gapBarTime;
        boolean loading = player.getState() == AudioPlayer.State.LOADING;
        long duration = song == null ? 0L : song.duration;
        // 拖进度条时先按拖到的位置显示，松手才真正 seek
        long shownPos = dragTarget == DragTarget.PROGRESS && pendingProgress >= 0
                ? (long) (pendingProgress * duration) : player.getCurrentPositionMs();
        // 右边显示的是「距离结束还有多久」，带个负号，和左边已播时长一眼区分开
        String elapsed = loading ? "Loading" : timestamp(shownPos);
        String remaining = "-" + timestamp(Math.max(0L, duration - shownPos));
        GlHelper.drawText(elapsed, artX, timeY, AP_TIME_FONT, AP_TEXT_FAINT);
        GlHelper.drawText(remaining, artX + artD - measure(remaining, AP_TIME_FONT), timeY, AP_TIME_FONT, AP_TEXT_FAINT);

        // 三个控制按钮：居中于进度条。timeY 已经跟着进度条上移 28，这里再下压 2，净上移 26。
        float ctrlY = timeY + timeH + gapTimeCtrl - 2.0f;
        float sideW = 34.0f;
        float playW = 42.0f;
        float ctrlGap = 14.0f;
        float bx = artX + artD * 0.5f - (sideW * 2.0f + playW + ctrlGap * 2.0f) * 0.5f;
        playerBtnRects[1] = Rectangle.ofXYWH(bx, ctrlY + (ctrlH - sideW) * 0.5f, sideW, sideW);
        playerBtnRects[2] = Rectangle.ofXYWH(bx + sideW + ctrlGap, ctrlY + (ctrlH - playW) * 0.5f, playW, playW);
        playerBtnRects[3] = Rectangle.ofXYWH(bx + sideW + ctrlGap + playW + ctrlGap,
                ctrlY + (ctrlH - sideW) * 0.5f, sideW, sideW);
        drawPlayerButton(ctx, 1, playerBtnRects[1], ICON_PREV, ICON_FONT,
                mouseX, mouseY, this::prevSong, -1.0f, AP_TEXT, dt);
        drawPlayerButton(ctx, 2, playerBtnRects[2],
                player.getState() == AudioPlayer.State.PLAYING ? ICON_PAUSE : ICON_PLAY,
                ICON_LARGE, mouseX, mouseY, loading ? null : player::togglePause, 0.0f, AP_TEXT, dt);
        drawPlayerButton(ctx, 3, playerBtnRects[3], ICON_NEXT, ICON_FONT,
                mouseX, mouseY, this::nextSong, -1.0f, AP_TEXT, dt);

        // 音量滑块：点音量按钮展开，宽度高度都从中间「长出来」。
        // 用的是和主进度条同一套画法（白、左圆右直、带泛光），所以高宽比一致。
        volumeBarReveal += ((volumeBarOpen ? 1.0f : 0.0f) - volumeBarReveal) * Math.min(1.0f, dt / 0.12f);
        if (volumeBarReveal > 0.01f) {
            float volBarW = artD * volumeBarReveal;
            float volBarH = Math.max(1.0f, barH * volumeBarReveal);
            float volBarX = artX + (artD - volBarW) * 0.5f;
            float volBarY = ctrlY - 20.0f + (barH - volBarH) * 0.5f;
            float volume = dragTarget == DragTarget.VOLUME && pendingVolume >= 0
                    ? pendingVolume : player.getVolume();
            drawPlayerProgress(ctx, volBarX, volBarY, volBarW, volBarH, volume);
            lastVolumeRect = Rectangle.ofXYWH(artX, ctrlY - 26.0f, artD, barH + 18.0f);
            clickAreas.add(new ClickArea(artX, ctrlY - 26.0f, artD, barH + 18.0f,
                    () -> dragTarget = DragTarget.VOLUME));
        }

        float padL = DESIGN_W * 0.055f;

        // 右列整块给歌词
        float lyricX = dividerX + padL;
        renderLyrics(ctx, song, lyricX, 8.0f, DESIGN_W - sideInset - 16.0f - lyricX, DESIGN_H - 16.0f);

        // 关闭放右上角。图标单独上移 1px，悬停矩形保持原样。
        float closeSize = 32.0f;
        playerBtnRects[4] = Rectangle.ofXYWH(DESIGN_W - closeSize - 18.0f, 18.0f, closeSize, closeSize);
        drawPlayerButton(ctx, 4, playerBtnRects[4], ICON_CLOSE, ICON_FONT,
                mouseX, mouseY, this::returnFromPlayer, -1.0f, AP_TEXT, dt);
    }

    /** 点音量按钮展开／收起音量滑块。 */
    private void toggleVolumeBar() {
        volumeBarOpen = !volumeBarOpen;
    }

    /** 沉浸式页自己的帧间隔。不走 renderRoot 那套全局计时，免得互相吃掉 delta。 */
    private float playerMotionDelta() {
        long now = System.nanoTime();
        float dt = playerMotionNs == 0L ? 0.0f : (now - playerMotionNs) / 1.0e9f;
        playerMotionNs = now;
        return clamp(dt, 0.0f, 0.05f);
    }

    /** 命中哪个按钮就按哪个（按下状态要撑到松手，所以得记下来）。 */
    private void pressPlayerButton(double mouseX, double mouseY) {
        double designX = toDesignX(mouseX);
        double designY = toDesignY(mouseY);
        for (int i = 0; i < PLAYER_BTN_COUNT; i++) {
            Rectangle rect = playerBtnRects[i];
            if (rect != null && contains(designX, designY,
                    rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight())) {
                playerPressedButton = i;
                playerBtnPress[i].setTarget(1.0);
                return;
            }
        }
    }

    /**
     * 沉浸式页的进度条：白色、轻微泛光、左端圆角右端直角。
     *
     * <p>泛光走的是和歌词同一套离屏模糊，但半径小得多——进度条是细长条，
     * 光晕一大就糊了，只要贴着边缘微微透出来就够。
     */
    private void drawPlayerProgress(DrawContext ctx, float x, float y, float w, float h, float value) {
        float progress = clamp(value, 0, 1);
        float radius = h * 0.5f;
        float[] trackRadii = {radius, radius, radius, radius};

        ctx.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, w, h, trackRadii),
                new Paint().setColor(AP_TRACK));
        if (progress <= 0.001f) {
            return;
        }
        float filled = w * progress;

        // 右端半径：进入轨道的右圆角区之后才开始长出来，用线性过渡。
        //
        // 这个式子不是凑出来的：设右端半径 r，白色右上角的最右点是 filled - r，
        // 而轨道在顶边处的最右点是 w - radius。要让两者相切就得 filled - r = w - radius，
        // 即 r = filled - (w - radius)——正好就是把 (filled - (w - radius)) / radius 归一化后
        // 再乘 radius。所以白色右上角会严丝合缝地贴着轨道的圆角走，
        // 既不会在快播完时顶掉圆角，也不需要任何裁剪。
        float rightR = radius * clamp((filled - (w - radius)) / radius, 0.0f, 1.0f);
        float[] fillRadii = {radius, rightR, rightR, radius};

        // 泛光按已播宽度画，不裁，让它自然溢出轨道一圈
        ctx.drawBlurredRoundedRect(RoundedRectangle.ofXYWHRadii(x, y - 1.0f, filled, h + 2.0f, fillRadii),
                0.0f, 0.0f, 8.0f, 0.0f, AP_BAR_GLOW);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHRadii(x, y, filled, h, fillRadii),
                new Paint().setColor(AP_BAR));
    }

    /**
     * 沉浸式页的按钮：无边框、无底色，只有图标。
     *
     * <p>三样东西凑出「手感」：悬停垫底平滑淡入、按下走弹簧回弹、图标随交互微缩放
     * （悬停放大 4%、按下缩小 6%，取值和 deobfmusic 一致）。缺一样，控件看着就是死的。
     *
     * @param index       第几个按钮，用来索引动画状态
     * @param iconOffsetY 只挪图标的额外垂直偏移，悬停矩形保持原位
     * @param activeColor 可点击时的图标颜色
     */
    private void drawPlayerButton(DrawContext ctx, int index, Rectangle box, String icon, FontRenderer iconFont,
                                  float mouseX, float mouseY, Runnable action,
                                  float iconOffsetY, int activeColor, float dt) {
        float x = box.getX();
        float y = box.getY();
        float w = box.getWidth();
        float h = box.getHeight();
        boolean enabled = action != null;
        boolean hovered = enabled && contains(mouseX, mouseY, x, y, w, h);

        // 悬停：指数逼近，时间常数 55ms
        playerBtnHover[index] += ((hovered ? 1.0f : 0.0f) - playerBtnHover[index]) * Math.min(1.0f, dt / 0.055f);
        playerBtnPress[index].update(dt);
        float hoverK = playerBtnHover[index];
        float pressK = (float) playerBtnPress[index].getValue();

        if (hoverK > 0.01f) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, h * 0.5f),
                    new Paint().setColor(withAlpha(AP_HOVER, hoverK)));
        }

        float iconX = x + (w - measure(icon, iconFont)) * 0.5f;
        float iconY = y + (h - iconFont.getMetrics().capHeight()) * 0.5f + 8.0f + iconOffsetY;
        float iconScale = 1.0f + hoverK * 0.04f - pressK * 0.06f;
        boolean scaled = Math.abs(iconScale - 1.0f) > 0.001f;
        if (scaled) {
            float pivotX = x + w * 0.5f;
            float pivotY = y + h * 0.5f;
            ctx.save();
            ctx.translate(pivotX, pivotY);
            ctx.scale(iconScale, iconScale);
            ctx.translate(-pivotX, -pivotY);
        }
        GlHelper.drawText(icon, iconX, iconY, iconFont, enabled ? activeColor : AP_TEXT_FAINT);
        if (scaled) {
            ctx.restore();
        }
        if (enabled) {
            clickAreas.add(new ClickArea(x, y, w, h, action));
        }
    }

    private void renderLyrics(DrawContext ctx, SongInfo song, float x, float y, float w, float h) {
        if (song == null) {
            GlHelper.drawText("Play a song to see lyrics", x, y + h * 0.495f, AP_TITLE_FONT, DIM);
            return;
        }

        List<LyricLine> lines = lyricsFor(song);
        if (lines.isEmpty()) {
            String message = lyricsCache.containsKey(song.id) ? "Lyrics unavailable" : "Loading lyrics...";
            GlHelper.drawText(message, x, y + h * 0.495f, AP_TITLE_FONT, DIM);
            return;
        }

        final float lineH = 46.64f;
        final int count = lines.size();
        long position = MusicPlayer.AUDIO_PLAYER.getCurrentPositionMs();
        int current = findCurrentLyricLine(lines, position);

        // 换歌或行数变了：重建弹簧并直接落位，不让上一首的位置飘过来
        if (lyricSongId != song.id || lyricLineY.length != count) {
            lyricSongId = song.id;
            lyricLineY = new SpringSolver[count];
            lyricLineScale = new SpringSolver[count];
            lyricRestY = new float[count];
            lyricCascade = new float[count];
            for (int i = 0; i < count; i++) {
                lyricLineY[i] = new SpringSolver();
                lyricLineScale[i] = new SpringSolver(2.0, 25.0, 100.0);
            }
            lyricAnchor = -1;
            lyricLastBlockTop = Float.NaN;
            lyricSpringsReady = false;
            lyricLastPosMs = -1L;
            lyricFrameNs = 0L;
        }

        // 整块的位置：不足一屏居中，否则让当前行停在区域内 26.4% 处（沿用原来的锚点比例）
        float blockTop;
        if (count * lineH < h) {
            blockTop = (h - count * lineH) * 0.5f;
        } else {
            // 上界必须是「第一行作为当前行时的位置」，不能写成 0。
            // 写 0 的话第一句会被硬按在区域顶边上——0:00 时还没轮到下一句，
            // 当前行一直是第一行，于是它就一直贴在框沿上，看着像跑到框外去了。
            blockTop = clamp(h * 0.264f - current * lineH, h - count * lineH, h * 0.264f);
        }
        for (int i = 0; i < count; i++) {
            lyricRestY[i] = y + blockTop + i * lineH;
        }

        // 帧间隔自己维护，不动 renderRoot 那套全局计时
        long now = System.nanoTime();
        float dt = lyricFrameNs == 0L ? 0.0f : (now - lyricFrameNs) / 1.0e9f;
        lyricFrameNs = now;
        dt = clamp(dt, 0.0f, 0.05f);

        boolean seek = lyricLastPosMs >= 0L
                && LyricSprings.isHardClockDiscontinuity(lyricLastPosMs / 1000.0, position / 1000.0);
        long seekDeltaMs = lyricLastPosMs >= 0L ? position - lyricLastPosMs : 0L;
        lyricLastPosMs = position;

        boolean anchorMoved = current != lyricAnchor;
        boolean layoutMoved = Float.isNaN(lyricLastBlockTop)
                || Math.abs(blockTop - lyricLastBlockTop) > 0.5f;

        if (anchorMoved || layoutMoved || seek) {
            double posSec = position / 1000.0;
            double lineEndSec = lineEndMs(lines, current) / 1000.0;
            LyricSprings.Physics physics;
            if (seek) {
                physics = LyricSprings.seekSpring(current - lyricAnchor, seekDeltaMs / 1000.0);
            } else {
                double gap = current > 0
                        ? Math.max(lines.get(current).timeMs() - lineEndMs(lines, current - 1), 0L) / 1000.0
                        : 0.0;
                physics = LyricSprings.lineTransitionSpring(lines.get(current).hasWordTiming(), gap);
                physics = LyricSprings.retimeLineSpring(physics, lineEndSec, posSec,
                        lineEndSec - posSec - 0.5 < 0.6);
            }
            for (int i = 0; i < count; i++) {
                lyricLineY[i].setParams(physics.mass(), physics.damping(), physics.stiffness());
                lyricLineScale[i].setParams(physics.mass(), physics.damping(), physics.stiffness());
            }

            // 级联锚点取可见的第一行，让波形从屏幕边缘往中间推
            int cascadeAnchor = current;
            while (cascadeAnchor > 0 && lyricRestY[cascadeAnchor - 1] + lineH > y) {
                cascadeAnchor--;
            }
            for (int i = 0; i < count; i++) {
                double delay = seek ? 0.0 : LyricSprings.cascadeDelay(
                        LyricSprings.validLineDistance(lines, cascadeAnchor, i), false);
                lyricCascade[i] = (float) delay;
                lyricLineY[i].setTarget(lyricRestY[i], delay);
                lyricLineScale[i].setTarget(i == current ? 1.0 : 0.96, delay);
            }
            lyricAnchor = current;
            lyricLastBlockTop = blockTop;
        }

        for (int i = 0; i < count; i++) {
            if (!lyricSpringsReady) {
                lyricLineY[i].setValue(lyricRestY[i]);
                lyricLineScale[i].setValue(1.0);
                continue;
            }
            lyricLineY[i].update(dt);
            lyricLineScale[i].update(dt);
        }
        lyricSpringsReady = true;

        // 左边多给 3px 余量：字形的抗锯齿边缘和泛光需要一点空间，贴着边会被切掉
        Rectangle viewport = Rectangle.ofXYWH(x - 3.0f, y, w + 3.0f, h);
        ctx.save();
        ctx.clipRect(viewport, true);

        for (int i = 0; i < count; i++) {
            float rowY = (float) lyricLineY[i].getValue();
            if (rowY < y - lineH || rowY > y + h) {
                continue;
            }
            int distance = Math.abs(i - current);
            boolean active = i == current;
            FontRenderer font = active ? AP_LYRIC_ACTIVE_FONT : AP_LYRIC_FONT;
            // 层次全靠 alpha，颜色一律用白。以前远处行同时叠了「更深的灰」和「更低的 alpha」，
            // 两个衰减相乘，越远越接近全黑。
            float alpha = (0.30f + 0.70f * lyricActiveK(lines, i, position))
                    * (distance >= LYRIC_FADE_DISTANCE ? 0.85f : 1.0f);
            String raw = lines.get(i).text();
            String text = ellipsize(raw == null || raw.isBlank() ? "···" : raw, font, w);

            float scale = (float) lyricLineScale[i].getValue();
            boolean scaled = Math.abs(scale - 1.0f) > 0.001f;
            if (scaled) {
                float anchorY = rowY + lineH * 0.5f;
                ctx.save();
                ctx.translate(x, anchorY);
                ctx.scale(scale, scale);
                ctx.translate(-x, -anchorY);
            }
            if (active) {
                drawKaraokeLine(text, x, rowY, font, lineProgress(lines, current, position), alpha,
                        lines.get(current), lineEndMs(lines, current), position);
            } else {
                GlHelper.drawText(text, x, rowY, font, withAlpha(CREAM, alpha));
            }
            if (scaled) {
                ctx.restore();
            }
        }

        // 泛光必须在裁剪框内叠回。放在 restore 之后的话，当前行的光晕会整圈溢到歌词区外面，
        // 看着就是「歌词跑出屏幕还留了一点点」。
        if (current >= 0 && current < count) {
            emitLyricGlow(lines.get(current), x, (float) lyricLineY[current].getValue(), w);
        }
        ctx.restore();
    }

    /**
     * 当前行的泛光层。
     *
     * <p>只画当前行，并且用固定的高亮色——发光是被高斯核扩散开的低频信息，逐字细节在里面留不住，
     * 画整行反而更省。扫光的层次感留给主画面那一遍。
     */
    private void emitLyricGlow(LyricLine line, float x, float rowY, float w) {
        String raw = line.text();
        if (raw == null || raw.isBlank()) {
            return;
        }
        String text = ellipsize(raw, AP_LYRIC_ACTIVE_FONT, w);
        LyricGlowFbo.begin();
        if (LyricGlowFbo.isCollecting()) {
            GlHelper.drawText(text, x, rowY, AP_LYRIC_ACTIVE_FONT, withAlpha(CREAM, GLOW_ALPHA));
            LyricGlowFbo.end(GLOW_RADIUS);
        }
    }

    /**
     * 一行的结束时间。
     *
     * <p>有逐字时间就用最后一个字的时间窗算准；没有就退回到「下一行的起点」——
     * 这也是 LRC 格式能给出的最好估计。
     */
    private static long lineEndMs(List<LyricLine> lines, int index) {
        LyricLine line = lines.get(index);
        List<LyricLine.Word> words = line.words();
        if (words != null && !words.isEmpty()) {
            LyricLine.Word last = words.get(words.size() - 1);
            return last.startMs() + Math.max(last.durationMs(), 1L);
        }
        if (index + 1 < lines.size()) {
            return lines.get(index + 1).timeMs();
        }
        return line.timeMs() + 6000L;
    }

    /**
     * 某一行此刻的「亮起程度」0..1。
     *
     * <p>提前 450ms 开始淡入（歌词比人声早一点点浮起来），行尾后 100ms 开始淡出。
     * 这样旧行不是被当前行替换时瞬间压暗，而是自己缓缓沉下去。
     */
    private static float lyricActiveK(List<LyricLine> lines, int index, long positionMs) {
        long start = lines.get(index).timeMs();
        long end = Math.max(lineEndMs(lines, index), start + 1L);
        long fadeInStart = start - 450L;
        long fadeInEnd = fadeInStart + 600L;
        if (positionMs < fadeInStart) {
            return 0.0f;
        }
        if (positionMs < fadeInEnd) {
            return smoothstep((positionMs - fadeInStart) / (float) Math.max(1L, fadeInEnd - fadeInStart));
        }
        long fadeOutStart = end + 100L;
        long fadeOutEnd = fadeOutStart + 250L;
        if (positionMs < fadeOutStart) {
            return 1.0f;
        }
        if (positionMs >= fadeOutEnd) {
            return 0.0f;
        }
        return 1.0f - smoothstep((positionMs - fadeOutStart) / (float) Math.max(1L, fadeOutEnd - fadeOutStart));
    }

    private static float smoothstep(float t) {
        t = clamp(t, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * 「唱到这个字之后过了 τ 秒」对应的上浮系数 0..1。
     *
     * <p>是一条阻尼比约 0.94 的二阶弹簧阶跃响应（就是 deobfmusic 用的那条曲线）：
     * 字刚唱到时弹起来，2.5 秒内回落到原位。上浮快、回来慢，所以看着是「跳一下」。
     */
    private static float lyricLiftK(double tau) {
        if (tau <= 0.0) {
            return 0.0f;
        }
        if (tau > 2.5) {
            return 1.0f;
        }
        double decay = 3.49999983766082;
        double omegaD = 3.7416574 * Math.sqrt(0.12500008735550994);
        double envelope = Math.exp(-decay * tau);
        double y = 1.0 - envelope * (Math.cos(omegaD * tau) + decay / omegaD * Math.sin(omegaD * tau));
        return (float) Math.max(0.0, y);
    }

    /**
     * 当前行唱到哪儿了，返回 0..1。
     *
     * <p>优先按 {@link LyricLine#words()} 的逐字时间精确算（直连网易能拿到 yrc）；
     * 没拿到逐字时间就退回「本行起点 → 下一行起点」线性分摊。
     */
    private static float lineProgress(List<LyricLine> lines, int index, long positionMs) {
        if (index < 0 || index >= lines.size()) {
            return 0.0f;
        }
        LyricLine line = lines.get(index);
        // 1) 有逐字数据：当前字的时间窗（wordStart, wordStart+wordDur）内插值
        List<LyricLine.Word> words = line.words();
        if (words != null && !words.isEmpty()) {
            for (int w = 0; w < words.size(); w++) {
                LyricLine.Word word = words.get(w);
                long wStart = word.startMs();
                long wEnd = wStart + Math.max(word.durationMs(), 1L);
                if (positionMs < wEnd) {
                    // 字内进度 + 字之前所有字的累计宽度占比
                    float within = clamp((positionMs - wStart) / (float) (wEnd - wStart), 0.0f, 1.0f);
                    return (w + within) / words.size();
                }
            }
            return 1.0f;
        }
        // 2) 没逐字：按行时长分摊
        long start = line.timeMs();
        long end = index + 1 < lines.size() ? lines.get(index + 1).timeMs() : start + 6000L;
        if (end <= start) {
            return 1.0f;
        }
        return clamp((positionMs - start) / (float) (end - start), 0.0f, 1.0f);
    }

    /**
     * 画当前歌词行：未唱部分压暗，已唱部分提亮，分界随播放连续推进。
     *
     * <p>亮色不是在字与字之间跳着走，而是按「已唱宽度」裁一块区域出来再叠一层亮色，
     * 所以唱到半个字也看得出来，分界是连续的。
     *
     * <p>注意这里刻意不套缩放变换：裁剪区域是按当前 pose 变换后的坐标算的，
     * 而当前行本来就不缩放，套上去只会把裁剪算歪。
     */
    /**
     * 画当前歌词行的扫光：逐个字画，唱到的字向上浮一点再落回原位。
     *
     * <p>改成逐字之后就不再用裁剪做扫光了——每个字自己决定亮还是暗，界线按字走，
     * 同时还能给每个字单独加上浮位移（整行一次画是做不出来的）。
     */
    private void drawKaraokeLine(String text, float x, float rowY, FontRenderer font, float progress,
                                 float alpha, LyricLine line, long lineEndMs, long positionMs) {
        int count = text.length();
        if (count == 0) {
            return;
        }
        long lineStart = line.timeMs();
        long span = Math.max(1L, lineEndMs - lineStart);
        int dimColor = withAlpha(DIM, alpha);
        int litColor = withAlpha(CREAM, alpha);
        float cursor = x;
        for (int i = 0; i < count; i++) {
            String glyph = String.valueOf(text.charAt(i));
            // 这个字唱完的时刻。有逐字数据时 lineProgress 已经把整体进度算准了，这里按行内位置均摊足够。
            long charEnd = lineStart + span * (i + 1) / count;
            float lift = -2.0f * lyricLiftK((positionMs - charEnd) / 1000.0);
            boolean lit = (i + 1) / (float) count <= progress;
            GlHelper.drawText(glyph, cursor, rowY + lift, font, lit ? litColor : dimColor);
            cursor += measure(glyph, font);
        }
    }

    private void drawControl(DrawContext ctx, float x, float y, float w, float h, String icon, boolean primary,
                             float mouseX, float mouseY, Runnable action) {
        boolean enabled = action != null;
        boolean hover = enabled && contains(mouseX, mouseY, x, y, w, h);
        if (primary || hover) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, h, h * 0.5f),
                    new Paint().setColor(primary ? enabled ? BERRY_HOVER : RAISED : RAISED));
        }
        drawCentered(icon, x, y + (h - ICON_LARGE.getMetrics().capHeight()) * 0.5f + 8.0f, w, ICON_LARGE,
                enabled ? primary ? CREAM : hover ? ACCENT : CREAM : DIM);
        if (enabled) {
            clickAreas.add(new ClickArea(x, y, w, h, action));
        }
    }

    private void drawSplitProgress(DrawContext ctx, float x, float y, float w, float height, float value, boolean thumb) {
        float progress = clamp(value, 0, 1);
        float radius = height * 0.5f;
        if (progress <= 0.001f) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, height, radius), new Paint().setColor(RAISED));
            return;
        }
        if (progress >= 0.999f) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, w, height, radius), new Paint().setColor(ACCENT));
            return;
        }
        float splitX = x + w * progress;
        float gap = thumb ? 11.0f : 3.0f;
        float playedW = Math.max(0, splitX - x - gap * 0.5f);
        float remainingX = splitX + gap * 0.5f;
        float remainingW = Math.max(0, x + w - remainingX);
        if (playedW > 0) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, playedW, height, radius), new Paint().setColor(ACCENT));
        }
        if (remainingW > 0) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(remainingX, y, remainingW, height, radius), new Paint().setColor(RAISED));
        }
        if (thumb) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(splitX - 4, y - 2, 8, height + 4, 4), new Paint().setColor(CREAM));
        }
    }

    private void drawSlider(DrawContext ctx, float x, float y, float w, float value) {
        float progress = clamp(value, 0, 1);
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y - 1, w, 4, 2), new Paint().setColor(RAISED));
        if (progress > 0) {
            ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y - 1, w * progress, 4, 2), new Paint().setColor(ACCENT));
        }
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x + w * progress - 4, y - 3, 8, 8, 4), new Paint().setColor(CREAM));
    }

    private void drawAlbum(DrawContext ctx, SongInfo song, float x, float y, float size, float radius) {
        drawAlbum(ctx, song, x, y, size, radius, BERRY, ACCENT);
    }

    /**
     * @param placeholder 封面没加载出来时的占位底色
     * @param iconColor   占位图标颜色
     */
    private void drawAlbum(DrawContext ctx, SongInfo song, float x, float y, float size, float radius,
                           int placeholder, int iconColor) {
        if (song != null && song.id == albumSongId && albumTexture != null) {
            org.joml.Matrix4f pose = ctx.getPoseStack().last().pose();
            DrawContext.getRoundedRectShader().drawTextured(pose, x, y, x + size, y + size,
                    radius, radius, radius, radius, 0xFFFFFFFF, albumTexture.getGlId(), 0, 0, 1, 1);
            return;
        }
        // 封面还没加载出来时的占位。
        ctx.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, size, size, radius), new Paint().setColor(placeholder));
        float iconY = y + (size - ICON_LARGE.getMetrics().capHeight()) * 0.5f + 8.0f;
        drawCentered(ICON_MUSIC, x, iconY, size, ICON_LARGE, iconColor);
    }

    private List<SongInfo> recommendations() {
        List<SongInfo> result = new ArrayList<>();
        SongInfo current = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        addUnique(result, current);
        for (SongInfo song : MusicPlayer.PLAYLIST.getSongs()) {
            addUnique(result, song);
        }
        for (SongInfo song : playQueue) {
            addUnique(result, song);
        }
        return result;
    }

    private static void addUnique(List<SongInfo> songs, SongInfo candidate) {
        if (candidate != null && songs.stream().noneMatch(song -> song.id == candidate.id)) {
            songs.add(candidate);
        }
    }

    private boolean isCurrentSong(SongInfo song) {
        SongInfo current = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        return current != null && song != null && current.id == song.id;
    }

    private void openPage(Page target) {
        page = target;
        searchFocused = target == Page.SEARCH && searchFocused;
        if (target != Page.SEARCH) {
            searchFocused = false;
            searchSelectAll = false;
        }
    }

    private void openPlayer(Page returnPage) {
        if (returnPage != Page.PLAYER) {
            playerReturnPage = returnPage;
        }
        searchFocused = false;
        searchSelectAll = false;
        page = Page.PLAYER;
    }

    private void returnFromPlayer() {
        page = playerReturnPage == Page.PLAYER ? Page.HOME : playerReturnPage;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        // 登录弹层在最上层：用原始屏幕坐标优先匹配，命中就吞掉点击（不穿透到下层 UI）。
        // 弹层是在 translate/scale 变换之外画的，所以这里不能走 toDesignX/Y。
        if (loginDialogOpen) {
            for (int i = dialogClickAreas.size() - 1; i >= 0; i--) {
                ClickArea area = dialogClickAreas.get(i);
                if (area.contains(mouseX, mouseY)) {
                    area.action().run();
                    return true;
                }
            }
            return true;
        }
        double designX = toDesignX(mouseX);
        double designY = toDesignY(mouseY);
        // 先记下按到了哪个沉浸式页按钮（按下状态要撑到松手），再走通用的点击区
        pressPlayerButton(mouseX, mouseY);
        for (int i = clickAreas.size() - 1; i >= 0; i--) {
            ClickArea area = clickAreas.get(i);
            if (!area.contains(designX, designY)) {
                continue;
            }
            area.action().run();
            if (dragTarget == DragTarget.PROGRESS) {
                updateProgress(designX);
            } else if (dragTarget == DragTarget.VOLUME) {
                updateVolume(designX);
            }
            return true;
        }
        searchFocused = false;
        searchSelectAll = false;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double designX = toDesignX(mouseX);
        if (button == 0 && dragTarget == DragTarget.PROGRESS) {
            updateProgress(designX);
            return true;
        }
        if (button == 0 && dragTarget == DragTarget.VOLUME) {
            updateVolume(designX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // 松手就把按下的弹簧放回去，图标会弹一下
        if (playerPressedButton >= 0) {
            playerBtnPress[playerPressedButton].setTarget(0.0);
            playerPressedButton = -1;
        }
        if (button == 0 && dragTarget == DragTarget.PROGRESS && pendingProgress >= 0) {
            commitProgress(pendingProgress);
            pendingProgress = -1;
        }
        if (button == 0 && dragTarget == DragTarget.VOLUME && pendingVolume >= 0) {
            MusicPlayer.AUDIO_PLAYER.setVolume(pendingVolume);
            pendingVolume = -1;
        }
        dragTarget = DragTarget.NONE;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        float designX = toDesignX(mouseX);
        float designY = toDesignY(mouseY);
        if (page == Page.SEARCH && searchViewport != null && searchViewport.contains(designX, designY)) {
            searchScroll = clamp(searchScroll - (float) delta * 30, 0, maxSearchScroll);
            return true;
        }
        if (page == Page.QUEUE && queueViewport != null && queueViewport.contains(designX, designY)) {
            queueScroll = clamp(queueScroll - (float) delta * 30, 0, maxQueueScroll);
            return true;
        }
        if (page == Page.PLAYLIST && playlistViewport != null && playlistViewport.contains(designX, designY)) {
            playlistScroll = clamp(playlistScroll - (float) delta * 30, 0, maxPlaylistScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!searchFocused || codePoint < 32 || codePoint == 127) {
            return super.charTyped(codePoint, modifiers);
        }
        searchText = searchSelectAll ? String.valueOf(codePoint) : searchText + codePoint;
        searchSelectAll = false;
        searchDirty = true;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                startSearch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                searchText = searchSelectAll ? "" : searchText.isEmpty() ? "" : searchText.substring(0, searchText.length() - 1);
                searchSelectAll = false;
                searchDirty = true;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE) {
                searchText = "";
                searchSelectAll = false;
                searchDirty = true;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                searchSelectAll = false;
                return true;
            }
            if (Screen.isPaste(keyCode)) {
                String paste = Minecraft.getInstance().keyboardHandler.getClipboard();
                searchText = searchSelectAll ? paste : searchText + paste;
                searchSelectAll = false;
                searchDirty = true;
                return true;
            }
            if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_A) {
                searchSelectAll = !searchText.isEmpty();
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && page == Page.PLAYER) {
            returnFromPlayer();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void startSearch() {
        searchDirty = false;
        String query = searchText.trim();
        long sequence = searchSeq.incrementAndGet();
        if (query.isEmpty()) {
            searchResults = List.of();
            searching = false;
            return;
        }
        searching = true;
        NeteaseApi.search(query, 20).whenComplete((results, error) -> {
            if (searchSeq.get() == sequence) {
                searchResults = results == null ? List.of() : results;
                searching = false;
                searchScroll = 0;
            }
        });
    }

    private void playSongAndOpen(SongInfo song, List<SongInfo> queue, int index, boolean autoAdvance, Page returnPage) {
        playSong(song, queue, index, autoAdvance);
        openPlayer(returnPage);
    }

    private void playSong(SongInfo song, List<SongInfo> queue, int index, boolean autoAdvance) {
        long request = playRequestSeq.incrementAndGet();
        playlistAutoAdvance = autoAdvance;
        queueIndex = index;
        List<SongInfo> queueSnapshot = new ArrayList<>(queue);
        playQueue.clear();
        playQueue.addAll(queueSnapshot);
        lyricSongId = -1;

        NeteaseApi.getLyrics(song.id).thenAccept(lines -> {
            List<LyricLine> safeLines = lines == null ? List.of() : lines;
            lyricsCache.put(song.id, safeLines);
            lyricsRequested.put(song.id, true);
            try {
                LyricsModule module = NiloreClient.getInstance().getModuleManager().getModule(LyricsModule.class);
                if (module != null) {
                    module.setLyrics(song.id, safeLines);
                }
            } catch (Exception ignored) { }
        });
        if (MusicPlayer.AUDIO_PLAYER.isPreloadedFor(song)) {
            String preloadedUrl = MusicPlayer.AUDIO_PLAYER.getPreloadedUrl(song);
            if (preloadedUrl != null) {
                startPlayback(song, preloadedUrl, request, autoAdvance, true);
                return;
            }
        }
        NeteaseApi.getSongUrl(song.id).thenAccept(result -> {
            if (result == null || playRequestSeq.get() != request) {
                return;
            }
            String url = result.url();
            if (url == null || url.isBlank()) {
                System.err.println("[MusicPlayer] No playable URL for " + song.name);
                return;
            }
            // 优先用播放地址接口顺带返回的准确时长（网易的 time 字段）。
            // 搜索接口经常不给时长，之前只能拿文件大小去估，进度条和倒计时会一直是错的；
            // 而网易官方 source 那边 size 恒为 0，连估都估不出来。
            if (result.durationMs() > 0) {
                song.duration = result.durationMs();
            } else if (song.duration <= 0 && result.size() > 0) {
                song.duration = result.size() * 1000L / 40000;
            }
            startPlayback(song, url, request, autoAdvance);
        });
    }

    private void startPlayback(SongInfo song, String url, long request, boolean autoAdvance) {
        startPlayback(song, url, request, autoAdvance, false);
    }

    private void startPlayback(SongInfo song, String url, long request, boolean autoAdvance, boolean usePreload) {
        if (playRequestSeq.get() == request) {
            MusicPlayer.AUDIO_PLAYER.play(song, url, autoAdvance ? () -> playNextFromPlaylist(request) : null, usePreload);
        }
    }

    private void playNextFromPlaylist(long request) {
        if (playRequestSeq.get() != request || !playlistAutoAdvance || playQueue.isEmpty()) {
            return;
        }
        int next = (queueIndex + 1) % playQueue.size();
        playSong(playQueue.get(next), playQueue, next, true);
    }

    private void onCrossfadeTrack() {
        SongInfo song = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        if (song == null) {
            return;
        }
        for (int i = 0; i < playQueue.size(); i++) {
            if (playQueue.get(i).id == song.id) {
                queueIndex = i;
                break;
            }
        }
        lyricSongId = -1;
        if (lyricsCache.containsKey(song.id)) {
            return;
        }
        NeteaseApi.getLyrics(song.id).thenAccept(lines -> {
            List<LyricLine> safeLines = lines == null ? List.of() : lines;
            lyricsCache.put(song.id, safeLines);
            lyricsRequested.put(song.id, true);
            try {
                LyricsModule module = NiloreClient.getInstance().getModuleManager().getModule(LyricsModule.class);
                if (module != null) {
                    module.setLyrics(song.id, safeLines);
                }
            } catch (Exception ignored) { }
        });
    }

    private void requestPreloadForNext() {
        if (!MusicPlayer.AUDIO_PLAYER.isMelodifyEnabled() || !playlistAutoAdvance
                || playQueue.isEmpty() || queueIndex < 0) {
            return;
        }
        int next = (queueIndex + 1) % playQueue.size();
        SongInfo nextSong = playQueue.get(next);
        if (nextSong == null || MusicPlayer.AUDIO_PLAYER.isPreloadedFor(nextSong)) {
            return;
        }
        NeteaseApi.getSongUrl(nextSong.id).thenAccept(result -> {
            if (result == null || result.url() == null || result.url().isBlank()
                    || MusicPlayer.AUDIO_PLAYER.isPreloadedFor(nextSong)) {
                return;
            }
            // 预加载时顺手把时长补上，轮到这首歌时进度条不至于从 0 开始
            if (result.durationMs() > 0 && nextSong.duration <= 0) {
                nextSong.duration = result.durationMs();
            }
            MusicPlayer.AUDIO_PLAYER.preloadNext(nextSong, result.url());
        });
    }

    private void nextSong() {
        if (playQueue.isEmpty() || queueIndex < 0) {
            return;
        }
        int next = (queueIndex + 1) % playQueue.size();
        playSong(playQueue.get(next), playQueue, next, playlistAutoAdvance);
    }

    private void prevSong() {
        if (playQueue.isEmpty() || queueIndex < 0) {
            return;
        }
        int previous = (queueIndex - 1 + playQueue.size()) % playQueue.size();
        playSong(playQueue.get(previous), playQueue, previous, playlistAutoAdvance);
    }

    private void updateProgress(double mouseX) {
        if (lastProgressRect == null) {
            return;
        }
        SongInfo song = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        if (song == null || song.duration <= 0) {
            return;
        }
        pendingProgress = clamp((float) ((mouseX - lastProgressRect.getX()) / lastProgressRect.getWidth()), 0, 1);
    }

    private void commitProgress(float progress) {
        SongInfo song = MusicPlayer.AUDIO_PLAYER.getCurrentSong();
        if (song != null && song.duration > 0) {
            MusicPlayer.AUDIO_PLAYER.seekToMs((long) (clamp(progress, 0, 1) * song.duration));
        }
    }

    private void updateVolume(double mouseX) {
        if (lastVolumeRect == null) {
            return;
        }
        float volume = clamp((float) ((mouseX - lastVolumeRect.getX()) / lastVolumeRect.getWidth()), 0, 1);
        pendingVolume = volume;
        MusicPlayer.AUDIO_PLAYER.setVolume(volume);
        MusicPlayer module = NiloreClient.getInstance().getModuleManager().getModule(MusicPlayer.class);
        if (module != null) {
            module.setVolumeSetting(volume);
        }
    }

    private void ensureAlbum(SongInfo song) {
        if (song == null) {
            return;
        }
        if (albumBytes != null) {
            try {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(albumBytes));
                // 取色和压色雾都必须排在 DynamicTexture 前面：它上传后会接管并关闭这张图
                setTheme(MonetPalette.fromImage(image));
                NativeImage palette = CoverBackdrop.prepareFluidPalette(image);
                DynamicTexture texture = new DynamicTexture(image);
                albumTexture = new Texture(texture.getId(), image.getWidth(), image.getHeight());
                // 背景交叉淡化：旧色雾 → 新色雾
                Texture fluid = CoverBackdrop.uploadPalette(palette);
                if (fluid != null) {
                    fluidPrev = fluidTexture;
                    fluidTexture = fluid;
                    backdropFade = fluidPrev == null ? 1.0f : 0.0f;
                }
            } catch (Exception e) {
                System.err.println("[MusicPlayerScreen] Album art failed: " + e.getMessage());
            }
            albumBytes = null;
        }
        if (albumLoading || (song.id == albumSongId && albumTexture != null)
                || (song.id == albumSongId && albumRetryCount >= 2)) {
            return;
        }
        if (song.id != albumSongId) {
            albumRetryCount = 0;
        }
        albumSongId = song.id;
        albumTexture = null;
        albumBytes = null;
        albumLoading = true;
        albumRetryCount++;
        NeteaseApi.getAlbumPicUrl(song).thenAccept(url -> {
            if (url == null || url.isEmpty()) {
                albumLoading = false;
                return;
            }
            try {
                byte[] bytes = MusicHttp.getBytes(URI.create(url));
                if (bytes != null && bytes.length > 100) {
                    albumBytes = bytes;
                } else {
                    albumSongId = -1;
                }
            } catch (Exception e) {
                albumSongId = -1;
            } finally {
                albumLoading = false;
            }
        }).exceptionally(error -> {
            albumLoading = false;
            albumSongId = -1;
            return null;
        });
    }

    private List<LyricLine> lyricsFor(SongInfo song) {
        List<LyricLine> cached = lyricsCache.get(song.id);
        if (cached != null) {
            return cached;
        }
        if (lyricsRequested.putIfAbsent(song.id, true) == null) {
            NeteaseApi.getLyrics(song.id).thenAccept(lines ->
                    lyricsCache.put(song.id, lines == null ? List.of() : lines));
        }
        return List.of();
    }

    private int findCurrentLyricLine(List<LyricLine> lines, long position) {
        int current = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs() > position) {
                break;
            }
            current = i;
        }
        return current;
    }

    private float toDesignX(double screenX) {
        return (float) ((screenX - layoutOriginX) / layoutScale);
    }

    private float toDesignY(double screenY) {
        return (float) ((screenY - layoutOriginY) / layoutScale);
    }

    private static String greeting() {
        int hour = LocalTime.now().getHour();
        return hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
    }

    private static void drawCentered(String text, float x, float y, float width, FontRenderer font, int color) {
        GlHelper.drawText(text, x + (width - GlHelper.getStringWidth(text, font)) * 0.5f, y, font, color);
    }

    private static int withAlpha(int color, float alpha) {
        int sourceAlpha = color >>> 24 & 255;
        return ColorUtil.fromARGB(color >>> 16 & 255, color >>> 8 & 255, color & 255,
                (int) (sourceAlpha * clamp(alpha, 0, 1)));
    }

    private static float measure(String text, FontRenderer font) {
        return GlHelper.getStringWidth(text == null ? "" : text, font);
    }

    private static String ellipsize(String value, FontRenderer font, float maxWidth) {
        if (value == null || maxWidth <= 0) {
            return "";
        }
        if (measure(value, font) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int end = value.length();
        while (end > 0 && measure(value.substring(0, end) + suffix, font) > maxWidth) {
            end--;
        }
        return value.substring(0, end) + suffix;
    }

    private static String timestamp(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean contains(double mouseX, double mouseY, float x, float y, float w, float h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record LayoutTransform(float originX, float originY, float scale) { }

    private record Bounds(float x, float y, float width, float height) {
        private boolean contains(double mouseX, double mouseY) {
            return MusicPlayerScreen.contains(mouseX, mouseY, x, y, width, height);
        }
    }

    private enum Page { HOME, PLAYER, SEARCH, QUEUE, PLAYLIST, ABOUT }

    private enum DragTarget { NONE, PROGRESS, VOLUME }

    private record ClickArea(float x, float y, float width, float height, Runnable action) {
        private boolean contains(double mouseX, double mouseY) {
            return MusicPlayerScreen.contains(mouseX, mouseY, x, y, width, height);
        }
    }
}
