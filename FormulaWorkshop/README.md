# 方程式试车工坊 · Formula Workshop

一款原创的 Windows 像素赛车游戏。你可以从四条赛道的新手练习起步，参加排位和正赛，或在车库调校赛车，用自己的最佳圈幽灵比较进步。游戏还提供赛道编辑器、房间对战和迷你四驱车组装比赛。

## 下载与启动

**[下载 Windows x64 便携包](https://raw.githubusercontent.com/xiaohaoyiqu/Games/main/FormulaWorkshop/downloads/FormulaWorkshop-Windows-x64.zip)** · [SHA-256 校验文件](downloads/SHA256SUMS.txt)

1. 将整个 ZIP 解压到可写目录。需要 64 位 Windows 和支持 OpenGL 3.2 的显卡。
2. 双击解压目录中的 **FormulaWorkshop.exe**。包内自带 Java，启动器需要同目录中的运行时、游戏 JAR 和素材。
3. 从主菜单的「新手入门 · 四图驾驶指南」开始。遇到启动问题时运行同目录的 `Start.cmd` 查看日志；运行 `Start.cmd --low-spec` 可使用低配置模式。

默认按 **W** 加速、**S** 制动、**A/D** 转向、**空格**全制动、**Esc** 暂停。[游玩指南](PLAYING.md) · [第三方组件与许可](THIRD_PARTY.md)

## 从源码运行

安装 64 位 JDK 17 或更高版本和 Maven 3.8 或更高版本，在本目录打开 PowerShell。首次构建需要下载依赖。

```powershell
mvn "-Dmaven.test.skip=true" package
java --enable-native-access=ALL-UNNAMED -jar target/formula-workshop.jar
```

`src/main` 包含游戏程序及内置资源，`assets` 包含可替换的像素素材与部件示例，`scripts` 包含像素图和音乐的生成源码，`native` 包含可选车轮状态扩展的 C++ 源码。
