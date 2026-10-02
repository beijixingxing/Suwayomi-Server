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
    suspend fun exists(path: String): Boolean           // 文件/目录是否存在
    suspend fun writeFile(path, content, size)          // 写入文件
    suspend fun readFile(path: String): InputStream?    // 读取文件
    suspend fun deleteFile(path: String): Boolean       // 删除文件
    suspend fun fileSize(path: String): Long            // 获取文件大小
    suspend fun listFiles(dirPath): List<StorageFile>   // 列出目录内容
    suspend fun createDirectory(dirPath: String)        // 创建目录（含父目录）
    suspend fun deleteDirectory(dirPath): Boolean       // 递归删除目录
    suspend fun move(from, to): Boolean                 // 移动/重命名
}
```

#### `LocalDownloadStorage.kt`
`server/src/main/kotlin/.../download/storage/LocalDownloadStorage.kt`

基于 `java.io.File` 的本地文件系统实现。所有阻塞操作通过 `withContext(Dispatchers.IO)` 执行。

#### `WebDavDownloadStorage.kt`
`server/src/main/kotlin/.../download/storage/WebDavDownloadStorage.kt`

基于 **OkHttp**（项目已有依赖）的完整 WebDAV 客户端。

| 方法 | WebDAV 动词 | 说明 |
|------|------------|------|
| `exists()` | `HEAD` | 2xx 返回 `true` |
| `writeFile()` | `PUT` | 自动通过 `MKCOL` 创建父目录；`contentLength` 带断言校验 |
| `readFile()` | `GET` | 返回原始 `InputStream`，由调用方关闭 |
| `deleteFile()` | `DELETE` | |
| `fileSize()` | `HEAD` | 读取 `Content-Length` 响应头 |
| `listFiles()` | `PROPFIND` Depth:1 | 正则解析 XML（同时支持 `<D:response>` 和 `<response>`） |
| `createDirectory()` | `MKCOL` | 逐段遍历路径创建缺失层级；接受 405（已存在），其他错误传播 |
| `deleteDirectory()` | `DELETE` | 先 `listFiles()` 递归列出子项并删除，再 `DELETE` 目录本身 |
| `move()` | `MOVE` | |

URL 构造使用逐段 `URLEncoder.encode()` 处理中日韩字符和特殊符号。

> **注意**：WebDAV XML 响应使用 `DAV:` 命名空间（如 `<D:response>`、`<D:href>`），`parsePropfind` 的正则已兼容此格式与无命名空间格式。未来可考虑改用项目已有的 `javax.xml.parsers.DocumentBuilderFactory` 进行标准 DOM 解析。

#### `DownloadStorageFactory.kt`
`server/src/main/kotlin/.../download/storage/DownloadStorageFactory.kt`

```kotlin
object DownloadStorageFactory {
    fun create(): DownloadStorage = when (serverConfig.downloadStorageType.value) {
        DownloadStorageType.LOCAL  -> LocalDownloadStorage(serverConfig.downloadsPath.value)
        DownloadStorageType.WEBDAV -> getWebDav()
    }
}
```

工厂方法对 `WebDavDownloadStorage` 实例做了缓存：当 WebDAV 配置（URL、用户名、密码、远程路径）发生变化时，通过 `WebDavConfig` 数据类比对自动失效并重建。

#### `StoragePaths.kt`
`server/src/main/kotlin/.../download/storage/StoragePaths.kt`

将本地绝对路径（如 `/comics/mangas/源名/漫画/章节.cbz`）转换为存储相对路径（`mangas/源名/漫画/章节.cbz`），通过剥离 `downloadsRoot` 前缀实现。

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
| `ChaptersFilesProvider.kt` | 新增 `resolveSourceFolder()` 受保护方法：优先返回页面缓存目录，缓存为空时回退到本地下载目录。 |
| `ArchiveProvider.kt` | 构造函数接受 `DownloadStorage`；`handleSuccessfulDownload()` 先在临时文件构建 CBZ，再通过 `storage.writeFile()` 上传；CBZ 本地缓存（`ConcurrentHashMap` + `Mutex`）避免阅读时逐页重复下载整个压缩包。 |
| `FolderProvider.kt` | 构造函数接受 `DownloadStorage`；`handleSuccessfulDownload()` 递归上传页面缓存到存储后端。 |
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
| 2 | 从本地切换到 WebDAV 重复下载章节时静默无输出 | `downloadImpl()` 检测到本地 `finalDownloadFolder` 已有页面文件后跳过下载，缓存为空，`handleSuccessfulDownload()` 直接返回 | 在 `ChaptersFilesProvider` 中增加 `resolveSourceFolder()` 回退方法：缓存为空时从本地下载目录读取文件上传 |
| 3 | Factory 缓存的 WebDAV 实例永不失效 | 缓存实例不随配置变化而更新 | 新增 `WebDavConfig` 数据类比对，配置变更时自动重建 |
| 4 | WebDAV 上 `deleteDirectory()` 非递归删除失败 | `DELETE` 对非空 WebDAV 集合返回 409 | 改为递归删除：先 `listFiles()` 列出子项 → 逐个删除 → 最后 `DELETE` 目录本身 |
| 5 | 每次 404 预检查都产生 WARN 级别堆栈日志 | OkHttp 的项目级 `Call.await()` 扩展对**所有**非 2xx 响应抛出异常 | 替换为原生阻塞 `client.newCall(request).execute()`，404 作为普通响应返回不抛异常 |
| 6 | 前端 `useTestWebDavConnection()` 使用了 `any` 类型 | 缺少内联 TypeScript 类型 | 新增 `TestWebDavConnectionMutation` 及变量类型 |
| 7 | `ChapterDownloadHelper` 混杂了 `File` 和 `DownloadStorage` 两套 API | 存在 `isWebDav` 标志分叉的 LOCAL/WebDAV 两条路径 | 统一为单一路径：两个 Provider 都接受解析后的 `DownloadStorage` 实例 |
| 8 | WebDAV 模式下 UI 仍显示「下载位置」设置项 | `TextSetting` 未根据存储类型做条件渲染 | 包裹为 `{storageType === DownloadStorageType.Local && (...)}` |
| 9 | `listFiles()` 返回空列表，导致无法删除/阅读 WebDAV 文件夹章节 | `parsePropfind` 正则 `<response>` / `<href>` 不匹配标准 WebDAV `DAV:` 命名空间（`<D:response>` / `<D:href>`） | 正则改为 `<(?:D:)?response>` 格式，同时匹配命名空间和非命名空间 XML |
| 10 | MKCOL 权限错误（403）被静默吞掉，`createDirectory` 返回成功但目录未创建 | `catch (e: Exception)` 吞掉了所有异常（含真正的权限/服务器错误） | 移除 try-catch，只在内层 `execute().use{}` 中接受 2xx / 405，其他异常自然传播 |
| 11 | `getAsArchiveStream` 下载的临时 CBZ 文件永不删除 | 返回 `localCbz.inputStream()` 后无清理逻辑 | CBZ 本地缓存方案接管文件生命周期，缓存文件通过 `deleteOnExit()` 及 remote-size 失效机制管理 |
| 12 | `DownloadStorageType.from()` 未被任何代码调用 | GraphQL 解析走 `EnumSetting`，不需要手动反序列化 | 删除冗余方法 |

---

## 测试场景

| # | 存储方式 | 保存格式 | 预期结果 |
|---|----------|----------|----------|
| 1 | 本地 | CBZ | `.cbz` 文件写入 `downloadsPath/mangas/...` |
| 2 | 本地 | 文件夹 | `章节/` 目录及逐页图片写入 `downloadsPath/mangas/...` |
| 3 | WebDAV | CBZ | `.cbz` 文件上传至 `<webdavUrl>/<remotePath>/mangas/...` |
| 4 | WebDAV | 文件夹 | `章节/` 目录及逐页图片通过 MKCOL+PUT 上传 |
| 5 | 本地→WebDAV 重复下载 | 文件夹 | 从已有本地目录读取页面，上传至 WebDAV（sourceFolder 回退） |
| 6 | 本地→WebDAV 重复下载 | CBZ | 从已有本地页面重建 CBZ，上传至 WebDAV（sourceFolder 回退） |
| 7 | 测试连接按钮 | — | Toast 提示成功/失败及服务器返回信息 |

### 已验证

- 14 个以上 CBZ 章节成功下载至 WebDAV，零异常。
- WebDAV 文件夹模式（非 CBZ）下载正常完成——页面成功上传，服务器端脚本自动打包为 `.cbz`。
- 本地 ↔ WebDAV 切换在 CBZ 和文件夹两种模式下均正常。
- 之前用本地模式下载过的章节重新下载时，正确回退到本地文件并上传至 WebDAV。

---

## 升级 / 合并注意事项

- **向后兼容**：`downloadStorageType` 默认值为 `LOCAL`，保持原有行为不变。
- **无新增依赖**：WebDAV 实现基于 OkHttp，已是项目现有依赖。
- **Settings proto 编号**：99–103，归属 `DOWNLOADER` 组，不与现有设置冲突。
- **前端 i18n**：英文源字符串在 `en.po` 中；17 种语言的翻译在各语言 `.po` 文件中。目前仅手动翻译了 `zh-Hans` 和 `zh-Hant`，其他语言回退显示英文。
- **`CUSTOM` WebUI 部署**：部署自定义 WebUI 静态文件时，**必须递增 `revision` 文件**（如 `r100000` → `r100001`），否则服务器会从内存缓存返回旧版 WebUI 文件。