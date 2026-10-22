# aiyuedu 正式版发布说明

本页说明 [aiyuedu 3.26102211 正式版](https://github.com/yuancii/aiyuedu/releases/tag/v3.26102211) 与上游原版的区别。APK 为 `aiyuedu-3.26102211-release.apk`，包名 `com.legado.app.release`，版本号 `37556`，对应源码提交 [`f4432c08b`](https://github.com/yuancii/aiyuedu/commit/f4432c08b9bcb4b3ad6b18ccbbdf81a282f7b41c)。文件 SHA-256：`C1B47331DB98E8AF30013069B145FAAA336D4B79BD13CF4A1392AA1F3D208C07`。历史调试 APK 不对应本次正式版。

## 与 Legado 原版的关系

本项目基于 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 的 `05ad1ccd10752fb772497953e158c7422b91586f` 提交修改。下文“原版”仅指这一上游基线。本项目的“正式版”是个人修改版的发布称呼，**不是 Legado 官方版**，也不代表上游作者认可或参与本项目。

| 项目 | 上游基线 | 本项目正式版源码 |
|---|---|---|
| 首页底栏 | Material 底栏 | 悬浮液态玻璃底栏，增加透明度、模糊、尺寸、颜色、胶囊动画和拖动设置 |
| 高亮操作 | 上游选区操作 | 调整选区内高亮样式的清除及笔记保留行为 |
| 系统要求 | Android 6.0（API 23）起 | Android 8.0（API 26）起；较低系统不支持 |
| Firebase | 上游构建配置 | 仓库不包含配置文件；默认跳过 Google Services 和 Firebase 依赖，需要时由构建者自行配置 |
| 关于 → 开发人员 | 上游贡献者页面 | 跳转至本项目仓库 |

液态玻璃的模糊效果需要 Android 12 及以上，折射效果需要 Android 13 及以上；较低系统使用半透明面板。引入 Compose 后 APK 体积可能大于上游基线。实现细节见 [README.md](README.md) 和 [CHANGELOG.md](CHANGELOG.md)。

## 相比 10 月 21 日测试版

- 回退实验性的 EPUB“书籍原排版”。测试版出现正文跳页、重复页及翻页动画问题，本次正式版源码保留原有阅读器排版。
- 修复缺少 `app/google-services.json` 时构建被 `processAppDebugGoogleServices` 阻止的问题；未提供配置文件时不启用 Firebase。
- “我的 → 关于 → 开发人员”改为打开本项目仓库。

## 学习项目声明

本仓库是基于 GPL-3.0 开源项目的个人学习与研究修改版，**与 Legado 官方无关**。项目不提供书籍内容或书源；用户自行导入文件、添加规则并判断内容来源与使用是否合法。源码和 APK 按 [GPL-3.0](LICENSE) 提供，保留原项目及第三方作者的权利；本项目不额外限制许可证授予的权利。软件按许可证的无担保条款提供，使用风险由使用者自行承担。详见 [完整免责声明](DISCLAIMER.md)。
