# 地下城纪行

《地下城纪行》是一款 2.5D 横版动作游戏。你可以独自挑战回响遗迹，也可以在同一局域网组建 2 至 4 人的小队。当前版本为 `0.5.91-prototype.1`。

## 在 Windows 上游玩

下载下方两个文件，放进同一个文件夹，保留文件名，然后双击 `DungeonChronicle.exe`。可执行文件内含游戏引擎，PCK 包含场景、角色、地图、音效逻辑与其他资源。

- [下载 Windows x64 游戏程序](https://raw.githubusercontent.com/xiaohaoyiqu/Games/main/dungeon-chronicle/windows/DungeonChronicle.exe)
- [下载配套资源包](https://raw.githubusercontent.com/xiaohaoyiqu/Games/main/dungeon-chronicle/windows/DungeonChronicle.pck)

## 第一局怎么玩

从主菜单进入石桥镇，在左侧委托板接受任务，再从右侧遗迹入口出征。每间战斗房清怪后，走到出口按 E 前进。靠近药剂可以恢复，过门会收取遗漏的金币。通关后按 Enter 回城交付委托，在背包和铁匠处调整装备。首次通关且达到 2 级后开放精英难度。主菜单的训练房可以练习走位、连击与闪避。

| 默认按键 | 操作 |
| --- | --- |
| WASD 或方向键 | 移动，包含场地纵深走位 |
| J | 普攻，可衔接三段 |
| K 或空格 | 跳跃 |
| L 或 Shift | 冲刺闪避 |
| U | 破空斩 |
| E | 交互或过门 |
| I / Q | 背包 / 委托 |
| Esc | 单人暂停，组队时暂停个人操作 |
| F1 | 根据场景返回菜单、回城或打开撤退选项 |

组队时，由一人创建房间，其他玩家输入房主的局域网地址与端口，默认端口为 24567。请使用同一版本。全员准备后由房主出征，每位玩家的成长保存在自己的设备上。撤退后请等队员都看到结算并完成保存，再关闭房间。

## 源码与许可

使用 Godot 4.5.1 标准版导入 [游戏工程](source/project.godot)，按 F6 运行当前场景，或按 F5 从主菜单开始。源码附带游戏使用的[角色、场景、道具、技能与字体](source/素材/运行/)。

Godot 及其第三方组件的许可见 [Godot 许可](licenses/GODOT-LICENSE.txt) 和 [组件版权说明](licenses/GODOT-COPYRIGHT.txt)。源码随附的 Noto Sans CJK SC 字体遵循 [SIL Open Font License 1.1](licenses/Noto-OFL.txt)。
