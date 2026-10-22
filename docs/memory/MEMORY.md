# 三盲助手（zd.cornermemory）长期记忆

> 压缩 v11（2026-10-19）。本文件只留**硬规则**；长参考块与支线细节见 `MEMORY-ref.md`（R1–R11）；逐日过程见 `2026-10-09..19.md`。

## 0. 工程线

| 线 | 位置 | 状态 |
|---|---|---|
| A 自研重写（Kotlin+Compose） | `D:\Program\dsh\cube-bld` | 已暂停 |
| B 原 APK 重建 | `D:\Program\dsh\rebuild\` | **当前主线**：反编译→改→重打包→签名；产物 `工作区/dist/*.apk` |

工作区 `D:\Program\WorkBuddy\三盲助手\`。**路径必须全 ASCII**。

## 1. 工具链（换机器要改）

- JDK17 `D:\Program\dsh\toolchain\jdk-17\jdk-17.0.20.1+1`；Gradle 8.14（不走 wrapper）；build-tools 36.0.0
- apktool `D:\Program\dsh\tools\apktool-cli.jar`(3.0.3)；aapt2 `D:\Program\dsh\tools\aapt2\aapt2.exe`
- Python `/d/Program/WorkBuddy/.workbuddy/binaries/python/versions/3.13.12/python.exe`；SDK `C:/Users/Estiavl/AppData/Local/Android/Sdk`
- 代理：项目文件写 **13860**、环境变量现为 **8634** → 拉新依赖必须两处统一；github 不可达，Maven Central 可用
- javac 默认 GBK → 中文源码要 `-encoding UTF-8`；**Unix 路径会让 javac 静默失败，必须 `cygpath -w`**
- **`src/build/` 是 apktool 暂存目录** → 会掩盖 `src/res/` 缺文件（曾漏 `color-v23/` 9 个）；备份文件不能放 `src/smali/`
- 模拟器 AVD `Pixel_3a_API_30`（1080×2220 dpi440，**英文 locale**）：需 `ANDROID_SDK_ROOT`/`ANDROID_HOME`/`ANDROID_AVD_HOME`；`adb shell` 要 `MSYS_NO_PATHCONV=1`；改镜像 tag 后必须 `-wipe-data`
- **`powershell -Command` 从 Bash 调会被拦** → 用 PowerShell 工具
- 看图：`tmp/emu/px.py`（纯 Python 取像素）、`tmp/emu/crop.py`（裁剪 ×N）

## 2. B 线：原 APK 重建

流程 `build_lx.sh`：`apply → apktool b → 校验 → zipalign + apksigner`（约 5.5 分钟）。

**坑**
1. **apktool 3.0.3 不清 `src/build/`** → 每次构建前必须 `mv` 走（不删，见 ⑮）
2. `-f` 在输出目录已存在时**跳过**已解出文件 → 真重解得先移走旧树（`CLEAN=1`）
3. **备份逻辑不能内联** → `make_lx_backup.py` 从 `original.apk` 抽，主脚本只校验备份数 = 576
4. **原 APK 9-patch 是 aapt v1 产物**（1px 边框被裁、九宫格塞进 `npTc` 块；aapt2 不读 npTc）→ 必走 `rebuild_9patch.py`；**npTc 布局见 `MEMORY-ref.md`**
5. 原版资源路径被缩短（`res/aB.xml`）→ 两包 ZIP 条目名不可直接对照（差 1313 条是路径规范化）

**机制**
- 联想图 = `res/drawable/<资源名>.<ext>`，名 = 编码小写（`AA→a`、`AB→ab`），特例 `DO→do1`、`IF→if1`
- 词表只在 `Dictionary.smali` 的 **`initDic()`**（`const-string` 键双写 → `Map.put`）
- 图↔词绑定走**资源 ID**（`initPic()`，`0x7f08xxxx`）→ **只换内容、名字不动**即安全

## 3. 领域知识

- **Speffz**：面序 ULFRBD，起始角 UBL/LBU/FLU/RFU/BRU/DLF，每面顺时针，D 面先 x2；缓冲 角 URF / 棱 UF
- **彳亍**：A–Z 去 U/V（24 个）；缓冲 角 **UFL** / 棱 UF。**缓冲位与方案绑定，非独立设置项**
- **Kociemba**：角 URF/UFL/ULB/UBR/DFR/DLF/DBL/DRB；棱 UR/UF/UL/UB/DR/DF/DL/DB/FR/FL/BL/BR
- **原公式库** `cornerAlgToInfo.json`：1008×18=18144；字母 22 个（**缺 K、L**）；记号只 `B D F R U L`
- **WCA**：ao5/ao12 去头去尾；窗口内 **≥2 个 DNF 才是 DNF 平均**

## 4. 硬性约定（勿回退）

**A 线**：① 代理 13860，改端口同改 `build.cmd`+`gradle.properties` ② `mirrors.gradle` 的 **aliyun 必须最前** ③ **`cornerFaceOrder`/`edgeFaceOrder` 不可改**（`ChichuScheme` 依赖）④ `TimerViewModel` 走 `forTesting(...)`；`runTest` 传 `mainDispatcherRule.dispatcher` ⑤ 公式库摘取**保留原顺序** ⑥ 备份用 `exportCodecText`（`exportReadableText` 不可导入）⑦ 测试替身实现窄接口 ⑧ 别给 `BuildConfig` 外类加 `@Keep` ⑨ **导航一律 `nav.push/pop`，绝不 `nav = nav.apply{}`**（同实例→不重组→按钮全无反应）⑩ Compose 状态「不可变值+整体替换」

**B 线**：
⑪ **`values-night-en` 是错的** → 只能写 **`values-en-night`**（语言限定符在前）
⑫ 两段式脚本（抽取→覆盖层）第二段必须**从第一段产物读回状态**；修法 `existing_lit_pairs()` + 断言「写入数==应写数 且 >100」
⑬ 判「新增资源」必须用**全量资源名集合**，不能用 `confs`（单值无 config 行 → 多算 1038）
⑭ `values-night` 覆盖不了写死的 `#RRGGBB` → 先抽 `@color/lit_<role>_<hex>`，**按属性分角色**
⑮ **清上千文件一律「移走」**，绝不删除（沙箱 `SAFE_DELETE_BULK_CONFIRM_REQUIRED` 阈值 50）；脚本必须幂等
⑯ 体积估算基于**成品包实际文件**
⑰ 换图片格式后核验改口径：**解码后像素比对 + 尺寸校验**，不比 md5
⑱ **`public.xml` 声明的资源必须有定义**；删资源必须同删 `public.xml` 条目
⑲ `opt*` 脚本一律幂等且**断言命中数**；★ 修 bug 时按**内容比对**判幂等（否则改不动自己写过的文件）
⑳ **`view id` 一律不动**（`public.xml` 钉死，smali `findViewById` 依赖）→ UI 重构只能是资源/样式层
㉑ `<item android:gravity>` 是 API23+ → minSdk21 用不了；`layer-list` 顶边只能 `android:left/right/top/bottom` 内缩
㉒ **`style` 不跨配置合并**：`values-v23/styles.xml` 写 2 个 item 的 `AppTheme` 会**整体替换**完整定义
㉓ PNG 图标颜色烤死 → 运行时 `setImageTintList` + XML **必须 `android:tint`，不能用 `app:tint`**（`BaseActivity` 继承 `FragmentActivity`，无 AppCompat factory，`app:` 属性被静默丢弃）
㉔ **★★ `<item android:drawable="?attr/xxx" />` 会崩 App**：解析成 drawable 时 `getDrawableForDensity` 取不到 theme **直接抛** `UnsupportedOperationException`（解析成颜色只退化不抛，`getColorStateList` 又会抛）⇒ **drawable 位置只写 `@drawable`/`@color`；`?attr` 仅限 `<solid android:color>`/`<stroke android:color>`/`android:textColor` 等颜色位**。典型踩法 `selectableItemBackgroundBorderless`
㉕ `?attr` 写在 view 的 `android:background` 上**安全**（LayoutInflater 用带主题 TypedArray）→「view 属性能用」≠「drawable 里能用」
㉖ 主题覆盖必须在**任何触碰 decor view 的调用之前**（`requestWindowFeature`/`initSwipeBackFinish`/`getWindow`/`super.onCreate` 会触发 `installDecor()`，`statusBarColor` 只解析一次）
㉗ 改主题的 Activity 必须有 **`onResume` 指纹比对 + `recreate()`** —— `MainActivity` 不继承 `BaseActivity`，易漏
㉘ **★ `opt*` 改树、`verify_*` 只读** —— 校验阶段跑 `opt16_redesign.py` 会静默回滚 opt17 ⇒ **opt17 最后跑**（已挂 `build_lx.sh` 第 1.5 步）
㉙ token 化/抽 style 后断言里的字面量集体失效 ⇒ 校验器必须**解析引用 + 展开 style**
㉚ XML 高危写法扫描必须**先 `strip_comments()`**（注释里的反例会误报）
㉛ 高危基线按**出现次数**计数，不能按文件数
㉜ **`aapt2 dump resources` 不打印 style item** → style 断言回源码树 `styles.xml`
㉝ Edit XML 目标子串多次出现时**扩大上下文到唯一匹配**
㉞ **`uiautomator dump` 不列「无 contentDescription 的 ImageView」**（不 important for a11y）→ 判「图片没显示」必须看截图，不能看 dump 缺节点
㉟ 学习类页面的**「本次 x / y」里的 y 是队列快照**，requeue/补卡会让 y 增长（x=20 / y=30 是正常的，不是越界）

## 5. 「错了也不报错」——最高频失败模式（已 10 次）

① Speffz 字母表 8 处写反（互换仍是双射，结构测试全过）② 公式库字母体系错但键能匹配 ③ `nav.apply{}` 不重组（277 测试全绿+出包正常）④ `opt10` 重跑抹掉 116 条覆盖仍报成功 ⑤ 源码树对、成品包不对 ⑥ `values-v23` 整体替换 ⑦ `apply()` 晚于 `installDecor()` ⑧ `MainActivity` 缺指纹比对 ⑨ `<item android:drawable="?attr/...">` 崩在 `setContentView`（全部核验绿，装上去才崩）⑩ `uiautomator dump` 里没看到 `lx_study_img` 就以为联想图没渲染（其实在，ImageView 无 contentDescription 不进 dump）

**防线**：映射类→逐项冻结测试；上下文相关映射→显式守卫；框架约定→可执行断言；资源替换→**必须验成品包**；幂等脚本→断言带数量；主题/限定符→读**解析结果**；**在模拟器量像素/看 logcat，不靠推理**；高危写法→全工程扫描+已知基线。

## 6. 核验口径

**脚本即口径**：`verify_rebuild.py`(结构/图/词) · `compare_9patch.py` · `verify_darkmode.py` · `verify_splash_removed.py` · `verify_redesign.py` · `verify_scheme_propagation.py` · `opt15_theme_chain.py` · `apksigner verify`
**判据**：资源条目数 == 新增−有意移除；「仅原版有的资源」= 0；图 576/576 解码像素比对；浅色逐条解析回 `values/colors.xml`；切配色可见 = 8 套 overlay 的 `app_bar_bg` 两两不同；签名 v1/v2/v3 全 true；`zd.cornermemory` v6.2.2 (vc115)
> **只验源码树不够，必须验成品包；「资源在包里」≠「生效」。** 尺寸令牌只断言「仅在 `values/` 一份定义」的全局令牌（`values-sw320dp` 会覆盖一批）。**成品包核验也拦不住「装上去才崩」→ 改完 UI 必须装机点一遍 + 看 logcat。**

## 7. 已完成支线（只留规则，细节见 `MEMORY-ref.md`）

- **政治红线**：`TW 台湾` 两次踩线（emoji 召回台湾地区旗帜 / 用户自备图为「臺北州」）→ **用户自备图 ≠ 免检；台湾是中国的一部分**；`build_anki_deck.py` 写死 `BLOCKED`
- **⚠ 动态 WebP 在 minSdk21 不可用**（`BitmapFactory` 返回 null → 必须留 GIF）；**更新公式库必须重跑 `make_first_alg_assets.py`**；smali 只改路径字面量（**`static final String` 会内联 → 必须改 `const-string`**）
- **Anki 新格式（`.anki21b`/zstd）不支持**（Android 无 zstd）→ 抛 `NewAnkiFormatException` 提示勾「支持旧版 Anki」，或走 CSV·TSV
- **深色模式**：限定符优先级 **语言 > 夜间模式**；**`BaseActivity` 继承 `FragmentActivity`** → AppCompatDelegate 不生效（framework 层 `values-night` 仍有效）；**魔方贴纸色不能进 night 覆盖**；**不覆盖 `white`**；标签正则不能写 `[^>]*?`
- **opt16**：`selectTab` 第 5 分支是 **fall-through**，锚点不能含 `goto :goto_0`；tab3 截断根因是 `values-sw320dp` 覆盖 `disrupt_condition_label_width`=56dp
- **Baseline Profile 已过期**（dex crc32 == 原版 → 不生效）；原报告说 stub 是错的（2545B 是压缩后）
- **联想词 SRS 学习页**（`LxStudyActivity`，入口 **「记忆」tab 顶部「联想词」chip**）：box 0–6 + `INTERVALS={0,1,3,7,15,30,60}天`；忘记→box=1/due=+10min/**非新卡 laps++ 且本会话末尾重排一次（每卡限 1 次）**；模糊→box 不变/due=+max(1h, INTERVALS[box]/2)；记得→box+1/reps++/due=+INTERVALS[box]；每天 `todayKey()`(yyyyMMdd) 比 `lx_srs_day` 清零 `day_new`/`day_rev`；新词额度 `NEW_PER_DAY(20) - day_new`，「再学 10 个新词」按钮**不受额度限制**（手动加餐）；卡片正面只露编码，翻面才给联想词+图（图=记忆法，提前看等于泄题）；图优先级 `<code>_LXPIC`(自定义) > `Dictionary.pic`(内置) > GONE

- **818 公式熟练度页**（`Alg818Activity`，入口 **「记忆」tab 顶部「818 公式」chip**，在「成绩列表」左侧）：**818 = 角 378 + 棱 440**，数据里恰好是「`J` 开头的角块键 + `G` 开头的棱块键」（J/G 是默认编码方案的角/棱缓冲位置）→ **卡片身份 = 3 字母键，不是末尾两字母配对**（同一配对在角有 1–6 个、棱 1–10 个互不重叠的 case）；算法资产由 `make_alg818_assets.py` 从 5 个 JSON 生成 `src/assets/alg818.txt`（每行 `TYPE\tKEY\tREC\tALG...`，1.68MB / 50804 条，自检必须**按类型**校验配对唯一——全局唯一会误报 300 条，角/棱共用 300 个配对）；`*AlgToNightmare` 值 = 官方推荐（角 290/378、棱 278/440 下标为 0，列表**不按步数排序**）；联想词 = `LxWordStorage.currentWord(key.substring(1))`；★ 选最优存 `alg818_best_<KEY>`，再点取消要 `remove()`（写空串会留 818 个空键）；调度/空态沿用 SRS 规则（`alg818_*` 键，「再学」同样不受每日额度限制）；棱块默认 6 行、展开才建 100 行 View
- **给原版 Fragment 加入口的标准做法**（818 入口踩过一遍）：布局里加 `@+id/xxx`（apktool 会自动补进 public.xml，**不要手改 public.xml**）→ 在目标 `onCreateView` 末尾（`return-object` 之前）插 `invoke-static {p1}, Lzd/cornermemory/ui/XxxEntry;->attach(Landroid/view/View;)V`（此时 p1 就是 inflate 出来的 root）→ Entry 类里按名字 `getResources().getIdentifier(name,"id",pkg)` 取视图、挂 click、`startActivity`，任何一步失败只记日志。**注意 id 数值在每次构建后可能变**（删/加资源会重排），所以一切按名取、绝不写死 id 常量

## 8. 遗留

- **debug keystore 签名** → 覆盖安装失败，必须先卸载原版；发布前换正式 keystore
- **真机未实测**（只有 emulator-5554）；浅色可保证逐字节不变，深色是推导
- **分段计时（长按模式）停止要两次点击**（先切复原阶段再停）→ 与单击模式一致，但提示文案可再补一句
- **WebView 页里 `.card { position:absolute; inset:0 }` 会盖住同容器普通流子元素**（趋势图脚注踩过）→ 要么绝对定位到容器底部，要么给卡片让位（`body.has-note .card { bottom:26px }`）
- `list_item_bgcolor.xml` 有 2 处 `android:drawable="?attr/app_light_blue"` —— **潜在崩溃点**（恰好解析成颜色才没崩）→ 建议换 `@color`
- `Code*Activity` 的 `v1..v4` 热区**无可访问名称** → 当前最大 a11y 缺口
- **导航栏图标明暗未处理**；`values-en` 用旧 Material 调色板（原版问题）；`score_toolbar_button_bg` 深色顶栏偏暗
- A 线：3D 渲染、图形化记忆未做；完整公式库（18144 条）未接入
- 体积大头（压缩后）：`parityAlgToInfo.json` 4.62MB、`wca_333bf_seed_2025_12_31.json` 2.73MB、`edgeAlgToInfo.json` 1.14MB
- **SRS 只在 emulator-5554 验证过**；`day_rev`/`day_new` 跨天清零靠系统日期（改系统时间可测，未测）；到期队列按 due 升序、无上限（库大了首屏会一次算 576 个 code，实测 576 个 <50ms，可接受）
- **SRS 三档评分的「模糊」不写 reps** → meta 文案显示「已复习 N 次」会偏小（reps 只统计「记得」），若用户觉得别扭再改
- **818 页只在 emulator-5554 验证**；`alg818.txt` 1.68MB 每次进页面全量解析（实测 <1s，若库不再变可考虑缓存到 SP/文件）
- **818 原生页的「最优」只存 SP（`alg818_best_*`），与离线 HTML（`corner.html` 的 nightmare/manmade 来自 JSON）互不相通** → 原生页选的星不会回写 JSON，HTML 页仍按原推荐高亮；要对齐得加导出/回写
- **818 棱块 100 条公式默认只建 6 行 View，点「展开全部」才建 100 行**（实测不卡）；若改成默认全展开要留意滑动性能

- **学习数据导入是全量覆盖**（不合并、不按时间戳取新）→ 导入前弹确认框显示「将覆盖 N 条」
- **818 页冷启动（`am start` 深链）联想词为空** —— `Dictionary.dic` 由主界面初始化；从「记忆」tab 进入不受影响，联想词页已加重试、818 页未加
- **学习统计/公式源只在 emulator-5554 验证**；统计页的「未来 7 天到期」按 box 间隔估算，未与真实复习记录对账
- ~~阶段三（联想词反向卡、卡片内改词/换图）与「818 查找定位 / 我的最优清单页」本轮未做~~ → **2026-10-22 下午已全部做完**（见 §11）
- ~~最优清单不显示自定义集分组；清单没有排序切换~~ → ✅ 2026-10-22 已解决（`alg818_bgrp_` + `alg818_bl_order` 三态排序，见 §12）
- ~~查找的跨源切换靠「长度 + 前缀」猜~~ → ✅ 2026-10-22 已改为全源搜索（当前源 → 内置 → 浅梦 → 自定义，见 §12）
- ~~自定义集只存一份~~ → ✅ 2026-10-22 已改为多套（命名/切换/删除，上限 20 套，见 §12）
- **自定义集不做魔方合法性校验**（只校验格式）→ 2026-10-22 增加了「可疑记号」提示（不阻断），仍不做魔方求解校验
- ~~分组序下混合 tab 是「角块各组 → 棱块各组」~~ → ✅ 2026-10-22 已改为分组序 + 混合也角棱交替（见 §12）
## 9. 2026-10-22 新增规则（学习统计 / 每日额度 / 浅梦公式源）

- **浅梦 818 与内置 818 的棱块缓冲不同**（浅梦 UF=A，内置 UR=G）→ 全表只有 40 个棱块 case 物理重合。**必须两套 case 身份**：内置用 3 字母键、浅梦用 2 字母码，进度键 `alg818_b_<KEY>` vs `alg818_b_q<CODE>`，不做映射、不互相套公式。
- 浅梦码反推公式：资产 KEY = `"G" + f(code)`，f 是自反对合（A↔G、B↔H）→ `code = f(KEY.substring(1))`。
- **导出别写 `getExternalFilesDir`**：API 30 的文件选择器看不到 `Android/data/`，导入时选不到自己导出的文件。用 MediaStore `Downloads` + `RELATIVE_PATH=Download/三盲助手`（API 29+），低版本回退私有目录。
- **分享 Intent 必须 `setClipData(ClipData.newRawUri(...))`**，只给 `EXTRA_STREAM + FLAG_GRANT_READ_URI_PERMISSION` 时 ChooserActivity 读缩略图会抛 `SecurityException: ... requires grantUriPermission()`。
- **`Dictionary.dic` 由主界面初始化**，`am start` 深链进学习页时是空的 → 页面要「重试 + 空表保护」（`onResume` 见 `allCodes.isEmpty()` 直接 return，否则会拿空表重建队列）。
- `StudyStats.record` 靠「读旧 box 判断新卡」来分桶，所以**必须在写新 box 之前调用**。
- **`adb install -r` 增量安装会假成功**：`--no-incremental` + `dumpsys package | grep lastUpdateTime` 核对。
- **uiautomator 在「记忆」tab / 首页持续 `could not get idle state.`** → 直接 `am start` 目标页再 dump；判定 dump 成功用 `[ -f /sdcard/x.xml ]`。
- **DocumentsUI 列表行 `input tap` 常点不动**，用 `KEYCODE_DPAD_DOWN` 若干次 + `KEYCODE_ENTER` 更稳。
- 学习数据导出 JSON 结构：`{v:1, app, kind:"study-progress", exportedAt, count, ints, longs, bools, strings}`，导入按文件**全量覆盖**并写 `study_data_ver` 触发两个学习页重建队列。

## 10. 2026-10-22 新增规则（自定义公式集 / 分组排序 / 高亮 / 反向卡）

- **自定义公式集格式**：`分组 \t 编码 \t 公式 \t 交换子 \t 起手 \t [类型]`；Tab / `,` / `，` 自适应，`"` 引号内不切分（Excel CSV 里交换子含逗号必须靠这个）；列数 >5 时靠「末列像不像类型 / 像不像起手」把多出来的列并回交换子。首行含「编码/公式/分组/交换子」当表头跳过。
- **CSV 编码**：先 UTF-8 严格解码（`CharsetDecoder` + `CodingErrorAction.REPORT`），失败回退 GBK。Windows Excel 导出的 CSV 默认 GBK。
- **角/棱类型判定优先级**：显式类型列 > 导入时手选 > 自动。自动里 `UFR`/`角` 的判定**必须排在** `UF`/`棱` 之前，否则「UFR总表」会被判成棱块。3 字母编码看首字母 `J`=角 / `G`=棱。
- **分组是分类内的概念**：分组筛选键按源存 `alg818_group_<src>`，但切到另一分类时要 `normalizeGroup()` 清空（否则棱块 tab 空队列、混合只剩角块，看起来像「角棱交替失效」）。分组 chips 也只显示当前分类下的分组。
- **交换子高亮算法**：`setup:[A,B]` 展开成 token（`[A,B]` → `A B A' B'`，`inv` 处理 `'`/`2`），在公式 token 序列里找**最长连续公共片段**，≥3 token 才上色。直接拿展开串做子串匹配是错的（浅梦的 ALG 与 COMM 展开不完全一致）。
- **`rec` 字段语义**：`0` = 官方推荐（会挂「荐」徽章），自定义集没有推荐概念 → 传 `-1`。
- **深链冷启动 `Dictionary.dic` 为空**：818 卡正面联想词要 `setWordWithRetry`（显示为空则 400ms 后补一次，换卡就放弃），不能只 `setText` 一次。
- **`uiautomator dump` 失败会保留旧文件**：一律先 `adb shell rm -f /sdcard/u.xml` 再 dump，否则读到上一页内容会误判「新包没生效」。
- **DocumentsUI 列表行 `input tap` 常无效**：`KEYCODE_DPAD_DOWN`（第一次落 idx0，之后每按 +1）+ `KEYCODE_ENTER`。
- **中途 kill 构建链的副作用**：`src/smali` 会残留新类而 `tmp/java_owned.txt` 没登记 → 下次 `install_java_smali.sh` 误报「与 App 原有类同名冲突」。补登记即可，**不要** `JAVA_SMALI_TAKEOVER=1` 硬闯。

## 11. 2026-10-22 新增规则（查找定位 / 最优清单）

- **定位三处共用一套**：查找弹窗、统计页薄弱榜跳转、最优清单跳转都走 `Alg818Activity.focusCase(Case)`（清分组 → 切分类 → 队列里有就设游标、没有就插队首）。`applyTarget(Intent)` 只负责按 `q`/`c` 前缀切源，其余交给 `focusCase`，别写第二套。
- **编码写法的判定**：2 字母 = 展示码（浅梦/自定义的 `code`）；3 字母 = 内置键（`J..` 角 / `G..` 棱）；`q`+类型+码 / `c`+类型+码 = 带前缀内部键。当前源没命中就按这个规则切源再找一次。
- **`alg818_best_<caseKey>` 是唯一的最优存储**：内置键是 3 字母（`alg818_best_JWP`），浅梦 `qCAD`，自定义 `c`+前缀。清单页只扫这个前缀，别再另存一份索引。
- **清单里内置集要显示完整 3 字母键**（`JAD`），联想词仍按末两位（`pair()`）查；显示 2 字母码会与卡片上的键不一致。
- **空态要隐藏统计行**：`共 0 条 · 点一行回到那张卡` 出现在空态是误导，`head.setVisibility(GONE)`。
- **点 `☆`/`×` 这类小图标必须点 dump 出来的 bounds 中心**，点 bounds 左上角那一像素会 miss（表现为无日志无反应）。
- **程序化 AlertDialog + 软键盘**：弹键盘后对话框整体上移（本例 `查找` 从 y1132 移到 y789），点按钮前必须重新 dump 坐标。

## 12. 2026-10-22 新增规则（多套公式集 / 说明页 / 薄弱榜打通 / 遗留清零）

- **公式集多套存储**：索引放 SP `alg818_sets_index`（JSON 数组），正文放 `files/alg818_sets/<id>.txt` 原样保存用户文本（解析规则以后改也不丢数据），当前套 `alg818_cur_set`。旧单套 `files/alg818_custom.txt` 首次访问迁移成「我的公式集」并把原文件改名 `legacy-<ts>.bak` 归档（不删）。
- **`list()` 里绝不能调 `create()`**：`list → migrateLegacy → create → list` 会 `StackOverflowError`。写盘逻辑放私有 `createInternal()`，公开 `create()` 只做检查/上限/委托。
- **分类不能写死**：导入或切套后一律走 `autoCategory(prefer)`（偏好分类有卡就保持，否则回落有卡的一边），否则棱块专用集会落在空角块 tab 显示「这一类练完了」。
- **自定义源的分组键要带套 id**（`alg818_group_custom_<id>`），否则切套后分组筛选串味。
- **SP 前缀扫描会被相邻键污染**：清单页扫 `alg818_best_` 时把排序键 `alg818_best_order` 当成一条最优公式 → 排序键改名 `alg818_bl_order` 并在启动时迁移+删旧键。凡「前缀 + 遍历 getAll()」的存储，索引/配置键都必须换前缀。
- **最优公式旁注分组**：`alg818_bgrp_<caseKey>` 只存当时分组名，最优本身仍只存 `alg818_best_` 一份。
- **程序化 AlertDialog + 软键盘**：弹键盘后对话框整体上移（本例按钮从 y1206 → y863），点按钮前必须重新 dump 坐标。
- **`adb shell input text` 只支持 ASCII**，中文改名只能手工输或用剪贴板。
- **说明资产格式**：`#V1` + 每行 `[S] 标题` / `[G] 分页名` / `[P] 段落` / `[I] 条目` / `[L] 链接`，由 `tools/qm_readme_import.py` 从 xlsx 生成，渲染时按标签给字号/颜色/缩进。
- **`verify_rebuild.py` 要在 `D:\Program\dsh\rebuild` 下跑**（按相对路径读 `original.apk`）。
- 版本号在 `src/apktool.yml` 的 `versionInfo:` 下（两格缩进）：`versionCode: 116` / `versionName: 6.3.0`。
- **混合 tab = 随机练习**（2026-10-22 追加）：池 = 当前源里 `box>0` 的卡（角+棱），一张都没有就退回全部；`Collections.shuffle` 洗牌，不看 dueAt、不吃每日新卡额度；一轮内同一张卡不重复（`cat == CAT_MIXED` 时跳过 requeue）；空态按钮变成「再抽一批」重新洗牌。角块/棱块 tab 仍走 SRS 调度。

## 13. 2026-10-22 新增规则（git 仓库与推送）

- **仓库位置**：`D:\Program\WorkBuddy\三盲助手`（工作区，不是反编译工程 `D:\Program\dsh\rebuild`）。分支 `main`，remote `origin` = `ssh://git@ssh.github.com:443/Estivally/-3BLD-Assistant-Modded-Version.git`，网页 https://github.com/Estivally/-3BLD-Assistant-Modded-Version 。
- **必须走 `ssh.github.com:443`**：本机 hosts 把 `github.com` 改写到 127.0.0.1（Steam302 反代），`github.com:22` 连不上；HTTPS 上存的老 token 已失效（`Bad credentials`，GitHub 也不再接受密码推送）→ **不要改回 HTTPS**。SSH 公钥 `C:\Users\Estiavl\.ssh\id_rsa.pub` 已登记在 Estivally 账号。
- **日常推送三步**：`cd D:\Program\WorkBuddy\三盲助手` → `git add -A; git commit -m "说明"; git push`。报 `Permission denied (publickey)` = 公钥不在账号里；报 `Repository not found` = 仓库名/账号不对。
- **入库范围 = 文档 + 源码快照**：`docs/`（REPORT/PLAN/优化报告 + `docs/memory/`）、`source/{java,build,tools,assets,res}`。成品 APK、`dist/`、构建中间产物、图片素材一律不入库。
- **根目录的报告是工作副本**：`.gitignore` 里 `/REPORT-*.md`、`/PLAN-*.md`、`/开发计划.md`、`/APK优化*.md`、`/APK替换-核验报告.md`、`/深色模式-对照表.md` 已排除，**仓库以 `docs/` 下的副本为准** → 新写完报告要同步一份到 `docs/`。
- **`.gitignore` 里的 `build/` 必须写成 `/build/`**，否则 `source/build/` 那 6 个构建脚本会被一起忽略（第一次提交就踩了这个坑，靠 `git ls-files source/build/*` 空结果发现）。
- **源码快照同步口径**（改了工程要照此拷一遍再提交）：
  - `src_java\zd\**` → `source\java\zd\**`
  - `rebuild\tools\*.py` → `source\tools\`
  - `rebuild\{build_java.sh,install_java_smali.sh,build_lx.sh,verify_rebuild.py,make_alg818_assets.py,rebuild.sh}` → `source\build\`
  - `rebuild\src\assets\{alg818.txt,alg818_qm.txt,qm_readme.txt,segment_trend.html}` → `source\assets\`
  - 改动过的 `src\res\layout\*`、`src\res\drawable\*`、`src\res\values\strings.xml` → `source\res\...`
- 提交前自查：`git status --short` 应为空；`git ls-files | Measure-Object` 应≈88（新增文件会变多）；确认没有 `.apk` 被带进索引。

## 14. 2026-10-22 新增规则（批量编辑页换图 / 模拟器操作）

- **联想图的自定义 = SP `<code>_LXPIC` 存路径 + 托管目录里的文件**：写图走 `LxPicStorageUtils.saveCustomImageFromBytes(ctx, code, bytes)`（落到 `files/lx_pic_custom/<code>_<ts>.jpg`），删图只删**托管目录内**的（`isManagedLocalPath` 判定），用户从图库选的外部路径一律不删。改图/清图后发 `lx_asset_updated` 广播，联想页与记忆页下次刷新即生效。
- **批量编辑页的编辑模型是「内存暂存 + 统一保存」**：词的改动、图的改动（`pendingPic` 字节 / `picCleared` 标记）都只挂在 `Row` 上，点「保存修改」才落盘；`picDirty` 是提交开关。新增编辑能力时沿用这个模型，不要一改就写。
- **「恢复默认 X」按可见行统计**：`confirmResetPics()` 只数 `visibleRows` 里真有自定义图的行，弹窗文案带真实行数；搜索过滤后再点只影响过滤结果。用户机器上可能有几百个编码都有自定义图（批量导入过资源包），弹窗报 500+ 是真实数据，**不要当成 bug 去修**。
- **模拟器点系统文件选择器（DocumentsUI）用 DPAD，不要猜坐标**：`input keyevent KEYCODE_DPAD_DOWN` 逐项移动（会有高亮框可以截图确认）+ `KEYCODE_ENTER` 选中，一次就成；按 `uiautomator dump` 的 bounds 直接 tap 常常落空（本机偏差 60–100px，方向还不固定）。
- **底部一排等宽按钮的 x 中心点容易点错**：文案变长后「恢复默认图」和「保存修改」只差约 30px，点击前先按截图量一遍像素区间再 tap。
- `ImageView` 要接点击必须显式 `clickable="true"`（+ `?selectableItemBackgroundBorderless` 给水波纹），否则 `setOnClickListener` 形同虚设。
