# 三盲助手 6.2.2 —— 源码优化建议

分析对象：`三盲助手_6.2.2.apk` 反编译源码（apktool 3.0.3 反编译树 + jadx 1.5.6 还原的 Java）  
分析日期：2026-10-11  
反编译产物：`D:\Program\dsh\rebuild\jadx_out\`（应用自有代码 167 个 Java 文件 / 810 个类）

---

## 0. 体检结论

| 维度   | 现状                                                | 结论                                       |
| ---- | ------------------------------------------------- | ---------------------------------------- |
| 架构   | Kotlin + AndroidX + 协程 + greenDAO                 | 现代，结构清晰                                  |
| 代码组织 | `ui` / `fragment` / `utils` / `adapter` / `db` 分层 | 合理；但 `TimerFragment` 单文件 5397 行          |
| 体积   | 38 MB（74% 是离线数据资产）                                | 有 **约 12–15 MB** 可压缩空间                   |
| 启动   | 有 Splash + 后台预载 + baseline.prof                   | 设计思路对，但几处实现拖后腿                           |
| 发布就绪 | **targetSdk 33**                                  | ❌ **2026-08-31 起 Google Play 强制 API 36** |
| 安全隐私 | 43 个 Activity 仅 1 个导出、有 networkSecurityConfig     | 大体收敛得好；有 4 处需补                           |
| 无障碍  | 109 个图片控件无 `contentDescription`                   | ❌ 明显缺失                                   |
| 深色模式 | `AppTheme` 硬编码 Light，无 `values-night/colors.xml`  | ❌ 不支持                                    |
| 国际化  | 86 个语言变体，但**布局里 247 处硬编码中文**                      | ⚠ 翻译覆盖不到                                 |

---

## P0 · 阻断发布

### 0.1 targetSdk 33 → 36（Android 16）

```
当前：compileSdk 33 / targetSdk 33 / minSdk 21
要求：2026-08-31 起，Google Play 新应用与更新必须 targetSdk ≥ 36
```

现在（2026-10）若走 Google Play，**这个包无法提交**。改动：

```kotlin
android {
    compileSdk = 36
    defaultConfig { targetSdk = 36 }
}
```

升上去要处理 Android 14/15/16 的行为变更，重点是：

- **前台服务类型**必须显式声明（本应用有蓝牙计时器，属 `connectedDevice`）
- **精确闹钟**需 `SCHEDULE_EXACT_ALARM` 或改用非精确闹钟（若计时器用 `AlarmManager`）
- `READ_EXTERNAL_STORAGE` 在 33+ 已失效 → 应改 `READ_MEDIA_IMAGES`
- `WRITE_EXTERNAL_STORAGE` 在 30+ 已失效 → 应走 MediaStore / SAF

### 0.2 签名仍是 debug keystore

发布前必须换正式 keystore（此项已知，仅作提醒）。

---

## P1 · 体积（可省约 12–15 MB）

### 1.1 576 张联想图 JPEG → WebP　【实测可省 8.2 MB】

这是最大的一块。当前 `res/drawable/` 下 576 张 JPEG：

| 格式         |           体积 |  相对 JPEG |
| ---------- | -----------: | -------: |
| 原 JPEG（现状） | **16.65 MB** |        — |
| WebP q80   |      8.48 MB | **−49%** |
| WebP q90   |     13.02 MB |     −22% |

（上表是本次对全部 576 张实际转码测出来的，不是估算）

这 576 张是 512×512 的图，实际显示在卡片里通常不超过 400dp。  
**建议 q85 WebP**：肉眼无差别，省 ~5–6 MB。

注意事项：

- 资源名不能变（`initPic()` 按资源 ID 绑定，见 `APK替换-核验报告.md`），只改扩展名
- 转换后要在 `build_lx.sh` 的替换流程里同步改（现在按 `.jpg` 找图）
- `res/drawable/` 里还有别的 png/webp，可一并转

### 1.2 `rotate2.gif` 3.45 MB → 动态 WebP　【实测可省 2.2 MB】

`res/drawable/rotate2.gif`：1280×720、31 帧、**3.45 MB**，是 `res/` 里最大的单个文件。

| 格式          |          体积 |       省 |
| ----------- | ----------: | ------: |
| 原 GIF       |     3.45 MB |       — |
| 动态 WebP q85 | **1.24 MB** | **64%** |
| 动态 WebP q75 |     0.95 MB |     72% |

加载处在 `zd/cornermemory/ui/RoatePicActivity.java:17`，用的是  
`Glide...asGif().load(R.drawable.rotate2)` —— **转 WebP 后必须同时改代码**，  
`asGif()` 只认 GIF，要换成 `Glide...asDrawable()`（Glide 自带 `WebpDrawable` 解码器）。

`splash.gif`（0.68 MB / 864×1536 / 52 帧）转 WebP 收益小（q75 只省 0.14 MB，q85 反而变大），  
**建议保留 GIF**，或降分辨率重做。

### 1.3 Bugly + Tinker 是死代码　【约 150–212 个类】

排查结果：

- 全工程**没有任何 `Bugly.init()` / `initCrashReport()` 调用**
- 清单里**没有 `BUGLY_APP_ID` 之类的 meta-data**
- 但 `com/tencent/` 下有 150+ 个类，清单里还声明了  
  `com.tencent.bugly.beta.tinker.TinkerResultService`

推断：这是**蒲公英 SDK 的传递依赖**带进来的，且从未启用。

同时注意：`network_security_config.xml` 只对 pgyer 域名放开明文，而 Bugly 的上报端点是  
`http://android.bugly.qq.com` / `http://rqd.uu.qq.com` —— 在 targetSdk ≥ 28 下  
**明文默认被拦截**，所以即便启用也是坏的。

> ⚠ 附带一个反编译陷阱：`Graph.java` / `GraphicLineRenderer.java` 里出现了  
> `TinkerReport.KEY_LOADED_MISMATCH_DEX` 这种引用，看着像依赖 Tinker，  
> 其实是**常量值碰撞**——原代码自己的 `static final int` 被内联后，  
> jadx 把字面量匹配到了 Tinker 里同值的常量。**不要据此认为图形模块依赖 Tinker。**

**建议**：评估是否还需要蒲公英 SDK。若不需要（自用/社区分发），去掉后  
可减少 150–250 个类、去掉热更新能力（也去掉 `REQUEST_INSTALL_PACKAGES` 权限）、  
去掉一个隐私采集点。

### 1.4 两套图片加载库并存

| 库       |  类数 | 实际用途                                |
| ------- | --: | ----------------------------------- |
| Glide   | 327 | `SplashActivity`、`RoatePicActivity` |
| Picasso |  38 | `PicassoUtil`（用户自选图）                |

两者功能重叠。Glide 更强（支持 GIF / WebP / 生命周期绑定），**建议统一到 Glide**，  
删掉 Picasso + `PicassoProvider`（清单里那个 `<provider>` 也能去掉）。

### 1.5 其他零碎

- `acitivity_query.xml` —— **确认无引用的废弃布局**（无 `ActivityQuery` 类，无任何代码/布局引用），可删
- `android.support.v4` 遗留约 183 个类（来自 `BGASwipeBackHelper`、`grantor` 等老库）
- `res/raw/s4.mp3 ~ s20.mp3` 共 17 个（0.1 MB）—— 体积小，但 `SoundPool.load()` 一次性加载 17 个，见 P2.4

---

## P2 · 性能

### 2.1 ⭐ 公式查询在锁内做 I/O + 全量线性扫描

**文件**：`zd/cornermemory/utils/ReadSolutionLookup.java:479`（`readFirstAlgorithmFromInfo`）

```java
synchronized (map) {
    if (map.containsKey(str3)) return map.get(str3);
    String firstArrayValue = readFirstArrayValue(context, str, str2);  // ← 锁内做磁盘 I/O
    synchronized (map) { map.put(str3, firstArrayValue); }             // ← 多余的嵌套锁（可重入）
    return firstArrayValue;
}
```

而 `readFirstArrayValue` 每次都 **`open()` 资源 + 从文件头开始线性扫 JSON**，  
直到撞上目标 key 才停。最坏情况（key 在文件末尾）要读完整整  
`parityAlgToInfo.json` 的 **31 MB**。

后果：

- 查公式时若缓存未命中，**所有线程串行排队**在同一个锁上
- 一次"读解法"要做 ~20 次查询，首次打开页面会明显卡
- 抛异常时不写缓存 → 失败会反复重扫

**改法**（按收益排序）：

1. **把 I/O 挪出锁**：先查缓存 → 未命中则锁外读取 → 再 put（用 `ConcurrentHashMap` + `computeIfAbsent`，或对同一 key 做去重）
2. **缓存整个映射**：这些文件是静态数据，可在后台线程**一次性解析成 `Map<String,String>` 常驻**  
   （`parityAlgToInfo.json` 31 MB 解析后约 2–4 万个条目，内存 ~5–10 MB，可接受），  
   比每次扫文件快几个数量级
3. 退一步也要**把大文件切成索引**：预生成 `key → 文件偏移` 的小索引，用 `FileChannel.position()` 直接跳转

顺带：`MAX_INFO_CACHE_SIZE = 256` 声明了但没用（`removeEldestEntry` 里写死 `256`）；  
`readFirstAlgorithmFromInfo` 里层那个 `synchronized (map)` 是多余的。

### 2.2 联想图在主线程解码

**文件**：`zd/cornermemory/ui/LxPicActivity.java:246`

```java
private void showDefaultImage(String str) {
    Integer num = Dictionary.pic.get(str);
    if (num != null) {
        this.img.setImageResource(num.intValue());   // ← 主线程同步解码
```

`setImageResource` 会在 UI 线程同步解码。512×512 的图 → 约 **1 MB Bitmap + 20–40 ms** 解码，  
切换编码时必然掉帧。同文件 275 行还有一处（预览弹窗）。

**改法**：改用 Glide/Picasso 并指定目标尺寸：

```java
Glide.with(img)
     .load(resId)
     .override(imgWidth, imgHeight)   // 或 .fitCenter()
     .into(img);
```

### 2.3 `PicassoUtil` 不限制尺寸 → 大图 OOM 风险

**文件**：`zd/cornermemory/utils/PicassoUtil.java`（全文 22 行）

```java
Picasso.get().load(str).placeholder(...).error(...).into(imageView);
```

没有任何 `.resize()` / `.fit()` / `.centerInside()`。用户从相册导入的照片可能是  
4000×3000 → 单个 Bitmap 约 **48 MB**（ARGB_8888），几张大图就能 OOM。

**改法**：`.fit().centerInside()`，并加 `.memoryPolicy(MemoryPolicy.NO_CACHE)` 视情况而定。

### 2.4 启动时全量预载所有分组成绩

**文件**：`zd/cornermemory/ui/SplashActivity.java:175`（`preloadRemainingGroups`）

```java
for (int i2 = 0; i2 < length; i2++) {
    if (i2 != i && !App.getInstance().isGroupLoaded(i2)) {
        addGroupToCache(cjDao, map, i2);      // 每个分组全量 list() 进内存
    }
}
```

`addGroupToCache` 会把该分组的**全部成绩记录**读进 `App.allCjMap` 常驻内存，  
并为每个分组算一遍统计。成绩多的用户（几千条）会：

- 内存里长期压着全量数据
- 启动后台线程持续占 CPU

**改法**：只预载当前分组 + 邻近 1–2 个（`preloadPriorityGroups` 已经这么做了），  
其余改为**按需加载**（`ensureGroupLoadedAsync` 已具备这个能力）。

### 2.5 主线程上的初始化

| 位置                          | 内容                                                             | 成本                         |
| --------------------------- | -------------------------------------------------------------- | -------------------------- |
| `App.java:57-64` `onCreate` | `ProbabilityUtil.init()`                                       | 构造几十~上百个 `Probability` 对象  |
| 同上                          | `CoordinateUtil.initCoordinateValues()`                        | 21 个对象                     |
| `MainActivity.java:100-103` | `Dictionary.initDic/initPic/initFormulaCorner/initFormulaEdge` | **约 3300 次 `HashMap.put`** |

单看每项都不大（合计可能 30–80 ms），但都压在启动路径上。  
`Dictionary` 的四个初始化尤其适合**改成懒加载**（`static` 持有 + `synchronized` 单次初始化，  
或直接在后台线程预热），反正 `initPic()` 要等用户真进联想图页才有用。

### 2.6 baseline profile 是**真 profile**，但已过期

> ⚠ **本节原结论「基本是空的 / 是个 stub」已更正（2026-10-11 实测）。**

把 `assets/dexopt/baseline.prof` 按格式解开看，它是**货真价实的 profile**：

```
偏移 17 起 zlib 流 → 解出 19956 B
  版本标记 0x000b，类数 136，方法数 6410，dex 文件数 1
  dex 位置 "classes.dex"（长度 11，偏移 16）
  类/方法计数 4392 / 61058
```

2545 B 只是 **zlib 压缩后**的体积，解压后接近 20 KB —— 对 136 个类来说体量正常。
`baseline.profm`（185 B）也在。

**真正的问题是它已经对不上 dex 了**：profile 偏移 8 处存着一个 **u32 dex crc32**，
值是 `0x0c26b618`，正好等于**原版** `classes.dex`（9,196,772 B）的 crc32；
而重建版 dex 是 9,203,440 B / crc32 `0xd80eb27e`。**对不上 → profile 实际不生效**
（系统仍会读，但匹配不到 dex，不会崩，只是白搭）。

**改法**：用 Baseline Profile Gradle Plugin 重新生成（`generateBaselineProfile`，
在真机上走一遍主流程）。前置条件是 Gradle 源码工程 + `androidx.benchmark` + 真机/模拟器 ——
**本项目当前只有反编译树，且本机 `adb devices` 为空，做不了**。
已在《APK优化-实施报告.md》§5 记为遗留风险。

### 2.7 杂项

- `TimerFragment.java:1733, 1950` 用裸 `new Thread(...)`，而同文件已有  
  `wcaPickerExecutor` / `mHandler` —— 线程管理不统一，建议收敛到 Executor
- `SoundPool(10, 3, 5)` 在 `onCreateView` 里创建并 `load()` 17 个音频  
  （已在 `onDestroyView` 正确 `release()`，但每次切换 Tab 重建视图就会重新加载一遍）
- `ReadSolutionLookup.toStandardCode()` 每次调用都**遍历整个 Map** 找反查项，  
  可以预先构建反查表（`char → code`）

---

## P3 · 安全与隐私

### 3.1 `allowBackup="true"` 且没有备份规则

清单里 `android:allowBackup="true"`，但**没有 `fullBackupContent`，也没有  
`dataExtractionRules`**（Android 12+ 要求）。

后果：`adb backup` / 云备份会把应用私有数据（greenDAO 数据库 = 全部成绩记录、  
SharedPreferences = 用户编码设置）整体带出。对成绩记录类应用属中等风险。

**改法**：要么 `allowBackup="false"`，要么显式声明备份范围：

```xml
<application
    android:allowBackup="true"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules">
```

（`<exclude domain="sharedpref" path="..."/>` 排除敏感项）

### 3.2 权限可精简

| 权限                                                                                   | 评估                                                     |
| ------------------------------------------------------------------------------------ | ------------------------------------------------------ |
| `READ_PHONE_STATE`                                                                   | **建议删除** —— 无明显用途，属敏感权限                                |
| `READ_LOGS`                                                                          | **建议删除** —— 对第三方应用本就无效，只增加审核摩擦                         |
| `ACCESS_FINE_LOCATION` / `COARSE_LOCATION`                                           | 若只为 Android 12+ 蓝牙扫描，**建议删除**，改用 `neverForLocation` 标志 |
| `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE`                                   | Android 10+ 已失效，建议改 MediaStore / SAF 后移除               |
| `REQUEST_INSTALL_PACKAGES`                                                           | 只被蒲公英/热更新用到，**去掉蒲公英即可删**                               |
| `BLUETOOTH` / `BLUETOOTH_ADMIN`                                                      | 仅 `maxSdkVersion="30"` 需要                              |
| `INTERNET` / `NETWORK_STATE` / `WIFI_STATE` / `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | 合理，保留                                                  |

若确实需要定位来扫蓝牙，可在清单里加：

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
```

这样就不必申请定位权限。

### 3.3 缺少 `<uses-feature>` 声明

清单里**一个 `<uses-feature>` 都没有**。而应用声明了 `BLUETOOTH` 权限 ——  
在 Google Play 上，`BLUETOOTH` 权限会**隐式推导**出  
`<uses-feature android:name="android.hardware.bluetooth" />` 且 `required=true`，  
**导致没有蓝牙的设备（部分平板/模拟器）看不到这个应用**。

**改法**：显式声明为非必需：

```xml
<uses-feature android:name="android.hardware.bluetooth" android:required="false" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

### 3.4 明文 HTTP 端点

`network_security_config.xml` 只对 `www.pgyer.com` / `app-global.pgyer.com` 放开了明文，  
但代码里还硬编码了：

```
http://android.bugly.qq.com/rqd/async      ← Bugly 上报
http://rqd.uu.qq.com/rqd/sync              ← Bugly 上报
http://collecter.pgyer.com/                ← 蒲公英采集
```

前两个不在白名单内 → **在 targetSdk ≥ 28 上会被系统拦截**（即 Bugly 实际是坏的，  
印证 P1.3）。`collecter.pgyer.com` 未在白名单 → 同样被拦。

**改法**：删掉 Bugly（见 P1.3）；蒲公英若保留，把它所有端点都列入白名单，  
或直接升级到 HTTPS。

### 3.5 硬编码密钥（遗留）

清单 meta-data 里明文存着：

```
PGYER_API_KEY     = 6ea4ffab5fcddcc11ef2360aaf98c6d4
PGYER_FRONTJS_KEY = 0b86fa6288d92a951dcdbcfda1344892
```

反编译即可取得。建议移到服务端 / 用白盒加密，或干脆去掉蒲公英。

---

## P4 · 体验 / 无障碍 / 代码质量

### 4.1 不支持深色模式

- `AppTheme` 的 parent 是 `Theme.AppCompat.Light.NoActionBar`（**硬编码 Light**）
- `values-night/` 里只有 androidx 自带的 `styles.xml`，**没有 `colors.xml`**
- 实测：`background` / `text_primary` / `text_secondary` / `colorPrimary` 在 night 下**全部未覆盖**

系统切到深色模式时，应用仍然是浅色。

**改法**：

1. 主题改 `Theme.AppCompat.DayNight.NoActionBar`（或迁到 Material3 `Theme.Material3.DayNight`）
2. 新增 `values-night/colors.xml`，覆盖那 6 个语义色
3. 布局里的硬编码颜色（如 `#f8f9fb`、`#fafafa`）要换成 `@color/` 引用

### 4.2 无障碍：109 个图片控件缺 `contentDescription`


在应用自有布局中扫描，**109 个 `<ImageView>` / `<ImageButton>` 没有 `contentDescription`**，  
而 `contentDescription` 在整个 `zd` 包里只出现在 1 个 Java 文件中。

底部 5 个 Tab 用的就是 `ImageButton`（`mTab1Img`~`mTab5Img`），  
TalkBack 用户听到的是"按钮"，无法区分。

**改法**：

- 有交互的图片（Tab、工具栏按钮）→ 加 `android:contentDescription="@string/xxx"`
- 纯装饰的 → 加 `android:importantForAccessibility="no"`（比留空更明确）

### 4.3 国际化：布局里 247 处硬编码中文

应用支持 **86 个语言变体**，`values/strings.xml` 有 743 条（696 条含中文），  
但**布局文件里硬编码了 247 处中文**，翻译系统完全覆盖不到：

| 文件                                                  | 硬编码条数 |
| --------------------------------------------------- | ----: |
| `activity_motify_help.xml`                          |    20 |
| `activity_agreement.xml`                            |    19 |
| `activity_graphic.xml`                              |    16 |
| `activity_privacy.xml`                              |    15 |
| `dialog_score_manage.xml`                           |    14 |
| `tab1_ce.xml` / `tab1_corner.xml` / `tab1_edge.xml` |  各 12 |

例如 `acitivity_code.xml` 里的 `android:text="角块编码复习"`。  
切到英文/日文时这些文字仍显示中文。

**改法**：全部抽到 `strings.xml`，用 `@string/xxx` 引用。

### 4.4 布局文件名拼写错误

4 个文件把 `activity` 拼成了 **`acitivity`**：

```
acitivity_code.xml          （被 R.layout.acitivity_code 引用）
acitivity_code_corner.xml   （被引用）
acitivity_code_edge.xml     （被引用）
acitivity_query.xml         （无引用 → 可直接删）
```

前 3 个改名会牵动代码，建议**下次重构时一起改**；第 4 个是废弃文件，直接删。

### 4.5 其他代码味道

- `TimerFragment.java` **5397 行**、`BaseCodeActivity.java` **2976 行**、  
  `bean/Cube.java` **4189 行** —— 建议按职责拆分
- `Dictionary` 的 `dic` / `pic` / `formula_corner` / `formula_edge` 是  
  **`public static` 可变 `HashMap`**，任何地方都能改。建议改成  
  `private static final` + 只读访问器（或 `Collections.unmodifiableMap`）
- `ReadSolutionLookup.MAX_INFO_CACHE_SIZE = 256` 声明未使用（见 P2.1）
- 状态字段用 `int` 常量（`STARTUP_STATUS_IDLE = 0` 等）而非 `enum`，易错

---

## 建议执行顺序

> **进度（2026-10-11）**：第 2、3、4 项已完成（见《APK优化-实施报告.md》§2）；
> 第 6、7、8 项已完成（§8.1–§8.6）；第 9 项经核实**是误判**（profile 是真的，只是过期，
> 见 §2.6）；第 1 项按用户决定不做（不发布 Google Play）；第 5 项未做。

| 优先级   | 事项                                                 | 预期收益        | 改动量           | 状态 |
| ----- | -------------------------------------------------- | ----------- | ------------- | --- |
| **1** | targetSdk 33 → 36                                  | 解除发布阻断      | 中（要处理行为变更）    | ⏸ 不做（不发布 GP） |
| **2** | 576 张图转 WebP q85                                   | **−5~6 MB** | 小（改转换脚本）      | ✅ 完成（实际 −2.55 MB） |
| **3** | `rotate2.gif` → 动态 WebP                            | **−2.2 MB** | 小（改 1 行 + 转码） | ✅ 完成（改为「GIF 重编码」，动态 WebP 在 API 21–27 不显示） |
| **4** | 修 `readFirstAlgorithmInInfo` 锁内 I/O                | 公式页明显变快     | 小（改 1 个方法）    | ✅ 完成（真实病因是主线程线性扫描，非锁） |
| **5** | 补 `dataExtractionRules` / 删多余权限 / 加 `uses-feature` | 过审 + 隐私     | 小（改清单）        | ⬜ 未做 |
| **6** | 移除 Bugly/Tinker、统一图片库                              | 减类数 + 减体积   | 中             | ✅ 完成（−0.22 MB dex，290 个 smali 出包） |
| **7** | 联想图改 Glide 异步加载 + `PicassoUtil` 加 resize           | 消掉卡顿与 OOM   | 小             | ✅ 完成（14 文件 / 16 处） |
| **8** | 深色模式 + `contentDescription` + 抽硬编码字符串              | 体验/无障碍/国际化  | 大（但可分批）       | ✅ 完成（+120 CD / 269 字符串 / 394 字面色） |
| **9** | 接入 Baseline Profile                                | 冷启动提升       | 中             | ⚠ 需重生成（见 §2.6，需真机 + 源码工程） |

新增（本次分析中发现，建议后续处理）：

| 优先级 | 事项 | 说明 |
| ----- | --- | --- |
| **A** | 可点击非文字控件的无障碍名 | `Code*Activity` 布局里的 `v1..v4` 热区 `View` 没有可访问名称，TalkBack 读不出来。这是当前最大的 a11y 缺口 |
| **B** | `values-en` 用旧 Material 调色板 | 英文设备上品牌色变 `#3f51b5`、文字变纯黑。**原版就有**，本次只补了暗色对应层 |
| **C** | `twistsAlgToInfo.json` 派生文件 | 2.56 MB 仍走主线程线性扫描，与 P2.1 同一模式 |
| **D** | WebView 页面不跟随深色 | `assets/` 里 8 个 HTML 仍是浅色 |
| **E** | 状态栏/导航栏图标明暗 | 深色下建议 `windowLightStatusBar=false` |

---

## 附：本次分析用到的工具链（可复现）

```bash
# 1) apktool 反编译（已存在）
D:\Program\dsh\rebuild\src\

# 2) jadx 反编译为 Java
#    jadx 从 Maven Central 拉取（GitHub 不可达）
python fetch_jadx_deps.py                 # 解析依赖树
java -cp @jadx_libs/cp5.txt jadx.cli.JadxCLI \
     -j 8 --no-res -d jadx_out original.apk
# 需要额外补 2 个 jar（解析器漏掉的）：
#   logback-core-1.5.38.jar   ← jadx-cli 硬依赖 ch.qos.logback.classic.Level
#   并设置 JADX_TMP_DIR / -Djava.io.tmpdir 到项目内目录（否则写系统 temp 被拒）
```

产物：

- `jadx_out/sources/zd/cornermemory/**` —— 应用自有代码（167 个 Java 文件）
- `fetch_jadx_deps.py` / `jadx_libs/` —— 依赖解析脚本与 jar
