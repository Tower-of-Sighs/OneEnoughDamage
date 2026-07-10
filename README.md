# OneEnoughDamage

OneEnoughDamage 会扫描 Minecraft 和已加载模组中硬编码的 `LivingEntity#hurt(DamageSource, float)` 伤害调用，并把能归属到生物的伤害点注册成可配置的 Attribute。

简单来说，它把原本写死在代码里的技能、弹射物、尖牙、魔法、区域伤害等数值整理成字典和 TOML 配置，方便整合包作者统一调整。

## 功能

- 启动时扫描 `.class` 文件和模组 `.jar` 文件。
- 把可归属伤害点注册为 `oneenoughdamage:*` Attribute。
- 生成主字典、未归属字典和可编辑 TOML 配置。
- 重新生成 TOML 时备份旧文件，并保留已有手改数值。
- TOML 内容确实变化时，覆盖前会自动备份旧文件。
- 支持实体限定配置，例如 `[entity."minecraft:skeleton"]` 小节。
- 能把弹射物、效果实体、召唤实体等非生物伤害归属回对应生物。
- 支持调试模式下 TOML 热重载和增量同步。

## 生成文件

文件会生成在 `config/OED/` 下：

```text
damage_points-cache.json
damage-point-dictionary.md
damage-point-dictionary.toml
damage-point-unattributed.md
oneenoughdamage.toml
```

- `damage_points-cache.json`：扫描缓存，机器读取，不建议手改。
- `damage-point-dictionary.md`：已归属伤害点字典。
- `damage-point-dictionary.toml`：可编辑配置文件，主要改这个。
- `damage-point-unattributed.md`：暂时无法安全归属到生物的伤害点。
- `oneenoughdamage.toml`：模组行为配置。

当 `damage-point-dictionary.toml` 需要覆盖时，会先把旧文件复制成备份：

```text
damage-point-dictionary.backup-20260705-110203.toml
```

如果生成内容没有变化，不会重写 TOML，也不会创建备份。`damage-point-dictionary.toml` 更新时会备份原有配置，并在保留属性值修改的前提下重新生成。每个 `[entity."实体ID"]` 小节只作用于对应生物，例如在 `[entity."minecraft:zombie"]` 下写 `"oneenoughdamage:global_damage" = 1.5` 会让僵尸的 OED 伤害整体变为 1.5 倍。

## TOML 配置

修改等号右侧的数字即可调整伤害：

```toml
# Invoker - Invoker（祈唤师）（类型：生物）
[entity."illagerinvasion:invoker"]
# 模式：替换（r），默认 10.0，fuzs.illagerinvasion.world.entity.monster.InvokerFangs#damage#2
"oneenoughdamage:fuzs/illagerinvasion/world/entity/monster/invoker_fangs/damage/2/r" = 200.0
```

后缀含义：

- `/r`：替换原伤害。
- `/m`：作为倍率参与计算。

实体限定配置使用 `[entity."entity_id"]` 小节：

```toml
[entity."minecraft:skeleton"]
"oneenoughdamage:net/minecraft/world/entity/projectile/abstract_arrow/on_hit_entity/1/m" = 1.0
"minecraft:generic.attack_damage" = 2.0
"oneenoughdamage:global_damage" = 1.5
```

同一个 Attribute 可以给不同实体配置不同数值。写在根部的 key 是全局 Attribute 默认值；写在 `[entity."entity_id"]` 下的 key 只作用于对应实体类型。

## 弹射物和非生物实体伤害

非生物伤害源只有在能追溯到 LivingEntity 时才会进入主 TOML。

例子：

- `AbstractArrow#onHitEntity` 会按射手生成配置，例如 `minecraft:skeleton`、`minecraft:stray`、`minecraft:wither_skeleton`。
- 模组弹射物如果能追踪到创建或发射它的生物，也会挂到对应生物。
- 尖牙、召唤物、效果实体等如果能解析出召唤者，也会挂到召唤者。
- 无法安全归属的内容继续留在 `damage-point-unattributed.md`，不会强行写进 TOML。

现在没有全局 `oneenoughdamage:projectile_base_damage` 配置项。这个 id 只保留了一个旧存档兼容注册，避免已有世界里保存过该 Attribute 的实体刷 unknown-attribute 警告；它不会生成配置，不会主动添加到实体，也不参与伤害计算。

## 原版近战伤害

如果生物拥有原版 `minecraft:generic.attack_damage`，TOML 会生成实体限定的近战基础伤害配置：

```toml
[entity."minecraft:zombie"]
"minecraft:generic.attack_damage" = 3.0
```

这个值只修改对应实体类型的近战基础伤害。

## 配置文件

`oneenoughdamage.toml` 会在启动时读取：

```toml
readCache = false
debugMode = false
inferAttributeHolder = true
inferAttributeHolderSearchRadius = 32.0
```

- `readCache`：为 `true` 时，如果 `damage_points-cache.json` 已存在就复用缓存；为 `false` 时每次启动重新扫描。
- `debugMode`：启用 TOML 热重载和 Attribute 默认值调试 hook。这个开关本身需要重启才生效。
- `inferAttributeHolder`：当伤害没有直接 LivingEntity 攻击者时，尝试在附近推断归属实体。
- `inferAttributeHolderSearchRadius`：归属推断搜索半径，单位为方块。

调试模式开启后，OED 会监听 `damage-point-dictionary.toml`，只增量读取发生变化的 key，并把变更同步到已加载实体。

## 构建

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

开发常用命令：

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew compileJava
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew runServer
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew runClient
```

## 环境

- Minecraft 1.21.1
- NeoForge 21.1.228
- Java 21
- License: GNU GPL 3.0
