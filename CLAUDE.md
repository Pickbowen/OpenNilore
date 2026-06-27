# OpenNilore (OpenZen) - CLAUDE.md

## 项目概述
Minecraft 1.20.1 Forge PvP/工具客户端，使用自定义 ASM patching 框架（非 Mixin），支持 native DLL 注入和 Java Agent。

- **平台**: Forge 47.4.20, Java 17, Mojmap
- **包名**: `shit.nilore` (原 `shit.zen`)
- **构建**: Gradle + ForgeGradle，含自定义混淆任务（重命名所有 `shit.nilore.*` 为随机 16 字符）
- **注入链**: runtime.dll → DllMain → JNI → Agent_OnAttach → ModuleInit → Bootstrap.init() → SRG mapping → patch 注册

## 目录结构

### `src/main/java/shit/nilore/`

| 包 | 用途 |
|---|------|
| `asm/` | Bootstrap 和自定义字节码 patching 框架辅助类 |
| `command/` | 聊天命令 |
| `config/` | 配置持久化 |
| `dll/` | Native DLL 桥接类 (ClientInit, ModuleInit) |
| `event/` | 自定义事件总线，`@EventTarget` 注解，~35 种事件类型 |
| `gui/` | Click GUI (legacy + new)、面板、覆盖层 |
| `hud/` | HUD 元素 |
| `manager/` | 管理器: ModuleManager, CommandManager, ConfigManager, HudManager, LagManager, TargetManager |
| `modules/` | 模块基类和实现 |
| `network/` | 网络工具 |
| `patch/` | 22 个 ASM patch 类（注入到 Minecraft 类） |
| `render/` | 渲染工具 |
| `settings/` | 设置系统 (BooleanSetting, NumberSetting, ModeSetting, MultiSelectSetting) |
| `utils/` | 工具类 (game, math, misc, render, animation 子包) |

### `src/main/java/asm/patchify/`
自定义 Mixin-like patching 框架：
- 注解: `@Patch`, `@Inject`, `@Transform`, `@Overwrite`, `@WrapInvoke`, `@Accessor`, `@MethodAccessor`, `@FieldAccessor`, `@Local`, `@ModifyLocals`
- 加载链: `PatchAgent`(javaagent premain) → `ClassAgent` → `PatchClassFileTransformer` → `PatchTransformer`

### `native/`
C++ native 代码：
- `dll/` - OpenNilore.dll (DLL 注入, JNI 桥接)
- `loader/` - OpenNiloreLoader.exe (注入器, Qt6 GUI)
- 构建: CMake + vcpkg

## 模块系统

### 基类
- `shit.nilore.modules.Module` extends `ClientBase`
- 构造: `super("Name", Category.XXX)`
- 设置自动发现: 反射扫描所有 `Setting<?>` 字段
- 事件: `@EventTarget` 注解方法，启用/禁用时自动注册/注销 EventBus
- 生命周期: `onEnable()`, `onDisable()`
- 注册: `ModuleManager.initModules()` 中手动 `this.register(new XXX())`

### 模块分类 (Category)
COMBAT, MOVEMENT, PLAYER, RENDER, EXPLOIT, WORLD, MISC

### 模块列表

**combat/**: AntiBots, AntiFireball, AntiKB, AutoOffHand, AutoSoup, AutoThrow, Backtrack, Critical, CrystalAura, KillAura
- `antikb/`: AntiKBMode, JumpResetMode, MixMode, NoXZMode

**exploit/**: Disabler, FastPlace

**misc/**: AI (stub), AimAssist, AutoClicker, AutoRod, SafeWalk

**movement/**: CollisionSpeed, FastWeb, FireballBlink, Fly, GuiMove, HighJump, NoDelay, NoPush, NoSlow, Scaffold, Sprint, TargetStrafe

**player/**: AntiTNT, AntiVoid, AntiWeb, AutoMLG, AutoWebPlace, ChestStealer, GhostHand, Helper, InventoryManager, MidPearl, NoFall, Stuck
- `helper/`: HelperBase
- `helper/impl/`: BlockLava, BlockWater, ExtinguishFire, SelfExtinguish

**render/**: AspectRatio, ChestESP, ClickGuiModule, Compass, DamageGlow, ESP, FullBright, Interface, ItemTags, NameProtect, NameTags, NoHurtCam, OldHitting, Projectiles, Watermark, XRay
- `esp/`: ArrowEspColor, ClassEspColor, EspColorProvider, PotionEspColor
- `nametag/`: NameTagStyle, OpalNameTag, SimpleNameTag

**world/**: AntiStaff, AutoPlay, AutoTools, Debugger, Teams, WebUI

## Patch 列表 (shit.nilore.patch)
CallbackInfo, ChatScreenPatch, ClientLevelPatch, ConnectionPatch, EntityPatch, EntityRendererPatch, FriendlyByteBufPatch, GameRendererPatch, HumanoidModelPatch, ItemInHandLayerPatch, ItemInHandRendererPatch, ItemPatch, KeyboardHandlerPatch, KeyboardInputPatch, LevelRendererPatch, LivingEntityPatch, LivingEntityRendererPatch, LocalPlayerPatch, MinecraftPatch, PacketUtilsPatch, PlayerPatch, PlayerTabOverlayPatch, TimerPatch

## 事件类型 (shit.nilore.event.impl)
CameraPitch, Chat, ChatReceive, Disconnect, EntityHurt, EntityRemove, FallFlying, GameTick, GlRender, Jump, JumpMarker, Key, Motion, Packet, PacketSend, PostMotion, PreMotion, PrePacket, PreTick, Prioritized, RayTrace, ReceivePacket, Render2D, RenderEntity, Render, RotationAnimation, Rotation, Slowdown, Sneak, Sprint, Strafe, StuckInBlock, Tick, UpdateHeldItem, UseItemRayTrace, WorldChange

事件基类: `Event`, `EventBus`, `AbstractCancellable`, `Cancellable`, `EventMarker`, `EventPriority`, `EventTarget`, `Prioritized`

## HUD 元素 (shit.nilore.hud)
HudElement (base), IHudElement (interface), AutoPlayHud, DynamicIsland, EventAlertHud, KeyBindsHud, LieDetector, ModuleListHud, NeverloseWatermark, PlayerListHud, PotionEffectsHud, ScaffoldHud, TabListHud, TabListInfo, TargetHud, WatermarkHud
- `target/`: TargetStyle (base), MoonTargetStyle, RoundTargetStyle

## 设置类型 (shit.nilore.settings)
- `Setting<T>` (base), `SettingVisibility`
- `impl/`: BooleanSetting, NumberSetting, ModeSetting, MultiSelectSetting

## 命令 (shit.nilore.command)
- `Command` (base)
- `impl/`: BindCommand, ConfigCommand, InfoCommand, LanguageCommand, ToggleCommand

## 关键类
- `NiloreClient` - 主客户端类，初始化所有 manager、注册 patches
- `ClientBase` - 持有 `mc` (Minecraft) 和 `logger`
- `Module` - 模块基类
- `EventBus` - 事件总线
- `SmoothAnimationTimer` - 动画系统，`animate(target, duration, easing)` / `tick()` / `getValueF()`

## 构建注意事项
- 混淆任务 (`obfuscateClasses`) 在 `reobfJar` 后运行，重命名 `shit/nilore/**` 和 `asm/patchify/**`
- `generated_names.h` 自动生成，供 native DLL 使用
- `mapping/zen.mapping` 和 `mapping/zen-orignial.jar` 不要改名
- Access Transformer 已注释掉（build.gradle line 80）
- 外部依赖: Lombok 1.18.34, ASM 9.6, DevAuth (runtime only)

## 提交github
- git add -A
- git commit -m "你的提交说明"
- git push origin master