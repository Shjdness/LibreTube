# LibreTube 个人定制版 · 维护与许可证说明

这是 [LibreTube](https://github.com/libre-tube/LibreTube) 的独立 fork，用于满足个人使用需求的自定义修改。

## 基本信息

- 上游仓库：https://github.com/libre-tube/LibreTube
- 本仓库：https://github.com/Shjdness/LibreTube
- 基准提交：`b9a486e`（master，2026-11-15）
- 许可证：**GNU GPL v3.0**（与上游一致，全文见仓库根目录 `LICENSE`）

## 开源协议义务（GPL-3.0）

1. **允许**：自由使用、修改、复制、分发。
2. 仅 **个人 / 内部使用，不对外分发** 时，没有额外义务。
3. 若 **向他人分发** 修改版（APK 或源码），必须：
   - 继续以 GPL-3.0 发布，保留 `LICENSE` 与所有版权声明；
   - 提供 **对应版本** 的完整源代码——本公开仓库即可满足，但发布某个 APK 前，须确保该构建所对应的源码已推送并打上 tag；
   - 标注所做的修改及其日期（GPL-3.0 §5(a)），本文件与 git 提交记录共同承担这一职责；
   - 不得附加额外限制，不得移除任何 GPL 授权条款；
   - 若作为"用户产品"分发，需提供安装信息（GPL-3.0 §6）。
4. **商标**：LibreTube 的名称与图标不在 GPL 授权范围内。公开分发修改版时，应 **改名并更换图标**，避免造成官方背书或混淆。
5. 第三方依赖（NewPipe Extractor、AndroidX 等）各自保留其许可证，修改时不得移除其声明。

## 分支策略

- `master`：与上游保持一致，仅用于同步。
- `custom`：本仓库的定制修改在此分支进行。

## 同步上游

```bash
git fetch upstream
git checkout master && git merge upstream/master
git checkout custom && git merge master
```

## 构建

参考 `.github/workflows/ci.yml` 与 `CONTRIBUTING.md`：

- JDK 17（Temurin）+ Android SDK（compileSdk 36 / minSdk 26 / targetSdk 36）
- Windows：`gradlew.bat assembleDebug`

## 修改记录

- 2026-11-16：fork 并建立本地工作区，新增本说明文件；尚无功能性代码改动。
