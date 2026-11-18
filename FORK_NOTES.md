# LibreTube 个人定制版 · 维护与许可证说明

这是 [LibreTube](https://github.com/libre-tube/LibreTube) 的独立 fork，用于满足个人使用需求的自定义修改。

## 基本信息

- 上游仓库：https://github.com/libre-tube/LibreTube
- 本仓库：https://github.com/Shjdness/LibreTube
- 基准提交：`b9a486e`（master，2026-11-15）
- 许可证：**GNU GPL 第 3 版或更高版本**（与上游一致，全文见仓库根目录 `LICENSE`）

## 开源协议义务（GPL-3.0）

1. **允许**：自由使用、修改、复制、分发。
2. 仅 **个人 / 内部使用，不对外分发** 时，没有额外义务。
3. 若 **向他人分发** 修改版（APK 或源码），必须：
   - 继续以 GPL-3.0 发布，保留 `LICENSE` 与所有版权声明；
   - 提供 **对应版本** 的完整源代码——发布 APK 时同时明确提供可获取的对应源码，使用固定的提交链接即可定位该构建；
   - 标注所做的修改及其日期（GPL-3.0 §5(a)），本文件与 git 提交记录共同承担这一职责；
   - 不得附加额外限制，不得移除任何 GPL 授权条款；
   - 当 GPL-3.0 §6 的用户产品安装信息条款适用时，一并提供所需的安装信息。
4. **商标**：个人定制版保留上游来源标识。对外分发时应清楚标注该构建来自此 fork，避免造成官方背书或混淆。
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

参考 `.github/workflows/ci.yml` 与 `CONTRIBUTING.md`。Libreplayer 个人正式版构建任务为 `:app:assembleRelease`。

- JDK 17（Temurin）+ Android SDK（compileSdk 36 / minSdk 26 / targetSdk 36）
- Windows：`gradlew.bat :app:assembleRelease`
- 覆盖升级以 `com.github.libretube.debug` 和既有用户安装证书为准，后续构建递增 `versionCode`。
- 通过本机的 `keystore.properties` 配置同一签名密钥，或使用 Android SDK 的 `apksigner` 对正式 APK 签名。密钥与密码保存在仓库外。
- 发包前，用旧 APK 的数据库和音频文件做 `adb install -r` 验证，并核对新旧签名证书 SHA-256。

## 修改记录

- 2026-11-16：fork 并建立本地工作区，新增本说明文件。
- 2026-11-16：移除相关内容、联网搜索建议、SponsorBlock、应用内升级、捐赠、帮助、关于。设置中保留许可证与源码入口。下载统一选取最高码率音轨，按频道浏览。媒体库播放列表优先播放已完整下载的本地音频。旧数据库升级时保留媒体库、历史和下载记录。
- 2026-11-16：移除视频播放器、视频画中画、分辨率和字幕设置，统一走音频播放。旧播放列表封面从已下载的缩略图或列表条目补取。
- 2026-11-16：更名 Libreplayer，移除登录和同步 UI，资料库改文字行，播放队列统一使用媒体前台服务。保留旧版安装标识及升级签名兼容。

- 2026-11-17：Libreplayer 1.1.0。保存公开书签的曲目与顺序以供离线浏览，用本地关注频道目录替换订阅动态。播单增加长标题换行、发布时间、作者排序和筛选，并让随机播放使用当前筛选结果。数据库自动迁移至 27，保留已有数据。

- 2026-11-17：Libreplayer 1.1.1。统一手动切歌与自动连播的队列顺序，避免等待非音频网络请求，并提前准备下一首音轨。

- 2026-11-17：Libreplayer 1.1.2。修复下载服务空闲后再次启动时的 Android 前台超时。播单按批提交下载，播放器按音频状态显示加载，本地封面与在线封面分开读取。

- 2026-11-18：Libreplayer 1.1.3。分组按最终勾选名单一次保存，统一频道链接与 ID 的勾选匹配，并将分组排序写入数据库。

- 2026-11-18：Libreplayer 2.0.0。统一播单播放与加队列入口，下载待取源任务持久化，关注分组以 Room 为准，并为迷你播放器测量和预留列表空间。
