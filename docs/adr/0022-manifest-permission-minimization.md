# 清单权限最小化：全仓声明从 20 条降到 8 条，每模块只留自己用的

应用只声明代码真正需要的权限。合并后的 APK 清单里由本仓声明的权限从 20 条降到 8 条（另有 1 条 AndroidX 自带的签名级 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，非本仓声明），同时修掉两处"默认要求相机硬件"的 `uses-feature`，并消除 `module_me` 独立清单携带集成清单没有的四项存储权限的不一致。

## 背景

- 权限声明是 IDE 模板与旧功能一路堆上来的：登录页当年的「邮箱自动补全」留下 `GET_ACCOUNTS`/`READ_PROFILE`/`READ_CONTACTS`，Wi-Fi 联调留下 `ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE`，旧 WakeLock 与定位试验留下 `WAKE_LOCK`/`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`。这些 API 在本仓与依赖的 `lib_common`（AAR `io.github.xrn1997:common`）里都已无调用点。
- `READ_PROFILE` 是 `signature|privileged` 级——普通应用**永远拿不到**，纯噪声；`GET_ACCOUNTS` 在 API 26+ 早已不返回有用信息。
- 两条 `uses-feature android.hardware.camera*` 未写 `android:required`，默认即 `true`：等于向安装过滤系统声明"本设备必须有相机"，而本应用只是借系统相机拍照。
- 上一轮 review 曾报出 `QUERY_ALL_PACKAGES`、三条 `io.github.thewharf.*` 自定义权限与 `<queries>` 块——**本仓源码清单与合并清单中都不存在这些条目**（`git log -S` 亦零命中），属误报；本轮据此未做删除动作。

## 决策

删权限的唯一前提：全仓（含依赖的 `lib_common`）找不到需要该权限的 API 调用。取证方式见下表：

**删除项**：

| 删除项 | 判它无用的依据 |
|---|---|
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | 全仓零 `WifiManager`；网络可用性判断走 `ConnectivityManager`（`lib_book_common` 的 `NetworkUtils` 与 `lib_common` 的 `NetworkUtil`） |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 全仓零 `LocationManager`/`FusedLocationProviderClient`/`requestLocationUpdates`（书源规则里第三方站点的 `location` 字段是 HTML 表单参数名，与 Android 定位无关；规则一律由用户导入，应用的 assets 里没有任何书源清单） |
| `READ_PHONE_STATE` | 全仓零 `TelephonyManager`/`getDeviceId` |
| `WAKE_LOCK` | 全仓零 `PowerManager`/`newWakeLock`；前台服务本身不需要它 |
| `GET_ACCOUNTS` / `READ_PROFILE` / `READ_CONTACTS` | 全仓零 `AccountManager`/`ContactsContract`/`CommonDataKinds`；且这三项从不参与任何运行时申请 |
| `CAMERA` | 零 `Camera`/CameraX/`ImageCapture`；拍照用 `ActivityResultContracts.TakePicture` 由系统相机应用持权限，本应用只需 FileProvider 授予落盘 Uri；裁剪是自己实现的 `ClipImageActivity`，不用第三方相机库 |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_VISUAL_USER_SELECTED` | 相册选取用 `ActivityResultContracts.PickVisualMedia`，Android 13+ 免权限、低版本回落 SAF 同样免权限 |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`（仅 `module_app`、`module_find` 两处） | 这两处本就不做文件导入；存储需求全部落在 `module_book` 的导入本地书链路上 |

**保留项**：

| 保留项 | 为什么必须留 |
|---|---|
| `INTERNET`、`ACCESS_NETWORK_STATE` | 全部网络请求与网络状态判断。`lib_common` 的 AAR 清单本就声明这两项，本仓保留声明是为了"读得懂自己的清单"，删它只是把声明藏到别人家里 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC` | 离线下载前台服务（`DownloadService` 声明 `foregroundServiceType="dataSync"`，缺任一项服务无法合法前台化），**不得删除** |
| `POST_NOTIFICATIONS` | 下载常驻通知与导入完成通知，API 33+ 运行时申请 |
| `MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE(maxSdk 32)` | **导入本地书**链路在用：`ImportBookActivity` 判 `Environment.isExternalStorageManager()` 后直读文件树扫 epub/txt/pdf——本地书多格式导入需要整目录直读，没有免权限的系统选取路径可替代。这三项**只留在 `module_book` 两份清单**，不再散落到其他模块 |

**结构规则**：

1. **每个模块只声明自己那一份**：`module_book` 持有存储三项，`module_me` 一条都不声明，`module_app` 只留 `INTERNET`/`ACCESS_NETWORK_STATE`/`FOREGROUND_SERVICE` 三项。每项需求只写在真正使用它的那个模块清单里，合并结果因此可读、可审，也不会因为某份清单长期没人回头看而偷偷留在权限面上。
2. **两份清单逐条一致**：`src/main/AndroidManifest.xml`（集成）与 `src/main/module/AndroidManifest.xml`（`isModule=true` 独立态）是替换关系，权限条目必须同步增删——本轮把 `module_me`/`module_find`/`module_book` 三对清单都对齐了。
3. **相机特性显式 `required="false"`**：只在 `module_app` 声明一次（`camera` 与 `camera.autofocus` 两条），其余模块不再重复声明；这样既保留"有相机时体验更好"的信息，又不做安装过滤。
4. **不引入 `tools:node="remove"` 覆盖**：先删自己的声明，再用合并清单核对是否有库把条目加回来——实测**没有库重新贡献**被删的条目（见落地状态），因此不需要覆盖规则。将来若接入声明权限的第三方库（相机/图片裁剪类），必须重跑同一核对，必要时才加 `tools:node="remove"`。

## 落地状态

合并清单核对（Agent 实测）：`:module_app:assembleRealDebug`/`:assembleMockDebug` 后读 `module_app/build/intermediates/merged_manifests/*/AndroidManifest.xml`，两 flavor 一致，只剩 `INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE(maxSdk 32)`、`POST_NOTIFICATIONS` 八项 + AndroidX 自带的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（库声明、签名级、无害；mock flavor 另带一条 `DUMP`，来自调试期依赖，非本仓声明）。两条 `uses-feature` 均为 `required="false"`。

源码清单核对：`grep -rn "uses-permission|uses-feature" --include=AndroidManifest.xml`（排除 `build/`）已无 Wi-Fi/定位/电话/WakeLock/账号/联系人/相机/媒体条目。

## 遗留

`lib_common` 的 `FileUtil` 里仍留有 `imageFileUri`/`videoFileUri`/`audioFileUri`/`sdCardPath` 这几个会查询 MediaStore 或拼外部存储路径的辅助函数，**本仓当前一个都没调用**（只用了 `generateFileName`/`privateFile`/`contentUri`）。将来若有代码改走那几个函数，需连同 `READ_MEDIA_*`/`ACCESS_WIFI_STATE` 一起重新评估——这是一处已知的"库内潜伏调用点"。
