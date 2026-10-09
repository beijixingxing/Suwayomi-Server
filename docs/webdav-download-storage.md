# WebDAV 下载存储 — 开发文档

## 概述

为 Suwayomi-Server 的漫画下载功能新增可选的 **WebDAV** 存储后端，与现有的本地文件系统下载路径并列。用户可以在下载设置界面中切换 **本地** 和 **WebDAV** 两种存储方式，同时独立选择 **CBZ 压缩包** 或 **文件夹（逐页图片）** 两种保存格式。

### 架构设计

改动前，下载管线直接将文件写入 `downloadsPath` 配置的本地目录，所有文件 I/O 硬编码为本地文件系统操作。

新设计引入 **`DownloadStorage` 接口**，抽象下载管线所需的全部 I/O 操作。提供两种实现：

| 后端 | 实现类 | 写入目标 |
|------|--------|----------|
| 本地文件系统 | `LocalDownloadStorage` | `downloadsPath` 目录 |
| 远程 WebDAV | `WebDavDownloadStorage` | WebDAV URL + 认证凭据 + 远程路径 |

通过 **`DownloadStorageFactory`** 单例在调用时根据服务器配置解析当前后端，并对 WebDAV 实例做了可失效缓存。

```
┌──────────────────────────────────────────────────┐
│             ChapterDownloadHelper                │
│   provider() → ArchiveProvider | FolderProvider   │
└──────────────────────┬───────────────────────────┘
                       │
              ┌────────▼────────┐
              │ DownloadStorage │  (接口)
              └────┬──────┬─────┘
                   │      │
     ┌─────────────▼┐  ┌──▼──────────────────┐
     │ LocalDownload │  │ WebDavDownload      │
     │ Storage       │  │ Storage             │
     │ (本地文件系统)  │  │ (OkHttp/WebDAV)     │
     └───────────────┘  └─────────────────────┘
```

所有传递给 `DownloadStorage` 的路径均为 **相对于下载根目录的相对路径**（如 `mangas/源名/漫画名/章节.cbz`），由 `StoragePaths.toStorageRelative()` 完成转换。

---

## 后端文件

### 新增文件

#### `DownloadStorage.kt`
`server/src/main/kotlin/.../download/storage/DownloadStorage.kt`

存储后端抽象接口，定义数据类 `StorageFile`（含 `path`、`isDirectory`、`size`）。

```kotlin
interface DownloadStorage {
    suspend fun exists(path: String): Boolean              // 文件/目录是否存在
    suspend fun writeFile(path, content, size)             // 写入文件
    suspend fun readFile(path: String): InputStream?       // 读取文件
    suspend fun deleteFile(path: String): Boolean          // 删除文件（幂等：缺失即成功）
    suspend fun fileSize(path: String): Long               // 获取文件大小
    suspend fun listFiles(dirPath): List<StorageFile>      // 列出目录内容
    suspend fun createDirectory(dirPath: String)           // 创建目录（含父目录）
    suspend fun deleteDirectory(dirPath: String): Boolean  // 递归删除目录（幂等）
    fun localPathOrNull(path: String): File?               // 本地后端可直接访问的路径，否则 null
    suspend fun cleanupEmptyParents(path: String)          // 删除后清理空的父目录（仅本地实现）
}
```

`localPathOrNull()` 是可选能力：本地后端返回真实路径，使读取可以**零拷贝**直接进行；远程后端返回 `null`，调用方改走有界缓存。`cleanupEmptyParents()` 让本地后端在删除章节后清理空的漫画/源目录。

#### `LocalDownloadStorage.kt`
`server/src/main/kotlin/.../download/storage/LocalDownloadStorage.kt`

基于 `java.io.File` 的本地文件系统实现。所有阻塞操作通过 `withContext(Dispatchers.IO)` 执行。

#### `WebDavDownloadStorage.kt`
`server/src/main/kotlin/.../download/storage/WebDavDownloadStorage.kt`

基于 **OkHttp**（项目已有依赖）的完整 WebDAV 客户端。

| 方法 | WebDAV 动词 | 说明 |
|------|------------|------|
| `exists()` | `HEAD` | 2xx 返回 `true` |
| `writeFile()` | `PUT` | 通过 `MKCOL` 确保父目录存在（已创建的目录会被记住，避免每次上传重复探测）；`contentLength` 带断言校验 |
| `readFile()` | `GET` | 返回原始 `InputStream`，由调用方关闭；404 返回 `null`，其他非 2xx 抛出 `IOException`（不再把服务器错误伪装成「文件不存在」） |
| `deleteFile()` | `DELETE` | 404 视为成功（幂等） |
| `fileSize()` | `HEAD` | 读取 `Content-Length` 响应头 |
| `listFiles()` | `PROPFIND` Depth:1 | 正则解析 XML（同时支持 `<D:response>` 和 `<response>`） |
| `createDirectory()` | `MKCOL` | 逐段遍历路径创建缺失层级；接受 405（已存在），其他错误传播 |
| `deleteDirectory()` | `DELETE` | 先 `listFiles()` 递归列出子项并删除，再 `DELETE` 目录本身；404 视为成功 |

> `MOVE` 未实现：`DownloadStorage.move()` 因无任何调用方已被移除（死代码清理）。

URL 构造使用逐段 `URLEncoder.encode()` 处理中日韩字符和特殊符号。

> **注意**：WebDAV XML 响应使用 `DAV:` 命名空间（如 `<D:response>`、`<D:href>`），`parsePropfind` 的正则已兼容此格式与无命名空间格式。未来可考虑改用项目已有的 `javax.xml.parsers.DocumentBuilderFactory` 进行标准 DOM 解析。

#### `DownloadStorageFactory.kt`
`server/src/main/kotlin/.../download/storage/DownloadStorageFactory.kt`

```kotlin
object DownloadStorageFactory {
    fun create(): DownloadStorage = when (serverConfig.downloadStorageType.value) {
        DownloadStorageType.LOCAL  -> localStorage()
        DownloadStorageType.WEBDAV -> getWebDav() ?: localStorage()   // 未配置 URL 时回退本地
    }
}
```

工厂方法对 `WebDavDownloadStorage` 实例做了缓存：当 WebDAV 配置（URL、用户名、密码、远程路径）发生变化时，通过 `WebDavConfig` 数据类比对自动失效并重建。

本地后端统一使用 `ApplicationDirs.downloadsRoot`（已包含 `downloadsPath` 为空时的默认值回退），并以 `mangaDownloadsRoot` 作为空父目录清理的上界。此外，工厂额外提供 `localStorage()` 供 `provider()` 做跨后端读取回退。

#### `StoragePaths.kt`
`server/src/main/kotlin/.../download/storage/StoragePaths.kt`

将本地绝对路径（如 `/comics/mangas/源名/漫画/章节.cbz`）转换为存储相对路径（`mangas/源名/漫画/章节.cbz`），通过剥离 `downloadsRoot` 前缀实现。若路径不在下载根下，记录 WARN 后按根相对路径处理，避免静默把宿主机绝对路径泄漏到 WebDAV URL 中。

#### `RemoteCopyCache.kt`
`server/src/main/kotlin/.../download/storage/RemoteCopyCache.kt`

远程后端无法就地读取，内容需先落到本地临时区。该类是**有界** LRU 缓存：按签名（远端大小 / 页数与总字节）校验有效性，超出条数上限时淘汰最久未使用的条目并删除其文件，避免长跑服务器把磁盘写满。本地后端不会使用它。

#### `DownloadStorageType.kt`
`server/server-config/.../graphql/types/DownloadStorageType.kt`

```kotlin
enum class DownloadStorageType { LOCAL, WEBDAV }
```

GraphQL 解析由 `EnumSetting` 处理，无需手动反序列化方法。

### 修改文件（后端）

| 文件 | 变更内容 |
|------|----------|
| `ServerConfig.kt` | 在 `DOWNLOADER` 组下新增 5 个设置项（proto 编号 99–103）：`downloadStorageType`（枚举）、`webdavUrl`、`webdavUsername`、`webdavPassword`、`webdavRemotePath`（字符串）。密码类配置标记为不参与备份。 |
| `ChapterDownloadHelper.kt` | 统一 `provider()` 为单一路径，将解析后的 `DownloadStorage` 实例传递给 `ArchiveProvider` 和 `FolderProvider`。文件存在性检查改用 `storage.exists()`。 |
| `ChaptersFilesProvider.kt` | 下载主流程：按**写入后端**判定章节是否已存在（`existsInActiveBackend()`），页循环跳过最终目录与缓存中已有的页面，成功后调用 Provider 的 `handleSuccessfulDownload()` 上传。 |
| `ArchiveProvider.kt` | 构造函数接受 `DownloadStorage`（写入后端）与可选的读取后端；`handleSuccessfulDownload()` 合并**最终目录中已存在的页面**与**下载缓存**（缓存同名文件优先）后在临时文件构建 CBZ，再通过 `storage.writeFile()` 上传；`delete()` 同时从写入与读取两个后端删除；CBZ 本地缓存（`ConcurrentHashMap` + `Mutex`）避免阅读时逐页重复下载整个压缩包。 |
| `FolderProvider.kt` | 构造函数接受 `DownloadStorage`（写入后端）与可选的读取后端；`handleSuccessfulDownload()` 把最终目录中缓存缺少的页面补进缓存后递归上传；`delete()` 同时从写入与读取两个后端删除。 |
| `TachideskGraphQLSchema.kt` | 注册 `WebDavConnectionMutation` 为顶层 GraphQL 对象。 |
| `Constants.kt` | 为自定义构建硬编码 `getTachideskVersion` 和 `getTachideskRevision`。 |

### 新增 GraphQL Mutation

`WebDavConnectionMutation.kt` 暴露 `testWebDavConnection` 变更：

```graphql
mutation {
  testWebDavConnection(input: {
    url: "http://192.168.1.100:5005"
    username: "user"
    password: "pass"
  }) {
    success
    message
  }
}
```

向目标 URL 发送 `PROPFIND Depth:1` 请求，2xx/207 响应返回 `success: true`，否则返回 `success: false` 及可读的错误信息。

---

## 前端文件

### 修改文件

#### `DownloadSettings.tsx`
`src/features/downloads/screens/DownloadSettings.tsx`

- 将手写的 `FormControl`+`Select`+`MenuItem` 替换为项目标准的 `SelectSetting<DownloadStorageType>`。
- 新增 WebDAV 配置区域（URL、用户名、密码、远程路径、测试连接按钮），仅在 WebDAV 选中时显示。
- **「下载位置」**（`downloadsPath`）字段在 WebDAV 模式下隐藏，仅本地模式时显示。
- 测试连接按钮调用 `useTestWebDavConnection()`，通过 Toast 提示成功/失败。

#### `Settings.constants.ts`
`src/features/settings/Settings.constants.ts`

新增 `DOWNLOAD_STORAGE_TYPE_SELECT_VALUES`，使用项目标准的 `msg` 宏提供选择描述：

```ts
export const DOWNLOAD_STORAGE_TYPE_SELECT_VALUES: SelectSettingValue<DownloadStorageType>[]
```

#### `SettingsMutation.ts`
`src/lib/graphql/settings/SettingsMutation.ts`

新增 `TEST_WEBDAV_CONNECTION` GraphQL 变更及内联 TypeScript 类型：
```ts
export type TestWebDavConnectionMutation = {
    testWebDavConnection: {
        clientMutationId?: string | null
        success: boolean
        message: string
    } | null
}
```

#### `RequestManager.ts`
`src/lib/graphql/settings/RequestManager.ts`

新增 `useTestWebDavConnection()` hook，使用正确的泛型类型。

#### `SettingsFragments.ts`
`src/lib/graphql/settings/SettingsFragments.ts`

在设置 GraphQL 片段中新增 5 个字段：
`downloadStorageType`、`webdavUrl`、`webdavUsername`、`webdavPassword`、`webdavRemotePath`。

#### i18n 翻译文件
`src/i18n/locales/zh-Hans.po`、`zh-Hant.po`

新增 16 条手动翻译，覆盖所有 WebDAV 相关的 UI 字符串（选择值、字段标签、对话框描述、按钮文本）。

---

## 配置说明

所有新增设置项归于 `DOWNLOADER` 组，在 `server.conf` 中形式如下：

```properties
# 下载存储后端
server.downloadStorageType = "WEBDAV"  # 默认: LOCAL ; 可选: LOCAL, WEBDAV

# WebDAV 连接配置（仅 downloadStorageType = WEBDAV 时生效）
server.webdavUrl          = "http://100.94.34.46:5005"
server.webdavUsername     = "用户名"
server.webdavPassword     = "密码"
server.webdavRemotePath   = "漫画"
```

已有设置 `server.downloadAsCbz` 继续生效：`true` 时章节保存为单个 `.cbz` 压缩包，`false` 时保存为逐页图片的文件夹。此设置与存储后端独立。

---

## 开发过程中修复的 Bug

| # | 症状 | 根因 | 修复方式 |
|---|------|------|----------|
| 1 | CBZ 流在调用方读取前已关闭 | `readFile()` 使用了 `execute(request).use{}`，导致 Response 及其流立即关闭 | 移除 `.use{}`，返回原始 `InputStream`，由调用方负责关闭 |
| 2 | 从本地切换到 WebDAV 重复下载章节时静默无输出 | `downloadImpl()` 检测到本地 `finalDownloadFolder` 已有页面文件后跳过下载，缓存为空，`handleSuccessfulDownload()` 无内容可上传 | Provider 的 `handleSuccessfulDownload()` 合并最终目录与缓存两处内容（见第 20 项） |
| 3 | Factory 缓存的 WebDAV 实例永不失效 | 缓存实例不随配置变化而更新 | 新增 `WebDavConfig` 数据类比对，配置变更时自动重建 |
| 4 | WebDAV 上 `deleteDirectory()` 非递归删除失败 | `DELETE` 对非空 WebDAV 集合返回 409 | 改为递归删除：先 `listFiles()` 列出子项 → 逐个删除 → 最后 `DELETE` 目录本身 |
| 5 | 每次 404 预检查都产生 WARN 级别堆栈日志 | OkHttp 的项目级 `Call.await()` 扩展对**所有**非 2xx 响应抛出异常 | 替换为原生阻塞 `client.newCall(request).execute()`，404 作为普通响应返回不抛异常 |
| 6 | 前端 `useTestWebDavConnection()` 使用了 `any` 类型 | 缺少内联 TypeScript 类型 | 新增 `TestWebDavConnectionMutation` 及变量类型 |
| 7 | `ChapterDownloadHelper` 混杂了 `File` 和 `DownloadStorage` 两套 API | 存在 `isWebDav` 标志分叉的 LOCAL/WebDAV 两条路径 | 统一为单一路径：两个 Provider 都接受解析后的 `DownloadStorage` 实例 |
| 8 | WebDAV 模式下 UI 仍显示「下载位置」设置项 | `TextSetting` 未根据存储类型做条件渲染 | 包裹为 `{storageType === DownloadStorageType.Local && (...)}` |
| 9 | `listFiles()` 返回空列表，导致无法删除/阅读 WebDAV 文件夹章节 | `parsePropfind` 正则 `<response>` / `<href>` 不匹配标准 WebDAV `DAV:` 命名空间（`<D:response>` / `<D:href>`） | 正则改为 `<(?:D:)?response>` 格式，同时匹配命名空间和非命名空间 XML |
| 10 | MKCOL 权限错误（403）被静默吞掉，`createDirectory` 返回成功但目录未创建 | `catch (e: Exception)` 吞掉了所有异常（含真正的权限/服务器错误） | 移除 try-catch，只在内层 `execute().use{}` 中接受 2xx / 405，其他异常自然传播 |
| 11 | `getAsArchiveStream` 下载的临时 CBZ 文件永不删除 | 返回 `localCbz.inputStream()` 后无清理逻辑 | 改为有界缓存 `RemoteCopyCache`（LRU 淘汰并删除文件），不再依赖 `deleteOnExit()` |
| 12 | `DownloadStorageType.from()` 未被任何代码调用 | GraphQL 解析走 `EnumSetting`，不需要手动反序列化 | 删除冗余方法 |
| 13 | 切换存储后端后，已下载章节全部无法读取 | `provider()` 只查询当前激活的后端，而 `isDownloaded` 是数据库标记、不随切换变化 | `provider()` 增加跨后端回退：激活后端没有内容时改用本地后端**读取**，写入仍指向激活后端 |
| 14 | LOCAL 文件夹模式每读一页都把整章图片复制到临时目录，且临时目录永不清理（磁盘无限增长） | 为了让 LOCAL 与 WebDAV 共用「先下载到本地」的读路径，牺牲了原本的零拷贝读取 | 接口新增 `localPathOrNull()`；本地后端直接返回真实路径，不再复制。远程后端改用有界缓存 |
| 15 | LOCAL CBZ 模式同样被复制到 `/tmp`，且缓存无上限 | `downloadCbzToLocal()` 对所有后端都走缓存 | 本地快路径命中真实文件；远程缓存由 `RemoteCopyCache` 限制条数并淘汰 |
| 16 | 删除不存在的章节从「成功」变为「失败」，且删除后遗留空的漫画/源目录 | `deleteFile()`/`deleteDirectory()` 对缺失目标返回 `false`；`FileDeletionHelper.cleanupParentFoldersFor()` 调用被移除 | 两个后端的删除改为幂等（缺失即成功）；新增 `cleanupEmptyParents()` 钩子恢复空目录清理 |
| 17 | WEBDAV 模式下 `downloadImpl()` 误判章节已完成，重新下载不会上传到 WebDAV | 读取回退让 `getImageCount()` 找到了旧后端的内容 | 新增 `existsInActiveBackend()`，`downloadImpl()` 按**写入后端**判定是否已完成 |
| 18 | `downloadsPath` 为空（默认值）时，LOCAL 模式把文件写到进程工作目录而非 `<dataRoot>/downloads` | 工厂使用了原始 `serverConfig.downloadsPath.value` 而不是带默认值回退的 `ApplicationDirs.downloadsRoot` | 工厂统一改用 `ApplicationDirs.downloadsRoot` 与 `mangaDownloadsRoot` |
| 19 | 选择 WEBDAV 但未填 URL 时，所有下载与读取都抛 `IllegalArgumentException` | `require(config.url.isNotBlank())` 在热路径上抛出 | 未配置 URL 时记录 WARN 并回退本地存储，前端同时给出提示 |
| 20 | 迁移/部分重下载场景上传的 CBZ 只有 ComicInfo.xml 没有页面（或文件夹模式丢失已有页面） | `handleSuccessfulDownload()` 只上传下载缓存；页循环跳过的页面留在最终目录里，不在缓存中 | 两个 Provider 的 `handleSuccessfulDownload()` 合并最终目录与缓存：ArchiveProvider 合并两处文件打包 CBZ，FolderProvider 把最终目录缺少的页面补进缓存再上传；同名冲突时缓存（较新）优先 |
| 21 | 切换存储后端后重新下载章节，旧后端的内容成为孤儿文件 | Provider 的 `delete()` 只从写入后端删除 | `delete()` 在读取后端与写入后端不同时同时删除两处，并各自清理空父目录 |
| 22 | WebDAV 服务器端目录被外部删除后，上传因 409 失败且目录缓存永不恢复 | `writeFile()` 遇 409 直接抛异常，`ensuredDirectories` 备忘录仍认为目录存在 | 409 时清空目录备忘录并抛出可读异常，下载器重试时自动重建目录；`deleteDirectory()` 同步移除被删目录的子目录备忘 |
| 23 | 部分服务器（如 Alist）返回大写 `D:` 命名空间的 PROPFIND，解析为空 | 正则只匹配小写 `d:` 前缀 | 正则改为 `<(?:[A-Za-z][A-Za-z0-9]*:)?response>` 形式，任意前缀与无前缀均匹配 |
| 24 | 文件名含 `+` 时 WebDAV href 解析后 `+` 变成空格 | `URLDecoder.decode` 把 `+` 当作编码的空格 | 解码前把字面 `+` 替换为 `%2B` |
| 25 | 每次创建 `WebDavDownloadStorage` 实例都新建一个 `OkHttpClient`（线程池泄漏） | 实例级 client 随配置变更不断创建 | 共享 companion 级 `SHARED_CLIENT`，实例复用 |

**第 13–19 项为切换功能的完整审查修复**，其中 13 是核心的用户可见缺陷（切换后旧章节无法阅读），14/15 是本次改动引入的 LOCAL 性能与磁盘回归。**第 20–25 项为第二轮审查（F1–F6）修复**：20/21 修复迁移与双后端删除的数据完整性缺陷，22–24 修复 WebDAV 协议兼容性，25 修复资源泄漏。

---

## 存储层单元测试

`server/src/test/kotlin/suwayomi/tachidesk/manga/impl/download/storage/`

| 测试类 | 用例数 | 覆盖内容 |
|--------|--------|----------|
| `StoragePathsTest` | 5 | 下载根前缀剥离、尾部斜杠、Windows 分隔符、根外路径、避免部分前缀误匹配 |
| `LocalDownloadStorageTest` | 8 | 写入/读回、缺失返回 null、`deleteFile`/`deleteDirectory` 幂等、`listFiles` 相对路径、`localPathOrNull`、空父目录清理 |
| `RemoteCopyCacheTest` | 6 | 未知键、签名匹配、签名变更失效、文件缺失失效、LRU 淘汰并删除文件、缓存条数上限 |
| `WebDavDownloadStorageTest` | 6 | MockWebServer 驱动：小写/无前缀命名空间 PROPFIND 解析、文件名 `+` 保留、目录删除后重建（备忘录失效）、409 后目录缓存重置、PUT 上传内容 |
| `ProviderDownloadTest` | 5 | 以真实 `download()` 入口驱动：迁移场景合并上传（CBZ 与文件夹两种模式）、同名冲突缓存优先、双后端删除 |

运行：`./gradlew :server:test --tests "suwayomi.tachidesk.manga.impl.download.storage.*"`

---

## 测试场景

| # | 存储方式 | 保存格式 | 预期结果 |
|---|----------|----------|----------|
| 1 | 本地 | CBZ | `.cbz` 文件写入 `downloadsPath/mangas/...` |
| 2 | 本地 | 文件夹 | `章节/` 目录及逐页图片写入 `downloadsPath/mangas/...` |
| 3 | WebDAV | CBZ | `.cbz` 文件上传至 `<webdavUrl>/<remotePath>/mangas/...` |
| 4 | WebDAV | 文件夹 | `章节/` 目录及逐页图片通过 MKCOL+PUT 上传 |
| 5 | 本地→WebDAV 重复下载 | 文件夹 | 合并最终目录已有页面与缓存后上传至 WebDAV |
| 6 | 本地→WebDAV 重复下载 | CBZ | 合并最终目录已有页面与缓存重建 CBZ，上传至 WebDAV |
| 7 | 测试连接按钮 | — | Toast 提示成功/失败及服务器返回信息 |
| 8 | 切换后阅读旧章节（仅本地存在） | CBZ / 文件夹 | WebDAV 模式下通过本地回退成功读取，页面正常返回 |
| 9 | 本地读取 | CBZ / 文件夹 | 直接读取真实文件，不产生 `/tmp` 副本 |
| 10 | WebDAV 未配置 URL | — | 记录 WARN 并回退本地存储，不再抛异常 |

### 已验证

- 14 个以上 CBZ 章节成功下载至 WebDAV，零异常。
- WebDAV 文件夹模式（非 CBZ）下载正常完成——页面成功上传，服务器端脚本自动打包为 `.cbz`。
- 本地 ↔ WebDAV 切换在 CBZ 和文件夹两种模式下均正常。
- 之前用本地模式下载过的章节重新下载时，正确回退到本地文件并上传至 WebDAV。
- F1–F6 修复后：338 项单元测试全部通过（含新增 11 项），ktlint 无违规；实机部署验证了读取、删除、完整下载→上传（CBZ 18 页 + ComicInfo）全链路。
- 部署要点：**`downloadsPath` 不要指向 WebDAV 存储的 rclone FUSE 挂载**（如 `/comics`）——那会让 LOCAL 与 WEBDAV 两种模式实际指向同一存储，且挂载的目录缓存/VFS 写缓存造成「本地」视图与服务器真实状态短暂不一致。保持 `downloadsPath` 为空（默认 `<dataRoot>/downloads`，容器数据卷真实磁盘）即可：LOCAL 写本地磁盘、WEBDAV 走 HTTP，两者独立；切换后端时本地副本作为读取回退与迁移源（合并上传）生效。此配置已实机验证：LOCAL 下载落数据卷、WEBDAV 下载上服务器、本地→WebDAV 迁移重下载（RX≈0、TX=CBZ 全量）、双后端删除均正常。

---

## 升级 / 合并注意事项

- **向后兼容**：`downloadStorageType` 默认值为 `LOCAL`，保持原有行为不变。
- **无新增依赖**：WebDAV 实现基于 OkHttp，已是项目现有依赖。
- **Settings proto 编号**：99–103，归属 `DOWNLOADER` 组，不与现有设置冲突。
- **前端 i18n**：英文源字符串在 `en.po` 中；17 种语言的翻译在各语言 `.po` 文件中。目前仅手动翻译了 `zh-Hans` 和 `zh-Hant`，其他语言回退显示英文。
- **`CUSTOM` WebUI 部署**：部署自定义 WebUI 静态文件时，**必须递增 `revision` 文件**（如 `r100000` → `r100001`），否则服务器会从内存缓存返回旧版 WebUI 文件。