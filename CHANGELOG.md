# 更新日志

本项目的版本记录。历史版本（上游 Legado 2022 年及以前的条目）请见上游仓库：
<https://github.com/LegadoTeam/legado>。

发布包见 [Releases](https://github.com/yuancii/aiyuedu/releases)（调试构建均标记为 pre-release）。

---

## 2026-10-18 · debug-20261018（仓库整理）

本次不涉及功能改动，只做仓库与合规层面的清理：

- **移除仓库内的 Firebase 凭据**：删除 `app/google-services.json`（上游 Legado 项目的配置，`project_id=legado-b3157`），并加入 `.gitignore`；本地开发改为自备自己的 `google-services.json`（不入库）。
- **新增完整免责声明**：[DISCLAIMER.md](DISCLAIMER.md)（项目性质、GPL-3.0、内容与书源免责、使用限制、无担保与责任限制、隐私、商标、联系方式）。
- **调整仓库说明**：`README.aiyuedu.md` 提升为首页 [README.md](README.md)；上游原始 README 保留为 [README-legado.md](README-legado.md)，并注明版权归属。
- 构建说明同步更新：缺失 `google-services.json` 时构建会报 `File google-services.json is missing`，需自备。

---

## 2026-10-16 · v3.261016（首个调试构建）

基于上游 Legado `beta`（提交 `05ad1ccd10752fb772497953e158c7422b91586f`，版本 3.26101518）。

### 悬浮液态玻璃底栏

- 将原本占位的 Material `BottomNavigationView` 替换为**悬浮在内容之上的液态玻璃面板**（Compose + `backdrop` 1.0.6）；
- 对真实列表内容做采样处理：饱和度提升、背景模糊（半径可调）、边缘 `lens` 折射与色散、边缘高光与投影；
- 选中项胶囊弹簧滑动（`spring(dampingRatio = 0.62f, stiffness = 420f)`），按住底栏可横向拖动切换、松手吸附；
- 按下时胶囊放大提亮，并有跟随手指的柔光；
- 新增 6 项设置（设置 → 主题 →「底栏图集」下方）：透明度、模糊程度、底栏高度、左右边距、选中胶囊宽度、动效颜色；
- 内容区自动补底部留白，列表末项不再被底栏遮挡。

### 高亮

- 文本选择菜单新增「**清除高亮样式**」：只清除选中那一段，两侧高亮保留；
- 被清除的段落保留原记录身份与其笔记；选区内没有带样式高亮时不显示该项。

### 构建与兼容性

- `minSdk` 23 → **26**（上游 `modules/rhino` 的 `htmlunit-core-js` 使用 `MethodHandle.invokeExact`，D8 要求 `--min-api 26`）；
- 新增 Compose 1.10.3、`io.github.kyant0:backdrop:1.0.6`、`io.github.kyant0:shapes:1.2.0`；
- 模糊效果需 Android 12（API 31）以上，折射需 Android 13（API 33）以上，低版本自动降级为半透明面板。

### 已知限制

- 底栏暂无外投影（裁剪时被一并裁掉）；
- 不支持 Android 6.0–7.1；
- 包体较上游增大（引入 Compose 运行时）。
