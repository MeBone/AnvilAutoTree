# AnvilCraft:AutoTree

铁砧工艺的合成终端扩展，适用于 Minecraft 26.1.2、NeoForge 26.1.2.126 和 AnvilCraft 1.6.0+snapshot.2907，JEI 29.53.0.115 可选。模组需同时安装在客户端和服务端。[下载最新版](https://github.com/MeBone/AnvilAutoTree/releases/latest)

## 功能

- JEI 紫色「+」打开可拖动、缩放的合成树，预览材料、库存和缺口，支持快捷键确认。
- 终端支持懒狗下单，不用写样板即可自寻合成，支持大型板条箱、潜影集装箱以及超维存储终端、不同终端相关合成性能有所不同，可在模组配置里进行修改。
- 批量下单按目标成品数填写，超维存储最多 1024 件，潜影集装箱最多 64 件。大型板条箱和本地终端支持单次补料。
- 超维批量合成时，左键「合成」将成品入库，Shift + 左键将成品放入超维合成栏。
- 超维存储和潜影集装箱可合并多套多方块配方的材料，按堆叠上限分装到空潜影盒并放入合成表，注意此操作需要库存本身存在潜影盒。
- 在持有绑定的超维终端时，缺货的方块可通过对着目标方块按中键打开合成树，普通成品放入物品栏，多方块材料包装入潜影盒。

- 超维存储可用流体端口的牛奶等流体灌装配方所需的桶或玻璃瓶，空容器不足时可自动合成。例如，制作一个蛋糕消耗 3000 mB 牛奶，返还 3 个空桶。

## 模组配置

客户端窗口位置和大小可在 **AnvilCraft:AutoTree → 配置 → 客户端** 中拖动预览并保存。

通用配置文件为 `config/anvilautotree-common.toml`。

| 配置项 | 默认值 | 说明 |
| --- | ---: | --- |
| `usePlayerInventory` | false | 允许合成树使用玩家物品栏里的材料，可在模组配置的「通用」页切换 |
| `parallel` | 1024 | 超维每刻合成上限。潜影为超维的 1/12，板条箱为潜影的 1/12，向下取整，最低 1 次 |
| `maxDepth` | 16 | 合成链最大深度 |
| `maxSteps` | 256 | 单份合成计划的最大步骤数 |

## 构建

使用 Java 25 和 Gradle 9 运行 `gradle build`。使用本地实例构建时，在 UTF-8 文本文件中逐行列出 Minecraft patched JAR、NeoForge universal JAR、实例运行库、AnvilCraft、AnvilLib UI、AnvilLib Multiblock 和 JEI 的路径，并向 Gradle 传入 `-PlocalGameClasspath=<路径列表文件>`。
