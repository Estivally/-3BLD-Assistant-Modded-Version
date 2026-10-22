# 三盲助手 · 长参考块（按需查阅）

> 从 `MEMORY.md` 拆出来的「不常查但查起来很贵」的参考块。
> **`MEMORY.md` 是必读的硬规则；本文件是实现细节与历史。**

---

## R1. 原 APK 的 9-patch：npTc 字节布局

**背景**：原 APK 的 9-patch 是 **aapt v1** 产物。v1 会把 1 像素边框**裁掉**，把九宫格信息塞进
自定义 PNG 块 **`npTc`**；而 **aapt2 只认边框像素、不读 npTc**。所以 apktool 原生解码会**丢内边距**，
必须走 `rebuild_9patch.py` 从 npTc 重建。

**布局（对照 112 个文件 100% 验证）**：

```
@0   byte×4    was_deserialized, numXDivs, numYDivs, numColors
@4   u32 小端  xDivsOffset（恒 32）
@8   u32 小端  yDivsOffset
@12  u32×4     内边距，顺序 (左, 右, 上, 下)   ← 不是 (左, 上, 右, 下)！
@28  u32 小端  colorsOffset
@xDivsOffset   numXDivs 个 u32 **大端** → xDivs
@yDivsOffset   numYDivs 个 u32 **大端** → yDivs
```

**关键**：偏移是**小端**、数组本身是**大端**（v1 只对数组做 `htonl`）。

**aapt2 侧的校验口径**：四角**全透明**、边框标记**不透明黑**、尺寸 ≥3×3。

**核验脚本**：`rebuild_9patch.py`（重建）、`compare_9patch.py`（比对可拉伸区间 x/y + 内边距，
当前 112/112 语义一致）、`inspect_9patch.py`、`dump_border.py`、`border_to_divs.py`。

---

## R2. jadx 反编译工具链（可复用）

```bash
# 1) 依赖解析（Maven 坐标是 io.github.skylot，不是 com.github.skylot）
python D:/Program/dsh/rebuild/fetch_jadx_deps.py      # → jadx_libs/*.jar + classpath.txt
# 2) 手工补解析器漏掉的 jar：logback-core-1.5.38.jar
#    （jadx-cli 硬依赖 ch.qos.logback.classic.Level）；slf4j-simple 仅排错时需要
# 3) 跑
JADX_TMP_DIR=... JADX_CONFIG_DIR=... JADX_CACHE_DIR=... \
java -Xmx6g -Djava.io.tmpdir=D:/Program/dsh/rebuild/tmp \
     -cp "$(cat jadx_libs/cp5.txt)" jadx.cli.JadxCLI -j 8 --no-res -d jadx_out original.apk
```

**坑**：
1. `tools/jadx-cli.jar`（64 KB）**不是 fat jar**，没主清单属性，跑不了。
2. **缺 SLF4J provider 时 jadx 静默 `exit 1`** —— 错误走 logger，NOP 就啥都看不到。排错先塞 `slf4j-simple`。
3. jadx 启动要往**系统 temp 写 native 库，被沙箱拒绝** → 必须设
   `JADX_TMP_DIR`/`JADX_CONFIG_DIR`/`JADX_CACHE_DIR` + `-Djava.io.tmpdir` 到项目内。
4. 结果：2117 类，75 失败（可忽略）；应用自有代码 167 个 Java 文件。

**⚠⚠ 两条推翻先前结论的硬事实**
- **jadx 会误重建 `synchronized` 的作用范围**（异常处理器切分监视器区间时）。
  `ReadSolutionLookup.readFirstAlgorithmFromInfo` 的 Java 看着 I/O 在锁内，
  原始 smali 里 `monitor-exit` 在调用**之前** → **I/O 本来就在锁外，是标准双检锁**。
  → **判断锁范围必须读 smali，不能信 jadx。**
- **常量值碰撞**：`Graph.java` 里出现 `TinkerReport.KEY_LOADED_MISMATCH_DEX` —— 看着像依赖 Tinker，
  其实是 jadx 把内联字面量匹配到同值常量。
  → 判断「某库是否真被使用」要 grep **调用点**（`Bugly.init()`），不能只看常量引用。

---

## R3. 优化 5–12 明细

- `opt5_remove_tencent.py`：Bugly / Tinker → `dropped/tencent`（`libtiny.so` 保留）。
- `opt6_unify_image_lib.py`：Picasso → `dropped/picasso`（`showPicture` 内部换 Glide）。
- `opt7_async_mnemonic_images.py`：联想图异步加载。
- `opt8_content_description.py`：补 `contentDescription`。
- `opt9_extract_strings.py`：抽字符串（`verify_strings.py` 核验）。
- `opt10_darkmode.py`：394 个字面色 → 182 个 `lit_*`。**默认干跑**。
- 全部幂等；`verify_darkmode.py` 判「浅色逐字节等价」。

---

## R4. opt11 主题与外观：实现细节

- **168 个颜色 → 主题属性** `app_<原名>`（`attrs.xml` 声明；`app_*` **不在 `public.xml`**）。
- **8 个覆盖样式** `ThemeOverlay.App.Scheme.<scheme>[.Night]`，`parent=""`（绝对值，不继承）。
  4 套方案（indigo / teal / violet / graphite）× 深浅。
- `AppTheme` 里写 `<item name="app_x">@color/x</item>` **恒等映射**作为默认值。
  ★ **3 个 AppTheme 变体（`values/`、`values-en/`、`values-en-night/`）都要插默认值。**
- ★ **`?attr/` 用在 `res/color/` 需 API 23+** → 主题感知的颜色状态列表放 `res/color-v23/`。
- `ThemeManager`：`wrap()` 改 uiMode（深色模式要它）／`apply()` 应用 overlay／`currentKey()` 指纹。
- ★ 这 8 个 overlay 在 `aapt2 dump resources` 里是**非 PUBLIC 条目**（行尾没有 ` PUBLIC`）。
- 相关脚本：`opt11_palette.py`、`opt11_apply.py`、`opt11_freeze.py`、
  `verify_scheme_propagation.py`（28 条，含「8 套取值两两不同」）。

---

## R5. opt13 UI 重设计 Stage A：实现细节

- 三个渐变 drawable（`toolbar_gradient` / `bottom_nav_gradient` / `background_page_gradient`）
  → `layer-list` / 纯色。
- 顶栏底栏文字改主题属性。
- `MainActivity.resetImgs` / `selectTab` 里 **10 处写死色** → `ThemeManager.themeColor()`，
  **10 处 `setImageTintList`**（PNG 里烤死的 `#afb5bd` / `#1878eb` / 纯白不跟主题）。
- `CustomToolbar` 默认标题色 `-1`（白）→ 主题色。
- ★ **必须用 `android:tint` 不能用 `app:tint`**（`BaseActivity` 继承 `FragmentActivity`，
  没有 AppCompat LayoutInflater factory，`app:` 命名空间的属性被**静默丢掉**）—— opt14 又查出 8 处。
- ★ **死资源不删**：`back_layout` / `toolbar_modern` / `item_card_modern` 是
  `@id/back_layout` / `@id/toolbar` / `@id/card_title` 的**唯一定义处**，删了 aapt2 报
  `no definition for declared symbol`。
- 脚本：`opt13_redesign.py`。

---

## R6. opt14 配色跟随：实现细节

**用户反馈**：「只改变了用户设置里的 ui，其他 ui 根本没变化」→ 追问后确认是「**配色不跟着变**」。

★ **根因**：opt13 把两块方案色渐变换成了恒白的 `app_surface`，而 **4 套浅色配色的 `app_surface`
全是 `#ffffff`**、`app_background` 都近似 `#f8f8f8` → 切配色时全 app 只剩 17 个文件会变。

**做法**：
1. 新增 `app_bar_bg` / `app_bar_text` 两个主题属性 → 8 套 overlay 各赋值
   （浅色 = 该配色深调 + 白字；深色 = 暗调表面 + 该配色浅调字）。
2. `toolbar_gradient` 改纯色 `?attr/app_bar_bg` —— **改这一个文件 = 47 个页面的顶栏一起变**
   （`view_custom_toolbar.xml` 被 47 个布局 `include`）。
3. `colorPrimaryDark` → `?attr/app_bar_bg`，让状态栏与顶栏同色；`windowLightStatusBar=false`。
4. ★ **`app_bar_bg` 必须写进 AppTheme 本体，不能放 `values-v23/`**
   （`style` 资源不跨配置合并 → 会整体替换完整定义）。
5. ★ 顶栏三件套都要改：标题、返回图标、右上角 `ic_score_manage`
   （原本是深 slate，在深色顶栏上看不见）。

**结果**：8 套对比度 **5.47–11.07**（全部通过 WCAG 4.5）。
脚本：`opt14_scheme_propagation.py`（10 步，全部带命中数断言）、`verify_scheme_propagation.py`。

---

## R7. opt15 主题链修复：实现细节

**定位过程（真模拟器量像素）**：
- 主页顶栏 `#4f46e5`、状态栏 `#4f46e5`；**主题设置页**顶栏 `#4f46e5` 但状态栏 `#303f9f`（两边不一样！）
- 在主题页点「紫玫瑰」→ 该页顶栏变 `#6d28d9` ⇒ **`applyStyle` 本身是好的**
  （推翻了「overlay 没生效」的猜测）
- 返回主页 → 顶栏仍是 `#4f46e5` ⇒ **主页根本没重建**；logcat 确认没有 MainActivity 重启
- 主题页那排彩色色块是 smali 里**写死的 `PREVIEW` 表**，所以「设置页好看」什么也证明不了

**两个独立根因**：
1. `BaseActivity.onCreate` 里 `apply()` 调用得太晚（在 `super.onCreate()` 之后）。
   `requestWindowFeature()` / `initSwipeBackFinish()` / `getWindow()` 任何一个都会让 `PhoneWindow`
   走 `installDecor()`，而 `android:statusBarColor` 从 `?attr/colorPrimaryDark` **只解析一次**、定死。
   → 修法：把 `getTheme()` + `ThemeManager.apply()` 提到 `onCreate` **方法最前**。
   ★ **`MainActivity` 没有这些前置调用，所以它的状态栏反而是对的** —— 这个「不对称」是定位线索。
2. `MainActivity` 缺 `onResume` 指纹比对（它不继承 `BaseActivity`，重复实现时漏了）。
   → 补 `themeKey` 字段 + `onResume` 比对 + `recreate()`。

**实测修复后**：主题页状态栏 `#303f9f` → `#6d28d9`；切青碧 → `#0f766e`；回主页立即 `#0f766e`。

脚本：`opt15_theme_chain.py`（幂等，**17 条断言**；`verify()` 每次运行都执行，与是否打了补丁无关）。

---

## R8. opt16 UI 体系 v2：实现细节

**用户诉求**：「算了还是按照你自己的 ui 设计重构代码吧」（放弃增量修补）。范围限定**核心流程**。

**六件事**（`opt16_redesign.py`，幂等，27 条断言）：

1. **修 tab3 标签截断**：8 个条件标签被截成「编码长…」。根因 = `values-sw320dp` 把
   `disrupt_condition_label_width` 覆盖成 **56dp**，而标签是固定宽度 + `ellipsize="end"`。
   （`values-sw320dp` 才是绝大多数手机命中的那份；模拟器 sw=393dp 也命中它。）
   → 改 `wrap_content`，从根上不可能截断。
2. **组件尺寸**：`toolbar_height` 50→56dp（M3 small app bar）、`bottom_nav_height` 56→64dp。
3. **底栏选中指示条**（三个新 drawable）：
   - `bottom_nav_indicator.xml`：`layer-list` + 3dp 圆角条，颜色 `?attr/app_colorPrimary`
     （**颜色位置，安全**）；靠 `android:bottom` 内缩 `bottom_nav_indicator_inset`
     = `bottom_nav_height - 3dp` 顶到栏顶（**不能用 `<item android:gravity>`，那是 API 23 才有的**）。
   - `bottom_nav_tab_ripple.xml`：自写 `<ripple android:color="#26808080">`，**不用 `?attr`**
     （RippleDrawable 的 `android:color` 走 `getColorStateList`，同样会抛）。15% 中性灰在浅色/深色
     底栏上都看得见。
   - `bottom_nav_tab_bg.xml`：`selector`，**一个 `?` 引用都不留**。选中 →
     `layer-list[bottom_nav_tab_ripple, bottom_nav_indicator]`；未选中 → `bottom_nav_tab_ripple`。
   - `bottom.xml` / `layout-land/bottom.xml` 各 5 处背景替换；`MainActivity` 补 **10 处 `setSelected`**
     （`resetImgs` 5 置 false + `selectTab` 5 置 true）。
     ★ `selectTab` 第 5 个分支（`mFrag1`）是 **fall-through** 到 `:goto_0`，**锚点不能含 `goto :goto_0`**。
4. **圆角现代化**：`radius_sm` 4→8dp、`radius_md` 8→12dp（`lg/xl` 不变）。
5. **触控目标 ≥48dp**：动作/条件按钮 36~42→48dp；chip 类 30~36→44dp。
6. **字号收敛**：核心 21 个布局里把 8/9/11.5/15/17/22/27/32sp 吸附到规范档。
   ★ 规范档**必须含 11 / 28 / 56**，否则 11sp 会被吸到 10sp（31 处）、54/58sp 会被吸到 40sp。

### ★★ 首次构建的崩溃（本次最大的坑）

装上模拟器一点就闪退，logcat：

```
Caused by: java.lang.UnsupportedOperationException:
    Failed to resolve attribute at index 0: TypedValue{t=0x2/d=0x7f0400f3 a=-1}
  at TypedArray.getDrawableForDensity(TypedArray.java:997)
  at StateListDrawable.inflateChildElements(StateListDrawable.java:182)
Caused by: Resources$NotFoundException: File res/drawable/bottom_nav_tab_bg.xml ...
  at LayoutInflater.parseInclude → createViewFromTag → MainActivity.onCreate(:105)
```

写的是 `<item android:drawable="?attr/selectableItemBackgroundBorderless" />`。
**根因与完整推理见 `MEMORY.md` §4㉔㉕。**

**为什么工程里其它 selector 一直没事**（诊断的关键反例）：
- `edit_text_bg` / `option_border_selector` / `timer_input_field_bg` / `lx_pic_primary_button_bg` 等
  **14 个 selector** 的 `?attr` 都在 `<solid android:color>` / `<stroke android:color>` 位置 → `getColor`，不抛。
- `list_item_bgcolor.xml` 把 `?attr/app_light_blue` 写在 `android:drawable` 上也没崩 ——
  因为 `app_light_blue` 解析成**颜色**。
- 原来 `bottom.xml` 直接把 `?selectableItemBackgroundBorderless` 写在 **view 的 `android:background`**
  上是好的：view 属性走 LayoutInflater 的**带主题** TypedArray，`applyStyle` 会把 `?attr` **彻底解析掉**。

**两条防线（都已落进脚本）**：
1. `opt16_redesign.py` 的 `verify()`：局部断言 + **全工程扫描** `android:drawable="?attr/...`，
   已知基线 2（`list_item_bgcolor`），超基线即失败。
   ★ 扫描前必须 `strip_comments()` —— 否则注释里的示例文本会误报（我自己先踩了一次）。
2. `verify_redesign.py` 的成品包判据：`bottom_nav_tab_bg.xml` 的 xmltree 里 **`=?` 出现 0 次**。

---

## R9. 尺寸令牌与过期的核验基线

- `toolbar_height` 的版本史：`优化版.apk`(10-11) 本来就是 **56dp** → opt13 压到 **50dp**
  → opt16 提回 **56dp**。所以 `verify_redesign.py` 里 `== "50.000000"` 是 opt13/14 那条基线的残留，
  已改成 56dp。
- 断言尺寸令牌时**只断言「仅在 `values/` 一份定义」的全局令牌**：
  `values-sw320dp`（绝大多数手机命中，模拟器 sw=393dp 也命中）会覆盖一批（如
  `disrupt_button_height`、`main_action_height`、`disrupt_condition_label_width`、`main_body_text`）。
- 从包里读值的写法（`aapt2 dump resources`）：
  ```
  resource 0x7f0700ea dimen/toolbar_height PUBLIC
    () 56.000000dp
  ```
  → 正则 `resource 0x[0-9a-f]+ dimen/<名字>\b[^\n]*\n\s*\(\) ([\d.]+)dp`。
  ★ **非 PUBLIC 的条目行尾没有 ` PUBLIC`**（新增资源如 `bottom_nav_indicator_inset`、8 套 overlay）。
- `aapt2 dump xmltree` 的取值格式：`match_parent=-1`、`wrap_content=-2`、
  `@dimen`/`@drawable` 引用显示为 **`@0x7f…`**、`?attr` 引用显示为 **`?0x7f…`**。
- ★ **`aapt2 dump resources` 会列出 style 的 item**：
  ```
  resource 0x7f1001b0 style/FwTextCaption
    () (style) size=4
      0x01010095=@dimen/text_caption
      0x01010098=?attr/app_text_hint
  ```
  所以「包内展开 style」是可行的，不用反编译。
- ★ **`style` 属性在 xmltree 里没有属性 ID**：`A: style=@0x7f1001a0`
  → 正则必须写 `\bstyle=@(0x…)`；写成 `style\(0x…\)=@` 永远匹配不到。

---

## R10. opt17 UI 框架重构（尺寸 token + 具名 style）

**用户诉求**：「现在可以对UI框架进行重构了」。三个深度里选了
**方案 A · 框架化·视觉不变**（统一重复布局为单一源头、抽重复片段为具名 style、
全部保持原数值、视觉像素级不变）。

### 动手前先证伪了三条「bug」（全证错了）

体检阶段我报了三条「已证实的 bug」，动手前用**原版 APK** 逐条核对，全是**原版自带行为**：

| 我的判定 | 原版证据 | 结论 |
|---|---|---|
| `layout-land/timer_layout` 指向 `include_cube_net` 是错指 | 原版 land `res/kH.xml` → `0x7f0c0063`（通用版），portrait `res/Jj.xml` → `0x7f0c0064`（timer 版）；两个 include **原版都有** | 原版设计，横屏格子本来就大一号 |
| `bt_wca_library` textSize 11sp/12sp 漂移 | 原版竖屏 `Jj.xml` 自己就同时有 11sp(×3) 与 12sp(×2) | 原版自带 |
| 横屏 7 个 tab*.xml 硬编码中文是「没跟上的旧副本」 | 原版 land `res/5p2.xml` 就是 `text="清空"` 字面量，竖屏 `res/wi.xml` 本来就是 `@0x7f0f…` | 原版自带 |

★ **教训：判「这是不是 bug」必须先跟 `original.apk` 对，不能只看源码树内部的不一致。**
方法：`aapt2 dump resources original.apk | grep -A8 "layout/tab2"` 拿 config→文件路径，
再 `aapt2 dump xmltree original.apk --file res/5p2.xml` 看真实取值。

### 实际做的两段

**A 段 `opt17a_tokens.py`**：3762 + 80 = **3842 处** dp/sp 字面量 → `@dimen/fw_*`（**165 个 token**）。
- 名字 = `fw_<role>_<值>`，role ∈ space/text/autosize/radius/stroke/elev/inset/size/draw。
- 名字**可逆**（`fw_space_0_5` → 0.5dp、`fw_elev_neg6` → -6dp）—— 这是幂等的基础。
- 跳过语义位：`0dp`（weight 哨兵）、`0.1dp`（hairline），共 534 处。
- ★ **必须同时认 `app:` 命名空间** —— CardView 的 `cardCornerRadius/cardElevation`、
  TextView 的 `autoSize*` 都在 `app:` 下；只认 `android:` 会漏 80 处。`tools:` 必须排除。
- `autoSize*` 单独成角色 `autosize`，避免污染后面要读的 `fw_text_*` 字号阶。
- 产出 `values/framework_dimens.xml`，**只在 values/ 一份、任何 values-* 都不覆盖**
  → 各 config 取值一致 = 视觉不变。

**B 段 `opt17b_styles.py`**：**28 个具名 style**（`Fw*`），命中 54 文件 / 336 元素 /
从元素上摘掉 **1466 个属性**。
- 候选 = 同 tag 上「属性集合完全相同」的组（≥7 次）；排除已带 `style=` 的元素、
  `layout_*` 定位、`text`（内容）、`id`、`tools:`、`xmlns`。
- 名字按候选排名对应（`NAMES` 列表），脚本断言条数一致并打印「名字 ↔ 属性组」供复核。
- ★ `app:` 属性在 style 里写**不带前缀**的名字（项目自带 `values/styles.xml:212` 就是这么写
  `cardCornerRadius` 的，已验证可打包运行）。
- 为什么安全：style 与内联属性走**同一个 TypedArray**（`applyStyle` 先铺、元素自身属性再盖），
  搬进 style 只是换地方写，解析路径不变。

### ★★ 执行顺序陷阱（本次最大的坑）

`opt16_redesign.py` 这类脚本**复跑时会按自己的期望内容整份重写**某些文件
（实测把 `bottom_nav_indicator.xml` 里的 `@dimen/fw_space_24` 还原成 `24.0dp`）。
没有总驱动脚本、opt 靠手工顺序跑 → 顺序一错，opt17 的成果被静默回滚。

**修法**：opt17 挂进 `build_lx.sh` 的**第 1.5 步**（`apply_lx_update.py` 之后、`apktool b` 之前），
每次打包前必定重跑（幂等、约 2 秒）。

★ **校验阶段只能跑 `verify_*`（只读）；`opt*` 一律会改树。**
实测在校验循环里跑了 `opt16_redesign.py`，树当场被改，`verify_framework` 立刻 7/0 → 6/1。

### 三个「幂等」翻车点

1. **复跑把自产文件清空**：首次写 156 个 dimen；复跑「命中 0 处」→ 按空集合重写 →
   token 文件被清成 0 条。修法：**从树里已引用的 `@dimen/fw_*` 名字反解出值**一起写（自愈）。
2. **读回范围太窄**：只扫 `layout*`/`drawable*` 会漏掉「属性搬进 style 之后」的引用 ——
   实测漏掉 `fw_text_40`（只被 `framework_styles.xml` 引用）。修法：扫**整个 `src/res`**。
3. `--dry` 也要落盘映射表，否则没法复核。

### 校验：判据是「源码级零差异」

`tmp/verify_framework.py`（7 条）：
- **A 结构恒等**：把两个文件里**所有属性（名+值）整体剥离**后必须逐字节相同
  → 标签/嵌套/顺序/空白/注释/文本/节点数都没动。
- **B 元素序列恒等**。
- **C 有效属性恒等**：每元素算「style 展开 → 内联覆盖」的有效属性表，再把
  `@dimen/fw_*` 解回数值、`14.0sp` 与 `14sp` 归一 → 前后必须相等。
- D 引用 token 全有定义 / E 无死 token（**要把 `framework_styles.xml` 算进引用方**）/
  F `fw_*` 只在 values/ 一份 / G 引用 `@style/Fw*` 全有定义。
- 结果：改动 239 文件 / 未改 61 文件 / 7 通过 0 失败。

**成品包**：
- **资源 ID 稳定性**：前后对比 → 消失 0、新增 193（165 dimen + 28 style）、
  **已有资源 ID 位移 0**（smali 按数字引用 ID，这条是硬底线）。
  ★ 例外：8 个非 PUBLIC 的 `ThemeOverlay.App.Scheme.*` 位移了（新 style 按名字序插到前面）。
  已确认 **smali 里没有任何写死的 overlay ID**（`grep -rn "0x7f1001a" src/smali/` 为空），
  运行时靠 `getIdentifier` 按名字取 → 安全。
- compare_9patch 112/112 · verify_rebuild 0 丢失 · verify_darkmode · verify_splash_removed ·
  verify_redesign · verify_scheme_propagation · verify_lx_content · opt15 17 条 —— 全绿。

### ★ 校验器的「只认字面量」通病（本次修了 4 处）

token 化 / 抽 style 之后，**断言里写死的字面量会集体失效**，而且报的是「属性丢了」这类误导性错误。
统一修法：解析引用 + 展开 style。

| 校验器 | 原断言 | 修法 |
|---|---|---|
| `verify_redesign.py` | `"1.000000dp" in bg`、`"24.000000dp" in ind` | 新增 `dp_of()`/`has_dp()`：认 `@0x…` 引用，按资源表解成 dp |
| `verify_redesign.py` | bottom.xml「1 主色 + 4 hint」、5 处 tab 背景 | 新增 `styles_in()`/`style_items()`/`item_is()`，从包内资源表展开 style |
| `verify_scheme_propagation.py` | 同上（bottom.xml 文字色） | 同上；★ `style` 属性在 xmltree 里没有属性 ID，正则用 `\bstyle=@(0x…)` |
| `verify_darkmode.py` | 逐项 zip 对齐比 `(attr, value)` | 改 `Counter((attr, resolved))` **多重集比较** —— 顺序无语义；排序后比同样错（`#` 排在 `@` 前，配对全乱） |

### 实机验证（★ 判据是像素，不是推理）

- 装包 → 启动 → 5 个 tab → 无 FATAL。
- **像素回归比对**（`tmp/emu/pixdiff.py`：逐像素、可指定区域与容差）：
  - `30_home` / `32_tab2` / `35_tab5`：最大通道差 **≤3** = 抗锯齿噪声，实质相同。
  - `33_tab3`：分桶 **1–3: 31740 / 4–10: 0 / 11–40: 0 / >40: 316**，
    那 316 px 正好是「生成个数」输入框的**光标闪烁** → 零内容变化。
  - `34_tab4`：13.3% 差异，看图确认**只差打乱公式**（随机会重新生成），
    芯片/卡片/计时圆盘/字体/间距/配色全一致。
  - `31_tab1`：记忆页，随机编码 + 倒计时 + 联想图，本身动态。
- 同页连拍两张自比：tab3、tab4 **逐像素完全相同** → 当前构建是确定性的，
  差异只来自随机内容/光标。
- **配色切换回归**（8 个 overlay ID 位移后）：靛蓝 `#4f46e5` → 青碧 `#0f766e`
  （状态栏与顶栏同步）→ 深色 `#1b1e33` / 页面 `#1a1d21` → 浅色回 `#4f46e5` / `#ffffff`。
- 主题页坐标（**用 `uiautomator dump` 拿的准确 bounds**，比记忆坐标靠谱）：
  `主题与外观` 行 `[66,286][1014,483]`；`靛蓝·精修` y 427–486、`青碧·纸感` 647–706、
  `紫玫瑰·活力` 867–926、`石墨·琥珀` 1087–1146；`浅色/深色/跟随系统` y 1426–1535、
  x 中心 247/539/832。
  ★ 主题页**选完颜色会自动返回上一页**，「选完再点下一个」必然点空。

**产物**：`dist/三盲助手-6.2.2-UI框架重构版.apk`（29.7 MB，SHA-256 `a705a3e5…af489c`），
截图归档 `reports/UI框架重构/`。

## R11. 已完成支线速查（2026-10-19 从 MEMORY.md 迁出）

- **联想图 576 张**：222 内置 + 197 Noto Emoji + 155 网扒 + 2 待定。⚠ 政治红线：`TW 台湾` 两次踩线
  （emoji 召回台湾地区旗帜 / 用户自备图为日本殖民时期「臺北州」）→ **用户自备图 ≠ 免检；台湾是中国的一部分**。
  `build_anki_deck.py` 写死 `BLOCKED`，被拦编码**笔记保留但不出图**。
- **联想词批量导入/导出**（Oct15 写、10-19 补 dist + e2e）：入口「资源管理」→「批量导入」→
  `LxBatchImportActivity`（4 来源：Anki `.apkg` / CSV·TSV / ZIP / 文件夹 SAF）+ 导出 CSV。统一走
  `BatchImportParser` → 临时 `.lxres` → `LxPicImportExportUtils.importCustomAssets`，**不自己写 apply**。
  核心是**「来源没给的字段保持现状」**：词回填走 `currentWord()`；图回填走**流式** + 沿用
  `LxPicStorageUtils.buildFileDigest` 摘要 → 判「未变化」直接跳过、**不重编码**。
  e2e：`tmp/emu/run_batch_test.py` + `pick_file.py`（DocumentsUI 焦点走 Enter）。
- **批量导入格式说明弹窗**：`dialog_lx_batch_help.xml` + `LxBatchImportActivity.showHelpDialog()`，
  `getIdentifier` 反射取资源、失败降级 Toast，不动 `public.xml`。
- **WebView 深色（opt18）**：`assets/main.css` + 7 个 HTML 注入 CSS 变量 +
  `@media (prefers-color-scheme: dark)` + `html.app-dark` 兜底（`wrap()` 改 uiMode 后 WebView 可能不跟系统）；
  浅色恒等断言。
- **底栏选中 pill + 计时圆盘外环（opt19）**：8 套 overlay 新增 `app_nav_pill` / `app_dial_ring`；
  `bottom_nav_tab_pill.xml`（`<inset><shape oval>`）插入 `bottom_nav_tab_bg.xml` 选中态最底层；
  `timer_spotlight_bg.xml` 改 `layer-list`（底层 `stroke` 环 + 上层渐变），零结构改动、不新增 view id。
- **体积 33.55→29.08 MB**：576 图 → WebP q85。⚠ **动态 WebP 在 minSdk21 不可用**
  （`BitmapFactory` 返回 null → 必须留 GIF）。派生公式库 `*AlgToInfoFirst.json` →
  **更新公式库必须重跑 `make_first_alg_assets.py`**；smali 只改路径字面量
  （**`static final String` 会内联 → 必须改 `const-string`**）。`twistsAlgToInfo.json` 未做。
- **深色模式**：限定符优先级 **语言 > 夜间模式**（原版 `values-en/` 自带 AppTheme + 旧 Material 色 →
  盖掉 `values-night`，原版就有；模拟器英文 locale 命中的是 `values-en/styles.xml`）。
  **`BaseActivity` 继承 `FragmentActivity`** → AppCompatDelegate 夜间机制不生效，framework 层 `values-night` 仍有效。
  **魔方贴纸色不能进 night 覆盖**；**不覆盖 `white`**（只把当背景的 31 处改指 `@color/surface`）；
  `grey` 的 21 处文字改 `lit_text_6b7280`；**标签正则不能写 `[^>]*?`**（值含 `>` 会截断）。
- **opt12 删开机动画**：预载搬到 `StartupPreloader`，在 `registerStartupLoadingReceiver()` **之后**启动
  （否则首个状态广播丢失）；**`id/gif_img` 保留**（`activity_first.xml` 复用）。
- **opt16 UI 体系 v2**：`toolbar_height` 50→56dp、`bottom_nav_height` 56→64dp；`radius_sm` 4→8dp、
  `radius_md` 8→12dp；触控目标 ≥48dp（chip 44dp）；核心 21 个布局字号吸附规范档（**规范档必须含 11/28/56**）；
  tab3 标签截断根因是 `values-sw320dp` 把 `disrupt_condition_label_width` 覆盖成 56dp + `ellipsize="end"`；
  ★ `selectTab` 第 5 个分支是 **fall-through**，锚点不能含 `goto :goto_0`。
- **Baseline Profile**：原报告说 stub —— **错**（2545 B 是压缩后）；**但已过期**
  （profile 内 dex crc32 == 原版 dex → 不生效）。重生成需 Gradle 源码工程 + 真机。
