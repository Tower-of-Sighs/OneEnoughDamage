# OneEnoughDamage

扫描游戏和其他模组中硬编码的 `LivingEntity#hurt(DamageSource, float)` 伤害点，并把可归属到生物的伤害点注册成可配置的 Attribute。

简单来说，它把一些原本写死在代码里的技能、尖牙、弹射物、区域魔法等伤害值，整理成字典和 TOML 配置，方便整合包作者统一调整。

## 功能

- 启动时扫描 classpath 中的 `.class` 和 `.jar`，发现硬编码伤害点。
- 为可归属到 `LivingEntity` 的伤害点注册 `oneenoughdamage:*` Attribute。
- 生成主字典、未归属字典和可编辑 TOML 配置。
- 支持投射物基础伤害覆盖：`oneenoughdamage:projectile_base_damage`。
- 可选调试模式下监听 TOML 文件变化，并增量同步变更的 Attribute 值。

## 生成文件

运行后会在 `config/OED/` 下生成：

```text
damage_points-cache.json
damage-point-dictionary.md
damage-point-dictionary.toml
damage-point-unattributed.md
oneenoughdamage.properties
```

说明：

- `damage_points-cache.json`：扫描缓存，机器读取，不建议手改。
- `damage-point-dictionary.md`：可归属伤害点字典。
- `damage-point-dictionary.toml`：可编辑配置文件，只包含有 Attribute 的伤害点。
- `damage-point-unattributed.md`：暂时无法稳定归属到生物的伤害点汇总。
- `oneenoughdamage.properties`：模组行为配置。

覆盖生成 `damage-point-dictionary.toml` 前，旧文件会自动备份为类似：

```text
damage-point-dictionary.backup-20260705-110203.toml
```

## 配置伤害

编辑 `damage-point-dictionary.toml` 中等号右侧的数字即可：

```toml
# Invoker - Invoker（祈灵师）（类型：生物）
# 模式：替换（r），默认 10.0，伤害源 indirectMagic，fuzs.illagerinvasion.world.entity.monster.InvokerFangs#damage#2
"oneenoughdamage:fuzs/illagerinvasion/world/entity/monster/invoker_fangs/damage/2/r" = 200.0
```

后缀含义：

- `/r`：replace，直接替换原伤害。
- `/m`：multiplier，作为倍率参与计算。

投射物基础伤害：

```toml
"oneenoughdamage:projectile_base_damage" = -1.0
```

`-1.0` 表示禁用覆盖。

## 调试模式

`oneenoughdamage.properties` 中：

```properties
debugMode=false
```

改为 `true` 后重启，会启用：

- mixin 到 `Attribute#getDefaultValue`，从 TOML 缓存读取默认值。
- 监听 `damage-point-dictionary.toml` 的创建、修改、删除。
- 文件变化后只增量更新变更的 key。
- 只同步变化的 Attribute 到当前已加载实体，避免全量刷新。

`debugMode` 本身只在启动时读取，修改该开关需要重启。

## 构建

```text
./gradlew build
```

开发环境常用：

```text
./gradlew compileJava
./gradlew runServer
./gradlew runClient
```

## 环境

- Minecraft 1.20.1
- Forge 47.3.0
- Java 17
- License: GNU GPL 3.0
