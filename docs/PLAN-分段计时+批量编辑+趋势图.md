# 三盲助手 · 分段计时 + 批量编辑 + 趋势图 — 方案与实现记录

> 基线：zd.cornermemory v6.2.2（versionCode 115）B 线反编译重建
> 状态：三项功能已实现、出包、模拟器实测通过（2026-10-19）；阶段 0 收尾 0.1–0.3 已完成
> 本文档已按**实际实现**回写（原计划与实现的差异见第 0 节）

---

## 0. 原计划 vs 实际实现

| # | 原计划 | 实际实现 | 原因 |
|---|---|---|---|
| 1 | 功能 1 新建 `TimerSegmentHelper`，改触摸状态机 | **未新建任何类**：反编译树里 `TimerFragment` 本来就带 `isSegmentedTiming` / `currentPhase` / `p1Time` / `segmentPanel` 全套逻辑，只是入口被隐藏 | 原版已实现，只补入口 + 常显，改动面最小 |
| 2 | 手势「按住观察 → 松开计时 → 单击切执行 → 松开结束」 | 实际：**第 1 次点击开始记忆阶段 → 第 2 次点击进入复原阶段 → 第 3 次点击停止并保存**；长按模式下停止需要两次点击（先切阶段再停） | 沿用原版状态机，不改既有手势语义 |
| 3 | 功能 2 用 RecyclerView + 独立 `LxBatchEditAdapter` | 用 **ListView + 行内 EditText**，Adapter 是 `LxBatchEditActivity.RowAdapter` 内部类 | 576 行量小，ListView 足够，少一个文件 |
| 4 | 补丁 `Dictionary.initDic()` 末尾调 `overrideFromPrefs()` | **没有改 Dictionary**：直接复用原版「改词」的存储语义 —— SP 键 `<code>_LX` 覆盖，缺省回退 `Dictionary.dic`；读点原本就有（`NormalAdapter.smali:360`、`LxPicActivity.smali:873`） | 原版已有同一套机制，不引入第二套语义，也不改 smali |
| 5 | 入口放 `LxPicResourceManager` 菜单 | 实际是 `dialog_lx_pic_manage.xml` 里的 `action_batch_edit` 行，由 `LxBatchImportEntry.attach` 与批量导入统一接线 | 与批量导入同一入口，用户少找一层 |
| 6 | 功能 3 用 Chart.js（CDN 或本地 js） | **纯 Canvas 手绘**，零第三方库、零网络依赖 | 离线可用、包体不增、深色模式好控 |
| 7 | 功能 3 三条线（总 / 记忆 / 执行） | **两条线**（记忆 / 复原）+ 两层面积填充 | 总时间恒等于 P1+P2，两段画出来信息更直接 |
| 8 | 功能 3 过滤 `status != DNF` | 阶段 0.1 补齐为 **`STATUS == 0` 才入图**：+2 罚时（STATUS=1）与 DNF（STATUS=2）都不画，只计数并在页脚说明 | 罚时加在总时间上，归不到记忆或复原某一段 |

---

## 1. 功能 1：记忆/复原分段计时

### 1.1 数据层（不改 schema）

复用 `Cj.p1` / `Cj.p2`（Long，毫秒）：

| 字段 | 含义 |
|---|---|
| `Cj.p1` | 记忆阶段时长 |
| `Cj.p2` | 复原阶段时长 |

保存时 `Cj.setP1(p1Time)`、`Cj.setP2(total - p1Time)`，`singTime` 仍是总时间。
依据：`TimerFragment.smali:12300-12338`。

### 1.2 状态机（实测）

```
空闲  --点击-->  记忆阶段（面板显示「记忆阶段 / 等待记录记忆时长」）
记忆阶段 --点击--> 复原阶段（面板显示「复原阶段 / 记忆时长 xx」）
复原阶段 --点击--> 停止 → 保存对话框 → 入库 P1/P2
保存完成 → 回到空闲
```

- 触摸入口：`TimerFragment.touchUp` state==0 分支（`:20797-20848`）启动计时并 `showSegmentedPhaseOneUi()`；
  `touchDown` state==1 且 `currentPhase==1` → 记录 `p1Time = timer.getTime()`、切 phase 2（日志 `E TimerFragment: 记录 P1: …`）；
  `currentPhase==2` 再点 → `clocking()` 停止。
- **长按模式（is_long=1）**：按住到 `isOk` 松开才开始，所以停止要多点一次 —— 三次手势与单击模式一致，属设计而非 bug。

### 1.3 UI 改动

| 文件 | 动作 |
|---|---|
| `src/res/layout/timer_layout.xml` / `layout-land/` | 已有 `segment_panel`（圆角描边卡） |
| `src/smali/.../TimerFragment.smali` | 阶段 0.2：`resetSegmentedUi()` 里面板可见性由「恒 GONE」改为 `isSegmentedTiming ? VISIBLE : GONE`，空闲时也常显 |
| `src/res/layout/activity_timer_setting.xml` | 阶段 0.2：开关行下新增提示 `@id/timer_segment_hint` |
| `src/res/values/strings.xml` | `timer_segment_p1_prefix` / `phase_one` / `phase_two` / `waiting` / `timer_segment_hint` |

---

## 2. 功能 2：联想词批量编辑

### 2.1 存储（复用原版「改词」语义）

- SP 键：`<编码>_LX`（自定义联想词）、`<编码>_LXPIC`（自定义图片路径）
- 读取：`SP.getString(code + "_LX", Dictionary.dic.get(code))` —— 原版 `NormalAdapter` / `LxPicActivity` 就这么读
- 写入：值等于内置值或为空 → 删除键（SP 里只留真正偏离默认的条目）

`LxWordStorage`（`src_java/zd/cornermemory/utils/LxWordStorage.java`）封装 `wordKey` / `builtinWord` / `currentWord` / `hasOverride` / `putWord` / `resetWord`，全部 try-catch。

### 2.2 界面

`LxBatchEditActivity`（`src_java/zd/cornermemory/ui/LxBatchEditActivity.java`）：

```
顶部 CustomToolbar「批量编辑联想词」
搜索框（按编码或联想词过滤）+ 「批量替换」chip
ListView：每行 [编码] [内置词/当前词 EditText] [↺ 单行恢复]
底部：「全部恢复默认」+「保存修改」
```

- 批量替换弹窗 `dialog_lx_batch_replace.xml`：查找内容 / 替换为 / 取消 / 预览（N 行）/ 替换
- 保存：遍历 576 行，空或等于内置值 → `resetWord`，否则 `putWord`；`changed == 0` 提示「没有改动」
- 布局：`activity_lx_batch_edit.xml`、`item_lx_batch_edit.xml`、`dialog_lx_batch_replace.xml`、`dialog_lx_confirm.xml`

### 2.3 入口

`dialog_lx_pic_manage.xml` 的 `action_batch_edit` 行 → `LxBatchImportEntry.attach()` 统一接线
（与 `action_batch_import` 同一处，日志 `LxBatchImportEntry: 入口已接线: action_batch_edit`）。

---

## 3. 功能 3：分段趋势图

### 3.1 实现

| 文件 | 动作 |
|---|---|
| `src_java/zd/cornermemory/ui/SegmentTrendActivity.java` | 新建：查 `Cj`（`orderDesc(Date)`）→ 过滤 → 组装 JSON → `WebView.loadUrl` + `evaluateJavascript("LX.setData(...)")` |
| `src/assets/segment_trend.html` | 新建：纯 Canvas 双线 + 面积填充 + 统计卡 + 时间窗 chips + 深色 |
| `src/res/layout/activity_segment_trend.xml` | 新建：`CustomToolbar` + `WebView @id/trend_web` |
| `src/res/drawable/ic_trend_chart.xml` | 新建：24dp 矢量图标（柱状+基线） |
| `src_java/zd/cornermemory/ui/SegmentTrendEntry.java` | 新建：在成绩页工具栏右侧挂图标按钮 |
| `src/smali/.../CjActivity.smali` | 修改：`onCreate` 末尾插 `SegmentTrendEntry.attach(p0)` |

- 取数：`MAX_POINTS = 200`，取最近 200 条有分段数据的记录，按时间正序
- **过滤（阶段 0.1）**：`p1 > 0 && p2 >= 0 && STATUS == 0`；被排除的条数进 `excluded` 字段
- 交互：chips 最近 20 / 最近 50 / 全部；点/触图表出 tooltip（日期 + 记忆/复原秒）
- 空态：`📈 暂无分段计时记录` + 引导文案；被排除时副标题换成「有 N 条分段记录，但都是 DNF 或 +2 罚时…」

### 3.2 深色

`html.dark` 类 + CSS 变量（`--bg/--surface/--fg/--muted/--border/--grid/--mem/--exe`），
`dark` 由 `ThemeManager.isNight(this)` 决定、随 payload 传入。

---

## 4. 阶段 0 收尾改动（2026-10-19 晚）

| 项 | 内容 | 状态 |
|---|---|---|
| 0.1 | 趋势图按 `STATUS == 0` 过滤，排除数进页脚脚注 | ✅ 代码 + 出包 |
| 0.2 | 分段面板空闲常显（`resetSegmentedUi` 分支）+ 计时设置提示行 `timer_segment_hint` | ✅ 实测：空闲时 dump 可见「记忆阶段 / 等待记录记忆时长」 |
| 0.3 | 长按模式回归验证 | ✅ 结论：长按开始后需两次点击停止，与单击模式一致，非 bug |
| 0.4 | 文档回写（本文档 + REPORT + 记忆） | ✅ |
| 0.5 | 重新出包 + 模拟器复验 | 进行中 |

---

## 5. 验证记录（emulator-5554 / Pixel_3a_API_30）

- 计时设置：提示行 3 行换行正常，bounds `[33,1517][1047,1702]`
- 计时页空闲：`记忆阶段` `[318,1289][438,1324]`、`等待记录记忆时长` `[497,1287][761,1326]`
- 三次点击：logcat `E TimerFragment: 记录 P1: 3730, timer.getTime(): 3730, tvP1 可见性: 0`；
  保存「无惩罚」→ DB 新行 `_id=8 STATUS=0 SING_TIME=8074 P1=3730 P2=4344`（P1+P2=SING_TIME ✓）
- 趋势页：空态 / 有数据 / 深色三态像素校验通过（深色背景 20,22,27、卡片 28,31,38、记忆色 #818CF8）
- 批量编辑：`LxBatchEdit: 装载联想词 576 行`；搜索、批量替换、保存写 `AA_LX`、↺ 删键均正常

---

## 6. 风险与遗留

1. **长按模式停止需两次点击**：与单击模式行为一致，但首次使用可能困惑；可在提示行补一句说明。
2. **趋势页脚注曾被 `.card { position:absolute; inset:0 }` 盖住**：已改为 `body.has-note .card { bottom:26px }` + `.note` 绝对定位到底部。
3. **无正式签名**：只有 `~/.android/debug.keystore`，覆盖安装需先卸载。
4. **真机未实测**：仅模拟器。
5. **`list_item_bgcolor.xml` 的 2 处 `android:drawable="?attr/app_light_blue"`**：仍是潜在崩溃点（改 `@color` 会丢按方案着色，需另想方案）。

---

*2026-10-19 回写。*
