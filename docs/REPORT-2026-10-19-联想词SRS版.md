# 三盲助手 6.2.2 联想词 SRS 版 — 构建报告

> 构建时间：2026-10-19 19:33 前后
> 产物：`dist/三盲助手-6.2.2-联想词SRS版.apk`
> 大小：29,822,768 字节（约 28.4 MiB）
> SHA-256：`DBD062E5F706DBE07E60517B923C43FCA198D2A3E33DBD5D6B9DEC65FD7BFC714`
> 基线：zd.cornermemory v6.2.2（versionCode 115，targetSdk 33 / minSdk 21）
> 上一版：`dist/三盲助手-6.2.2-分段计时趋势版.apk`（含分段计时 / 批量编辑 / 趋势图 / 阶段 0）

---

## 一、本版新增

| 功能 | 入口 | 说明 |
|---|---|---|
| **联想词 SRS 学习页** | 其他 → 资源管理 → 学习联想词（间隔重复） | Anki 式间隔重复：按回忆成功率调度 576 个编码，薄弱的多见、记住的少见。卡片正面只露编码，点一下才翻出联想词与记忆图，三档评分（忘记 / 模糊 / 记得）即时写盘，无「保存」按钮 |

### 设计取舍

| 决策 | 理由 |
|---|---|
| 不引第三方库，调度逻辑全在 Activity 里 | 576 个编码的量级，SP 足够；也复用 App 已有的 SP 备份/迁移路径 |
| 三档而不是四档（没做 Anki 的 Easy） | 三盲训练场景里「太简单」没有信息量，Easy 只会让调度更激进 |
| 忘记时把卡追加到**本会话队列末尾**（每卡限 1 次） | 趁热打铁，一次会话内能见到第二面；限 1 次防止一张卡无限循环 |
| 新卡遗忘**不**重排、不计 lapse | 第一次见本来就会忘，重排等于原地打转 |
| 正面只露编码 | 联想词本身就是记忆法，先看到词就不用回忆了（等于泄题） |
| 翻面前三档按钮 alpha 0.45 且点击只 Toast | 防止手滑「记得」把没复习的卡推到 1 天后 |
| 空态「再学 10 个新词」不受每日额度限制 | 额度是默认节奏，用户主动加餐时不该被拦 |

---

## 二、技术要点

### 2.1 调度算法

`box` 0–6，`INTERVALS = {0, 1, 3, 7, 15, 30, 60}` 天，`MASTERED_BOX = 4`（≥15 天算「已掌握」）。

| 评分 | box | due | reps | laps | 重排 |
|---|---|---|---|---|---|
| 忘记（Again） | → 1 | now + 10 分钟 | 不变 | 非新卡 +1 | 非新卡且本会话未补过 → 追加到队尾 |
| 模糊（Hard） | <1 则 →1，否则不变 | now + max(1h, INTERVALS[box]/2) | 不变 | 不变 | 否 |
| 记得（Good） | min(6, box+1) | now + INTERVALS[box] | +1 | 不变 | 否 |

- 队列 = 到期复习（`due <= now`，按 due 升序）+ 新词（`box <= 0`，受 `NEW_PER_DAY(20) - day_new` 额度限制，每次取 `NEW_BATCH(10)`）
- 跨天：`todayKey()`（`yyyyMMdd` 整数）与 `lx_srs_day` 比对，不同则清零 `lx_srs_day_new` / `lx_srs_day_rev`

### 2.2 存储（SPUtils，键名即协议）

```
lx_srs_b_<CODE>   int   box 0..6
lx_srs_d_<CODE>   long  due 时间戳(ms)
lx_srs_r_<CODE>   int   reps（「记得」次数）
lx_srs_l_<CODE>   int   laps（「忘记」次数，仅非新卡累加）
lx_srs_day        int   今日 yyyyMMdd
lx_srs_day_new    int   今日已学新词数
lx_srs_day_rev    int   今日已复习数
```

### 2.3 UI 结构

`res/layout/activity_lx_study.xml`：`RelativeLayout`(background_page_gradient) → `CustomToolbar` → 进度行（`lx_study_progress` / `lx_study_counts`）→ `ProgressBar`(`lx_study_bar`) → `FrameLayout`(weight=1) 内互斥的 `card`(`lx_study_card`) 与 `empty`(`lx_study_empty`) → 三档按钮行(`lx_study_actions`)。

- 卡片正面 `lx_study_code` + `lx_study_hint`；背面 `lx_study_word` / `lx_study_img` / `lx_study_meta`
- 空态：`lx_study_empty_title`（今天的复习完成了）/ `lx_study_empty_desc`（已掌握 N · 学习中 N · 未学 N）/ `lx_study_new_btn`
- 图片优先级：SP `<code>_LXPIC`（用户自备）→ `Dictionary.pic`（内置 576 张）→ GONE；自备图两段解码 + `inSampleSize` 到 480px
- 所有 view id 走 `getIdentifier(name, type, pkg)` 按名取（**不进 `public.xml`**，不改原版 id）

### 2.4 入口接线

- `res/layout/dialog_lx_pic_manage.xml`：`action_batch_edit` 之后插入 `@+id/action_lx_study` 行（badge「学」，`score_manage_badge_import_bg` + `app_lit_text_059669`）
- `src_java/zd/cornermemory/ui/LxBatchImportEntry.java`：新增 `ROW_STUDY = "action_lx_study"`，`attach()` 里 `wire(...)` → `LxStudyActivity`
- `AndroidManifest.xml`：`<activity android:name="zd.cornermemory.ui.LxStudyActivity" android:exported="false" android:configChanges="orientation|screenSize"/>`

### 2.5 新增资源

| 类型 | 名称 |
|---|---|
| layout | `activity_lx_study` |
| drawable | `lx_study_progress_bg`（layer-list：`?attr/app_lit_bg_1a000000` 底 + `?attr/app_colorPrimary` 进度，圆角 `fw_radius_2`） |
| string | `lx_study_*` 共 19 条 |
| id | `action_lx_study` + `lx_study_*`（约 14 个，`@+id` 自动进 `public.xml` 的 id 表） |

`?attr` 只出现在**颜色位**（`<solid android:color>`），符合 MEMORY.md 硬约定 ㉔。

---

## 三、验证记录（emulator-5554，全部实测）

| 场景 | 期望 | 实测 |
|---|---|---|
| 首次进入 | 20 张新卡，正面只露编码 | `队列 due=0 new=20 total=20 (今日已新学 0)`，卡片 `AA` + 「点击卡片显示联想词」 |
| 未翻面点评分 | 不推进 | 点「忘记」后仍 `0 / 20`、同一张 AA |
| 翻面 | 联想词 + 记忆图 + meta | `圣诞树🎄` / 圣诞树插图 / 「新词 · 还没复习过」 |
| 记得 | box 0→1，due +1 天 | `评分 AA g=2 box=1 reps=1 laps=0 due=+1440min` |
| 新卡忘记 | box→1，due +10min，laps 不增，不重排 | `评分 AB g=0 box=1 reps=0 laps=0 due=+10min` |
| 到期卡优先 | 注入 `due=1000` 的 AC 后排第一 | `队列 due=1 new=18 total=19 (今日已新学 2)`，首卡 AC，`待复习 1 · 新词 573` |
| 到期卡忘记 → 重排 | laps 1→2，box 2→1，队列 +1 | `评分 AC g=0 box=1 reps=3 laps=2 due=+10min`，进度 `1 / 20`，第 20 张又是 AC，meta「已复习 3 次 · 忘记 2 次」 |
| 模糊 | box 0→1，due +12h | `评分 AX g=1 box=1 reps=0 laps=0 due=+720min` |
| 走完队列 | 空态 + 统计 | `本次 20 / 20`、`今天的复习完成了`、`已掌握 0 个 · 学习中 21 个 · 未学 555 个` |
| 再学 10 个新词 | 队列 20→30 | `手动补新词 10 个`，`本次 20 / 30`，下一张 AX |
| 深色模式 | 跟随主题 | 与既有页面一致（复用 `?attr` 令牌，未单独截图） |

截图：`dist/verify-shots/shot-13-lx-study-reveal.png`、`shot-14-lx-study-due-card.png`、`shot-15-lx-study-empty.png`、`shot-16-lx-study-newbatch.png`；dump：`ui-study-07..15.xml`。

---

## 四、坑与约束

- **`uiautomator dump` 不列「无 contentDescription 的 ImageView」**：翻面后 dump 里找不到 `lx_study_img`，一度以为图没渲染；截图证明图在。判「图片有没有显示」只能看截图。
- **uiautomator 的 bounds 要按 resource-id 精确取**：`lx_study_new_btn` 真实 bounds `[344,1315][735,1447]`，用 `Select-String` 抓行会算错中心（点两次没反应，误以为监听没绑）。
- **SP 是进程内缓存**：改 `shared_prefs/spUtils.xml` 前必须 `am force-stop`，否则改了不生效、退出还会被内存旧值覆盖。
- **「本次 x / y」的 y 是队列快照**：requeue / 补卡会让 y 变大（20 → 30），不是越界。
- 构建链照旧：`build_java.sh` → `install_java_smali.sh` → `build_lx.sh`；跑脚本必须带 `PYTHONIOENCODING=utf-8 PYTHONUTF8=1`（`opt17a_tokens.py` / `verify_rebuild.py` 打印 ✅ 会抛 GBK `UnicodeEncodeError`）。

---

## 五、复现命令

```bash
# 工作目录 D:\Program\dsh\rebuild，bash = D:\Program Files\Git\bin\bash.exe
export PYTHONIOENCODING=utf-8 PYTHONUTF8=1 SKIP_LX_UPDATE=1
bash build_java.sh && bash install_java_smali.sh && bash build_lx.sh
```

```powershell
# 装包
adb install -r D:\Program\dsh\rebuild\signed.apk
adb shell am force-stop zd.cornermemory
adb shell am start -n zd.cornermemory/.ui.MainActivity
```

---

## 六、未做 / 遗留

- 只在 emulator-5554 验证，真机未测
- 跨天清零未实测（需改系统时间）
- `day_rev` 只统计「非新卡评分次数」，不区分忘记/记得；若将来要做「今日正确率」需要再拆
- 「模糊」不写 reps，所以 meta 的「已复习 N 次」会偏小（只统计「记得」）
- 到期队列无分页上限：库是 576 个编码，一次全算 <50ms；若编码规模扩大需要改懒加载
- 版本号仍是 6.2.2 (115)、debug keystore（沿用之前的口径，未拍板是否改）
