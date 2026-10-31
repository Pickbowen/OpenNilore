package client.nilore.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.chars.Char2IntArrayMap;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.io.Closeable;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Setter;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import client.nilore.ClientBase;
import client.nilore.utils.math.MathUtil;

public class CustomFont
implements Closeable {

    public record GlyphEntry(float atX, float atY, float r, float g, float b, Glyph toDraw) {
    }

    static class MinecraftColorMap extends Char2IntArrayMap {
        MinecraftColorMap() {
            this.put('0', 0);
            this.put('1', 170);
            this.put('2', 43520);
            this.put('3', 43690);
            this.put('4', 0xAA0000);
            this.put('5', 0xAA00AA);
            this.put('6', 0xFFAA00);
            this.put('7', 0xAAAAAA);
            this.put('8', 0x555555);
            this.put('9', 0x5555FF);
            this.put('A', 0x55FF55);
            this.put('B', 0x55FFFF);
            this.put('C', 0xFF5555);
            this.put('D', 0xFF55FF);
            this.put('E', 0xFFFF55);
            this.put('F', 0xFFFFFF);
        }
    }

    private static final Char2IntArrayMap MC_COLOR_CODES = new CustomFont.MinecraftColorMap();
    /**
     * 字形预热用的后台线程。
     *
     * <p>之前是 {@code newCachedThreadPool()}：每提交一个预热任务就可能新起一条非守护线程，
     * 60 秒保活，注入后头几分钟正好是任务最密集的时候 —— 线程数、上下文切换、和渲染线程
     * 抢 CPU 一起叠在"冷启动"这个窗口里。单条守护线程按顺序跑完就够了（预热本来只关心
     * 最终有没有做完，不关心同时做几个），顺便保证 JVM 能正常退出。
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Nilore-Glyph-Warm");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    /** 已经预热过的 (字体名, 字号, 是否 hint) 组合。同一条 strike 只灌一次。 */
    private static final Set<String> WARMED_STRIKES = ConcurrentHashMap.newKeySet();

    /** 预热探针：可打印 ASCII。这些是模块名、数值、计分板数字的绝大部分。 */
    private static final String ASCII_PROBE =
            " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`"
                    + "abcdefghijklmnopqrstuvwxyz{|}~";

    /**
     * 预热探针：常用汉字 + 中文标点。
     *
     * <p>顺序按实际出现频率排（聊天/计分板/服务器名/血量数值），不是随机取样 ——
     * 后台线程可能只跑了一部分玩家就已经进服，先做热的字收益最大。
     */
    private static final String CJK_PROBE =
            "的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着下自之年过发后作里用道行所然家种事成方多经么去法学如都同现当没动面起看定天分还进好小部其些主样理心她本前开但因只从想实日军者意无力它与长把机十民第公此已工使情明性知全三又关点正业外将两高间由问很最重并物手应战向头文体政美相见被利什二等产或新己制身果加西斯月话合回特代内信表化老给世位次度门任常先海通教儿原东声提立及比员解水名真论处走义各入几口认条平系气题活尔更别打女变四神总何电数安少报才结反受目太量再感建务做接必场件计管期市直德资命山金指克许统区保至队形社便空决治展马科司五基眼书非则听白却界达光放强即像难且权思王象完设式色路记南品住告类求据程北边死张该交规万取拉格望觉术领共确传师观清今切院让识候带导争运笑飞风步改收根干造言联持组每济车亲极林服快办议往元英士证近失转夫令准布始怎呢存未远叫台单影具罗字爱击流备兵连调深商算质团集百需价花党华城石级整府离况亚请技际约示复病息究线似官火断精满支视消越器容照须九增研写称企八功吗包片史委乎查轻易早曾除农找装广显吧阿李标谈吃图念六引历首医局突专费号尽另周较注语仅考落青随选列武红响虽推势参希古众构房半节土投某案黑维革划敌致陈律足态护七兴派孩验责营星够章音跟志底站严巴例防族供效续施留讲型料终答紧黄绝奇察母京段依批群项故按河米围江织害斗双境客纪采举杀攻父苏密低朝友诉止细愿千值仍男钱破网热助倒育属坐帝限船脸职速刻乐否刚威毛状率甚独球般普怕弹校苦创假久错承印晚兰试股拿脑预谁益阳若哪微尼继送急血惊伤素药适波夜省初喜卫源食险待述陆习置居劳财环排福纳欢雷警获模充负云停木游龙树疑层冷洲冲射略范竟句室异激汉村哈策演简卡罪判担州静退既衣您宗积余痛检差富灵协角占配征修皮挥胜降阶审沉坚善妈刘读啊超免压银买皇养伊怀执副乱抗犯追帮宣佛岁航优怪香著田铁控税左右份穿艺背阵草脚概恶块顿敢守酒岛托央户烈洋哥索胡款靠评版宝座释景顾弟登货互付伯慢欧换闻危忙核暗姐介坏讨丽良序升监临亮露永呼味野架域沙掉括舰鱼杂误湾吉减编楚肯测败屋跑梦散温困剑渐封救贵枪缺楼县尚毫移娘朋画班智亦耳恩短掌恐遗固席松秘谢鲁遇康虑幸均销钟诗藏赶剧票损忽巨炮旧端探湖录叶春乡附吸予礼港雨呀板庭妇归睛饭额含顺输摇招婚脱补谓督毒油疗旅泽材灭逐莫笔亡鲜词圣择寻厂睡博勒烟授诺伦岸奥唐卖俄炸载洛健堂旁宫喝借君禁阴园谋宋避抓荣姑孙逃牙束跳顶玉镇雪午练迫爷篇肉嘴馆遍凡础洞卷坦牛宁纸诸训私庄祖丝翻暴森塔默握戏隐熟骨访弱蒙歌店鬼软典欲萨伙遭盘爸扩盖弄雄稳忘亿刺拥徒姆杨齐赛趣曲刀床迎冰虚玩析窗醒妻透购替塞努休虎扬途侵刑绿兄迅套贸毕唯谷轮库迹尤竞街促延震弃甲伟麻川申缓潜闪售灯针哲络抵朱埃抱鼓植纯夏忍页杰筑折郑贝尊吴秀混臣雅振染盛怒舞圆搞狂措姓残秋培迷诚宽宇猛摆梅毁伸摩盟末乃悲拍丁赵硬麦蒋操耶阻订彩抽赞魔纷沿喊违妹浪汇币丰蓝殊献桌啦瓦莱援译夺汽烧距裁偏符勇触课敬哭懂墙袭召罚侠厅拜巧侧韩冒债曼融惯享戴童犹乘挂奖绍厚纵障讯涉彻刊丈爆乌役描洗玛患妙镜唱烦签仙彼弗症仿倾牌陷鸟轰咱菜闭奋庆撤泪茶疾缘播朗杜奶季丹狗尾仪偷奔珠虫驻孔宜艾桥淡翼恨繁寒伴叹旦愈潮粮缩罢聚径恰挑袋灰捕徐珍幕映裂泰隔启尖忠累炎暂估泛荒偿横拒瑞忆孤鼻闹羊呆厉衡胞零穷舍码赫婆魂灾洪腿胆津俗辩胸晓劲贫仁偶辑邦恢赖圈摸仰润堆碰艇稍迟辆废净凶署壁御奉旋冬矿抬蛋晨伏吹鸡倍糊秦盾杯租骑乏隆诊奴摄丧污渡旗甘耐凭扎抢绪粗肩梁幻菲皆碎宙叔岩荡综爬荷悉蒂返井壮薄悄扫敏碍殖详迪矛霍允幅撒剩凯颗骂赏液番箱贴漫酸郎腰舒眉忧浮辛恋餐吓挺励辞艘键伍峰尺昨黎辈贯侦滑券崇扰宪绕趋慈乔阅汗枝拖墨胁插箭腊粉泥氏彭拔骗凤慧媒佩愤扑龄驱惜豪掩兼跃尸肃帕驶堡届欣惠册储飘桑闲惨洁踪勃宾频仇磨递邪撞拟滚奏巡颜剂绩贡疯坡瞧截燃焦殿伪柳锁逼颇昏劝呈搜勤戒驾漂饮曹朵仔柔俩孟腐幼践籍牧凉牲佳娜浓芳稿竹腹跌逻垂遵脉貌柏狱猜怜惑陶兽帐饰贷昌叙躺钢沟寄扶铺寿惧询汤盗肥尝匆辉奈扣廷澳嘛董迁凝慰厌脏腾幽枣夸赞琳盏唤赢肾罐";

    private final Object2ObjectMap<ResourceLocation, ObjectList<CustomFont.GlyphEntry>> glyphPageMap = new Object2ObjectOpenHashMap();
    private final float fontSize;
    /** 是否用合成粗体。字体文件没有 Bold 变体时（中文基本都没有）靠它把笔画撑粗。 */
    private final boolean bold;
    /** 合成粗体在纹理里的水平偏移量。随 guiScale 变化，所以每次 initFont 重算。 */
    private int boldPx;
    /** 主字体画不出来的字符交给它们兜底（西文字体 + 中文字体混排就靠这个）。 */
    private final Font[] fallbackFonts;
    /** 按当前 guiScale 派生过的兜底字体，字形光栅化用的是这一组。 */
    private Font[] scaledFallbacks = new Font[0];
    private final ObjectList<GlyphPage> glyphPages = new ObjectArrayList();
    private final Char2ObjectArrayMap<Glyph> glyphCache = new Char2ObjectArrayMap();
    private final int pageSize;
    private final int charsPerPage;
    private final String preloadChars;
    private float scale = 1.0f;
    private final float rasterScaleHint;
    private final boolean hinted;
    private Font scaledFont;
    private int guiScaleCache = -1;
    private Future<Void> preloadFuture;
    private boolean initialized;
    private static final Color SHADOW_COLOR = new Color(26, 26, 26, 160);
    @Setter
    private float letterSpacing = 0.0f;
    private FontMetricsImpl fontMetrics;

    public CustomFont(Font font, float fontSize, int pageSize, int charsPerPage, @Nullable String preloadChars) {
        this(font, fontSize, pageSize, charsPerPage, preloadChars, false, null);
    }

    public CustomFont(Font font, float fontSize, int pageSize, int charsPerPage, @Nullable String preloadChars, boolean bold) {
        this(font, fontSize, pageSize, charsPerPage, preloadChars, bold, null);
    }

    public CustomFont(Font font, float fontSize, int pageSize, int charsPerPage, @Nullable String preloadChars,
                      boolean bold, @Nullable Font[] fallbacks) {
        this(font, fontSize, pageSize, charsPerPage, preloadChars, bold, fallbacks, 0f, true);
    }

    public CustomFont(Font font, float fontSize, int pageSize, int charsPerPage, @Nullable String preloadChars,
                      boolean bold, @Nullable Font[] fallbacks, float rasterScale, boolean hinted) {
        this.bold = bold;
        this.fallbackFonts = fallbacks == null ? new Font[0] : fallbacks;
        this.fontSize = fontSize;
        this.pageSize = pageSize;
        this.charsPerPage = charsPerPage;
        this.preloadChars = preloadChars;
        this.rasterScaleHint = Math.max(0f, rasterScale);
        this.hinted = hinted;
        this.fontMetrics = new FontMetricsImpl(font);
        this.initFont(font, fontSize);
    }

    /**
     * Size of a glyph atlas block, in code points.
     *
     * <p>Glyphs are rasterized strictly on demand: the block layout is computed up
     * front from cheap bounds measuring, but a glyph only becomes a texture region the
     * first time it is actually drawn. A Chinese lyric line therefore costs one glyph
     * per unseen character instead of a whole block.
     */
    private static final int DEFAULT_PAGE_SIZE = 64;

    public CustomFont(Font font, float fontSize) {
        this(font, fontSize, DEFAULT_PAGE_SIZE, 5, null, false, null);
    }

    public CustomFont(Font font, float fontSize, boolean bold) {
        this(font, fontSize, DEFAULT_PAGE_SIZE, 5, null, bold, null);
    }

    public CustomFont(Font font, float fontSize, boolean bold, @Nullable Font[] fallbacks) {
        this(font, fontSize, DEFAULT_PAGE_SIZE, 5, null, bold, fallbacks);
    }

    public CustomFont(Font font, float fontSize, boolean bold, @Nullable Font[] fallbacks,
                      float rasterScale, boolean hinted) {
        this(font, fontSize, DEFAULT_PAGE_SIZE, 5, null, bold, fallbacks, rasterScale, hinted);
    }

    private static int alignToPageBoundary(int value, int pageSize) {
        return pageSize * (int)Math.floor((double)value / (double)pageSize);
    }

    public static String stripFormatting(String text) {
        char[] chars = text.toCharArray();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < chars.length; ++i) {
            char c = chars[i];
            if (c == '§') {
                ++i;
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    private void checkGuiScaleChanged() {
        if (this.rasterScaleHint > 0f) {
            return;
        }
        int guiScale = (int)ClientBase.mc.getWindow().getGuiScale();
        if (guiScale != this.guiScaleCache) {
            this.close();
            this.initFont(this.scaledFont, this.fontSize);
        }
    }

    private void initFont(Font font, float fontSize) {
        if (this.initialized) {
            throw new IllegalStateException("Double call to init()");
        }
        this.initialized = true;
        this.guiScaleCache = (int)ClientBase.mc.getWindow().getGuiScale();
        this.scale = this.rasterScaleHint > 0f
                ? Math.max(0.25f, this.rasterScaleHint)
                : Math.max(0.25f, (float) this.guiScaleCache);
        // 屏幕上想粗大约 0.5px，但字形是按 scale 倍光栅化进纹理的，
        // 所以偏移量要按同一个倍率折算成纹理像素。
        this.boldPx = this.bold ? Math.max(1, Math.round(this.scale * 0.5f)) : 0;
        // 兜底字体也要按同一个倍率派生，否则中文和西文的笔画粗细、大小会对不上
        this.scaledFallbacks = new Font[this.fallbackFonts.length];
        for (int i = 0; i < this.fallbackFonts.length; i++) {
            this.scaledFallbacks[i] = this.fallbackFonts[i].deriveFont(fontSize * (float) this.scale);
        }
        this.scaledFont = font.deriveFont(fontSize * (float)this.scale);
        this.fontMetrics = new FontMetricsImpl(this.scaledFont);
        if (this.preloadChars != null && !this.preloadChars.isEmpty()) {
            this.preloadFuture = this.startPreload();
        }
        this.warmJava2dGlyphs();
    }

    /**
     * 在后台把这一档字号的 Java2D 字形数据先做出来。
     *
     * <p>这是"注入后一直掉帧、好几分钟之后才顺"的真正原因。字体的 TTF 解析（苹方 11 MB）
     * 在 {@code Font.createFont} 时只做了一半 —— 真正的字形轮廓、度量、rasterizer 是
     * Java2D 在**第一次遇到某个字符**时才建的，而那个"第一次"发生在渲染线程上：
     * 聊天、计分板、玩家列表每出现一个新的汉字，就在那一帧的 drawString 里同步做一次
     * Java2D 字形生成。帧时间于是随"这一帧碰巧出现了几个新字"抖动几毫秒到几十毫秒，
     * 直到常用字集合基本覆盖完（正常的对局节奏就是几分钟）才停下来。
     *
     * <p>这里按**实际要用的 strike**（{@code scaledFont} 与兜底字体精确到字号）在后台
     * 一次性把 ASCII 与常用汉字过一遍，把这份成本挪出渲染线程。渲染线程随后遇到的字符
     * 命中已建好的 glyph 数据，只剩下往图集里 drawString + 记脏矩形。
     */
    private void warmJava2dGlyphs() {
        Font strike = this.scaledFont;
        if (strike == null) {
            return;
        }
        // strike 的键：同一个字体同一档字号只预热一次。guiScale 变化会 derive 出新的 strike，
        // 自然拿到新键重新预热。
        String key = strike.getFontName() + '@' + strike.getSize2D() + (this.hinted ? "" : "-raw");
        if (!WARMED_STRIKES.add(key)) {
            return;
        }
        Font[] fallbacks = this.scaledFallbacks;
        EXECUTOR.execute(() -> {
            try {
                FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, false);
                warmStrike(strike, frc);
                for (Font fallback : fallbacks) {
                    // 正文字体画不出来的字符（中文）走的就是兜底，那才是真正贵的一份，
                    // 而且它恰好是渲染线程第一次画中文时才会被碰到的。
                    if (fallback != null && fallback.canDisplay('\u4e00')) {
                        warmStrike(fallback, frc);
                    }
                }
            } catch (Throwable ignored) {
                // 预热失败不影响正确性，只是回到交给渲染线程按需生成的老路。
            }
        });
    }

    private static void warmStrike(Font font, FontRenderContext frc) {
        if (font == null) {
            return;
        }
        font.getStringBounds(ASCII_PROBE, frc);
        font.getStringBounds(CJK_PROBE, frc);
    }

    private Future<Void> startPreload() {
        return EXECUTOR.submit(() -> {
            for (char c : this.preloadChars.toCharArray()) {
                if (Thread.interrupted()) break;
                this.getOrLoadGlyph(c);
            }
            return null;
        });
    }

    private GlyphPage createGlyphPage(char startChar, char endChar) {
        GlyphPage glyphPage = new GlyphPage(startChar, endChar, this.scaledFont, CustomFont.getTempResourceLocation(),
                this.charsPerPage, this.boldPx, this.scaledFallbacks, this.hinted);
        this.glyphPages.add(glyphPage);
        return glyphPage;
    }

    private Glyph loadGlyph(char c) {
        for (GlyphPage existing : this.glyphPages) {
            if (!existing.contains(c)) continue;
            return existing.getGlyph(c);
        }
        int pageStart = CustomFont.alignToPageBoundary(c, this.pageSize);
        GlyphPage page = this.createGlyphPage((char)pageStart, (char)(pageStart + this.pageSize));
        return page.getGlyph(c);
    }

    @Nullable
    private Glyph getOrLoadGlyph(char c) {
        return this.glyphCache.computeIfAbsent(c, this::loadGlyph);
    }

    public void drawString(PoseStack poseStack, String text, double x, double y, int color) {
        float r = (float)(color >> 16 & 0xFF) / 255.0f;
        float g = (float)(color >> 8 & 0xFF) / 255.0f;
        float b = (float)(color & 0xFF) / 255.0f;
        float a = (float)(color >> 24 & 0xFF) / 255.0f;
        this.drawStringRGB(poseStack, text, (float)x, (float)y, r, g, b, a);
    }

    public void drawStringShadow(PoseStack poseStack, String text, double x, double y, int color) {
        float r = (float)(color >> 16 & 0xFF) / 255.0f;
        float g = (float)(color >> 8 & 0xFF) / 255.0f;
        float b = (float)(color & 0xFF) / 255.0f;
        float a = (float)(color >> 24 & 0xFF) / 255.0f;
        this.drawStringRGB(poseStack, text, (float)x, (float)y, r, g, b, a);
    }

    public void drawStringWithShadow(PoseStack poseStack, String text, double x, double y, int color) {
        double offset = this.hinted ? 1.0 / (double)this.scale : 0.5;
        this.drawStringColor(poseStack, text, (double)((float)x) + offset, (double)((float)y) + offset, SHADOW_COLOR);
        this.drawString(poseStack, text, (float)x, (float)y, color);
    }

    public void drawStringColor(PoseStack poseStack, String text, double x, double y, Color color) {
        this.drawStringRGB(poseStack, text, (float)x, (float)y, (float)color.getRed() / 255.0f, (float)color.getGreen() / 255.0f, (float)color.getBlue() / 255.0f, color.getAlpha());
    }

    public void drawStringRGB(PoseStack poseStack, String text, float x, float y, float r, float g, float b, float a) {
        this.drawStringRGBFull(poseStack, text, x, y, r, g, b, a, false, 0);
    }
    public void drawStringRGBFull(PoseStack poseStack, String text, float x, float y, float baseR, float baseG, float baseB, float alpha, boolean rainbow, int rainbowOffset) {
        if (this.preloadFuture != null && !this.preloadFuture.isDone()) {
            try {
                this.preloadFuture.get();
            } catch (ExecutionException | InterruptedException ex) {
            }
        }

        this.checkGuiScaleChanged();
        float curR = baseR;
        float curG = baseG;
        float curB = baseB;
        poseStack.pushPose();
        poseStack.translate(this.snap(x), this.snap(--y), 0.0);
        poseStack.scale(1.0F / this.scale, 1.0F / this.scale, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f pose = poseStack.last().pose();
        char[] chars = text.toCharArray();
        float penX = 0.0F;
        float penY = 0.0F;
        boolean inFormatting = false;
        int lineStart = 0;
        synchronized (this.glyphPageMap) {
            for (int i = 0; i < chars.length; i++) {
                char ch = chars[i];
                if (inFormatting) {
                    inFormatting = false;
                    char upper = Character.toUpperCase(ch);
                    if (MC_COLOR_CODES.containsKey(upper)) {
                        int packed = MC_COLOR_CODES.get(upper);
                        int[] rgb = colorToRGB(packed);
                        curR = rgb[0] / 255.0F;
                        curG = rgb[1] / 255.0F;
                        curB = rgb[2] / 255.0F;
                    } else if (upper == 'R') {
                        curR = baseR;
                        curG = baseG;
                        curB = baseB;
                    }
                } else if (ch == 167) {
                    inFormatting = true;
                } else if (ch == '\n') {
                    penY += this.getStringHeight(text.substring(lineStart, i)) * this.scale;
                    penX = 0.0F;
                    lineStart = i + 1;
                } else {
                    Glyph glyph = this.getOrLoadGlyph(ch);
                    if (glyph != null) {
                        if (glyph.value() != ' ') {
                            ResourceLocation textureLocation = glyph.owner().textureLocation;
                            GlyphEntry entry = new GlyphEntry(penX, penY, curR, curG, curB, glyph);
                            this.glyphPageMap.computeIfAbsent(textureLocation, key -> new ObjectArrayList<>()).add(entry);
                        }

                        penX += glyph.width() + this.letterSpacing;
                    }
                }
            }

            for (ResourceLocation textureLocation : this.glyphPageMap.keySet()) {
                RenderSystem.setShaderTexture(0, textureLocation);
                List<GlyphEntry> entries = this.glyphPageMap.get(textureLocation);
                if (!entries.isEmpty()) {
                    // 这一步必须在画 quad 之前：它把本页这一帧新光栅化的所有字形一次性传上 GPU。
                    // 之前是每个字形自己在 getOrLoadGlyph 里 bind + upload，一屏新中文就是
                    // 每帧几十次 GL 往返，字形缓存热起来之前整段时间都在掉帧。
                    entries.get(0).toDraw.owner().flush();
                }
                Tesselator tesselator = Tesselator.getInstance();
                BufferBuilder bufferBuilder = tesselator.getBuilder();
                bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

                for (GlyphEntry entry : entries) {
                    float atX = entry.atX;
                    float atY = entry.atY;
                    float r = entry.r;
                    float g = entry.g;
                    float b = entry.b;
                    Glyph glyph = entry.toDraw;
                    GlyphPage page = glyph.owner();
                    float glyphWidth = glyph.width();
                    float glyphHeight = glyph.height();
                    float u1 = (float) glyph.u() / page.imageWidth;
                    float v1 = (float) glyph.v() / page.imageHeight;
                    float u2 = (float) (glyph.u() + glyph.width()) / page.imageWidth;
                    float v2 = (float) (glyph.v() + glyph.height()) / page.imageHeight;
                    bufferBuilder.vertex(pose, atX + 0.0F, atY + glyphHeight, 0.0F).uv(u1, v2).color(r, g, b, alpha).endVertex();
                    bufferBuilder.vertex(pose, atX + glyphWidth, atY + glyphHeight, 0.0F).uv(u2, v2).color(r, g, b, alpha).endVertex();
                    bufferBuilder.vertex(pose, atX + glyphWidth, atY + 0.0F, 0.0F).uv(u2, v1).color(r, g, b, alpha).endVertex();
                    bufferBuilder.vertex(pose, atX + 0.0F, atY + 0.0F, 0.0F).uv(u1, v1).color(r, g, b, alpha).endVertex();
                }

                tesselator.end();
            }

            this.glyphPageMap.clear();
        }

        poseStack.popPose();
        RenderSystem.disableBlend();
    }

    public void drawStringGradient(PoseStack poseStack, String text, float x, float y, int topColor, int bottomColor) {
        if (this.preloadFuture != null && !this.preloadFuture.isDone()) {
            try {
                this.preloadFuture.get();
            } catch (ExecutionException | InterruptedException ignored) {
            }
        }
        this.checkGuiScaleChanged();
        float topR = (topColor >> 16 & 0xFF) / 255.0f;
        float topG = (topColor >> 8 & 0xFF) / 255.0f;
        float topB = (topColor & 0xFF) / 255.0f;
        float topA = (topColor >>> 24 & 0xFF) / 255.0f;
        float bottomR = (bottomColor >> 16 & 0xFF) / 255.0f;
        float bottomG = (bottomColor >> 8 & 0xFF) / 255.0f;
        float bottomB = (bottomColor & 0xFF) / 255.0f;
        float bottomA = (bottomColor >>> 24 & 0xFF) / 255.0f;
        poseStack.pushPose();
        poseStack.translate(this.snap(x), this.snap(--y), 0.0);
        poseStack.scale(1.0f / this.scale, 1.0f / this.scale, 1.0f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f pose = poseStack.last().pose();
        float penX = 0.0f;
        synchronized (this.glyphPageMap) {
            for (char ch : text.toCharArray()) {
                Glyph glyph = this.getOrLoadGlyph(ch);
                if (glyph == null) continue;
                if (glyph.value() != ' ') {
                    this.glyphPageMap.computeIfAbsent(glyph.owner().textureLocation, key -> new ObjectArrayList<>())
                            .add(new GlyphEntry(penX, 0.0f, 0.0f, 0.0f, 0.0f, glyph));
                }
                penX += glyph.width() + this.letterSpacing;
            }
            for (ResourceLocation textureLocation : this.glyphPageMap.keySet()) {
                RenderSystem.setShaderTexture(0, textureLocation);
                List<GlyphEntry> pageEntries = this.glyphPageMap.get(textureLocation);
                if (!pageEntries.isEmpty()) {
                    pageEntries.get(0).toDraw.owner().flush();
                }
                BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
                bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                for (GlyphEntry entry : this.glyphPageMap.get(textureLocation)) {
                    Glyph glyph = entry.toDraw;
                    GlyphPage page = glyph.owner();
                    float u1 = (float) glyph.u() / page.imageWidth;
                    float v1 = (float) glyph.v() / page.imageHeight;
                    float u2 = (float) (glyph.u() + glyph.width()) / page.imageWidth;
                    float v2 = (float) (glyph.v() + glyph.height()) / page.imageHeight;
                    float right = entry.atX + glyph.width();
                    float bottom = entry.atY + glyph.height();
                    bufferBuilder.vertex(pose, entry.atX, bottom, 0.0f).uv(u1, v2).color(bottomR, bottomG, bottomB, bottomA).endVertex();
                    bufferBuilder.vertex(pose, right, bottom, 0.0f).uv(u2, v2).color(bottomR, bottomG, bottomB, bottomA).endVertex();
                    bufferBuilder.vertex(pose, right, entry.atY, 0.0f).uv(u2, v1).color(topR, topG, topB, topA).endVertex();
                    bufferBuilder.vertex(pose, entry.atX, entry.atY, 0.0f).uv(u1, v1).color(topR, topG, topB, topA).endVertex();
                }
                Tesselator.getInstance().end();
            }
            this.glyphPageMap.clear();
        }
        poseStack.popPose();
        RenderSystem.disableBlend();
    }

    public void drawStringCentered(PoseStack poseStack, String text, double x, double y, int color) {
        float r = (float)(color >> 16 & 0xFF) / 255.0f;
        float g = (float)(color >> 8 & 0xFF) / 255.0f;
        float b = (float)(color & 0xFF) / 255.0f;
        float a = (float)(color >> 24 & 0xFF) / 255.0f;
        this.drawStringRGB(poseStack, text, (float)(x - (double)(this.getStringWidth(text) / 2.0f)), (float)y, r, g, b, a);
    }

    public void drawStringCenteredColor(PoseStack poseStack, String text, double x, double y, Color color) {
        this.drawStringRGB(poseStack, text, (float)(x - (double)(this.getStringWidth(text) / 2.0f)), (float)y, (float)color.getRed() / 255.0f, (float)color.getGreen() / 255.0f, (float)color.getBlue() / 255.0f, (float)color.getAlpha() / 255.0f);
    }

    public void drawStringCenteredRGB(PoseStack poseStack, String text, float x, float y, float r, float g, float b, float a) {
        this.drawStringRGB(poseStack, text, x - this.getStringWidth(text) / 2.0f, y, r, g, b, a);
    }

    public float getStringWidth(String text) {
        char[] chars = CustomFont.stripFormatting(text).toCharArray();
        float lineWidth = 0.0f;
        float maxWidth = 0.0f;
        for (char c : chars) {
            if (c == '\n') {
                maxWidth = Math.max(lineWidth, maxWidth);
                lineWidth = 0.0f;
                continue;
            }
            Glyph glyph = this.getOrLoadGlyph(c);
            lineWidth += (glyph == null ? 0.0f : (float)glyph.width() / (float)this.scale) + this.letterSpacing;
        }
        return Math.max(lineWidth, maxWidth);
    }

    public float getStringHeight(String text) {
        char[] chars = CustomFont.stripFormatting(text).toCharArray();
        if (chars.length == 0) {
            chars = new char[]{' '};
        }
        float lineHeight = 0.0f;
        float totalHeight = 0.0f;
        for (char c : chars) {
            if (c == '\n') {
                if (lineHeight == 0.0f) {
                    lineHeight = this.getOrLoadGlyph(' ') == null ? 0.0f : (float)((Glyph)(Objects.requireNonNull((Object)(this.getOrLoadGlyph(' '))))).height() / (float)this.scale;
                }
                totalHeight += lineHeight;
                lineHeight = 0.0f;
                continue;
            }
            Glyph glyph = this.getOrLoadGlyph(c);
            lineHeight = Math.max(glyph == null ? 0.0f : (float)glyph.height() / (float)this.scale, lineHeight);
        }
        return lineHeight + totalHeight;
    }

    public float getFontHeight() {
        return (float)(this.fontMetrics.getLeading() + this.fontMetrics.getAscent() + this.fontMetrics.getDescent()) / (float)this.scale;
    }

    public FontMetricsImpl getFontMetrics() {
        return this.fontMetrics;
    }

    public float getScale() {
        return this.scale;
    }

    private double snap(double value) {
        return this.hinted ? (double) (Math.round(value * (double) this.scale) / (double) this.scale)
                : MathUtil.round(value, 1);
    }

    public void close() {
        try {
            if (this.preloadFuture != null && !this.preloadFuture.isDone() && !this.preloadFuture.isCancelled()) {
                this.preloadFuture.cancel(true);
                this.preloadFuture.get();
                this.preloadFuture = null;
            }
            for (GlyphPage glyphPage : this.glyphPages) {
                glyphPage.reset();
            }
            this.glyphPages.clear();
            this.glyphCache.clear();
            this.initialized = false;
        } catch (Exception exception) {
            // empty catch block
        }
    }

    @Contract(value="-> new", pure=true)
    @NotNull
    public static ResourceLocation getTempResourceLocation() {
        return ResourceLocation.tryParse("nilore:temp/" + CustomFont.generateRandomName());
    }

    private static String generateRandomName() {
        return IntStream.range(0, 32).mapToObj(i -> String.valueOf((char)new Random().nextInt(97, 123))).collect(Collectors.joining());
    }

    public static int @NotNull [] colorToRGB(int color) {
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        return new int[]{r, g, b};
    }

    public float getStringHeightAlias(String text) {
        return this.getStringHeight(text);
    }

    public void drawStringRainbow(PoseStack poseStack, String text, float x, float y, int offset) {
        this.drawStringRGBFull(poseStack, text, x, y, 255.0f, 255.0f, 255.0f, 255.0f, true, offset);
    }

    public void drawStringCenteredRainbow(PoseStack poseStack, String text, float x, float y, int offset) {
        this.drawStringRainbow(poseStack, text, x - this.getStringWidth(text) / 2.0f, y, offset);
    }

    public void resetLetterSpacing() {
        this.letterSpacing = 0.0f;
    }

    }