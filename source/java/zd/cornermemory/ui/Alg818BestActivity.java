package zd.cornermemory.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zd.cornermemory.utils.Dictionary;
import zd.cornermemory.utils.LxWordStorage;
import zd.cornermemory.utils.SPUtils;
import zd.cornermemory.view.CustomToolbar;

/**
 * 「我的最优」清单页：把 818 卡片里点 ★ 选出来的公式汇总成一张表。
 *
 * <p>数据就是 SP 里的 {@code alg818_best_<caseKey>}（value = 公式），
 * 三个公式源（内置 / 浅梦 / 自定义）的键前缀不同，这里按前缀分源展示：
 * 内置 = 3 字母键（JWP）、浅梦 = {@code q} + 类型 + 2 字母码（qCAD）、自定义 = {@code c} + 类型 + 码。
 *
 * <p>点一行 → 带着 {@code target_code} 跳回 818 页，那边 {@code applyTarget} 会先切到对应公式源、
 * 再把这张卡拉到队首；点右侧「×」→ 取消这一条的最优。
 */
public class Alg818BestActivity extends BaseActivity {

    private static final String TAG = "Alg818BestPage";

    private static final String K_BEST = "alg818_best_";
    /** 注意：不能叫 alg818_best_*，否则会被 load() 的前缀扫描当成一条最优公式。 */
    private static final String K_ORDER = "alg818_bl_order";
    private static final String K_ORDER_OLD = "alg818_best_order";
    private static final String ORDER_SRC = "src";
    private static final String ORDER_STEPS = "steps";
    private static final String ORDER_GROUP = "group";

    /** 清单排序：按来源 / 按步数 / 按分组 */
    private String order = ORDER_SRC;

    private static final String SRC_BUILTIN = "builtin";
    private static final String SRC_QM = "qm";
    private static final String SRC_CUSTOM = "custom";

    private final HashMap<String, Integer> IDS = new HashMap<String, Integer>();

    private LinearLayout body;
    private TextView head;
    private TextView empty;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_alg818_best", "layout"));

        body = (LinearLayout) findViewById(id("alg818_best_body", "id"));
        head = (TextView) findViewById(id("alg818_best_head", "id"));
        empty = (TextView) findViewById(id("alg818_best_empty", "id"));
        scroll = (ScrollView) findViewById(id("alg818_best_scroll", "id"));

        CustomToolbar tb = (CustomToolbar) findViewById(id("toolbar", "id"));
        if (tb != null) {
            tb.setTitle(str("alg818_best_title"));
            View back = tb.getBackButton();
            if (back != null) {
                back.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
            }
        }

        if (SPUtils.getInstance().getString(K_ORDER_OLD, null) != null) {
            // 旧版本把排序写在 alg818_best_order，会污染最优清单；迁到新键后清掉
            if (SPUtils.getInstance().getString(K_ORDER, null) == null) {
                SPUtils.getInstance().put(K_ORDER,
                        SPUtils.getInstance().getString(K_ORDER_OLD, ORDER_SRC));
            }
            SPUtils.getInstance().remove(K_ORDER_OLD);
            Log.i(TAG, "清理旧排序键 " + K_ORDER_OLD);
        }
        String o = SPUtils.getInstance().getString(K_ORDER, ORDER_SRC);
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
    protected void onResume() {
        super.onResume();
        if (body != null) {
            render();
        }
    }

    // ------------------------------------------------------------------ 数据

    /** 一条最优记录。 */
    private static final class Entry {
        String caseKey;
        String src;
        String code;
        String wordKey;
        String type;
        String alg;
        /** 这条最优公式所属分组（自定义/浅梦集才有，内置集为空） */
        String group;

        String sourceName() {
            if (SRC_QM.equals(src)) {
                return "alg818_src_qm";
            }
            if (SRC_CUSTOM.equals(src)) {
                return "alg818_src_custom";
            }
            return "alg818_src_builtin";
        }
    }

    private List<Entry> load() {
        List<Entry> out = new ArrayList<Entry>();
        Map<String, ?> all;
        try {
            all = SPUtils.getInstance().getAll();
        } catch (Throwable t) {
            Log.w(TAG, "读 SP 失败", t);
            return out;
        }
        if (all == null) {
            return out;
        }
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith(K_BEST)) {
                continue;
            }
            Object v = e.getValue();
            if (!(v instanceof String)) {
                continue;
            }
            String alg = ((String) v).trim();
            if (alg.length() == 0) {
                continue;
            }
            String caseKey = k.substring(K_BEST.length());
            if (caseKey.length() < 2 || K_ORDER_OLD.equals(k)) {
                continue;
            }
            Entry en = new Entry();
            en.caseKey = caseKey;
            en.alg = alg;
            Object gv = all.get("alg818_bgrp_" + caseKey);
            en.group = gv instanceof String ? ((String) gv).trim() : "";
            if (caseKey.startsWith("q")) {
                en.src = SRC_QM;
                en.type = caseKey.length() > 1 && caseKey.charAt(1) == 'C' ? "alg818_badge_corner" : "alg818_badge_edge";
                en.code = caseKey.substring(2);
                en.wordKey = en.code;
            } else if (caseKey.startsWith("c")) {
                en.src = SRC_CUSTOM;
                en.type = caseKey.length() > 1 && caseKey.charAt(1) == 'C' ? "alg818_badge_corner" : "alg818_badge_edge";
                en.code = caseKey.substring(2);
                en.wordKey = en.code;
            } else {
                en.src = SRC_BUILTIN;
                en.type = caseKey.charAt(0) == 'J' ? "alg818_badge_corner" : "alg818_badge_edge";
                en.code = caseKey;
                en.wordKey = caseKey.substring(1);
            }
            out.add(en);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int sa = srcOrder(a.src);
                int sb = srcOrder(b.src);
                if (sa != sb) {
                    return sa < sb ? -1 : 1;
                }
                return a.code.compareTo(b.code);
            }
        });
        return out;
    }

    private static int srcOrder(String s) {
        if (SRC_BUILTIN.equals(s)) {
            return 0;
        }
        return SRC_QM.equals(s) ? 1 : 2;
    }

    /** 清单排序：按来源（默认）/ 按步数 / 按分组（未分组的排最后）。 */
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

    private String wordOf(String code) {
        try {
            String w = LxWordStorage.currentWord(code);
            return w == null ? "" : w;
        } catch (Throwable t) {
            return "";
        }
    }

    private static int steps(String alg) {
        if (alg == null || alg.length() == 0) {
            return 0;
        }
        String t = alg.trim();
        if (t.length() == 0) {
            return 0;
        }
        return t.split("\\s+").length;
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        List<Entry> list = load();
        sortList(list);
        Log.i(TAG, "最优公式 " + list.size() + " 条，排序 " + order);
        TextView orderChip = (TextView) findViewById(id("alg818_best_order", "id"));
        if (orderChip != null) {
            orderChip.setText(str(ORDER_STEPS.equals(order) ? "alg818_best_order_steps"
                    : (ORDER_GROUP.equals(order) ? "alg818_best_order_group"
                    : "alg818_best_order_src")));
        }

        body.removeAllViews();
        if (head != null) {
            head.setText(fmt1(str("alg818_best_count"), Integer.valueOf(list.size())));
            head.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (list.isEmpty()) {
            if (scroll != null) {
                scroll.setVisibility(View.GONE);
            }
            if (empty != null) {
                empty.setVisibility(View.VISIBLE);
                empty.setText(str("alg818_best_empty"));
            }
            return;
        }
        if (scroll != null) {
            scroll.setVisibility(View.VISIBLE);
        }
        if (empty != null) {
            empty.setVisibility(View.GONE);
        }

        LinearLayout card = card();
        for (int i = 0; i < list.size(); i++) {
            card.addView(rowView(list.get(i), i > 0));
        }
        body.addView(card);
    }

    private View rowView(final Entry en, boolean divider) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        String word = wordOf(en.wordKey == null ? en.code : en.wordKey);
        title.setText(str(en.sourceName()) + " · " + str(en.type) + "  " + en.code
                + (word.length() > 0 ? "  " + word : ""));
        title.setTextSize(13f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(colorAttr("app_text_primary", 0xFF111111));
        texts.addView(title);

        TextView alg = new TextView(this);
        SpannableString sp = new SpannableString(en.alg);
        // 公式正文用等宽体，便于对比步法；不动颜色（这里没有交换子上下文）
        alg.setText(sp);
        alg.setTextSize(12f);
        alg.setTypeface(android.graphics.Typeface.MONOSPACE);
        alg.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        alg.setPadding(0, dp(2), 0, 0);
        texts.addView(alg);

        TextView len = new TextView(this);
        String groupPart = "";
        if (SRC_QM.equals(en.src) || SRC_CUSTOM.equals(en.src)) {
            groupPart = "  ·  " + ((en.group != null && en.group.length() > 0)
                    ? en.group : str("alg818_best_nogroup"));
        }
        len.setText(fmt1(str("alg818_moves"), Integer.valueOf(steps(en.alg))) + groupPart);
        len.setTextSize(11f);
        len.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        len.setPadding(0, dp(2), 0, 0);
        texts.addView(len);

        row.addView(texts);

        TextView del = new TextView(this);
        del.setText("×");
        del.setTextSize(16f);
        del.setGravity(Gravity.CENTER);
        del.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        del.setContentDescription(str("alg818_best_delete"));
        del.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                remove(en);
            }
        });
        row.addView(del);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jump(en);
            }
        });

        if (!divider) {
            return row;
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(colorAttr("app_lit_bg_1a000000", 0x1A000000));
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        wrap.addView(line);
        wrap.addView(row);
        return wrap;
    }

    private void jump(Entry en) {
        try {
            Intent i = new Intent(this, Alg818Activity.class);
            i.putExtra("target_code", en.caseKey);
            startActivity(i);
            Log.i(TAG, "跳到 " + en.caseKey);
        } catch (Throwable t) {
            Log.e(TAG, "跳转失败", t);
            Toast.makeText(this, str("alg818_find_none"), Toast.LENGTH_SHORT).show();
        }
    }

    private void remove(Entry en) {
        try {
            SPUtils.getInstance().remove(K_BEST + en.caseKey);
            Log.i(TAG, "取消最优 " + en.caseKey);
            Toast.makeText(this, fmt1(str("alg818_best_deleted"), en.code), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "取消最优失败", t);
        }
        render();
    }

    // ------------------------------------------------------------------ 视图工具

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(id("card_background", "drawable"));
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        c.setPadding(dp(16), dp(6), dp(16), dp(6));
        return c;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String str(String name) {
        int v = id(name, "string");
        return v == 0 ? name : getString(v);
    }

    private String fmt1(String f, Object a) {
        try {
            return String.format(f, a);
        } catch (Throwable t) {
            return f;
        }
    }

    private int id(String name, String type) {
        String key = type + "/" + name;
        Integer hit = IDS.get(key);
        if (hit != null) {
            return hit.intValue();
        }
        int v = 0;
        try {
            v = getResources().getIdentifier(name, type, getPackageName());
        } catch (Throwable ignored) {
            v = 0;
        }
        if (v != 0) {
            IDS.put(key, Integer.valueOf(v));
        }
        return v;
    }

    private int colorAttr(String name, int fallback) {
        try {
            int attr = getResources().getIdentifier(name, "attr", getPackageName());
            if (attr == 0) {
                return fallback;
            }
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(attr, tv, true)) {
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
