package zd.cornermemory.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zd.cornermemory.utils.Dictionary;
import zd.cornermemory.utils.LxWordStorage;
import zd.cornermemory.utils.SPUtils;
import zd.cornermemory.view.CustomToolbar;

/**
 * 学习统计页：总体 / 联想词 / 818 三个口径。
 *
 * <p>每张卡片都是代码拼的（不给每种卡片写一份 layout），数据全部来自 {@link StudyStats}，
 * 设置读写走 {@link StudyPrefs}，导出导入走 {@link StudyDataIO}。
 *
 * <p>薄弱榜点一行直接跳到对应学习页，并把那张卡拉到队首（{@code target_code}）。
 */
public class StudyStatsActivity extends BaseActivity {

    private static final String TAG = "StudyStatsPage";

    private static final int REQ_IMPORT = 1001;

    private static final String SRC_ALL = "all";

    private static final int WEAK_N = 10;

    private final HashMap<String, Integer> IDS = new HashMap<String, Integer>();

    private TextView tabAll;
    private TextView tabLx;
    private TextView tabAlg;
    private LinearLayout body;

    private String src = SRC_ALL;
    private int lxTotal = 0;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_study_stats", "layout"));

        tabAll = (TextView) findViewById(id("study_stats_tab_all", "id"));
        tabLx = (TextView) findViewById(id("study_stats_tab_lx", "id"));
        tabAlg = (TextView) findViewById(id("study_stats_tab_alg", "id"));
        body = (LinearLayout) findViewById(id("study_stats_body", "id"));

        CustomToolbar tb = (CustomToolbar) findViewById(id("toolbar", "id"));
        if (tb != null) {
            tb.setTitle(str("study_stats_title"));
            View back = tb.getBackButton();
            if (back != null) {
                back.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { finish(); }
                });
            }
            StudySettingsDialog.attachGear(this, tb);
        }

        tabAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSrc(SRC_ALL); }
        });
        tabLx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSrc(StudyStats.SRC_LX); }
        });
        tabAlg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSrc(StudyStats.SRC_ALG); }
        });

        lxTotal = countLxTotal();
        Log.i(TAG, "联想词总数 " + lxTotal);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (body != null) {
            render();
        }
    }

    private int countLxTotal() {
        int n = 0;
        try {
            Map<String, String> dic = Dictionary.dic;
            if (dic == null) {
                return 0;
            }
            for (String code : dic.keySet()) {
                if (LxWordStorage.builtinWord(code).length() > 0) {
                    n++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "统计联想词总数失败", t);
        }
        return n;
    }

    // ------------------------------------------------------------------ 渲染

    private void setSrc(String s) {
        src = s;
        render();
    }

    private void render() {
        if (body == null) {
            return;
        }
        if (lxTotal <= 0) {
            // 冷启动时词典可能还没加载完，这里补算一次，避免总数显示 0。
            lxTotal = countLxTotal();
        }
        body.removeAllViews();
        updateTabs();
        if (SRC_ALL.equals(src)) {
            renderAll();
        } else {
            renderOne(src);
        }
        renderDataCard();
    }

    private void updateTabs() {
        styleTab(tabAll, SRC_ALL.equals(src));
        styleTab(tabLx, StudyStats.SRC_LX.equals(src));
        styleTab(tabAlg, StudyStats.SRC_ALG.equals(src));
    }

    private void styleTab(TextView t, boolean on) {
        if (t == null) {
            return;
        }
        t.setTextColor(on ? colorAttr("app_colorPrimary", 0xFF4F46E5)
                : colorAttr("app_text_primary", 0xFF111111));
        t.setAlpha(on ? 1f : 0.6f);
    }

    private void renderAll() {
        int streak = StudyStats.streakOf(StudyStats.SRC_LX, StudyStats.SRC_ALG);
        int newToday = StudyStats.todayOf("new", StudyStats.SRC_LX, StudyStats.SRC_ALG);
        int revToday = StudyStats.todayOf("rev", StudyStats.SRC_LX, StudyStats.SRC_ALG);
        int again = StudyStats.todayOf("again", StudyStats.SRC_LX, StudyStats.SRC_ALG);
        int hard = StudyStats.todayOf("hard", StudyStats.SRC_LX, StudyStats.SRC_ALG);
        int good = StudyStats.todayOf("good", StudyStats.SRC_LX, StudyStats.SRC_ALG);

        int[] cLx = StudyStats.counts(StudyStats.SRC_LX, lxTotal);
        int[] cAlg = StudyStats.counts(StudyStats.SRC_ALG, 818);
        int total = lxTotal + 818;
        int[] cAll = {cLx[0] + cAlg[0], cLx[1] + cAlg[1], cLx[2] + cAlg[2]};

        body.addView(overviewCard(streak, newToday, revToday, again, hard, good, total, cAll));
        body.addView(daysCard(str("study_stats_last7"), str("study_stats_last7_hint"),
                StudyStats.SRC_LX, StudyStats.SRC_ALG));
        body.addView(dueCard(str("study_stats_due7"), StudyStats.SRC_LX, StudyStats.SRC_ALG));
        body.addView(sourceCard(str("study_stats_src_lx"), StudyStats.SRC_LX, lxTotal, cLx));
        body.addView(sourceCard(str("study_stats_src_alg"), StudyStats.SRC_ALG, 818, cAlg));
    }

    private void renderOne(String s) {
        int total = StudyStats.SRC_ALG.equals(s) ? 818 : lxTotal;
        int streak = StudyStats.streak(s);
        int newToday = StudyStats.today(s, "new");
        int revToday = StudyStats.today(s, "rev");
        int again = StudyStats.today(s, "again");
        int hard = StudyStats.today(s, "hard");
        int good = StudyStats.today(s, "good");
        int[] c = StudyStats.counts(s, total);

        body.addView(overviewCard(streak, newToday, revToday, again, hard, good, total, c));
        body.addView(daysCard(str("study_stats_last7"), str("study_stats_last7_hint"), s));
        body.addView(dueCard(str("study_stats_due7"), s));
        body.addView(boxCard(s));
        body.addView(weakCard(s));
    }

    // ------------------------------------------------------------------ 卡片

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(id("card_background", "drawable"));
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private TextView titleView(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(colorAttr("app_text_primary", 0xFF111111));
        return t;
    }

    private TextView lineView(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private TextView bigView(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(28f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(colorAttr("app_colorPrimary", 0xFF4F46E5));
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private LinearLayout overviewCard(int streak, int newToday, int revToday,
                                      int again, int hard, int good, int total, int[] c) {
        LinearLayout card = card();
        card.addView(titleView(str("study_stats_overview")));
        card.addView(bigView(streak > 0 ? fmt1(str("study_stats_streak"), streak)
                : str("study_stats_streak_none")));
        card.addView(lineView(fmt2(str("study_stats_today"), newToday, revToday)));

        int rated = again + hard + good;
        if (rated > 0) {
            card.addView(lineView(fmt2(str("study_stats_accuracy"), good * 100 / rated, rated)));
        } else {
            card.addView(lineView(str("study_stats_accuracy_none")));
        }

        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        nums.setPadding(0, dp(12), 0, 0);
        nums.addView(statCell(fmt1(str("study_stats_mastered"), c[0]), 0));
        nums.addView(statCell(fmt1(str("study_stats_learning"), c[1]), 1));
        nums.addView(statCell(fmt1(str("study_stats_unseen"), c[2]), 2));
        card.addView(nums);
        card.addView(lineView(fmt1(str("study_stats_total"), total)));
        return card;
    }

    private View statCell(String text, int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (index > 0) {
            lp.leftMargin = dp(8);
        }
        box.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(index == 0 ? colorAttr("app_lit_text_059669", 0xFF059669)
                : (index == 1 ? colorAttr("app_lit_text_4f46e5", 0xFF4F46E5)
                : colorAttr("app_text_secondary", 0xFF6B7280)));
        box.addView(t);
        return box;
    }

    /** 近 7 天：深色 = 新学、浅色 = 复习（多来源合并）。 */
    private LinearLayout daysCard(String title, String hint, String... srcs) {
        int[] n = new int[7];
        int[] r = new int[7];
        for (String s : srcs) {
            int[] d = StudyStats.lastDays(s, 7);
            for (int i = 0; i < 7; i++) {
                n[i] += d[i * 2];
                r[i] += d[i * 2 + 1];
            }
        }
        return barCard(title, hint, n, r, false);
    }

    /** 未来 7 天到期张数（多来源合并，单色柱）。 */
    private LinearLayout dueCard(String title, String... srcs) {
        int[] n = new int[7];
        for (String s : srcs) {
            int[] d = StudyStats.dueNext7(s);
            for (int i = 0; i < 7; i++) {
                n[i] += d[i];
            }
        }
        return barCard(title, null, n, null, true);
    }

    private LinearLayout barCard(String title, String hint, int[] n, int[] r, boolean future) {
        LinearLayout card = card();
        card.addView(titleView(title));
        if (hint != null) {
            card.addView(lineView(hint));
        }
        int max = 1;
        for (int i = 0; i < n.length; i++) {
            int v = n[i] + (r == null ? 0 : r[i]);
            if (v > max) {
                max = v;
            }
        }
        int barMax = dp(88);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barMax + dp(4)));
        row.setPadding(0, dp(10), 0, 0);

        for (int i = 0; i < n.length; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            clp.leftMargin = dp(3);
            clp.rightMargin = dp(3);
            col.setLayoutParams(clp);

            int total = n[i] + (r == null ? 0 : r[i]);
            if (total > 0) {
                int hn = Math.max(2, n[i] * barMax / max);
                int hr = r == null ? 0 : Math.max(r[i] == 0 ? 0 : 2, r[i] * barMax / max);
                if (hr > 0) {
                    View top = new View(this);
                    top.setBackground(rounded(colorAttr("app_lit_text_4f46e5", 0xFF4F46E5), 3, 0x55));
                    top.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, hr));
                    col.addView(top);
                }
                View bottom = new View(this);
                bottom.setBackground(rounded(colorAttr("app_colorPrimary", 0xFF4F46E5), 3, 0xFF));
                bottom.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, hn));
                col.addView(bottom);
            } else {
                View dot = new View(this);
                dot.setBackground(rounded(colorAttr("app_text_secondary", 0xFF6B7280), 1, 0x33));
                dot.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
                col.addView(dot);
            }
            row.addView(col);
        }
        card.addView(row);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setPadding(0, dp(6), 0, 0);
        Calendar cal = Calendar.getInstance();
        if (!future) {
            cal.add(Calendar.DAY_OF_MONTH, -(n.length - 1));
        }
        for (int i = 0; i < n.length; i++) {
            TextView t = new TextView(this);
            if (future) {
                t.setText(i == 0 ? "今天" : String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
            } else {
                t.setText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
            }
            t.setTextSize(10f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            t.setLayoutParams(lp);
            labels.addView(t);
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        card.addView(labels);

        int sum = 0;
        for (int i = 0; i < n.length; i++) {
            sum += n[i] + (r == null ? 0 : r[i]);
        }
        if (sum == 0) {
            card.addView(lineView(str("study_stats_empty")));
        }
        return card;
    }

    private LinearLayout boxCard(String s) {
        LinearLayout card = card();
        card.addView(titleView(str("study_stats_box")));
        card.addView(lineView(str("study_stats_box_hint")));
        int[] h = StudyStats.boxHistogram(s);
        int max = 1;
        for (int i = 0; i < h.length; i++) {
            if (h[i] > max) {
                max = h[i];
            }
        }
        for (int i = 0; i < h.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);

            TextView label = new TextView(this);
            label.setText(String.valueOf(i));
            label.setTextSize(11f);
            label.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
            label.setLayoutParams(new LinearLayout.LayoutParams(dp(20),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(label);

            LinearLayout track = new LinearLayout(this);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setBackground(rounded(0x1A000000, 4, 0xFF));
            track.setLayoutParams(new LinearLayout.LayoutParams(0, dp(8), 1f));
            if (h[i] > 0) {
                track.setWeightSum(max);
                View fill = new View(this);
                int col = i >= 4 ? colorAttr("app_lit_text_059669", 0xFF059669)
                        : (i == 0 ? colorAttr("app_text_secondary", 0xFF6B7280)
                        : colorAttr("app_colorPrimary", 0xFF4F46E5));
                fill.setBackground(rounded(col, 4, 0xFF));
                fill.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, h[i]));
                track.addView(fill);
            }
            row.addView(track);

            TextView num = new TextView(this);
            num.setText(String.valueOf(h[i]));
            num.setTextSize(11f);
            num.setGravity(Gravity.END);
            num.setTextColor(colorAttr("app_text_primary", 0xFF111111));
            num.setLayoutParams(new LinearLayout.LayoutParams(dp(44),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(num);
            card.addView(row);
        }
        return card;
    }

    private LinearLayout weakCard(final String s) {
        LinearLayout card = card();
        List<String> weak = StudyStats.weakTop(s, WEAK_N);
        card.addView(titleView(fmt1(str("study_stats_weak"), WEAK_N)));
        if (weak.isEmpty()) {
            card.addView(lineView(str("study_stats_weak_empty")));
            return card;
        }
        for (final String code : weak) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));

            TextView name = new TextView(this);
            String word = wordOf(s, code);
            // 浅梦集的 case 键是 "q" + 角/棱 + 2 字母编码，展示时去掉前缀
            String label = code.startsWith("q")
                    ? str("alg818_src_qm") + " " + code.substring(2) : code;
            name.setText(label + (word.length() > 0 ? "  " + word : ""));
            name.setTextSize(13f);
            name.setTextColor(colorAttr("app_text_primary", 0xFF111111));
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            TextView laps = new TextView(this);
            int n = StudyStats.lapsOf(s, code);
            laps.setText(fmt2(str("study_stats_weak_row"), n,
                    fmt2(str("study_stats_weak_level"),
                            levelName(StudyStats.boxOf(s, code)), StudyStats.boxOf(s, code))));
            laps.setTextSize(11f);
            laps.setTextColor(colorAttr("app_lit_text_dc2626", 0xFFDC2626));
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
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openTarget(s, code); }
            });
            card.addView(row);
        }
        return card;
    }

    private LinearLayout sourceCard(String title, final String s, int total, int[] c) {
        LinearLayout card = card();
        card.addView(titleView(title));
        card.addView(lineView(fmt3(str("study_stats_three_counts"), c[0], c[1], c[2])));
        card.addView(lineView(fmt1(str("study_stats_total"), total)));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setSrc(s); }
        });
        return card;
    }

    private void renderDataCard() {
        LinearLayout card = card();
        card.addView(titleView(str("study_stats_data")));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);

        TextView exp = chip(str("study_stats_export"));
        exp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doExport(); }
        });
        row.addView(exp);

        TextView imp = chip(str("study_stats_import"));
        imp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPickImport(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                dp(40), 1f);
        lp.leftMargin = dp(8);
        imp.setLayoutParams(lp);
        row.addView(imp);

        card.addView(row);
        body.addView(card);
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(colorAttr("app_text_primary", 0xFF111111));
        t.setBackgroundResource(id("control_chip_bg", "drawable"));
        t.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        return t;
    }

    // ------------------------------------------------------------------ 导出 / 导入

    private void doExport() {
        try {
            int[] n = new int[1];
            String content = StudyDataIO.buildJson(n);
            String name = StudyDataIO.exportName();
            Uri pub = StudyDataIO.exportToDownloads(this, name, content);
            if (pub != null) {
                // 写到公共下载目录：用户能在文件管理器里找到，导入时也能从系统文件选择器选到。
                StudyDataIO.shareUri(this, pub);
                Toast.makeText(this, fmt1(str("study_stats_export_downloads"), n[0]),
                        Toast.LENGTH_LONG).show();
                Log.i(TAG, "导出到下载目录 " + pub);
                return;
            }
            File f = StudyDataIO.exportToFile(this);
            StudyDataIO.share(this, f);
            Toast.makeText(this, fmt1(str("study_stats_export_ok"), n[0]), Toast.LENGTH_SHORT).show();
            Log.i(TAG, "导出文件 " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "导出失败", t);
            Toast.makeText(this, fmt1(str("study_stats_export_fail"), String.valueOf(t.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void doPickImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(this, fmt1(str("study_stats_import_fail"), String.valueOf(t.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) {
            return;
        }
        final Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        int n = StudyDataIO.peekCount(this, uri);
        if (n < 0) {
            Toast.makeText(this, str("study_stats_import_bad"), Toast.LENGTH_LONG).show();
            return;
        }
        String msg = fmt2(str("study_stats_import_confirm"), n, currentCount());
        new AlertDialog.Builder(this)
                .setTitle(str("study_stats_import"))
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doImport(uri); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int currentCount() {
        int n = 0;
        try {
            Map<String, ?> all = zd.cornermemory.utils.SPUtils.getInstance().getAll();
            if (all != null) {
                for (String k : all.keySet()) {
                    if (k != null && (k.startsWith("lx_srs_") || k.startsWith("alg818_")
                            || k.startsWith("lx_h_"))) {
                        n++;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private void doImport(Uri uri) {
        try {
            int n = StudyDataIO.importFrom(this, uri);
            Toast.makeText(this, fmt1(str("study_stats_import_ok"), n), Toast.LENGTH_SHORT).show();
            render();
        } catch (Throwable t) {
            Log.w(TAG, "导入失败", t);
            Toast.makeText(this, fmt1(str("study_stats_import_fail"), String.valueOf(t.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ 设置

    private void showSettings() {
        StudySettingsDialog.show(this, new Runnable() {
            @Override public void run() { render(); }
        });
    }

    // ------------------------------------------------------------------ 跳转

    private void openTarget(String s, String code) {
        Intent i;
        if (StudyStats.SRC_ALG.equals(s)) {
            i = new Intent(this, Alg818Activity.class);
        } else {
            i = new Intent(this, LxStudyActivity.class);
        }
        i.putExtra("target_code", code);
        startActivity(i);
    }

    /** 818 里给这个 case 标过的 ★ 最优公式（没有就空串）。 */
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
        return t.split("\\s+").length;
    }

    private String wordOf(String s, String code) {
        try {
            if (StudyStats.SRC_ALG.equals(s)) {
                if (code.startsWith("q")) {
                    return LxWordStorage.currentWord(code.substring(2));
                }
                return LxWordStorage.currentWord(code.substring(1));
            }
            return LxWordStorage.currentWord(code);
        } catch (Throwable t) {
            return "";
        }
    }

    private String levelName(int box) {
        if (box <= 0) {
            return str("alg818_level_new");
        }
        if (box >= 4) {
            return str("alg818_level_3");
        }
        return box >= 2 ? str("alg818_level_2") : str("alg818_level_1");
    }

    // ------------------------------------------------------------------ 小工具

    private GradientDrawable rounded(int color, int radiusDp, int alpha) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setAlpha(alpha);
        d.setCornerRadius(dp(radiusDp));
        return d;
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

    private String fmt2(String f, Object a, Object b) {
        try {
            return String.format(f, a, b);
        } catch (Throwable t) {
            return f;
        }
    }

    private String fmt3(String f, Object a, Object b, Object c) {
        try {
            return String.format(f, a, b, c);
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
