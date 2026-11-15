package client.nilore.modules.impl.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import org.apache.commons.lang3.StringUtils;
import client.nilore.NiloreClient;
import client.nilore.event.impl.ChatReceiveEvent;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.settings.impl.StringSetting;
import client.nilore.event.EventTarget;

/**
 * 名字保护: 把自己玩家名在聊天 / Tab 列表 / HUD 里的出现替换掉。
 *
 * <p>替换来源由 Mode 决定: Hidden = 固定字面量; Random = 从在线玩家里抽一个;
 * Custom = 用 {@code .np <名字>} 设的自定义名。加 {@code Colored} 后, 替换出来的
 * 名字在聊天和 Tab 列表里带自定义颜色 —— 走的是组件级重排版, 原本消息里的其它
 * 颜色和格式全部保留 (旧实现直接 {@code getString()} 展平, 整行格式都会丢)。</p>
 */
public class NameProtect
extends Module {
    public static NameProtect INSTANCE;

    private final ModeSetting modeSetting = new ModeSetting("Mode", "Fixed", "Random", "Hidden", "Custom")
            .withDefault("Hidden");

    /** 不在 GUI 里显示, 用 {@code .np <名字>} 命令设置, 配置正常持久化。 */
    private final StringSetting customName = new StringSetting("Custom Name", "Player");

    private final BooleanSetting colored = new BooleanSetting("Colored", false);

    private final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1,
            () -> (Boolean) this.colored.getValue());

    private final NumberSetting green = new NumberSetting("Green", 170, 0, 255, 1,
            () -> (Boolean) this.colored.getValue());

    private final NumberSetting blue = new NumberSetting("Blue", 0, 0, 255, 1,
            () -> (Boolean) this.colored.getValue());

    private String cachedRandomName = null;
    private final Random random = new Random();

    public NameProtect() {
        super("NameProtect", Category.RENDER);
        this.setEnabled(true);
        INSTANCE = this;
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        if (this.modeSetting.is("Random")) {
            this.cachedRandomName = null;
        }
    }

    // ------------------------------------------------------------------
    // 设置读取 (供命令使用)
    // ------------------------------------------------------------------

    public ModeSetting mode() {
        return this.modeSetting;
    }

    public StringSetting customNameSetting() {
        return this.customName;
    }

    public BooleanSetting colorEnabledSetting() {
        return this.colored;
    }

    public NumberSetting colorRedSetting() {
        return this.red;
    }

    public NumberSetting colorGreenSetting() {
        return this.green;
    }

    public NumberSetting colorBlueSetting() {
        return this.blue;
    }

    /**
     * 替换后的名字颜色, 未启用染色时返回 null。聊天组件里可以用任意 RGB,
     * 客户端渲染器都支持; 只有走原版 § 码的地方才会降级。
     */
    public static TextColor activeColor() {
        NameProtect instance = INSTANCE;
        if (instance == null || !instance.isEnabled()) {
            return null;
        }
        if (!(Boolean) instance.colored.getValue()) {
            return null;
        }
        int r = instance.red.getValue().intValue();
        int g = instance.green.getValue().intValue();
        int b = instance.blue.getValue().intValue();
        return TextColor.fromRgb((r << 16) | (g << 8) | b);
    }

    // ------------------------------------------------------------------
    // 替换核心
    // ------------------------------------------------------------------

    private static String ownName() {
        return mc.player == null ? null : mc.player.getName().getString();
    }

    /**
     * 当前应该替换成的名字。Hidden 是字面量, Custom 用自定义名(空则视为未配置,
     * 返回 null 由调用方放行), 其余从在线玩家里抽一个。
     */
    public static String replacementName() {
        NameProtect instance = INSTANCE;
        if (instance == null || !instance.isEnabled() || mc.player == null) {
            return null;
        }
        if (instance.modeSetting.is("Hidden")) {
            return "Hidden";
        }
        if (instance.modeSetting.is("Custom")) {
            String name = instance.customName.getValue();
            return name == null || name.isBlank() ? null : name;
        }
        return instance.generateRandomName();
    }

    public static String replacePlayerName(String string) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) {
            return string;
        }
        if (mc.player == null) {
            return string;
        }
        String string2 = mc.player.getName().getString();
        // Hidden/Custom 不需要任何查找; 原来 Random 分支每次调用都扫一遍在线列表,
        // 而 Tab 列表每个条目每拍都要走这里。
        String string3 = replacementName();
        if (string3 != null && !string3.equals(string2) && string.contains(string2)) {
            return StringUtils.replace(string, string2, string3);
        }
        return string;
    }

    public static String getProtectedName() {
        if (mc.player == null || INSTANCE == null || !INSTANCE.isEnabled()) {
            return mc.player != null ? mc.player.getName().getString() : "Player";
        }
        String string = mc.player.getName().getString();
        String string2 = replacementName();
        if (string2 != null && !string2.equals(string)) {
            return string2;
        }
        return string;
    }

    // ------------------------------------------------------------------
    // 组件级替换 (保留格式 + 染色)
    // ------------------------------------------------------------------

    /**
     * 组件树的就地替换: 找到叶子文本里的玩家名, 拆成 前段 / 替换名 / 后段 三段,
     * 前后段沿用叶子的样式, 替换名叠加自定义颜色。
     *
     * <p>原实现 {@code Component.literal(protected(component.getString()))} 把整棵树
     * 展平成纯文本 —— Tab 列表里的队伍颜色、聊天里的格式会全部丢掉。现在只有
     * 真正命中名字的叶子会被重建, 其余原样 {@code copy()} 保留 (包括可翻译内容)。</p>
     */
    public static Component protectComponent(Component component) {
        String find = ownName();
        String replace = replacementName();
        if (component == null || find == null || find.isEmpty() || replace == null || replace.equals(find)) {
            return component;
        }
        if (!component.getString().contains(find)) {
            return component;
        }
        return rebuild(component, find, replace, activeColor());
    }

    /**
     * 只染色不替换 —— 给已经在别处(比如 JSON 字符串级)替换过的组件补颜色用。
     */
    public static Component colorOccurrences(Component component) {
        TextColor color = activeColor();
        if (component == null || color == null) {
            return component;
        }
        String replace = replacementName();
        if (replace == null || !component.getString().contains(replace)) {
            return component;
        }
        return rebuild(component, replace, replace, color);
    }

    private static MutableComponent rebuild(Component source, String find, String replace, TextColor color) {
        if (source.getSiblings().isEmpty()) {
            return rebuildLeaf(source, find, replace, color);
        }
        // Component.copy() 会把 siblings 一并复制进新的组件, 之后再 append 重建好的子节点,
        // 等于把每个子节点写了两遍 —— 表现就是"含名字的聊天消息整条重复一次"。
        // 先快照再清空: copy() 若与原组件共享同一个 siblings 列表, 直接边遍历边清会丢子节点。
        List<Component> childrenSnapshot = new ArrayList<>(source.getSiblings());
        MutableComponent out = source.copy();
        out.getSiblings().clear();
        for (Component child : childrenSnapshot) {
            out.append(rebuild(child, find, replace, color));
        }
        return out;
    }

    private static MutableComponent rebuildLeaf(Component leaf, String find, String replace, TextColor color) {
        String text = leaf.getString();
        if (!text.contains(find)) {
            return leaf.copy();
        }
        MutableComponent out = Component.literal("").setStyle(leaf.getStyle());
        int from = 0;
        while (true) {
            int at = text.indexOf(find, from);
            if (at < 0) {
                break;
            }
            if (at > from) {
                out.append(plain(text.substring(from, at), leaf));
            }
            MutableComponent replaced = plain(replace, leaf);
            if (color != null) {
                replaced = replaced.withStyle(style -> style.withColor(color));
            }
            out.append(replaced);
            from = at + find.length();
        }
        if (from < text.length()) {
            out.append(plain(text.substring(from), leaf));
        }
        return out;
    }

    private static MutableComponent plain(String text, Component source) {
        return Component.literal(text).setStyle(source.getStyle());
    }

    private String generateRandomName() {
        if (mc.getConnection() == null) {
            return null;
        }
        java.util.ArrayList<PlayerInfo> arrayList = new java.util.ArrayList<>(mc.getConnection().getOnlinePlayers());
        java.util.ArrayList<String> arrayList2 = new java.util.ArrayList<>();
        String string = mc.player.getName().getString();
        for (PlayerInfo playerInfo : arrayList) {
            String string2 = playerInfo.getProfile().getName();
            if (string2.equals(string)) continue;
            arrayList2.add(string2);
        }
        if (arrayList2.isEmpty()) {
            return null;
        }
        if (this.cachedRandomName == null || !arrayList2.contains(this.cachedRandomName)) {
            this.cachedRandomName = arrayList2.get(this.random.nextInt(arrayList2.size()));
        }
        return this.cachedRandomName;
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /**
     * Tab 列表的名字/头部/尾部都走这里。原来是展平成纯文本再替换, 队伍颜色全丢;
     * 现在是组件级替换, 只动命中的名字, 其余原样保留。
     */
    @EventTarget
    public void onChatReceive(ChatReceiveEvent chatReceiveEvent) {
        Component component = chatReceiveEvent.getComponent();
        if (component == null) {
            return;
        }
        chatReceiveEvent.setComponent(protectComponent(component));
    }
}
