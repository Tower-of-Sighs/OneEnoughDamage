# OneEnoughDamage

你是否遇到过无法修改的硬编码伤害？

你是遇到过弹射物、尖牙、魔法、召唤物、区域伤害这种想平衡都无从下手的伤害？

你是否知道，利维坦有11种不同的伤害，而焰魔有17种不同的伤害？

你的整合包里是否有几百个需要平衡伤害的生物？

现在，有了 OED，调整伤害变得轻而易举，所有模组中的硬编码伤害都会在启动时被扫描，并自动为相关生物注册 Attribute 属性。
在配置文件中还能直接修改默认值，包括原版的伤害属性，一秒重载，供整合包作者平衡所有伤害。

## 属性自动注册

默认本模组启动时会扫描游戏本体以及所有模组的硬编码伤害，把能挂靠给具体生物的伤害注册为 Attribute 属性。

这里的挂靠会通过静态分析去追溯，如唤魔者召唤的尖牙就会挂靠给唤魔者，大部分召唤物伤害、buff 伤害等也能正确挂靠给所有可作为来源的生物。

属性 ID 示例：`oneenoughdamage:fuzs/illagerinvasion/world/entity/monster/invoker_fangs/damage/2/r`

命名规则是伤害点的具体路径+次序+结算方式，r 代表固定伤害，m 代表倍率伤害，仅用于如伤害随难度变化这种动态场景下，默认为 1.0。

模组越多，扫描所需时间会越久，预估200模组左右的整合包扫描时间会达到十秒，在初次启动后，可以开启配置中的 `readCache` 项，之后就可以直接读取缓存，跳过扫描阶段。

## 快速调整伤害

安装本模组后，你有两种方式可以调整生物的伤害，一是直接在配置文件中修改伤害的默认值，二是通过 KubeJS 等方式动态调整 Attribute。

在 `config/OED/` 下会生成这几个文件：

- `damage_points-cache.json`：扫描缓存，机器读取，体积较大，推荐无视。
- `damage-point-dictionary.md`：已归属伤害点字典，自带中英对照，按生物归类伤害，方便查询。
- `damage-point-dictionary.toml`：可编辑配置文件，样式类似字典，包含原版 `minecraft:generic.attack_damage`。
- `damage-point-unattributed.md`：暂时无法安全归属到生物的伤害点。
- `oneenoughdamage.toml`：模组行为配置。

在 `damage-point-dictionary.toml` 中修改属性的对应值可以直接改变属性注册时的默认值，但需要重启游戏生效，非常适合无需动态调整伤害的简单平衡。

如果你尚在测试，可以开启配置中的 `debugMode` 项，那么文件中默认值的修改就会被随时监听并注入到游戏中立即生效。

可修改项中包含原版通用的 `minecraft:generic.attack_damage`，哪怕你并不为硬编码伤害发愁，同样可以快速调整并测试大量生物的伤害。

`damage-point-dictionary.toml` 的更新方式是增量更新，当你新增了模组或删除了模组，原有配置会备份，并在保留属性值修改的前提下新增或减少行。

## 动态调整伤害

如果你需要动态调整伤害，推荐通过 KubeJS 调用 Attribute 相关方法修改或者使用指令。

推荐教程：
- [KubeJS 修改属性教程](https://docs.variedmc.cc/zh/modpack/kubejs/1.20.1/Entity/Attribute)
- [原版 /attribute 指令教程](https://zh.minecraft.wiki/w/%E5%91%BD%E4%BB%A4/attribute?variant=zh-cn)

以下是一个示例，灾变的紫水晶巨蟹每次受到伤害后，自身缩进地下再蹦出来所造成的范围伤害伤害翻倍，安装了 ProbeJS 之后打出 @attribute 即可触发属性名自动补全。

```javascript
// server_scripts
EntityEvents.hurt(event => {
    if (event.getEntity().is("cataclysm:amethyst_crab")) {
        /** @type {Internal.LivingEntity} */
        let entity = event.getEntity();
        let attr = `oneenoughdamage:com/github/l_ender/cataclysm/entity/animation_monster/boss_monsters/amethyst_crab_entity/area_attack/1/m`;
        entity.modifyAttribute(attr, "oneenoughdamage:example", 1, 'addition');
    }
});
```

## 覆盖面

大部分纯数值或简单变量组合的伤害都会被扫描到，考虑到部分模组作者有自己的想法，做到百分百覆盖是不可能的。

目前测试了原版、灾变、灾厄入侵，覆盖率非常高，后面这两个模组可以说全都是硬编码伤害。

灾变自己有一套动态伤害，因而注册的属性很大一部分是默认为 1.0 的倍率形式，这一块也不能说百分百能判断准确，只能靠不断修正。

对于灾变，本模组的主要应用场景其实是平衡同一生物的不同伤害，比如利维坦有11种不同的伤害，焰魔有17种不同的伤害，而原版只有可怜的一个 `minecraft:generic.attack_damage`，根本不够看。

实际上，还有一些硬编码是本模组目前还无法解决的，比如一个生物召唤出了没有 owner 字段的飞行物实体，飞行物对玩家造成了 DamageSource 为 null 的伤害。

通过黑魔法可以追溯到是什么类型的生物造成了这次伤害，但可惜的是在多个同类生物同时在场的情况下，想要追踪到目标生物非常困难。

对此，我们有凑合能用的就近搜索方案：

- `inferAttributeHolder`：当伤害没有直接 LivingEntity 攻击者时，尝试在附近推断归属实体。
- `inferAttributeHolderSearchRadius`：归属推断搜索半径，单位为方块。

如果你遇到了其他覆盖面不足的相关问题，也欢迎反馈。

## 未来计划

- 继续提高覆盖面
- 游戏内可视化编辑属性默认值
- 移植其他版本
