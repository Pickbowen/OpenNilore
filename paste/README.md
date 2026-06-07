# Zen 抄袭 Naven 的源代码证据

本文档基于公开可获取的两个仓库进行**代码段并排**对比：

- **参考实现（被抄袭方）**：[`Margele/Naven-Modern`](https://github.com/Margele/Naven-Modern)
- **待审项目（疑似抄袭方）**：本仓 OpenZen（Zen 客户端反混淆产物）

> 字段 / 类 / 方法名是反编译时根据 Naven 重命名出来的，**不能算抄袭指纹**。所以下面所有证据全是真实的**字符串字面量、魔法常量、控制流、lambda 表达式**——这些东西在反混淆过程中不会被自动还原，只能是抄过来的。

---

## 目录

- [AntiBots](#antibots)
- [CrystalAura](#crystalaura)
- [AntiFireball](#antifireball)
- [Scaffold](#scaffold)
- [FastWeb](#fastweb)
- [Stuck](#stuck)
- [AutoMLG](#automlg)
- [SafeWalk](#safewalk)
- [Disabler](#disabler)
- [AutoTools](#autotools)
- [ChestStealer](#cheststealer)
- [InventoryManager](#inventorymanager)
- [ChestESP](#chestesp)
- [Compass](#compass)
- [Projectiles](#projectiles)
- [工具类](#工具类)

---

## AntiBots

### 三条 ChatUtil 字符串字面量与拼接模板

```java
// Naven AntiBots.java
ChatUtils.addChatMessage("Fake Staff Detected! (" + uuidDisplayNames.get(entry.getKey()) + ")");
...
ChatUtils.addChatMessage("Bot Detected! (" + displayName + ")");
...
ChatUtils.addChatMessage("Bot Removed! (" + displayName + ")");
```

```java
// Zen AntiBots.java
ChatUtil.print("Fake Staff Detected! (" + suspectNames.get(entry.getKey()) + ")");
...
ChatUtil.print("Bot Detected! (" + botName + ")");
...
ChatUtil.print("Bot Removed! (" + name + ")");
```

三条字面量一字不差，拼接模板 `"X! (" + name + ")"` 形状完全一致。这是模块作者主观选的英文短句，撞车概率为零。

### `isBedWarsBot` 的核心三步

```java
// Naven AntiBots.java
public static boolean isBedWarsBot(Entity entity) {
    AntiBots module = (AntiBots) Naven.getInstance().getModuleManager().getModule(AntiBots.class);
    if (module.respawnTimeValue.getCurrentValue() < 1) {
        return false;
    }
    if (!respawnTime.containsKey(entity.getUUID())) {
        return false;
    }
    return System.currentTimeMillis() - respawnTime.get(entity.getUUID()) < module.respawnTimeValue.getCurrentValue();
}

public static boolean isBot(Entity entity) {
    return ids.contains(entity.getId());
}
```

```java
// Zen AntiBots.java
public static boolean isBedWarsBot(Entity entity) {
    AntiBots antiBots = INSTANCE;
    if (entity.getId() >= 1000000000 || entity.getId() <= -1) { return true; }
    if (entity.getName() == null) { return true; }
    if (entity.getScoreboardName().isEmpty()) { return true; }
    if (antiBots.newPlayerTimeout.getValue().floatValue() < 1.0f) { return false; }
    if (!playerAddTimes.containsKey(entity.getUUID())) { return false; }
    return (float)(System.currentTimeMillis() - playerAddTimes.get(entity.getUUID())) < antiBots.newPlayerTimeout.getValue().floatValue();
}

public static boolean isBot(Entity entity) {
    return confirmedBotIds.contains(entity.getId());
}
```

Zen 在前面加了几条短路判断遮掩，但核心三步 `< 1` 哨兵 → `containsKey` 检查 → `currentTimeMillis() - map.get(uuid) < timeout` 比对完全照抄，`isBot` 一行实现一模一样。

### Setting 字面量 `"Respawn Time"` + 2500 + 0~10000 + 100

```java
// Naven AntiBots.java
private final FloatValue respawnTimeValue = ValueBuilder.create(this, "Respawn Time")
        .setDefaultFloatValue(2500).setFloatStep(100)
        .setMinFloatValue(0).setMaxFloatValue(10000).build().getFloatValue();
```

```java
// Zen AntiBots.java
private final NumberSetting newPlayerTimeout =
        new NumberSetting("Respawn Time", 2500.0, 0.0, 10000.0, 100.0);
```

抛开字段名，`"Respawn Time"` 字符串、默认 2500、范围 0–10000、步长 100 四个字面量一一对应。

### `onPacket` 三个 instanceof 分支的过滤条件

```java
// Naven AntiBots.java
if (packet.getAction() == ClientboundPlayerInfoPacket.Action.ADD_PLAYER) {
    for (ClientboundPlayerInfoPacket.PlayerUpdate entry : packet.getEntries()) {
        if (entry.getDisplayName() != null && (entry.getDisplayName().getSiblings().isEmpty()
                && entry.getGameMode() == GameType.SURVIVAL)) {
            UUID uuid = entry.getProfile().getId();
            uuids.put(uuid, System.currentTimeMillis());
            uuidDisplayNames.put(uuid, entry.getDisplayName().getString());
        }
    }
} else if (e.getPacket() instanceof ClientboundAddPlayerPacket) {
    if (uuids.containsKey(packet.getPlayerId())) {
        String displayName = uuidDisplayNames.get(packet.getPlayerId());
        ChatUtils.addChatMessage("Bot Detected! (" + displayName + ")");
        entityIdDisplayNames.put(packet.getEntityId(), displayName);
        uuids.remove(packet.getPlayerId());
        ids.add(packet.getEntityId());
    }
}
```

```java
// Zen AntiBots.java
if (!infoUpdate.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) { return; }
for (ClientboundPlayerInfoUpdatePacket.Entry entry : infoUpdate.entries()) {
    if (entry.displayName() == null
            || !entry.displayName().getSiblings().isEmpty()
            || entry.gameMode() != GameType.SURVIVAL) { continue; }
    UUID uuid = entry.profile().getId();
    suspectJoinTimes.put(uuid, System.currentTimeMillis());
    suspectNames.put(uuid, entry.displayName().getString());
}
...
if (packet instanceof ClientboundAddPlayerPacket addPlayer) {
    if (!suspectJoinTimes.containsKey(addPlayer.getPlayerId())) { return; }
    String botName = suspectNames.get(addPlayer.getPlayerId());
    ChatUtil.print("Bot Detected! (" + botName + ")");
    confirmedBotNames.put(addPlayer.getEntityId(), botName);
    suspectJoinTimes.remove(addPlayer.getPlayerId());
    confirmedBotIds.add(addPlayer.getEntityId());
    return;
}
```

过滤条件三件套 `displayName != null && siblings.isEmpty() && gameMode == SURVIVAL` 一字不差（Zen 只用 De Morgan 取反 `continue`），随后五步 put / get / remove / add 顺序完全相同。

### 500ms 假员工阈值

```java
// Naven AntiBots.java
for (Map.Entry<UUID, Long> entry : uuids.entrySet()) {
    if (System.currentTimeMillis() - entry.getValue() > 500) {
        ChatUtils.addChatMessage("Fake Staff Detected! (" + uuidDisplayNames.get(entry.getKey()) + ")");
        uuids.remove(entry.getKey());
    }
}
```

```java
// Zen AntiBots.java
for (Map.Entry<UUID, Long> entry : suspectJoinTimes.entrySet()) {
    if (System.currentTimeMillis() - entry.getValue() <= 500L) continue;
    if (this.debug.getValue()) {
        ChatUtil.print("Fake Staff Detected! (" + suspectNames.get(entry.getKey()) + ")");
    }
    suspectJoinTimes.remove(entry.getKey());
}
```

500 ms 这个魔法常量、`currentTimeMillis() - entry.getValue()` 减法表达式、循环里就地 remove 的反模式写法三处都对得上。

---

## CrystalAura

### `onPacket` 末影水晶生成 9 步序列

```java
// Naven AttackCrystal.java
if (packet.getType() == EntityType.END_CRYSTAL) {
    EndCrystal pTarget = new EndCrystal(mc.level, packet.getX(), packet.getY(), packet.getZ());
    pTarget.setId(packet.getId());
    if (mc.player.distanceTo(pTarget) <= 4) {
        Vector2f rotations = RotationUtils.getRotations(pTarget);
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY(), mc.player.getZ(), rotations.getX(), rotations.getY(), mc.player.isOnGround()));
        mc.getConnection().send(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND));
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        mc.player.setYRot(RotationManager.rotations.x);
        mc.player.setXRot(RotationManager.rotations.y);
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(pTarget, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
```

```java
// Zen CrystalAura.java
if (addEntityPacket.getType() == EntityType.END_CRYSTAL) {
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
```

相同的距离阈值 `<= 4`、相同的 9 步操作序列（new EndCrystal → setId → distance → getRotations → PosRot → UseItem → save yaw/pitch → setYRot/XRot → InteractPacket → swing）。

### `onTick` 的 StreamSupport 查找

```java
// Naven AttackCrystal.java
Optional<Entity> any = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), true).filter(entity -> entity instanceof EndCrystal).findAny();
rotations = null;
if (any.isPresent()) {
    Entity entity = any.get();
    Vector2f rots = RotationUtils.getRotations(entity);
    double minDistance = RotationUtils.getMinDistance(entity, rots);
    if (minDistance <= 3) {
        rotations = rots;
        this.entity = entity;
```

```java
// Zen CrystalAura.java
Optional<Entity> crystalOpt = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), true).filter(entity -> entity instanceof EndCrystal).findAny();
aimRotation = null;
if (crystalOpt.isPresent() && (hitDistance = RotationUtil.getMinHitDistance(crystalEntity = crystalOpt.get(), rotation = RotationUtil.entityRotation(crystalEntity))) <= 3.0) {
    aimRotation = rotation;
    this.crystalTarget = crystalEntity;
```

`StreamSupport.stream(... .spliterator(), true)` 并行流、`EndCrystal` instanceof 过滤、`<= 3` 命中距离阈值、`aimRotation = null` 再赋值的控制流完全一致。

---

## AntiFireball

```java
// Naven AntiFireball.java
Stream<Entity> stream = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), true);
Optional<Fireball> fireball = stream.filter(entity -> entity instanceof Fireball && mc.player.distanceTo(entity) < 6).map(entity -> (Fireball) entity).findFirst();
if (!fireball.isPresent()) {
    return;
}
Fireball entity = fireball.get();
mc.gameMode.attack(mc.player, entity);
mc.player.swing(InteractionHand.MAIN_HAND);
```

```java
// Zen AntiFireball.java
Stream<Entity> stream = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false);
Optional<Fireball> optional = stream.filter(entity -> entity instanceof Fireball && (double)mc.player.distanceTo(entity) < 6.0).map(entity -> (Fireball)entity).findFirst();
if (!optional.isPresent()) {
    return;
}
Fireball fireball = optional.get();
ChatUtil.print("§c[AntiFireball] Attacking fireball...");
mc.gameMode.attack(mc.player, fireball);
mc.player.swing(InteractionHand.MAIN_HAND);
```

lambda 链 `instanceof Fireball && distanceTo < 6` 一字不差，魔法数 `6` 完全一致，`mc.gameMode.attack(mc.player, ...)` + `swing(MAIN_HAND)` 收尾相同。Zen 仅多一行 debug 输出。

---

## Scaffold

### `onEnable` 中 `getYRot() - 180` 的初始化

```java
// Naven Scaffold.java
public void onEnable() {
    if (mc.player != null) {
        oldSlot = mc.player.getInventory().selected;
        this.rots.set(mc.player.getYRot() - 180, mc.player.getXRot());
        this.lastRots.set(mc.player.yRotO - 180, mc.player.yRotO);
        this.pos = null;
    }
}
```

```java
// Zen Scaffold.java
public void onEnable() {
    if (mc.player != null) {
        this.oldSlot = mc.player.getInventory().selected;
        this.rots.setYawPitch(mc.player.getYRot() - 180.0f, mc.player.getXRot());
        this.lastRots.setYawPitch(mc.player.yRotO - 180.0f, mc.player.xRotO);
        ...
    }
    super.onEnable();
}
```

把 yaw 减 180 度作为脚手架初始 yaw（让玩家朝身后放方块）是 Naven 自定义做法；4 个字段、顺序、`yRotO/xRotO` 完全对应。

### 模式名 `"Normal" / "Telly Bridge" / "Keep Y"` + Eagle/Snap 的可见性 lambda

```java
// Naven Scaffold.java
public ModeValue mode = ValueBuilder.create(this, "Mode").setDefaultModeIndex(0).setModes("Normal", "Telly Bridge", "Keep Y")...
public BooleanValue eagle = ...("Eagle").setDefaultBooleanValue(true).setVisibility(() -> mode.isCurrentMode("Normal"))...
public BooleanValue snap  = ...("Snap").setDefaultBooleanValue(true).setVisibility(() -> mode.isCurrentMode("Normal"))...
public BooleanValue renderItemSpoof = ...("Render Item Spoof").setDefaultBooleanValue(true)...
```

```java
// Zen Scaffold.java
public final ModeSetting mode = new ModeSetting("Mode", "Normal", "Telly Bridge", "Old Telly", "Keep Y").withDefault("Normal");
public final BooleanSetting eagle = new BooleanSetting("Eagle", true, () -> this.mode.is("Normal"));
public final BooleanSetting snap  = new BooleanSetting("Snap", true, () -> this.mode.is("Normal"));
public final BooleanSetting renderItemSpoof = new BooleanSetting("Render Item Spoof", true);
```

模式列表 `Normal / Telly Bridge / Keep Y` 三个字符串字面量和顺序完全一致；Eagle/Snap 都用 `visibility = mode == "Normal"` 这种 lambda；`"Render Item Spoof"` 这种特定字符串字面量在两边都用作选项名。

### `doSnap` 内 `+ nextFloat(0, 0.5F) - 0.25F` 抖动指纹

```java
// Naven Scaffold.java
if (!shouldPlaceBlock) {
    rots.setX(mc.player.getYRot() + RandomUtils.nextFloat(0, 0.5F) - 0.25F);
}
```

```java
// Zen Scaffold.java
if (!lookingAtBlock && mc.player.tickCount % 4 == 0) {
    this.rots.setYaw(mc.player.getYRot() + RandomUtils.nextFloat(0.0f, 0.5f) - 0.25f);
}
```

用 `nextFloat(0, 0.5) - 0.25` 来得到 [-0.25, +0.25] 抖动量的写法是 Naven 风格（直接 `nextFloat(-0.25, 0.25)` 更自然）。这种"减半法"指纹两边完全一致。

### `isOnBlockEdge` —— 再来一次

```java
// Naven Scaffold.java
public static boolean isOnBlockEdge(float sensitivity) {
    return !mc.level.getCollisions(mc.player, mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-sensitivity, 0.0, -sensitivity)).iterator().hasNext();
}
...
mc.options.keyShift.setDown(mc.player.isOnGround() && isOnBlockEdge(0.3F));
```

```java
// Zen Scaffold.java
public static boolean isOnBlockEdge(float inflate) {
    return !mc.level.getCollisions(mc.player,
            mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-inflate, 0.0, -inflate))
            .iterator().hasNext();
}
...
mc.options.keyShift.setDown(mc.player.onGround() && isOnBlockEdge(0.3f));
```

Zen Scaffold 把 SafeWalk 的同一份 `isOnBlockEdge` 在自己类里又粘贴了一遍，连 `0.3f` 阈值与 `keyShift.setDown(onGround && isOnBlockEdge(0.3f))` 这一整行表达式都一样。

---

## FastWeb

### cobweb 阈值 + magic vector `(0.88, 1.88, 0.88)`

```java
// Naven FastWeb.java
public void onStuck(EventStuckInBlock e) {
    if (e.getState().getBlock() == Blocks.COBWEB) {
        playerInWebTick = mc.player.tickCount;
        ticksInWeb ++;
        if (ticksInWeb > 5) {
            Vec3 newSpeed = new Vec3(0.88, 1.88, 0.88);
            e.setStuckSpeedMultiplier(newSpeed);
        }
    }
}
```

```java
// Zen FastWeb.java
public void onStuckInBlock(StuckInBlockEvent stuckInBlockEvent) {
    if (stuckInBlockEvent.getBlockState().getBlock() == Blocks.COBWEB && mc.player != null) {
        this.lastWebTick = mc.player.tickCount;
        ++this.webCount;
        if (this.webCount > 5) {
            Vec3 vec3 = new Vec3(0.88, 1.88, 0.88);
            stuckInBlockEvent.setMotion(vec3);
        }
    }
}
```

`> 5` 阈值、`(0.88, 1.88, 0.88)` 三元魔法常量、记录 `tickCount` 然后递增计数器的顺序——三层细节全部撞车。0.88 / 1.88 不是 Minecraft 物理常量，是 Naven 独有的口味。

### `onMotion` 中的重置逻辑

```java
// Naven FastWeb.java
public void onMotion(EventMotion e) {
    if (e.getType() == EventType.POST) {
        if (playerInWebTick < mc.player.tickCount) {
            ticksInWeb = 0;
        }
    }
}
```

```java
// Zen FastWeb.java
public void onMotion(MotionEvent motionEvent) {
    if (motionEvent.isPre() && mc.player != null && this.lastWebTick < mc.player.tickCount) {
        this.webCount = 0;
    }
}
```

`lastTick < player.tickCount` 条件 + reset 计数到 0 的逻辑完全相同（仅 PRE/POST 差异）。

---

## Stuck

### `+ 1337` 魔法位移

```java
// Naven Stuck.java
if (tryDisable) {
    NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(mc.player.getX() + 1337, mc.player.getY(), mc.player.getZ() + 1337, mc.player.isOnGround()));
    while (!packets.isEmpty()) {
        NetworkUtils.sendPacketNoEvent(packets.poll());
    }
    tryDisable = false;
}
```

```java
// Zen Stuck.java
if (this.pendingDisable) {
    if (this.modeSetting.is("Delay")) {
        PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Pos(mc.player.getX() + 1337.0, mc.player.getY(), mc.player.getZ() + 1337.0, mc.player.onGround()));
    } ...
    while (!this.pongQueue.isEmpty()) {
        PacketUtil.sendQueued((Packet<ServerGamePacketListener>) this.pongQueue.poll());
    }
    ...
    this.pendingDisable = false;
}
```

把玩家 X/Z 同时加 **1337** 这种"骇客致敬"魔法常量、外加紧跟其后的 `pongQueue.poll()` 循环——这就是经典 Naven 指纹。

### `onPacket` 四个 instanceof 分支链顺序完全相同

```java
// Naven Stuck.java
public void onPacket(EventPacket e) {
    if (e.getPacket() instanceof ServerboundMovePlayerPacket) {
        e.setCancelled(true);
    } else if (e.getPacket() instanceof ServerboundPongPacket) {
        packets.offer((ServerboundPongPacket) e.getPacket());
        e.setCancelled(true);
    } else if (e.getPacket() instanceof ServerboundUseItemPacket || e.getPacket() instanceof ServerboundPlayerActionPacket) {
        packet = e.getPacket();
        stage = 1;
        e.setCancelled(true);
    } else if (e.getPacket() instanceof ClientboundPlayerPositionPacket) {
        while (!packets.isEmpty()) { NetworkUtils.sendPacketNoEvent(packets.poll()); }
        stage = 3;
        toggle();
    }
}
```

```java
// Zen Stuck.java
public void onPacket(PacketEvent packetEvent) {
    ...
    if (rawPacket instanceof ServerboundMovePlayerPacket movePacket) { ...; packetEvent.setCancelled(true); }
    else if (packetEvent.getPacket() instanceof ServerboundPongPacket) {
        this.pongQueue.offer((ServerboundPongPacket)packetEvent.getPacket());
        packetEvent.setCancelled(true);
    } else if (packetEvent.getPacket() instanceof ServerboundUseItemPacket || packetEvent.getPacket() instanceof ServerboundPlayerActionPacket) {
        this.capturedPacket = packetEvent.getPacket();
        this.stuckState = 1;
        packetEvent.setCancelled(true);
    } else if (packetEvent.getPacket() instanceof ClientboundPlayerPositionPacket && this.modeSetting.is("Delay")) {
        while (!this.pongQueue.isEmpty()) { PacketUtil.sendQueued(...); }
        this.stuckState = 3;
        this.setEnabled(false);
    }
}
```

四个 instanceof 分支顺序完全相同：`Move → Pong → (UseItem/PlayerAction) → ClientboundPos`。每个分支内部动作（offer/cancel、记 stage=1、最后 stage=3+toggle）也完全平行。

### `shouldRotate` 中 `BowlFoodItem || BowItem` 双过滤

```java
// Naven Stuck.java
if (packet instanceof ServerboundUseItemPacket) {
    ServerboundUseItemPacket blockPlacement = (ServerboundUseItemPacket) packet;
    ItemStack item = mc.player.getItemInHand(blockPlacement.getHand());
    if (item.getItem() instanceof BowlFoodItem || item.getItem() instanceof BowItem) {
        return false;
    }
    return true;
} else if (packet instanceof ServerboundPlayerActionPacket) {
    ...
    if (playerDigging.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
        if (mc.player.getUseItem().getItem() instanceof BowItem) { return true; }
    }
    return false;
}
```

```java
// Zen Stuck.java
if (this.capturedPacket instanceof ServerboundUseItemPacket useItemPacket) {
    ItemStack heldStack = mc.player.getItemInHand(useItemPacket.getHand());
    return !(heldStack.getItem() instanceof BowlFoodItem) && !(heldStack.getItem() instanceof BowItem);
}
if (this.capturedPacket instanceof ServerboundPlayerActionPacket actionPacket) {
    return actionPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
            && mc.player.getUseItem().getItem() instanceof BowItem;
}
```

`BowlFoodItem + BowItem` 的组合过滤是 Naven 自创口味（碗食物本就极少有人在这种场景去黑名单）。配上 `RELEASE_USE_ITEM + BowItem` 的分支，几乎是 1:1 翻译。

---

## AutoMLG

### 触发结构（落地预测 + 热栏扫描 + 切槽放水）

```java
// Naven AutoMLG.java
public static boolean isOnGround(double height) {
    Iterable<VoxelShape> collisions = mc.level.getBlockCollisions(mc.player, mc.player.getBoundingBox().move(0.0D, height, 0.0D));
    return collisions.iterator().hasNext();
}
...
for (int i = 0; i < 9; i++) {
    ItemStack item = mc.player.getInventory().getItem(i);
    if (!item.isEmpty() && item.getItem() == Items.WATER_BUCKET) {
        originalSlot = mc.player.getInventory().selected;
        mc.player.getInventory().selected = i;
        ...
    }
}
```

```java
// Zen AutoMLG.java
private boolean hasSolidBelow(BlockPos blockPos) {
    return this.isSolidNonMenu(blockPos.below()) || this.isSolidNonMenu(blockPos.below(2));
}
private boolean isSolidNonMenu(BlockPos blockPos) {
    BlockState blockState = mc.level.getBlockState(blockPos);
    boolean hasCollision = !blockState.getCollisionShape(mc.level, blockPos).isEmpty();
    ...
}
...
slot = ItemUtil.findItemInRange(0, 9, Items.WATER_BUCKET);
if (slot < 0) return;
...
this.selectSlot(slot);   // 内部就是 selected=oldSlot; selected=slot
```

固定 0..9 热栏扫描 + `WATER_BUCKET` + 保存 `originalSlot` 再切换 —— 结构相同，Zen 仅把 for 循环抽成 `ItemUtil.findItemInRange` 工具函数。`Fall Distance` 默认 3.0 / 步长 0.1f 两个 NumberSetting 字面量参数也完全一致。

---

## SafeWalk

### `isOnBlockEdge` 静态方法 —— 字符级雷同

```java
// Naven SafeWalk.java
public static boolean isOnBlockEdge(float sensitivity) {
    return !mc.level.getCollisions(mc.player, mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-sensitivity, 0.0, -sensitivity)).iterator().hasNext();
}
```

```java
// Zen SafeWalk.java
public static boolean isOnBlockEdge(float inset) {
    return !mc.level.getCollisions(mc.player, mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-inset, 0.0, -inset)).iterator().hasNext();
}
```

除参数名（`sensitivity` → `inset`）外完全一字不差。`move(0.0, -0.5, 0.0).inflate(-x, 0.0, -x).iterator().hasNext()` 这一连串调用是经典 Naven 指纹。

### `onDisable` 恢复 keyShift

```java
// Naven SafeWalk.java
public void onDisable() {
    boolean isHoldingShift = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyShift.getKey().getValue());
    mc.options.keyShift.setDown(isHoldingShift);
}
```

```java
// Zen SafeWalk.java
public void onDisable() {
    boolean keyDown = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyShift.getKey().getValue());
    mc.options.keyShift.setDown(keyDown);
}
```

仅变量名不同，控制流、API 调用、参数顺序完全一致。

---

## Disabler

### `duplicateRotPlace` 算法 —— `> 2` + `< 0.0001` 双阈值 + `rotated` 状态机

```java
// Naven Disabler.java
if (packet.hasRotation()) {
    float lastPlayerYaw = playerYaw;
    playerYaw = packet.getYRot(0);
    deltaYaw = Math.abs(playerYaw - lastPlayerYaw);
    rotated = true;
    // Guess what will happen if you placed the block in current tick
    if (deltaYaw > 2) {
        float xDiff = Math.abs(deltaYaw - lastPlacedDeltaYaw);
        if (xDiff < 0.0001) {
            log("Disabling DuplicateRotPlace!");
            ...
        }
    }
} else if (e.getPacket() instanceof ServerboundUseItemOnPacket) {
    if (rotated) {
        lastPlacedDeltaYaw = deltaYaw;
        rotated = false;
    }
}
```

```java
// Zen Disabler.java
if (movePacket.hasRotation()) {
    float prevYaw = this.currentYaw;
    float prevPitch = this.currentPitch;
    this.currentYaw = ReflectionUtil.getYRot(movePacket);
    this.currentPitch = ReflectionUtil.getXRot(movePacket);
    this.yawDiff = Math.abs(this.currentYaw - prevYaw);
    this.pitchDiff = Math.abs(this.currentPitch - prevPitch);
    this.rotated = true;
    float yawDelta;
    if (this.yawDiff > 2.0f && (double)(yawDelta = Math.abs(this.yawDiff - this.lastPlacedYawDiff)) < 1.0E-4) {
        ...
    }
} else if (packet instanceof ServerboundUseItemOnPacket && this.rotated) {
    this.lastPlacedYawDiff = this.yawDiff;
    this.lastPlacedPitchDiff = this.pitchDiff;
    this.rotated = false;
}
```

完全一致的算法：取上一 yaw → 算 deltaYaw → `> 2` 阈值 → 算与上次放置 deltaYaw 的差 → `< 0.0001` (1.0E-4) 判定 → `USE_ITEM_ON` 时把当前 delta 写入 `lastPlacedDeltaYaw` 并清零 `rotated`。Zen 仅把单一 yaw 扩展为 yaw + pitch 两份。

---

## AutoTools

### `getBestTool` 算法 —— `efficiencyLevel * efficiencyLevel + 1` 公式

```java
// Naven AutoTools.java
for (int index = 0; index < 9; index++) {
    ItemStack itemStack = mc.player.getInventory().getItem(index);
    if (InventoryUtils.isGodItem(itemStack)) continue;
    if (!itemStack.isEmpty() && !blockState.isAir() && (!(itemStack.getItem() instanceof SwordItem) || block instanceof WebBlock)) {
        float strVsBlock = itemStack.getItem().getDestroySpeed(itemStack, blockState);
        if (strVsBlock > 1 && !(block instanceof OreBlock || block instanceof RedStoneOreBlock)) {
            int i = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, itemStack);
            if (i > 0) {
                strVsBlock += (float) (i * i + 1);
            }
        }
        if (strVsBlock > dmg) {
            slot = index;
            dmg = strVsBlock;
        }
    }
}
if (dmg > 1F) return slot;
return -1;
```

```java
// Zen AutoTools.java
for (int i = 0; i < 9; ++i) {
    int efficiencyLevel;
    ItemStack itemStack = mc.player.getInventory().getItem(i);
    if (ItemUtil.isWeaponItem(itemStack) || itemStack.isEmpty() || blockState.isAir()
            || itemStack.getItem() instanceof SwordItem && !(block instanceof WebBlock)) continue;
    float destroySpeed = itemStack.getItem().getDestroySpeed(itemStack, blockState);
    if (destroySpeed > 1.0f && !(block instanceof DropExperienceBlock) && !(block instanceof RedStoneOreBlock)
            && (efficiencyLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, itemStack)) > 0) {
        destroySpeed += (float)(efficiencyLevel * efficiencyLevel + 1);
    }
    if (!(destroySpeed > bestSpeed)) continue;
    bestSlot = i;
    bestSpeed = destroySpeed;
}
if (bestSpeed > 1.0f) return bestSlot;
return -1;
```

循环上限 `9`、`WebBlock` 例外、附魔加成公式 **`i * i + 1`**、对矿石方块跳过加成、`> 1.0f` 的最终阈值、不命中返 `-1`，逐行对应。`OreBlock → DropExperienceBlock` 只是 1.17 → 1.19 Mojang 类名重命名后的同义替换。

---

## ChestStealer

### 三连 `Component.translatable("container.chest / chestDouble / enderchest")` + 兜底 `"Chest"`

```java
// Naven ChestStealer.java (1.17 API)
String chestTitle = container.getTitle().getString();
String chest = new TranslatableComponent("container.chest").getString();
String largeChest = new TranslatableComponent("container.chestDouble").getString();
String enderChest = new TranslatableComponent("container.enderchest").getString();
if (chestTitle.equals(chest) || chestTitle.equals(largeChest) || chestTitle.equals("Chest")
        || (pickEnderChest.getCurrentValue() && chestTitle.equals(enderChest))) {
```

```java
// Zen ChestStealer.java
String title = containerScreen.getTitle().getString();
String chestTitle = Component.translatable("container.chest").getString();
String doubleChestTitle = Component.translatable("container.chestDouble").getString();
String enderChestTitle = Component.translatable("container.enderchest").getString();
ChestMenu chestMenu = containerScreen.getMenu();
if (this.chestSetting.getValue() && (title.equals(chestTitle) || title.equals(doubleChestTitle) || title.equals("Chest"))) {
    ...
} else if (this.enderChestSetting.getValue() && title.equals(enderChestTitle) && ...) {
```

完全相同的三个翻译键 + 多出来的硬编码英文兜底 `"Chest"` + 单独把 ender chest 拎出来做开关。这种组合不是写 hack 的标准写法 —— Zen 把 1.17 `new TranslatableComponent(...)` 机械替换成 1.19+ 的 `Component.translatable(...)`。

---

## InventoryManager

### 中文字面量 `"点击使用"` —— 终极铁证

```java
// Naven InventoryCleaner.java
public boolean isItemUseful(ItemStack stack) {
    if (stack.isEmpty()) return false;
    if (InventoryUtils.isGodItem(stack)) return true;
    if (stack.getDisplayName().getString().contains("点击使用")) {
        return true;
    }
    if (stack.getItem() instanceof ArmorItem) { ... }
    else if (stack.getItem() instanceof SwordItem)   { return InventoryUtils.getBestSword()   == stack; }
    else if (stack.getItem() instanceof PickaxeItem) { return InventoryUtils.getBestPickaxe() == stack; }
    else if (stack.getItem() instanceof AxeItem && !InventoryUtils.isSharpnessAxe(stack)) {
        return InventoryUtils.getBestAxe() == stack;
    }
    ...
}
```

```java
// Zen InventoryManager.java
public boolean isUsefulItem(ItemStack itemStack) {
    if (itemStack.isEmpty()) return false;
    if (ItemUtil.isWeaponItem(itemStack)) return true;
    if (itemStack.getDisplayName().getString().contains("点击使用")) {
        return true;
    }
    if (itemStack.getItem() == Items.COBWEB) return true;
    Item item = itemStack.getItem();
    if (item instanceof ArmorItem armorItem) { ... }
    if (itemStack.getItem() instanceof SwordItem)   { return ItemUtil.getBestSword()   == itemStack; }
    if (itemStack.getItem() instanceof PickaxeItem) { return ItemUtil.getBestPickaxe() == itemStack; }
    if (itemStack.getItem() instanceof AxeItem && !ItemUtil.isLegitAxe(itemStack)) {
        return ItemUtil.getBestAxe() == itemStack;
    }
    ...
}
```

`contains("点击使用")` 是 Naven 作者针对国服服务端（hypixel.cn / 网易我的世界类）放进物品名的中文标记，与英文环境的 Minecraft **毫无关联**。Zen 一字不差搬了过来，连后续的 `getBestSword() == stack` 这种"引用比较"风格的奇怪判定也照抄。单凭这一行就足以认定整段 `isUsefulItem` 是直接复制粘贴的。

### 四种 offhand 模式与"水/岩浆桶上限 = 1"

```java
// Zen InventoryManager.java
private final ModeSetting offhandItemSetting = new ModeSetting("Offhand Items",
        "Golden Apple", "Fishing Rod", "None").withDefault("Projectile");
...
if ("Golden Apple".equals(offhandPreference))      { if (this.handleGoldenAppleOffhand()) return true; }
else if ("Projectile".equals(offhandPreference))   { if (this.handleProjectileOffhand()) return true; }
else if ("Fishing Rod".equals(offhandPreference))  { if (this.handleFishingRodOffhand()) return true; }
else if ("Block".equals(offhandPreference))        { if (this.handleBlockOffhand())      return true; }

public static int getMaxWaterBuckets() { return 1; }
public static int getMaxLavaBuckets()  { return 1; }
```

Naven 的 `isItemUseful` 里也分别处理 GoldenApple / Projectile (Snowball + Egg) / FishingRod / Block 四类，Zen 直接把它们提升为四个 `handleXxxOffhand()` 函数，并把 Naven 的 `getWaterBucketCount() / getLavaBucketCount()` 设置项简化成硬编码 `return 1;`。

---

## ChestESP

### 箱子打开判定 —— 四重条件

```java
// Naven ChestESP.java
if ((packet.getBlock() == Blocks.CHEST || packet.getBlock() == Blocks.TRAPPED_CHEST) && packet.getB0() == 1 && packet.getB1() == 1) {
    openedChests.add(packet.getPos());
}
```

```java
// Zen ChestESP.java
if ((clientboundBlockEventPacket.getBlock() == Blocks.CHEST || clientboundBlockEventPacket.getBlock() == Blocks.TRAPPED_CHEST) && clientboundBlockEventPacket.getB0() == 1 && clientboundBlockEventPacket.getB1() == 1) {
    this.openedChestPositions.add(clientboundBlockEventPacket.getPos());
```

四重条件（CHEST、TRAPPED_CHEST、b0 == 1、b1 == 1）完全一样，魔法常数 `1` 都来自 ChestBlockEntity.lidEvent。

### `getChestBox` 双箱合并算法

```java
// Naven ChestESP.java
ChestType chestType = state.getValue(ChestBlock.TYPE);
if (chestType == ChestType.LEFT) {
    return null;
}
BlockPos pos = chestBE.getBlockPos();
AABB box = BlockUtils.getBoundingBox(pos);
if (chestType != ChestType.SINGLE) {
    BlockPos pos2 = pos.relative(ChestBlock.getConnectedDirection(state));
    if (BlockUtils.canBeClicked(pos2)) {
        AABB box2 = BlockUtils.getBoundingBox(pos2);
        box = box.minmax(box2);
```

```java
// Zen ChestESP.java
ChestType chestType = blockState.getValue(ChestBlock.TYPE);
if (chestType == ChestType.LEFT) {
    return null;
}
BlockPos blockPos2 = chestBlockEntity.getBlockPos();
AABB aABB = BlockUtil.getBoundingBox(blockPos2);
if (chestType != ChestType.SINGLE && BlockUtil.canBeClicked(blockPos = blockPos2.relative(ChestBlock.getConnectedDirection(blockState)))) {
    AABB aABB2 = BlockUtil.getBoundingBox(blockPos);
    aABB = aABB.minmax(aABB2);
```

`LEFT` 跳过、`SINGLE` 单箱、`getConnectedDirection` 双箱合并、`minmax` 合并 AABB —— 整段算法逐行复刻。

### 箱子颜色常量

```java
// Naven ChestESP.java
private static final float[] chestColor = {0, 1, 0};
private static final float[] openedChestColor = {1, 0, 0};
```

```java
// Zen ChestESP.java (static init)
chestColor = new float[]{0.0f, 1.0f, 0.0f};
openedChestColor = new float[]{1.0f, 0.0f, 0.0f};
```

两个 `float[]` 颜色常数（绿色未开、红色已开）原值搬过来，连透明度 `0.25f` 也一致。

---

## Compass

### yaw 角度计算公式

```java
// Naven Compass.java
float yaw = (float) (Math.toDegrees(Math.atan2(spawnPosition.getZ() - renderZ, spawnPosition.getX() - renderX)) - 90 - renderYaw);
float x = mc.getWindow().getGuiScaledWidth() / 2f;
float y = mc.getWindow().getGuiScaledHeight() / 2f;
```

```java
// Zen Compass.java
float angle = (float)(Math.toDegrees(Math.atan2((double)this.spawnPosition.getZ() - this.renderZ, (double)this.spawnPosition.getX() - this.renderX)) - 90.0 - (double)this.renderYaw);
float centerX = (float)mc.getWindow().getGuiScaledWidth() / 2.0f;
float centerY = (float)mc.getWindow().getGuiScaledHeight() / 2.0f;
```

`atan2(Z, X) - 90 - yaw` 这个公式连项数顺序都没变，魔法常数 `90` 直接搬。

### 两个 BooleanSetting 字面量

```java
// Naven Compass.java
public BooleanValue compassOnly = ValueBuilder.create(this, "Compass Only").setDefaultBooleanValue(true).build()...;
public BooleanValue noPlayerOnly = ValueBuilder.create(this, "No Player Only").setDefaultBooleanValue(true).build()...;
```

```java
// Zen Compass.java
private final BooleanSetting compassOnly = new BooleanSetting("Compass Only", true);
private final BooleanSetting noPlayerOnly = new BooleanSetting("No Player Only", true);
```

字面量名（含空格大小写）`"Compass Only"` / `"No Player Only"` 与默认值 `true / true` 完全一致，注册顺序也相同。

### `renderX / renderZ / renderYaw` 的 `Mth.lerp` 三连

```java
// Naven Compass.java
renderX = Mth.lerp(e.getRenderPartialTicks(), mc.player.xOld, mc.player.getX());
renderZ = Mth.lerp(e.getRenderPartialTicks(), mc.player.zOld, mc.player.getZ());
renderYaw = Mth.lerp(e.getRenderPartialTicks(), mc.player.yRotO, mc.player.getYRot());
```

```java
// Zen Compass.java
this.renderX = Mth.lerp(renderEvent.partialTick(), mc.player.xOld, mc.player.getX());
this.renderZ = Mth.lerp(renderEvent.partialTick(), mc.player.zOld, mc.player.getZ());
this.renderYaw = Mth.lerp(renderEvent.partialTick(), mc.player.yRotO, mc.player.getYRot());
```

`xOld / zOld / yRotO` 的顺序都没换。

---

## Projectiles

### 弓蓄力公式（含魔法数 72000 / 3 / 0.1）

```java
// Naven Projectile.java
float bowPower = (72000 - player.getUseItemRemainingTicks()) / 20.0f;
bowPower = (bowPower * bowPower + bowPower * 2.0f) / 3.0f;
if (bowPower > 1 || bowPower <= 0.1F) bowPower = 1;
bowPower *= 3F;
```

```java
// Zen Projectiles.java
float pull = (72000 - localPlayer.getUseItemRemainingTicks()) / 20.0f;
pull = (pull * pull + pull * 2.0f) / 3.0f;
if (pull > 1.0f || pull <= 0.1f) pull = 1.0f;
pull *= 3.0f;
```

完整公式 `(72000 - x) / 20 → (p² + 2p) / 3 → clamp → × 3` 一模一样，仅变量名 `bowPower → pull`。

### 抛物物品白名单 —— 9 项 instanceof 链顺序相同

```java
// Naven Projectile.java
return item instanceof BowItem || item instanceof CrossbowItem || item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderpearlItem || item instanceof SplashPotionItem || item instanceof LingeringPotionItem || item instanceof FishingRodItem || item instanceof TridentItem;
```

```java
// Zen Projectiles.java
return item instanceof BowItem || item instanceof CrossbowItem
        || item instanceof SnowballItem || item instanceof EggItem
        || item instanceof EnderpearlItem || item instanceof SplashPotionItem
        || item instanceof LingeringPotionItem || item instanceof FishingRodItem
        || item instanceof TridentItem;
```

9 个 Item 类型按完全相同的顺序排列。

### 重力 switch + 三个颜色 RGB 常数

```java
// Naven Projectile.java
if (item instanceof BowItem || item instanceof CrossbowItem) return 0.05;
if (item instanceof PotionItem) return 0.4;
if (item instanceof FishingRodItem) return 0.15;
if (item instanceof TridentItem) return 0.015;
return 0.03;
...
new BasicProjectileData(Collections.singleton(ThrownEnderpearl.class), new Color(173, 12, 255));
new BasicProjectileData(Collections.singleton(ThrownEgg.class),       new Color(255, 238, 154));
new BasicProjectileData(Collections.singleton(Snowball.class),         new Color(255, 255, 255));
```

```java
// Zen Projectiles.java
if (item instanceof BowItem || item instanceof CrossbowItem) return 0.05;
if (item instanceof PotionItem) return 0.4;
if (item instanceof FishingRodItem) return 0.15;
if (item instanceof TridentItem) return 0.015;
return 0.03;
...
new ClassEspColor(Collections.singleton(ThrownEnderpearl.class), new Color(173, 12, 255));
new ClassEspColor(Collections.singleton(ThrownEgg.class),       new Color(255, 238, 154));
new ClassEspColor(Collections.singleton(Snowball.class),         new Color(255, 255, 255));
```

5 条 gravity 分支顺序与数值（`0.05 / 0.4 / 0.15 / 0.015 / 0.03`）完全照搬。3 个 RGB 颜色魔法数 `(173, 12, 255)` `(255, 238, 154)` `(255, 255, 255)` 完全一致，且 `Collections.singleton(X.class)` 包装方式一致。

### 5 个 BooleanSetting 名 + 默认值

```java
// Naven Projectile.java
ValueBuilder.create(this, "Show Arrows").setDefaultBooleanValue(true)...
ValueBuilder.create(this, "Show Pearls").setDefaultBooleanValue(true)...
ValueBuilder.create(this, "Show Potions").setDefaultBooleanValue(false)...
ValueBuilder.create(this, "Show Eggs").setDefaultBooleanValue(false)...
ValueBuilder.create(this, "Show Snowballs").setDefaultBooleanValue(false)...
```

```java
// Zen Projectiles.java
new BooleanSetting("Show Arrows", true);
new BooleanSetting("Show Pearls", true);
new BooleanSetting("Show Potions", false);
new BooleanSetting("Show Eggs", false);
new BooleanSetting("Show Snowballs", false);
```

5 个字符串（大小写、空格、复数 s）、5 个默认值（`true / true / false / false / false`）、注册顺序全部一致。

---

## 工具类

### `ChunkUtil.getLoadedChunks` —— `"Stream limit didn't work."` 异常文本逐字符一致

```java
// Naven ChunkUtils.java
public static Stream<LevelChunk> getLoadedChunks() {
    int radius = Math.max(2, mc.options.getEffectiveRenderDistance()) + 3;
    int diameter = radius * 2 + 1;
    ChunkPos center = mc.player.chunkPosition();
    ChunkPos min = new ChunkPos(center.x - radius, center.z - radius);
    ChunkPos max = new ChunkPos(center.x + radius, center.z + radius);
    Stream<LevelChunk> stream = Stream.iterate(min, pos -> {
        int x = pos.x;
        int z = pos.z;
        x++;
        if (x > max.x) { x = min.x; z++; }
        if (z > max.z) throw new IllegalStateException("Stream limit didn't work.");
        return new ChunkPos(x, z);
    }).limit((long) diameter * diameter).filter(c -> mc.level.hasChunk(c.x, c.z))
      .map(c -> mc.level.getChunk(c.x, c.z)).filter(Objects::nonNull);
    return stream;
}
```

```java
// Zen ChunkUtil.java
public static Stream<LevelChunk> getLoadedChunks() {
    if (mc.player == null || mc.level == null) return Stream.empty();
    final int radius = Math.max(2, mc.options.getEffectiveRenderDistance()) + 3;
    final int side = radius * 2 + 1;
    final ChunkPos center = mc.player.chunkPosition();
    final ChunkPos min = new ChunkPos(center.x - radius, center.z - radius);
    final ChunkPos max = new ChunkPos(center.x + radius, center.z + radius);
    return Stream.iterate(min, current -> {
        int x = current.x; int z = current.z;
        if (++x > max.x) { x = min.x; ++z; }
        if (z > max.z) throw new IllegalStateException("Stream limit didn't work.");
        return new ChunkPos(x, z);
    }).limit((long) side * side).filter(pos -> mc.level.hasChunk(pos.x, pos.z))
      .map(pos -> mc.level.getChunk(pos.x, pos.z)).filter(Objects::nonNull);
}
```

异常字符串 `"Stream limit didn't work."` 含句点、含撇号、连大小写都一致；`Math.max(2, ...) + 3`、`radius * 2 + 1`、`Stream.iterate` 的 lambda 行内 `if (x > max.x) { x = min.x; z++; }` 写法骨架完全一样。这种带异常 throw 的 `Stream.iterate` 谁都不会自己写两遍。

### `RotationUtil` 把 antlr-runtime 的 `OrderedHashSet` 当 Set 用

```java
// Naven RotationUtils.java
import org.antlr.v4.runtime.misc.OrderedHashSet;
...
Set<Vec3> points = new OrderedHashSet<>();
points.add(new Vec3(minX + maxX / 2, minY + maxY / 2, minZ + maxZ / 2));
points.add(getClosestPoint(eyePos, targetBox));
```

```java
// Zen RotationUtil.java
import org.antlr.v4.runtime.misc.OrderedHashSet;
...
OrderedHashSet<Vec3> samplePoints = new OrderedHashSet<>();
samplePoints.add(new Vec3(minX + maxX / 2.0, minY + maxY / 2.0, minZ + maxZ / 2.0));
samplePoints.add(RotationUtil.closestPoint(eyePos, aABB));
```

`org.antlr.v4.runtime.misc.OrderedHashSet` 是 ANTLR 解析器运行时里的类，**任何一个正常人写的旋转工具类都不会去 import 它**。两边都用它存 `Vec3` 采样点；连"先 add 中心点、再 add 最近点"的顺序和起手式都一模一样。

### Sensitivity GCD 公式 `f * f * f * 1.2F`

```java
// Naven RotationUtils.getFixedRotation
final float f = (float) (mc.options.sensitivity * 0.6F + 0.2F);
final float gcd = f * f * f * 1.2F;
final float deltaYaw = yaw - lastYaw;
final float deltaPitch = pitch - lastPitch;
final float fixedDeltaYaw = deltaYaw - (deltaYaw % gcd);
final float fixedDeltaPitch = deltaPitch - (deltaPitch % gcd);
return new Vector2f(lastYaw + fixedDeltaYaw, lastPitch + fixedDeltaPitch);
```

```java
// Zen RotationUtil.getSensitivitySnappedRotation
float sensitivityFactor = (float)(mc.options.sensitivity().get() * 0.6f + 0.2f);
float gcd = sensitivityFactor * sensitivityFactor * sensitivityFactor * 1.2f;
float yawDelta = yaw - prevYaw;
float pitchDelta = pitch - prevPitch;
float snappedYawDelta = yawDelta - yawDelta % gcd;
float snappedPitchDelta = pitchDelta - pitchDelta % gcd;
return new Rotation(prevYaw + snappedYawDelta, prevPitch + snappedPitchDelta);
```

魔法常量 `0.6 / 0.2 / 1.2` 三件套 + `f * f * f` 立方 + `delta - delta % gcd` 求余截断，整套公式逐行同构。

### `rotationTo` 的 yaw / pitch 公式

```java
// Naven RotationUtils.getRotations
double x = target.x - eye.x;
double y = target.y - eye.y;
double z = target.z - eye.z;
double diffXZ = Math.sqrt(x * x + z * z);
float yaw = (float) Math.toDegrees(Math.atan2(z, x)) - 90.0f;
float pitch = (float) (-Math.toDegrees(Math.atan2(y, diffXZ)));
return new Vector2f(MathHelper.wrapDegrees(yaw), MathHelper.wrapDegrees(pitch));
```

```java
// Zen RotationUtil.exactRotation
double dx = to.x - from.x;
double dy = to.y - from.y;
double dz = to.z - from.z;
double horizontalDist = Math.sqrt(dx * dx + dz * dz);
float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horizontalDist)));
return new Rotation(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch));
```

yaw 用 `atan2(z, x) - 90`、pitch 用 `-atan2(y, sqrt(x² + z²))`，外层 `Math.toDegrees` 强转 float 再 `wrapDegrees`，连 `- 90.0f` 的字面量写法都一致。这段代码在 Zen 里出现了 4 次（`rotationTo / exactRotation / rotationFromDeltas / createRotation`）。

### `BlockUtil.canBeClicked / getBoundingBox`

```java
// Naven BlockUtils.java
public static AABB getBoundingBox(BlockPos pos) {
    return getOutlineShape(pos).bounds().move(pos);
}
private static VoxelShape getOutlineShape(BlockPos pos) {
    return getState(pos).getShape(mc.level, pos);
}
public static boolean canBeClicked(BlockPos pos) {
    return getOutlineShape(pos) != Shapes.empty();
}
```

```java
// Zen BlockUtil.java
public static boolean canBeClicked(BlockPos blockPos) {
    return BlockUtil.getVoxelShape(blockPos) != Shapes.empty();
}
public static AABB getBoundingBox(BlockPos blockPos) {
    return BlockUtil.getVoxelShape(blockPos).bounds().move(blockPos);
}
private static VoxelShape getVoxelShape(BlockPos blockPos) {
    return BlockUtil.getBlockState(blockPos).getShape(mc.level, blockPos);
}
```

`!= Shapes.empty()`（而不是更地道的 `.isEmpty()`）这种引用比较的写法两边一致；三段方法的实现一行一行对得上。

### `MovementUtil` direction 函数 —— 7 个 if 分支顺序与角度全一致

```java
// Naven MoveUtils.direction
if (forward != 0.0F || strafe != 0.0F) {
    if (isMovingBack && !isMovingSideways)        return direction + 180.0F;
    else if (isMovingForward && isMovingLeft)     return direction + 45.0F;
    else if (isMovingForward && isMovingRight)    return direction - 45.0F;
    else if (!isMovingStraight && isMovingLeft)   return direction + 90.0F;
    else if (!isMovingStraight && isMovingRight)  return direction - 90.0F;
    else if (isMovingBack && isMovingLeft)        return direction + 135.0F;
    else if (isMovingBack)                        return direction - 135.0F;
}
return direction;
```

```java
// Zen MovementUtil.getDirectionAngle
if (forward != 0.0f || strafe != 0.0f) {
    if (forwardNegative && !hasStrafe)        return yaw + 180.0f;
    if (forwardPositive && strafeNegative)    return yaw + 45.0f;
    if (forwardPositive && strafePositive)    return yaw - 45.0f;
    if (!hasForward && strafeNegative)        return yaw + 90.0f;
    if (!hasForward && strafePositive)        return yaw - 90.0f;
    if (forwardNegative && strafeNegative)    return yaw + 135.0f;
    if (forwardNegative)                      return yaw - 135.0f;
}
return yaw;
```

7 个分支的顺序、判断组合、`+180 / +45 / -45 / +90 / -90 / +135 / -135` 七个常量一字不差。Zen 仅改了变量名（`isMovingBack` → `forwardNegative`），把 `else if` 改成提前 `return`，这是典型的"反编译后稍微整形"痕迹。注意 Zen 这个 `getDirectionAngle` 是 `private` 且**根本没被调用**（死代码），更说明是整团抄过来的。

### `RayTraceUtil.pick` 的 `+ 1.62` 硬编码 eye height

```java
// Naven RayTraceUtils.pick
Vec3 vec3 = new Vec3(mc.player.getX(), mc.player.getY() + 1.62, mc.player.getZ());
Vec3 vec31 = calculateViewVector(pXRot, pYRot);
Vec3 vec32 = vec3.add(vec31.x * pHitDistance, vec31.y * pHitDistance, vec31.z * pHitDistance);
return mc.player.level.clip(new ClipContext(vec3, vec32,
    ClipContext.Block.OUTLINE,
    pHitFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, mc.player));
```

```java
// Zen RayTraceUtil.rayTrace
Vec3 eyePos = new Vec3(mc.player.getX(), mc.player.getY() + 1.62, mc.player.getZ());
Vec3 viewVec = RayTraceUtil.getViewVector(pitch, yaw);
Vec3 endPos = eyePos.add(viewVec.x * range, viewVec.y * range, viewVec.z * range);
return mc.player.level().clip(new ClipContext(eyePos, endPos,
    ClipContext.Block.OUTLINE,
    clipFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, mc.player));
```

在 1.17+ 应该用 `mc.player.getEyeHeight()` 或 `getEyePosition(partialTicks)`，硬编码 `+ 1.62` 是历史遗留写法。两边都犯同一个 hardcode + 同样的 `Block.OUTLINE` + 同样的三元 `Fluid.ANY : Fluid.NONE`，ClipContext 的 5 个参数顺序也一致。

---

## 结语

上面这些代码段，凡是真正难辩解的硬证据全都集中在三类：

1. **字符串字面量**（`"Fake Staff Detected! ("`、`"Bot Detected! ("`、`"Bot Removed! ("`、`"Stream limit didn't work."`、`"点击使用"`、`"Compass Only"`、`"No Player Only"`、`"Show Arrows / Pearls / Potions / Eggs / Snowballs"`、`"Normal" / "Telly Bridge" / "Keep Y"`、`"Render Item Spoof"`、`"Respawn Time"`）。
2. **魔法常量**（`+1337`、`(0.88, 1.88, 0.88)`、`> 2 && < 0.0001`、`i * i + 1` 附魔加成、72000 / 0.1 / 3 弓蓄力、`0.6 / 0.2 / 1.2` 灵敏度 GCD、`+ 1.62` 硬编码 eye height、RGB `(173, 12, 255)` / `(255, 238, 154)` 等）。
3. **特征 import 与"死代码"**（工具类里出现 `org.antlr.v4.runtime.misc.OrderedHashSet`；`MovementUtil.getDirectionAngle` 是 `private` 且没人调用却整团抄了过来）。

这些东西在反混淆环节是**不会被自动还原**的——反编译只能把 `var1 / var2` 还原成可读变量，但字符串、常数、字节码里的 instanceof 链顺序、import 表都跟字节码绑死。它们出现在两个项目里完全一致，唯一合理解释就是源代码直接复制。
