# aiyuedu

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/yuancii/aiyuedu?include_prereleases&label=release)](https://github.com/yuancii/aiyuedu/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com/)

基于 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 的个人学习与研究项目。仓库中的“正式版”仅指本项目自行发布的稳定版本，并非 Legado 官方版本。

> ⚠️ **本项目与 Legado 官方无关**，不是官方版本，请勿混淆。
>
> 📄 [本项目发布说明](RELEASE_NOTES.md)　·　[完整免责声明](DISCLAIMER.md)　·　[上游原始 README](README-legado.md)　·　[更新日志](CHANGELOG.md)

---

## 来源与许可

| 项 | 说明 |
|---|---|
| 上游项目 | [LegadoTeam/legado](https://github.com/LegadoTeam/legado) |
| 上游版本 | tag `beta`，提交 `05ad1ccd10752fb772497953e158c7422b91586f`（3.26101518） |
| 许可证 | **GNU General Public License v3.0**（见 [LICENSE](LICENSE)） |

**修改声明**：本项目由 `yuancii` 于 **2026 年 10 月** 在上游代码基础上修改而来，修改内容见下方「与上游的差异」。

依据 GPL-3.0：

- 本项目**同样以 GPL-3.0 授权**，可自由使用、修改、再分发；
- 分发本项目的二进制（APK）时必须同时提供**对应源码**——本仓库即是；
- 请保留本文件、`LICENSE` 以及 `app/src/main/assets/licenses/` 目录下的第三方许可声明。

原项目版权归其作者所有。

---

## 与上游的差异

以下“原版”指上表所列的 Legado 上游基线，不代表上游项目后续版本的状态。本项目正式版源码在该基线上保留原阅读器排版，并包含下列修改：

| 项目 | Legado 上游基线 | 本项目正式版源码 |
|---|---|---|
| 首页底栏 | Material 底栏 | 可调节的悬浮液态玻璃底栏、胶囊点击与拖动动效 |
| 文本高亮 | 上游选区操作 | 调整选区清除高亮样式及笔记保留行为 |
| Android 版本 | 最低 Android 6.0（API 23） | 最低 Android 8.0（API 26） |
| Firebase | 上游构建配置 | 默认不启用；开发者可自行提供配置文件启用 |
| 关于 → 开发人员 | 上游贡献者页面 | 本项目 GitHub 仓库 |

10 月 21 日测试版加入的 EPUB“书籍原排版”因分页和动画问题已回退，**不包含在本次正式版源码中**。

### 一、悬浮液态玻璃底栏

将原来占位的 Material `BottomNavigationView` 底栏，改为**悬浮在内容之上的液态玻璃面板**。

**实现方式**：在底栏容器内叠加一个 Compose 层，通过 `backdrop` 库对**背后的真实列表内容**做采样与处理：

- `vibrancy()` 提升饱和度
- `blur()` 背景模糊（半径可调）
- `lens()` 边缘折射 + 色散（`chromaticAberration`）
- 边缘高光 + 投影 + 表面着色，整体裁剪在圆角形状内

**交互与动效**：

- 选中项胶囊用弹簧滑动（`spring(dampingRatio = 0.62f, stiffness = 420f)`）
- 按住底栏可**横向拖动**，胶囊跟手，松手吸附到手指所在的那一格
- 按下时胶囊放大并提亮，同时出现跟随手指的柔光
- 保留了原有底栏的全部逻辑：菜单显隐、底栏图集皮肤、角标

**新增设置项**（位于 设置 → 主题 → 「底栏图集」下方）：

| 设置 | 范围 | 默认 | 说明 |
|---|---|---|---|
| 液态玻璃透明度 | 0–100 | 80 | 0 全透明，100 不透明 |
| 液态玻璃模糊程度 | 0–60 | 24 | 单位 dp，0 为不模糊 |
| 底栏高度 | 40–96 | 56 | 单位 dp |
| 底栏左右边距 | 0–48 | 12 | 单位 dp，越小底栏越宽 |
| 选中胶囊宽度 | 40–120 | 82 | 占单格宽度的百分比 |
| 动效颜色 | 颜色选择 | `#E53935` | 选中胶囊与按压柔光的颜色 |

**其他配套改动**：

- 内容区滚动控件自动补底部留白（`clipToPadding = false` + `paddingBottom`），列表最后一项不再被底栏遮挡
- 点击反馈不再使用 Material 的 `itemBackground` 水波纹（它在拖动切换选中项时不会被正确取消，会残留在原来那一格），改为自绘按压柔光

### 二、高亮：清除选中部分的样式

文本选择菜单新增 **「清除高亮样式」**：

- **只清除选中那一段**的高亮，两侧已有的高亮保留
- 被清除的那一段沿用原记录身份与它的笔记
- 选区里没有带样式的高亮时，该菜单项**不显示**
- 该菜单项始终紧挨「高亮」排布

原有的「删除高亮」语义**未改动**（点击高亮 → 删除，仍是整条删除）。

### 三、构建配置

| 项 | 上游 | 本分支 |
|---|---|---|
| `minSdk` | 23 | **26** |
| Compose | 无 | **1.10.3**（ui / ui-graphics / foundation / runtime / animation） |
| 液态玻璃库 | 无 | `io.github.kyant0:backdrop:1.0.6`、`io.github.kyant0:shapes:1.2.0` |

> **`minSdk` 提到 26 的原因**：上游 `modules/rhino` 使用的 `htmlunit-core-js`（Rhino fork）内含 `MethodHandle.invokeExact`，D8 要求 `--min-api 26`。这是**上游原有的 debug 构建问题**（其 CI 只构建 release，release 经 R8 裁剪后不受影响），与液态玻璃功能无关。

**改动到的文件**：

```
构建配置
  gradle/libs.versions.toml
  app/build.gradle
  modules/rhino/build.gradle

底栏
  app/src/main/res/layout/activity_main.xml
  app/src/main/java/io/legado/app/lib/theme/view/ThemeBottomNavigationVIew.kt
  app/src/main/java/io/legado/app/ui/main/MainActivity.kt
  app/src/main/java/io/legado/app/ui/widget/glass/BottomBarGlass.kt      (新增)
  app/src/main/java/io/legado/app/ui/widget/glass/ViewBackdrop.kt       (新增)

设置项
  app/src/main/java/io/legado/app/constant/PreferKey.kt
  app/src/main/java/io/legado/app/constant/EventBus.kt
  app/src/main/java/io/legado/app/help/config/AppConfig.kt
  app/src/main/java/io/legado/app/ui/config/ThemeConfigFragment.kt
  app/src/main/res/xml/pref_config_theme.xml
  app/src/main/res/values/strings.xml
  app/src/main/res/values-zh/strings.xml
  app/src/main/java/io/legado/app/lib/prefs/VerticalSeekBarPreference.kt (新增)
  app/src/main/res/layout/view_preference_seekbar_vertical.xml           (新增)
  app/src/main/java/io/legado/app/lib/prefs/SeekBarPreference.kt

高亮
  app/src/main/java/io/legado/app/model/ReadBook.kt
  app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt
  app/src/main/java/io/legado/app/ui/book/read/TextActionMenu.kt
  app/src/main/res/menu/content_select_action.xml
```

---

## 已知限制

- **玻璃效果有系统版本门槛**：
  - `blur` / 颜色处理需 **Android 12（API 31）** 及以上
  - `lens` 折射与色散需 **Android 13（API 33）** 及以上
  - 更低版本自动降级为**半透明纯色面板**，不会崩溃
- **底栏没有外投影**：修圆角外灰边时，玻璃层的裁剪把库自带的外投影一并裁掉了，目前未恢复。
- **不支持 Android 6.0–7.1**：`minSdk` 为 26（原因见上）。
- 包体较上游增大（引入 Compose 运行时）。

---

## 构建

**环境要求**：

- JDK 21
- Android SDK：`compileSdk 36`、`build-tools 36.0.0`
- Gradle 8.14.4（wrapper 已带）、Android Gradle Plugin 8.13.2

**命令行构建**：

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/app/debug/app-app-debug.apk`

**Android Studio**：`构建 → Generate App Bundles or APKs → Generate APKs`

> **Firebase 配置可选**：本仓库已移除 `app/google-services.json`，并把它加入 `.gitignore`。不提供该文件时，构建会跳过 Google Services 插件和 Firebase 依赖；如需统计/性能数据，请把自己 Firebase 项目的 `google-services.json` 放到 `app/` 目录（不会被提交）。切勿使用他人项目的凭据。

---

## 下载

历史调试包见本仓库的 [Releases](https://github.com/yuancii/aiyuedu/releases) 页面。正式版 APK 将在与对应源码核对并测试后单独发布；不要将历史调试包当作本次正式版。

---

## 免责声明

本项目仅以个人学习、研究与自用为维护目的，与 Legado 官方无关；这不限制 GPL-3.0 授予的使用、修改和再分发权利。软件按许可证的**无担保**条款提供，使用者应自行核实内容来源并遵守适用法律及第三方网站规则。

📄 **完整免责声明（版权、书源、隐私、使用限制等条款）：[DISCLAIMER.md](DISCLAIMER.md)**
