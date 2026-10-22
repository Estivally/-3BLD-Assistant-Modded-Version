# -*- coding: utf-8 -*-
"""第二组改动：公式集上限、统计页薄弱榜打通最优、最优清单排序/分组、Manifest、版本号。"""
import io
import sys

BASE = r"D:\Program\dsh\rebuild"
log = []


def patch(path, pairs):
    p = BASE + path
    s = io.open(p, encoding="utf-8").read()
    for tag, old, new, cnt in pairs:
        n = s.count(old)
        if n != cnt:
            print("FAIL %s @ %s: 期望 %d 处，实际 %d 处" % (tag, path, cnt, n))
            print(repr(old[:160]))
            sys.exit(1)
        s = s.replace(old, new)
        log.append("%s x%d" % (tag, n))
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)


# ---------------- 1. 公式集数量上限 ----------------
patch(r"\src_java\zd\cornermemory\ui\Alg818CustomSet.java", [
    ("create-limit",
     """    public static SetInfo create(Context ctx, String name, String type, String text, Result r) {""",
     """    public static SetInfo create(Context ctx, String name, String type, String text, Result r) {
        if (list(ctx).size() >= 20) {
            Log.w(TAG, "公式集已达上限 20 套，拒绝新建");
            return null;
        }""", 1),
])

# ---------------- 2. 统计页薄弱榜：标了星的 case 显示 ★ 最优 N 步 ----------------
patch(r"\src_java\zd\cornermemory\ui\StudyStatsActivity.java", [
    ("weak-star",
     """            laps.setTextColor(colorAttr("app_lit_text_dc2626", 0xFFDC2626));
            row.addView(laps);""",
     """            laps.setTextColor(colorAttr("app_lit_text_dc2626", 0xFFDC2626));
            row.addView(laps);

            // 这一条在 818 里标过 ★ 最优 → 直接显示最优公式的步数（点整行进 818 看公式）
            String best = bestOf(code);
            if (best.length() > 0) {
                TextView star = new TextView(this);
                star.setText(fmt1(str("study_stats_best_chip"), Integer.valueOf(steps(best))));
                star.setTextSize(10f);
                star.setTextColor(colorAttr("app_lit_text_4f46e5", 0xFF4F46E5));
                star.setBackgroundResource(id("control_chip_bg", "drawable"));
                star.setPadding(dp(6), dp(1), dp(6), dp(1));
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.setMarginStart(dp(6));
                row.addView(star, slp);
            }""", 1),
    ("weak-star-helpers",
     """    private String wordOf(String s, String code) {""",
     """    /** 818 里给这个 case 标过的 ★ 最优公式（没有就空串）。 */
    private String bestOf(String code) {
        try {
            String v = SPUtils.getInstance().getString("alg818_best_" + code, "");
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 公式步数 = 空白分隔的 token 数。 */
    private int steps(String alg) {
        if (alg == null || alg.length() == 0) {
            return 0;
        }
        String t = alg.trim();
        if (t.length() == 0) {
            return 0;
        }
        return t.split("\\\\s+").length;
    }

    private String wordOf(String s, String code) {""", 1),
])

# ---------------- 3. 最优清单：排序切换 + 显示分组 ----------------
patch(r"\src_java\zd\cornermemory\ui\Alg818BestActivity.java", [
    ("entry-group",
     """        String type;
        String alg;""",
     """        String type;
        String alg;
        /** 这条最优公式所属分组（自定义/浅梦集才有，内置集为空） */
        String group;""", 1),
    ("load-group",
     """            Entry en = new Entry();
            en.caseKey = caseKey;
            en.alg = alg;""",
     """            Entry en = new Entry();
            en.caseKey = caseKey;
            en.alg = alg;
            Object gv = all.get("alg818_bgrp_" + caseKey);
            en.group = gv instanceof String ? ((String) gv).trim() : "";""", 1),
    ("order-field",
     """    private static final String K_BEST = "alg818_best_";""",
     """    private static final String K_BEST = "alg818_best_";
    private static final String K_ORDER = "alg818_best_order";
    private static final String ORDER_SRC = "src";
    private static final String ORDER_STEPS = "steps";
    private static final String ORDER_GROUP = "group";

    /** 清单排序：按来源 / 按步数 / 按分组 */
    private String order = ORDER_SRC;""", 1),
    ("order-init",
     """        render();
    }

    @Override
    protected void onResume() {""",
     """        String o = SPUtils.getInstance().getString(K_ORDER, ORDER_SRC);
        order = (ORDER_STEPS.equals(o) || ORDER_GROUP.equals(o)) ? o : ORDER_SRC;
        TextView orderChip = (TextView) findViewById(id("alg818_best_order", "id"));
        if (orderChip != null) {
            orderChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    order = ORDER_SRC.equals(order) ? ORDER_STEPS
                            : (ORDER_STEPS.equals(order) ? ORDER_GROUP : ORDER_SRC);
                    SPUtils.getInstance().put(K_ORDER, order);
                    Log.i(TAG, "清单排序 → " + order);
                    render();
                }
            });
        }

        render();
    }

    @Override
    protected void onResume() {""", 1),
    ("render-sort",
     """    private void render() {
        List<Entry> list = load();
        Log.i(TAG, "最优公式 " + list.size() + " 条");""",
     """    private void render() {
        List<Entry> list = load();
        sortList(list);
        Log.i(TAG, "最优公式 " + list.size() + " 条，排序 " + order);
        TextView orderChip = (TextView) findViewById(id("alg818_best_order", "id"));
        if (orderChip != null) {
            orderChip.setText(str(ORDER_STEPS.equals(order) ? "alg818_best_order_steps"
                    : (ORDER_GROUP.equals(order) ? "alg818_best_order_group"
                    : "alg818_best_order_src")));
        }""", 1),
    ("sortList",
     """    private void sortList(List<Entry> list) {""",
     """    private void sortList(List<Entry> list) {""", 0),
])

# sortList 方法插入：接在 srcOrder 之后
p = BASE + r"\src_java\zd\cornermemory\ui\Alg818BestActivity.java"
s = io.open(p, encoding="utf-8").read()
anchor = """    private String wordOf(String code) {"""
assert s.count(anchor) == 1
sort_method = """    /** 清单排序：按来源（默认）/ 按步数 / 按分组（未分组的排最后）。 */
    private void sortList(List<Entry> list) {
        if (ORDER_STEPS.equals(order)) {
            Collections.sort(list, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    int sa = steps(a.alg);
                    int sb = steps(b.alg);
                    if (sa != sb) {
                        return sa < sb ? -1 : 1;
                    }
                    return a.code.compareTo(b.code);
                }
            });
        } else if (ORDER_GROUP.equals(order)) {
            Collections.sort(list, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    String ga = a.group == null ? "" : a.group;
                    String gb = b.group == null ? "" : b.group;
                    boolean ea = ga.length() == 0;
                    boolean eb = gb.length() == 0;
                    if (ea != eb) {
                        return ea ? 1 : -1;
                    }
                    int g = ga.compareTo(gb);
                    if (g != 0) {
                        return g;
                    }
                    int sr = srcOrder(a.src) - srcOrder(b.src);
                    if (sr != 0) {
                        return sr;
                    }
                    return a.code.compareTo(b.code);
                }
            });
        }
    }

"""
s = s.replace(anchor, sort_method + anchor)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
log.append("sortList x1")

# 行内显示分组
patch(r"\src_java\zd\cornermemory\ui\Alg818BestActivity.java", [
    ("row-group",
     """        len.setText(fmt1(str("alg818_moves"), Integer.valueOf(steps(en.alg))));""",
     """        String groupPart = (en.group != null && en.group.length() > 0) ? "  ·  " + en.group : "";
        len.setText(fmt1(str("alg818_moves"), Integer.valueOf(steps(en.alg))) + groupPart);""", 1),
])

# ---------------- 4. 清单页布局：head 右侧加排序 chip ----------------
patch(r"\src\res\layout\activity_alg818_best.xml", [
    ("head-row",
     """        <TextView android:textSize="@dimen/fw_text_12" android:textColor="?attr/app_text_secondary" android:id="@+id/alg818_best_head" android:paddingTop="@dimen/fw_space_8" android:paddingBottom="@dimen/fw_space_4" android:layout_width="match_parent" android:layout_height="wrap_content" />""",
     """        <LinearLayout android:orientation="horizontal" android:gravity="center_vertical" android:paddingTop="@dimen/fw_space_8" android:paddingBottom="@dimen/fw_space_4" android:layout_width="match_parent" android:layout_height="wrap_content">
            <TextView android:textSize="@dimen/fw_text_12" android:textColor="?attr/app_text_secondary" android:id="@+id/alg818_best_head" android:layout_width="0.0dp" android:layout_weight="1.0" android:layout_height="wrap_content" />
            <TextView android:textSize="@dimen/fw_text_11" android:textStyle="bold" android:textColor="?attr/app_text_primary" android:gravity="center" android:id="@+id/alg818_best_order" android:background="@drawable/control_chip_bg" android:focusable="true" android:clickable="true" android:paddingStart="@dimen/fw_space_12" android:paddingEnd="@dimen/fw_space_12" android:layout_width="wrap_content" android:layout_height="@dimen/fw_size_28" android:text="@string/alg818_best_order_src" />
        </LinearLayout>""", 1),
])

# ---------------- 5. Manifest 注册两个新页 ----------------
patch(r"\src\AndroidManifest.xml", [
    ("manifest",
     """<activity android:configChanges="orientation|screenSize" android:exported="false" android:name="zd.cornermemory.ui.Alg818BestActivity"/>""",
     """<activity android:configChanges="orientation|screenSize" android:exported="false" android:name="zd.cornermemory.ui.Alg818BestActivity"/>
        <activity android:configChanges="orientation|screenSize" android:exported="false" android:name="zd.cornermemory.ui.Alg818SetListActivity"/>
        <activity android:configChanges="orientation|screenSize" android:exported="false" android:name="zd.cornermemory.ui.QmReadmeActivity"/>""", 1),
])

# ---------------- 6. 版本号 6.2.2(115) → 6.3.0(116) ----------------
patch(r"\src\apktool.yml", [
    ("version",
     """versionCode: 115
versionName: 6.2.2""",
     """versionCode: 116
versionName: 6.3.0""", 1),
])

print("OK 共 %d 步: %s" % (len(log), ", ".join(log)))
