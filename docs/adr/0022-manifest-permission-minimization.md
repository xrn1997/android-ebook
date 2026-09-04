# 清单权限最小化：逐条判据、保留项理由与两份清单的一致性

2026-09-04 评审后续清理定下：**应用只声明代码真正需要的权限**。合并后的 APK 清单里由本仓
声明的权限从 20 条降到 8 条（另有 1 条 AndroidX 自带的签名级
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，非本仓声明），并修掉两处"默认要求相机硬件"的
`uses-feature`。同时处理 AGENTS.md 明令禁止的一种状态：`module_me` 的独立清单带着集成清单没有的
四项存储权限。

## 背景

- 权限声明是 IDE 模板与旧功能一路堆上来的：登录页当年的「邮箱自动补全」留下
  `GET_ACCOUNTS`/`READ_PROFILE`/`READ_CONTACTS`，Wi-Fi 联调留下 `ACCESS_WIFI_STATE`/
  `CHANGE_WIFI_STATE`，旧 WakeLock 与定位试验留下 `WAKE_LOCK`/`ACCESS_FINE_LOCATION`/
  `ACCESS_COARSE_LOCATION`。这些 API 在本仓与依赖的 `lib_common`（AAR `io.github.xrn1997:common`）
  里都已无调用点。
- `READ_PROFILE` 是 `signature|privileged` 级——普通应用**永远拿不到**，纯噪声；
  `GET_ACCOUNTS` 在 API 26+ 早已不返回有用信息。
- 两条 `uses-feature android.hardware.camera*` 未写 `android:required`，默认即 `true`：
  等于向安装过滤系统声明"本设备必须有相机"，而本应用只是借系统相机拍照。
- 上一轮 review 曾报出 `QUERY_ALL_PACKAGES`、三条 `io.github.thewharf.*` 自定义权限与
  `<queries>` 块——**本仓源码清单与合并清单中都不存在这些条目**（`git log -S` 亦零命中），
  属误报；本轮据此未做删除动作，核对过程记录在下文。

## 判据（删一条的前提是"没有任何调用点"）

一条权限只有满足「全仓（含 `lib_common`）找不到需要它的 API 调用」才删。取证方式：

| 删除项 | 判它无用的依据 |
|---|---|
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | 全仓零 `WifiManager`；网络可用性判断走 `ConnectivityManager`（`lib_book_common` 的 `NetworkUtils` 与 `lib_common` 的 `NetworkUtil`） |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 全仓零 `LocationManager`/`FusedLocationProviderClient`/`requestLocationUpdates`（仅 `lib_ebook_api/assets/default_sources.json` 里第三方书源的 `location` 字段是 HTML 表单参数名，与 Android 定位无关） |
| `READ_PHONE_STATE` | 全仓零 `TelephonyManager`/`getDeviceId` |
| `WAKE_LOCK` | 全仓零 `PowerManager`/`newWakeLock`；前台服务本身不需要它 |
| `GET_ACCOUNTS` / `READ_PROFILE` / `READ_CONTACTS` | 全仓零 `AccountManager`/`ContactsContract`/`CommonDataKinds`；且这三项从不参与任何运行时申请（`shouldShowRequestPermissionRationale` 的两处调用只涉及导入与通知） |
| `CAMERA` | 零 `Camera`/CameraX/`ImageCapture`；拍照用 `ActivityResultContracts.TakePicture`（`ModifyInformationActivity.kt:110-112`）由系统相机应用持权限，本应用只需 FileProvider 授予落盘 Uri；裁剪是自己实现的 `ClipImageActivity`，不用第三方相机库 |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_VISUAL_USER_SELECTED` | 相册选取用 `ActivityResultContracts.PickVisualMedia`（`ModifyInformationActivity.kt:103,153`），Android 13+ 免权限、低版本回落 SAF 同样免权限；头像上传读的是 picker 授予的那条 content URI |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`（**仅 `module_app`、`module_find` 两处**） | 这两处本就不做文件导入；存储需求全部落在 `module_book` 的导入本地书链路上（见下表保留项） |

## 保留项（删了会真的坏）

| 保留项 | 为什么必须留 |
|---|---|
| `INTERNET`、`ACCESS_NETWORK_STATE` | 全部网络请求与网络状态判断。注：`lib_common` 的 AAR 清单**本就声明这两项**，会从依赖合并进来——本仓保留声明是为了"读得懂自己的清单"，删它只是把声明藏到别人家里 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC` | 离线下载前台服务，见 ADR-0018。**不得删除** |
| `POST_NOTIFICATIONS` | 下载常驻通知（`DownloadService`）与导入完成通知，API 33+ 运行时申请 |
| `MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE(maxSdk 32)` | **导入本地书**链路在用：`ImportBookActivity.kt:117/225` 判 `Environment.isExternalStorageManager()` 后直读文件树扫 epub/txt/pdf（ADR-0017）。这三项**只留在 `module_book` 两份清单**，不再散落到 `module_app`/`module_find` |

## 决策

1. **每个模块只声明自己那一份**：`module_book` 持有存储三项，`module_me` 一条都不声明，
   `module_app` 只留 `INTERNET`/`ACCESS_NETWORK_STATE`/`FOREGROUND_SERVICE` 三项。
   每项需求只写在真正使用它的那个模块清单里（存储三项归 `module_book`，头像相关一条都不需要），
   合并结果因此可读、可审，也不会因为某份清单长期没人回头看而偷偷留在权限面上。
2. **两份清单逐条一致**：`src/main/AndroidManifest.xml`（集成）与 `src/main/module/AndroidManifest.xml`
   （`isModule=true`）是替换关系，权限条目必须同步增删——本轮把 `module_me`/`module_find`/`module_book`
   三对清单都对齐了。
3. **相机特性显式 `required="false"`**：只在 `module_app` 声明一次（`camera` 与 `camera.autofocus` 两条），
   其余模块不再重复声明；这样既保留"有相机时体验更好"的信息，又不做安装过滤。
4. **不引入 `tools:node="remove"` 覆盖**：先删自己的声明，再用合并清单核对是否有库把条目加回来——
   实测**没有库重新贡献**被删的条目（见"验证"），因此不需要覆盖规则。将来若接入声明权限的第三方
   库（相机/图片裁剪类），必须重跑同一核对，必要时才加 `tools:node="remove"`。
5. **`READ_MEDIA_*` 不补回**：`lib_common` 的 `FileUtil` 里仍留有 `imageFileUri`/`videoFileUri`/
   `audioFileUri`/`sdCardPath` 这几个会查询 MediaStore 或拼外部存储路径的辅助函数，
   **本仓当前一个都没调用**（只用了 `generateFileName`/`privateFile`/`contentUri`）。
   将来若有代码改走那几个函数，需连同 `READ_MEDIA_*`/`ACCESS_WIFI_STATE` 一起重新评估——
   这是一处已知的"库内潜伏调用点"。

## 验证

- 合并清单核对（Agent 实测）：`:module_app:assembleRealDebug`/`:assembleMockDebug` 后读
  `module_app/build/intermediates/merged_manifests/*/AndroidManifest.xml`，两 flavor 一致，
  只剩 `INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、
  `MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE(maxSdk 32)`、
  `POST_NOTIFICATIONS` 八项 + AndroidX 自带的
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（库声明、签名级、无害；mock flavor 另带一条
  `DUMP`，来自调试期依赖，非本仓声明）。两条 `uses-feature` 均为 `required="false"`。
- 源码清单核对：`grep -rn "uses-permission|uses-feature" --include=AndroidManifest.xml`（排除 `build/`）
  已无 Wi-Fi/定位/电话/WakeLock/账号/联系人/相机/媒体条目。
- **人工装机验证项（Agent 止于构建，以下四条必须真机确认）**：
  1. 我的 → 头像 → 拍照改头像：期望系统相机正常拉起、拍完能进裁剪并上传成功
     （删 `CAMERA` 后若某些 OEM 上相机拉不起来，现象是点击"拍照"无反应或立即返回失败）；
  2. 我的 → 头像 → 相册选取：期望 Photo Picker 正常出图、能裁剪上传
     （删 `READ_MEDIA_*` 的失效现象是选完图后读取 Uri 被拒、上传报权限错误）；
  3. 导入本地书：期望"全部文件访问"授权页正常、能扫出并导入 epub/txt/pdf（保留项回归）；
  4. 离线下载：期望常驻通知正常、下载完成通知可点（`POST_NOTIFICATIONS` 与前台服务回归）。
  任一条失败即回退对应删除并在本 ADR 补记"该项其实被 X 路径需要"。

## 交叉引用

- ADR-0017：本地书多格式支持（存储三项保留的理由）。
- ADR-0018：离线下载前台服务（`FOREGROUND_SERVICE*` 不得删）。
- ADR-0021 权衡③：版本更新检查不申请任何新权限（公网 host 与 Android 17「本地网络」权限无关）。
- AGENTS.md「认证体系约定」段：本地联调一律 `adb reverse` + `127.0.0.1`，与本 ADR 一样
  遵循"不为便利扩权限面"的取向。
