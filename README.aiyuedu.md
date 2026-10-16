# aiyuedu

基于 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 的个人修改版调试构建。

> ⚠️ **本项目与 Legado 官方无关**，不是官方版本，请勿混淆。

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

> 注意：`app/google-services.json` 为上游项目的 Firebase 配置，若用于自己的分发请**替换为你自己的 Firebase 项目或移除 Firebase 依赖**。

---

## 下载

调试包见本仓库的 **Releases** 页面。

---

## 免责声明

本项目为个人学习与自用目的的修改版，按 GPL-3.0 的 **「无担保」** 条款提供，作者不对使用后果承担任何责任。请勿将其用于任何违反当地法律法规的用途。
