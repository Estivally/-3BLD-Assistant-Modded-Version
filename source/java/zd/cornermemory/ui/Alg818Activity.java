package zd.cornermemory.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import zd.cornermemory.utils.LxWordStorage;
import zd.cornermemory.utils.SPUtils;

/**
 * opt24：818 公式熟练度。
 *
 * <p>练的是盲拧 818（角块 378 + 棱块 440）的「联想词 → 公式」回忆：
 * 正面只给联想词和类型（角/棱），点一下才展开这个 case 的全部候选公式；
 * 每条公式右边一颗星，点星把该条选为「最优」，整行高亮。
 *
 * <p>调度沿用 {@link LxStudyActivity} 的 SRS 思路（忘记 / 模糊 / 记得 三档），
 * 但 key 前缀换成 {@code alg818_}，互不干扰。
 *
 * <p>数据来自 {@code assets/alg818.txt}（由 rebuild/make_alg818_assets.py 生成）：
 * 每行 {@code TYPE \t KEY \t REC \t ALG1 \t ALG2 ...}，TYPE=C 角块 / E 棱块，
 * REC 是原始库里的推荐下标（-1 表示无），行内第一条不一定是推荐。
 *
 * <p>没有 R 类：所有资源按名字 getIdentifier 取（见 build_java.sh 注释）。
 */
public class Alg818Activity extends BaseActivity {

    private static final String TAG = "Alg818";

    private static final String ASSET = "alg818.txt";

    /** 浅梦 202602 版公式集（由 rebuild/tools/alg818_qm_import.py 生成） */
    private static final String ASSET_QM = "alg818_qm.txt";

    /**
     * 公式源。两套数据的「case 身份」不同，所以进度分开存：
     * 内置集按默认编码方案（角缓冲 J=UFR、棱缓冲 G=UR）编排，键是 3 字母；
     * 浅梦集角块缓冲同为 UFR、棱块缓冲是 UF，键是 2 字母编码（缓冲位省略）。
     */
    private static final String K_SRC = "alg818_src";
    private static final String SRC_BUILTIN = "builtin";
    private static final String SRC_QM = "qm";
    /** 用户自己导入的公式集（分组 / 编码 / 公式 / 交换子 / 起手） */
    private static final String SRC_CUSTOM = "custom";

    /** 自定义集导入时手选的类型（C 角 / E 棱 / M 混合自动） */
    private static final String K_CUSTOM_TYPE = "alg818_custom_type";
    /** 当前分组筛选，按公式源分别记：alg818_group_<src>，空串=全部 */
    private static final String K_GROUP = "alg818_group_";
    /** 记忆顺序：编码序 / 分组序 */
    private static final String K_ORDER = "alg818_order";
    private static final String ORDER_CODE = "code";
    private static final String ORDER_GROUP = "group";

    private static final int REQ_IMPORT = 4201;
    /** 公式集管理页（多套自定义集） */
    private static final int REQ_SETS = 4202;

    /** SP 键前缀：与联想词 SRS（lx_srs_*）分开，互不影响 */
    private static final String K_BOX = "alg818_b_";
    private static final String K_DUE = "alg818_d_";
    private static final String K_REPS = "alg818_r_";
    private static final String K_LAPS = "alg818_l_";
    private static final String K_BEST = "alg818_best_";
    /** 最优公式所属分组（清单页显示用），键 = alg818_bgrp_<caseKey> */
    private static final String K_BGRP = "alg818_bgrp_";
    private static final String K_DAY = "alg818_day";
    private static final String K_DAY_NEW = "alg818_day_new";
    private static final String K_DAY_REV = "alg818_day_rev";

    /** 第 n 次「记得」后的间隔（毫秒），下标即 box */
    private static final long[] INTERVALS = {
            0L,
            1L * 24 * 60 * 60 * 1000,
            3L * 24 * 60 * 60 * 1000,
            7L * 24 * 60 * 60 * 1000,
            15L * 24 * 60 * 60 * 1000,
            30L * 24 * 60 * 60 * 1000,
            60L * 24 * 60 * 60 * 1000
    };

    private static final int NEW_PER_DAY = 20;
    private static final int NEW_BATCH = 10;
    private static final int MASTERED_BOX = 4;

    private static final int GRADE_AGAIN = 0;
    private static final int GRADE_HARD = 1;
    private static final int GRADE_GOOD = 2;

    private static final int DEFAULT_ROWS = 6;

    private static final int CAT_CORNER = 0;
    private static final int CAT_EDGE = 1;
    private static final int CAT_MIXED = 2;

    /** 一个 818 case：内部键 + 类型 + 推荐下标 + 全部候选公式（+ 浅梦集的分类/起手/备注） */
    private static final class Case {
        /** SP 里的身份键：内置集是 3 字母键，浅梦集是 "q" + 类型 + 2 字母编码 */
        final String key;
        final String type;
        /** 展示用的 2 字母编码（联想词就按它查） */
        final String code;
        /** 浅梦集的分类（U8/E8/Base/DU…），内置集为空 */
        final String cat;
        final String hand;
        final String comm;
        final String note;
        final int rec;
        final String[] algs;

        Case(String key, String type, int rec, String[] algs) {
            this(key, type, key.length() >= 3 ? key.substring(1) : key,
                    "", "", "", "", rec, algs);
        }

        Case(String key, String type, String code, String cat, String hand,
             String comm, String note, int rec, String[] algs) {
            this.key = key;
            this.type = type;
            this.code = code;
            this.cat = cat == null ? "" : cat;
            this.hand = hand == null ? "" : hand;
            this.comm = comm == null ? "" : comm;
            this.note = note == null ? "" : note;
            this.rec = rec;
            this.algs = algs;
        }

        /** 联想词用的两字母编码（自定义集可能写 3 字母带缓冲位，取后两位） */
        String pair() {
            return code.length() > 2 ? code.substring(code.length() - 2) : code;
        }

        boolean isCorner() {
            return "C".equals(type);
        }
    }

    private final List<Case> all = new ArrayList<Case>();
    private final List<Case> cornerCases = new ArrayList<Case>();
    private final List<Case> edgeCases = new ArrayList<Case>();
    private final Map<String, Case> byKey = new HashMap<String, Case>();
    private final List<Case> scope = new ArrayList<Case>();
    private final List<Case> queue = new ArrayList<Case>();
    private final Set<String> requeued = new HashSet<String>();
    private final Map<String, Integer> idCache = new HashMap<String, Integer>();

    private int cat = CAT_CORNER;
    private int cursor = 0;
    private int done = 0;
    private int emptyMore = 0;
    private boolean revealed = false;
    private boolean expanded = false;

    /** 当前公式源：builtin / qm / custom */
    private String src = SRC_BUILTIN;
    /** 当前分组筛选（空串=全部） */
    private String group = "";
    /** 记忆顺序：ORDER_CODE / ORDER_GROUP */
    private String order = ORDER_CODE;
    /** 当前公式源的分组（首次出现顺序）+ 分组 → 序号 */
    private final List<String> groups = new ArrayList<String>();
    private final Map<String, Integer> groupIndex = new HashMap<String, Integer>();

    private TextView srcBuiltin;
    private TextView srcQm;
    private TextView srcCustom;
    private TextView tvOrder;
    private HorizontalScrollView groupRow;
    private LinearLayout groupList;
    private TextView tabCorner;
    private TextView tabEdge;
    private TextView tabMixed;
    private TextView tvProgress;
    private TextView tvCounts;
    private TextView tvBadge;
    private TextView tvWord;
    private TextView tvHint;
    private TextView tvCase;
    private TextView tvAlgHead;
    private TextView tvMeta;
    private TextView tvExpand;
    private TextView tvEmptyTitle;
    private TextView tvEmptyDesc;
    private TextView tvNewBtn;
    private TextView btnAgain;
    private TextView btnHard;
    private TextView btnGood;
    private LinearLayout card;
    private LinearLayout backBox;
    private LinearLayout algList;
    private LinearLayout emptyBox;
    private ScrollView scroll;
    private ProgressBar bar;

    /** 数据版本戳：导入学习进度后 onResume 重建队列。 */
    private long dataVer;

    /** 本次导入时手选的类型（C / E / M），给解析器当默认 */
    private String importType = Alg818CustomSet.TYPE_AUTO;

    /** 公式源行末尾的两枚入口：公式集管理 / 浅梦说明 */
    private TextView tvSets;
    private TextView tvReadme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_alg818", "layout"));
        bindViews();
        if (card == null || tvWord == null) {
            Log.e(TAG, "布局缺 view，退出");
            finish();
            return;
        }
        src = SPUtils.getInstance().getString(K_SRC, SRC_BUILTIN);
        if (!SRC_QM.equals(src) && !SRC_CUSTOM.equals(src)) {
            src = SRC_BUILTIN;
        }
        if (SRC_CUSTOM.equals(src) && !Alg818CustomSet.exists(this)) {
            src = SRC_BUILTIN;
        }
        order = SPUtils.getInstance().getString(K_ORDER, ORDER_CODE);
        if (!ORDER_GROUP.equals(order)) {
            order = ORDER_CODE;
        }
        loadCases();
        group = SPUtils.getInstance().getString(groupKey(), "");
        if (!groups.contains(group)) {
            group = "";
        }
        if (!groupInCat(group, cat)) {
            group = "";
            SPUtils.getInstance().put(groupKey(), "");
        }
        resetDayIfNeeded();
        bindEvents();
        bindToolbar();
        setCategory(CAT_CORNER);
        dataVer = StudyDataIO.version();
        applyTarget(getIntent());
        Log.i(TAG, "818 装载: " + all.size() + " 个 case（角 " + cornerCases.size() + " / 棱 " + edgeCases.size() + "）");
    }

    @Override
    protected void onResume() {
        super.onResume();
        long v = StudyDataIO.version();
        if (v == dataVer) {
            return;
        }
        dataVer = v;
        Log.i(TAG, "学习数据已更新，重建队列");
        resetDayIfNeeded();
        buildQueue();
        showCurrent();
    }

    /** 工具栏：标题 + 返回 + 学习设置齿轮。 */
    private void bindToolbar() {
        View v = find("toolbar");
        if (!(v instanceof zd.cornermemory.view.CustomToolbar)) {
            return;
        }
        zd.cornermemory.view.CustomToolbar tb = (zd.cornermemory.view.CustomToolbar) v;
        tb.setTitle(str("alg818_title"));
        View back = tb.getBackButton();
        if (back != null) {
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { finish(); }
            });
        }
        StudySettingsDialog.attachGear(this, tb);
        // 工具栏右侧再加两枚入口：查找（定位到某个编码）/ ★（我的最优清单）
        LinearLayout right = tb.getRightButtonContainer();
        if (right != null) {
            right.addView(toolbarChip(str("alg818_best_btn"), new Runnable() {
                @Override
                public void run() {
                    openBest();
                }
            }), 0);
            right.addView(toolbarChip(str("alg818_find_btn"), new Runnable() {
                @Override
                public void run() {
                    locateDialog();
                }
            }), 0);
            Log.i(TAG, "工具栏入口已接线: 查找 / 我的最优");
        } else {
            Log.w(TAG, "rightButtonContainer 为空，查找/最优入口未接线");
        }
    }

    private TextView toolbarChip(String text, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(colorAttr("app_colorPrimary", 0xFF4F46E5));
        t.setBackgroundResource(id("score_toolbar_button_bg", "drawable"));
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return t;
    }

    private void openBest() {
        try {
            startActivity(new Intent(this, Alg818BestActivity.class));
        } catch (Throwable t) {
            Log.e(TAG, "打开最优清单失败", t);
        }
    }

    // ---------------------------------------------------------------- 查找定位

    private void locateDialog() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint(str("alg818_find_hint"));
        et.setTextSize(15f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(str("alg818_find_title"))
                .setView(box)
                .setPositiveButton(str("alg818_find_btn"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        locate(et.getText().toString());
                    }
                })
                .setNegativeButton(str("alg818_import_cancel"), null)
                .show();
    }

    /**
     * 按编码找 case 并把它拉到当前队首。
     *
     * <p>能匹配三种写法：内置集的 3 字母键（JWP）、浅梦/自定义集的 2 字母码（AD）、
     * 以及带前缀的内部键（qCAD / cCAD）。2 字母码在浅梦集里角/棱可能撞名，
     * 撞名时优先选当前分类下的那一个。
     */
    private void locate(String raw) {
        String q = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.US)
                .replace(" ", "").replace("-", "");
        if (q.length() == 0) {
            return;
        }
        // 三个源依次找：先当前源，再内置 / 浅梦 / 自定义；找到就切过去把卡放队首
        String start = src;
        String[] orderTry = {start, SRC_BUILTIN, SRC_QM, SRC_CUSTOM};
        for (int i = 0; i < orderTry.length; i++) {
            String s = orderTry[i];
            if (SRC_CUSTOM.equals(s) && !Alg818CustomSet.exists(this)) {
                continue;
            }
            if (!s.equals(src)) {
                switchSrc(s);
            }
            Case hit = findCase(q);
            if (hit != null) {
                Log.i(TAG, "查找到 " + hit.key + "（" + typeName(hit) + " " + hit.code + "）@ " + s);
                focusCase(hit);
                return;
            }
        }
        if (!start.equals(src)) {
            switchSrc(start);
        }
        Toast.makeText(this, fmt1(str("alg818_find_none"), q), Toast.LENGTH_SHORT).show();
        Log.i(TAG, "查找未命中: " + q + "（内置 / 浅梦 / 自定义都试过）");
    }

    /** 在当前已装载的公式源里找编码：先按内部键，再按展示码（撞名时优先当前分类）。 */
    private Case findCase(String q) {
        Case hit = byKey.get(q);
        if (hit == null && q.length() > 1 && (q.charAt(0) == 'Q' || q.charAt(0) == 'C')) {
            // 内部键是小写前缀（qCAD / cCAD），用户可能输成大写
            hit = byKey.get(Character.toLowerCase(q.charAt(0)) + q.substring(1));
        }
        if (hit != null) {
            return hit;
        }
        Case other = null;
        for (int i = 0; i < all.size(); i++) {
            Case c = all.get(i);
            if (q.equals(c.code) || q.equals(c.key)) {
                if (c.isCorner() == (cat != CAT_EDGE)) {
                    return c;
                }
                if (other == null) {
                    other = c;
                }
            }
        }
        return other;
    }

    /** 把某张卡放到队首并显示（查找、统计页跳转、最优清单共用）。 */
    private void focusCase(Case target) {
        if (target == null) {
            return;
        }
        if (group.length() > 0) {
            group = "";
            SPUtils.getInstance().put(groupKey(), "");
        }
        int want = target.isCorner() ? CAT_CORNER : CAT_EDGE;
        setCategory(want);
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).key.equals(target.key)) {
                cursor = i;
                done = i;
                showCurrent();
                Log.i(TAG, "定位到 " + target.key + " index=" + i);
                return;
            }
        }
        queue.add(cursor, target);
        showCurrent();
        Log.i(TAG, "补入 " + target.key + " 到队首");
    }

    /** 从统计页薄弱榜跳进来：把那张卡拉到队首。 */
    private void applyTarget(android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        String key = intent.getStringExtra("target_code");
        intent.removeExtra("target_code");
        if (key == null || key.length() == 0) {
            return;
        }
        // 浅梦集 key 以 q 开头、自定义集以 c 开头，跳转前先切到对应公式源
        if (key.startsWith("q") && !SRC_QM.equals(src)) {
            switchSrc(SRC_QM);
        } else if (key.startsWith("c") && !SRC_CUSTOM.equals(src)) {
            switchSrc(SRC_CUSTOM);
        } else if (!key.startsWith("q") && !key.startsWith("c") && !SRC_BUILTIN.equals(src)) {
            switchSrc(SRC_BUILTIN);
        }
        Case target = byKey.get(key);
        if (target == null) {
            return;
        }
        focusCase(target);
    }

    // ---------------------------------------------------------------- 视图绑定

    private void bindViews() {
        tabCorner = (TextView) find("alg818_tab_corner");
        tabEdge = (TextView) find("alg818_tab_edge");
        tabMixed = (TextView) find("alg818_tab_mixed");
        tvProgress = (TextView) find("alg818_progress");
        tvCounts = (TextView) find("alg818_counts");
        tvBadge = (TextView) find("alg818_badge");
        tvWord = (TextView) find("alg818_word");
        tvHint = (TextView) find("alg818_hint");
        tvCase = (TextView) find("alg818_case");
        tvAlgHead = (TextView) find("alg818_alg_head");
        tvMeta = (TextView) find("alg818_meta");
        tvExpand = (TextView) find("alg818_expand");
        tvEmptyTitle = (TextView) find("alg818_empty_title");
        tvEmptyDesc = (TextView) find("alg818_empty_desc");
        tvNewBtn = (TextView) find("alg818_new_btn");
        btnAgain = (TextView) find("alg818_again");
        btnHard = (TextView) find("alg818_hard");
        btnGood = (TextView) find("alg818_good");
        card = (LinearLayout) find("alg818_card");
        backBox = (LinearLayout) find("alg818_back");
        algList = (LinearLayout) find("alg818_alg_list");
        emptyBox = (LinearLayout) find("alg818_empty");
        scroll = (ScrollView) find("alg818_scroll");
        bar = (ProgressBar) find("alg818_bar");
        srcBuiltin = (TextView) find("alg818_src_builtin");
        srcQm = (TextView) find("alg818_src_qm");
        srcCustom = (TextView) find("alg818_src_custom");
        tvSets = (TextView) find("alg818_src_sets");
        tvReadme = (TextView) find("alg818_readme");
        tvOrder = (TextView) find("alg818_order");
        View gr = find("alg818_group_row");
        groupRow = gr instanceof HorizontalScrollView ? (HorizontalScrollView) gr : null;
        View gl = find("alg818_group_list");
        groupList = gl instanceof LinearLayout ? (LinearLayout) gl : null;
    }

    private void bindEvents() {
        bindClick("alg818_src_builtin", new Runnable() {
            @Override
            public void run() {
                switchSrc(SRC_BUILTIN);
            }
        });
        bindClick("alg818_src_qm", new Runnable() {
            @Override
            public void run() {
                switchSrc(SRC_QM);
            }
        });
        bindClick("alg818_src_custom", new Runnable() {
            @Override
            public void run() {
                switchSrc(SRC_CUSTOM);
            }
        });
        bindClick("alg818_src_import", new Runnable() {
            @Override
            public void run() {
                pickImportType();
            }
        });
        bindClick("alg818_src_sets", new Runnable() {
            @Override
            public void run() {
                openSets();
            }
        });
        bindClick("alg818_readme", new Runnable() {
            @Override
            public void run() {
                openReadme();
            }
        });
        bindClick("alg818_order", new Runnable() {
            @Override
            public void run() {
                order = ORDER_GROUP.equals(order) ? ORDER_CODE : ORDER_GROUP;
                SPUtils.getInstance().put(K_ORDER, order);
                Log.i(TAG, "记忆顺序 → " + order);
                setCategory(cat);
            }
        });
        bindClick("alg818_tab_corner", new Runnable() {
            @Override
            public void run() {
                if (cat != CAT_CORNER) setCategory(CAT_CORNER);
            }
        });
        bindClick("alg818_tab_edge", new Runnable() {
            @Override
            public void run() {
                if (cat != CAT_EDGE) setCategory(CAT_EDGE);
            }
        });
        bindClick("alg818_tab_mixed", new Runnable() {
            @Override
            public void run() {
                if (cat != CAT_MIXED) setCategory(CAT_MIXED);
            }
        });
        bindClick("alg818_card", new Runnable() {
            @Override
            public void run() {
                reveal();
            }
        });
        bindClick("alg818_expand", new Runnable() {
            @Override
            public void run() {
                expanded = !expanded;
                renderAlgs();
            }
        });
        bindClick("alg818_again", new Runnable() {
            @Override
            public void run() {
                grade(GRADE_AGAIN);
            }
        });
        bindClick("alg818_hard", new Runnable() {
            @Override
            public void run() {
                grade(GRADE_HARD);
            }
        });
        bindClick("alg818_good", new Runnable() {
            @Override
            public void run() {
                grade(GRADE_GOOD);
            }
        });
        bindClick("alg818_new_btn", new Runnable() {
            @Override
            public void run() {
                pullMore();
            }
        });
    }

    // ---------------------------------------------------------------- 数据装载

    private void loadCases() {
        all.clear();
        cornerCases.clear();
        edgeCases.clear();
        byKey.clear();
        groups.clear();
        groupIndex.clear();
        if (SRC_CUSTOM.equals(src)) {
            loadCustom();
            return;
        }
        boolean qm = SRC_QM.equals(src);
        String asset = qm ? ASSET_QM : ASSET;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(getAssets().open(asset), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }
                String[] f = line.split("\t");
                Case c = qm ? parseQm(f) : parseBuiltin(f);
                if (c == null) {
                    continue;
                }
                addCase(c);
            }
        } catch (Throwable t) {
            Log.e(TAG, "读取 " + asset + " 失败", t);
            Toast.makeText(this, "公式数据读取失败", Toast.LENGTH_LONG).show();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 自定义集：从 filesDir 读原文 → 解析 → 建 case。 */
    private void loadCustom() {
        String raw = Alg818CustomSet.load(this);
        if (raw == null || raw.trim().length() == 0) {
            Log.i(TAG, "自定义集为空");
            return;
        }
        Alg818CustomSet.SetInfo curSet = Alg818CustomSet.current(this);
        String hint = curSet == null || curSet.type == null
                ? SPUtils.getInstance().getString(K_CUSTOM_TYPE, Alg818CustomSet.TYPE_AUTO)
                : curSet.type;
        Alg818CustomSet.Result r = Alg818CustomSet.parse(raw, hint);
        for (int i = 0; i < r.items.size(); i++) {
            Alg818CustomSet.Item it = r.items.get(i);
            addCase(new Case("c" + it.type + it.code, it.type, it.code, it.group,
                    it.hand, it.comm, "", -1, it.algs.toArray(new String[0])));
        }
        Log.i(TAG, "自定义集装载: " + r.brief() + "，跳过 " + r.skipped + "，重复 "
                + r.duplicated + "，问题 " + r.errors.size());
        for (int i = 0; i < Math.min(3, r.errors.size()); i++) {
            Log.w(TAG, "自定义集问题: " + r.errors.get(i));
        }
    }

    private void addCase(Case c) {
        all.add(c);
        byKey.put(c.key, c);
        if (c.isCorner()) {
            cornerCases.add(c);
        } else {
            edgeCases.add(c);
        }
        if (c.cat != null && c.cat.length() > 0 && !groupIndex.containsKey(c.cat)) {
            groupIndex.put(c.cat, Integer.valueOf(groups.size()));
            groups.add(c.cat);
        }
    }

    /** 内置集：TYPE \t KEY \t REC \t ALG... */
    private Case parseBuiltin(String[] f) {
        if (f.length < 4) {
            return null;
        }
        String[] algs = new String[f.length - 3];
        for (int i = 3; i < f.length; i++) {
            algs[i - 3] = f[i];
        }
        return new Case(f[1], f[0], parseInt(f[2], -1), algs);
    }

    /** 浅梦集：TYPE \t KEY \t CAT \t HAND \t COMM \t NOTE \t ALG \t ALT \t ALT_COMM */
    private Case parseQm(String[] f) {
        if (f.length < 7) {
            return null;
        }
        String type = f[0];
        boolean corner = "C".equals(type);
        String code = qmCode(f[1], corner);
        String alt = f.length > 7 ? f[7] : "";
        boolean hasAlt = alt != null && alt.length() > 0;
        String[] algs = new String[hasAlt ? 2 : 1];
        algs[0] = f[6];
        if (hasAlt) {
            algs[1] = alt;
        }
        return new Case("q" + type + code, type, code, f[2], f[3], f[4], f[5], 0, algs);
    }

    /**
     * 资产里的键 → 浅梦原来的 2 字母编码。
     *
     * <p>生成资产时把「缓冲字母」对调过（浅梦棱块缓冲是 A=UF，内置集是 G=UR），
     * 这里再对调回来；角块两边缓冲同为 UFR，直接取后两位。
     */
    private static String qmCode(String key, boolean corner) {
        String s = key.length() >= 3 ? key.substring(1) : key;
        if (corner) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == 'A') {
                sb.append('G');
            } else if (ch == 'B') {
                sb.append('H');
            } else if (ch == 'G') {
                sb.append('A');
            } else if (ch == 'H') {
                sb.append('B');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private void resetDayIfNeeded() {
        SPUtils sp = SPUtils.getInstance();
        int today = todayKey();
        int last = sp.getInt(K_DAY, 0);
        if (last != today) {
            Log.i(TAG, "跨天重置今日计数: " + last + " → " + today);
            sp.put(K_DAY, today);
            sp.put(K_DAY_NEW, 0);
            sp.put(K_DAY_REV, 0);
        }
    }

    private int todayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    // ---------------------------------------------------------------- 队列

    /**
     * 切源 / 切公式集 / 导入之后挑一个「有内容」的分类：
     * 偏好分类还有卡就保持不变，空了才回落到有卡的那一边（棱块专用集不会落在角块 tab 上）。
     */
    private int autoCategory(int prefer) {
        boolean c = !cornerCases.isEmpty();
        boolean e = !edgeCases.isEmpty();
        if (prefer == CAT_CORNER && c) {
            return CAT_CORNER;
        }
        if (prefer == CAT_EDGE && e) {
            return CAT_EDGE;
        }
        if (prefer == CAT_MIXED && (c || e)) {
            return CAT_MIXED;
        }
        if (c) {
            return CAT_CORNER;
        }
        if (e) {
            return CAT_EDGE;
        }
        return prefer;
    }

    private void setCategory(int c) {
        cat = c;
        normalizeGroup();
        updateTabs();
        buildQueue();
        showCurrent();
    }

    /**
     * 分组筛选只在它所属的分类里才有意义：分组是角块/棱块各自的概念，
     * 从「角块 + R.D.」切到「棱块」时若不把分组清掉，队列会空（用户会以为没内容）。
     */
    private void normalizeGroup() {
        if (group.length() == 0) {
            return;
        }
        if (!groupInCat(group, cat)) {
            Log.i(TAG, "分组 " + group + " 不属于 " + catName() + "，回到全部");
            group = "";
            SPUtils.getInstance().put(groupKey(), "");
        }
    }

    private boolean groupInCat(String g, int c) {
        if (g == null || g.length() == 0) {
            return true;
        }
        List<Case> l = c == CAT_CORNER ? cornerCases : (c == CAT_EDGE ? edgeCases : all);
        for (int i = 0; i < l.size(); i++) {
            if (g.equals(l.get(i).cat)) {
                return true;
            }
        }
        return false;
    }

    /** 分组筛选按「源 + 公式集」分开记：浅梦一套、内置一套、每个自定义集各一套。 */
    private String groupKey() {
        return groupKeyOf(src);
    }

    private String groupKeyOf(String s) {
        if (SRC_CUSTOM.equals(s)) {
            return K_GROUP + SRC_CUSTOM + "_" + Alg818CustomSet.currentId(this);
        }
        return K_GROUP + s;
    }

    /** 切公式源：内置 818 / 浅梦 202602 / 自定义。三套 case 身份不同，进度各自独立。 */
    private void switchSrc(String s) {
        if (s == null || s.equals(src)) {
            return;
        }
        if (SRC_CUSTOM.equals(s) && !Alg818CustomSet.exists(this)) {
            // 还没导入过：直接走导入流程，别切到一个空源
            Toast.makeText(this, str("alg818_custom_empty"), Toast.LENGTH_LONG).show();
            pickImportType();
            return;
        }
        src = s;
        SPUtils.getInstance().put(K_SRC, s);
        loadCases();
        group = SPUtils.getInstance().getString(groupKeyOf(s), "");
        if (!groups.contains(group)) {
            group = "";
        }
        Log.i(TAG, "公式源 → " + s + "：" + all.size() + " 个 case（角 "
                + cornerCases.size() + " / 棱 " + edgeCases.size() + "），分组 " + groups.size()
                + "，当前分组「" + (group.length() == 0 ? "全部" : group) + "」");
        setCategory(autoCategory(CAT_CORNER));
    }

    private void updateTabs() {
        styleTab(tabCorner, cat == CAT_CORNER);
        styleTab(tabEdge, cat == CAT_EDGE);
        styleTab(tabMixed, cat == CAT_MIXED);
        styleTab(srcBuiltin, SRC_BUILTIN.equals(src));
        styleTab(srcQm, SRC_QM.equals(src));
        styleTab(srcCustom, SRC_CUSTOM.equals(src));
        if (tvReadme != null) {
            // 说明讲的是浅梦那两套 Excel，内置/自定义源下没有意义
            tvReadme.setVisibility(SRC_QM.equals(src) ? View.VISIBLE : View.GONE);
        }
        renderOrderChip();
        renderGroupChips();
    }

    private void renderOrderChip() {
        if (tvOrder == null) {
            return;
        }
        boolean byGroup = ORDER_GROUP.equals(order);
        tvOrder.setText(str(byGroup ? "alg818_order_group" : "alg818_order_code"));
        styleTab(tvOrder, byGroup);
    }

    private void styleTab(TextView t, boolean on) {
        if (t == null) {
            return;
        }
        t.setBackgroundResource(id(on ? "alg818_row_selected_bg" : "control_chip_bg", "drawable"));
        t.setTextColor(on ? colorAttr("app_lit_text_4f46e5", 0xFF4F46E5)
                : colorAttr("app_text_primary", 0xFF111827));
    }

    private void buildQueue() {
        queue.clear();
        requeued.clear();
        cursor = 0;
        done = 0;
        scope.clear();
        if (cat == CAT_CORNER) {
            scope.addAll(cornerCases);
        } else if (cat == CAT_EDGE) {
            scope.addAll(edgeCases);
        } else {
            scope.addAll(cornerCases);
            scope.addAll(edgeCases);
        }
        if (group.length() > 0) {
            for (int i = scope.size() - 1; i >= 0; i--) {
                if (!group.equals(scope.get(i).cat)) {
                    scope.remove(i);
                }
            }
        }

        SPUtils sp = SPUtils.getInstance();
        long now = System.currentTimeMillis();
        if (cat == CAT_MIXED) {
            // 混合 = 随机练习：从「学过的」卡里洗牌抽一批（一张一轮只出现一次），不看到期时间、不受每日额度限制
            List<Case> learned = new ArrayList<Case>();
            for (int i = 0; i < scope.size(); i++) {
                if (sp.getInt(K_BOX + scope.get(i).key, 0) > 0) {
                    learned.add(scope.get(i));
                }
            }
            boolean fallback = learned.isEmpty();
            List<Case> pool = fallback ? new ArrayList<Case>(scope) : learned;
            java.util.Collections.shuffle(pool, new java.util.Random(System.nanoTime()));
            queue.addAll(pool);
            Log.i(TAG, "随机练习 " + pool.size() + " 张（角 " + countCorner(pool) + " / 棱 "
                    + (pool.size() - countCorner(pool)) + "，池="
                    + (fallback ? "全部（还没学过任何卡）" : "已学 " + learned.size() + " 张") + "）");
            return;
        }
        final Map<String, Long> dueAt = new HashMap<String, Long>();
        List<Case> due = new ArrayList<Case>();
        List<Case> fresh = new ArrayList<Case>();
        for (int i = 0; i < scope.size(); i++) {
            Case c = scope.get(i);
            int box = sp.getInt(K_BOX + c.key, 0);
            if (box <= 0) {
                fresh.add(c);
                continue;
            }
            long t = sp.getLong(K_DUE + c.key, 0L);
            if (t <= now) {
                due.add(c);
                dueAt.put(c.key, Long.valueOf(t));
            }
        }
        Collections.sort(due, new Comparator<Case>() {
            @Override
            public int compare(Case a, Case b) {
                Long ta = dueAt.get(a.key);
                Long tb = dueAt.get(b.key);
                long va = ta == null ? 0L : ta.longValue();
                long vb = tb == null ? 0L : tb.longValue();
                if (va < vb) {
                    return -1;
                }
                if (va > vb) {
                    return 1;
                }
                return a.key.compareTo(b.key);
            }
        });
        sortFresh(fresh);

        int revLimit = StudyPrefs.revLimit(StudyPrefs.SRC_ALG);
        if (revLimit > 0 && due.size() > revLimit) {
            due = new ArrayList<Case>(due.subList(0, revLimit));
        }
        queue.addAll(due);
        int dayNew = sp.getInt(K_DAY_NEW, 0);
        int allowance = Math.max(0, StudyPrefs.newLimit(StudyPrefs.SRC_ALG) - dayNew);
        int take = Math.min(allowance, fresh.size());
        for (int i = 0; i < take; i++) {
            queue.add(fresh.get(i));
        }
        Log.i(TAG, "队列 " + catName() + " due=" + due.size() + " new=" + take
                + " total=" + queue.size() + " (今日已新学 " + dayNew + "，上限 new="
                + StudyPrefs.newLimit(StudyPrefs.SRC_ALG) + " rev="
                + (revLimit == 0 ? "不限" : String.valueOf(revLimit)) + ")");
    }

    private String catName() {
        return cat == CAT_CORNER ? "corner" : (cat == CAT_EDGE ? "edge" : "mixed");
    }

    /**
     * 新卡排序：编码序（按 2/3 字母编码）或分组序（先按分组出现顺序，组内再按编码）。
     * 混合 + 编码序时角棱轮流，保证两边都练到（不再随机打乱，顺序可预期、可复现）。
     */
    private void sortFresh(List<Case> fresh) {
        if (fresh.size() < 2) {
            return;
        }
        if (ORDER_GROUP.equals(order)) {
            Collections.sort(fresh, new Comparator<Case>() {
                @Override
                public int compare(Case a, Case b) {
                    int ga = groupOrder(a.cat);
                    int gb = groupOrder(b.cat);
                    if (ga != gb) {
                        return ga < gb ? -1 : 1;
                    }
                    return a.code.compareTo(b.code);
                }
            });
            if (cat == CAT_MIXED) {
                // 分组序 + 混合：分组顺序保留，但角棱交替（原来角组全排完才排棱组）
                interleave(fresh);
            }
            return;
        }
        if (cat == CAT_MIXED) {
            Collections.sort(fresh, new Comparator<Case>() {
                @Override
                public int compare(Case a, Case b) {
                    return a.code.compareTo(b.code);
                }
            });
            interleave(fresh);
            return;
        }
        Collections.sort(fresh, new Comparator<Case>() {
            @Override
            public int compare(Case a, Case b) {
                return a.code.compareTo(b.code);
            }
        });
    }

    private int countCorner(List<Case> l) {
        int n = 0;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).isCorner()) {
                n++;
            }
        }
        return n;
    }

    private int groupOrder(String g) {
        Integer i = g == null ? null : groupIndex.get(g);
        return i == null ? Integer.MAX_VALUE : i.intValue();
    }

    /** 角棱交替：角1 棱1 角2 棱2 …（按传进来的顺序，调用方先排好） */
    private void interleave(List<Case> fresh) {
        Log.i(TAG, "新卡交替: 角 " + countCorner(fresh) + " / 棱 " + (fresh.size() - countCorner(fresh)));
        List<Case> cs = new ArrayList<Case>();
        List<Case> es = new ArrayList<Case>();
        for (int i = 0; i < fresh.size(); i++) {
            Case c = fresh.get(i);
            if (c.isCorner()) {
                cs.add(c);
            } else {
                es.add(c);
            }
        }
        fresh.clear();
        int i = 0;
        int j = 0;
        while (i < cs.size() || j < es.size()) {
            if (i < cs.size()) {
                fresh.add(cs.get(i));
                i++;
            }
            if (j < es.size()) {
                fresh.add(es.get(j));
                j++;
            }
        }
    }

    /** 分组 chips：「全部」+ 当前公式源的分组；没有分组（内置集）就只留排序开关 */
    private void renderGroupChips() {
        if (groupList == null) {
            return;
        }
        groupList.removeAllViews();
        addGroupChip(str("alg818_group_all"), "", -1);
        for (int i = 0; i < groups.size(); i++) {
            String g = groups.get(i);
            if (!groupInCat(g, cat)) {
                continue;
            }
            addGroupChip(g, g, countInGroup(g));
        }
        if (groupRow != null) {
            groupRow.setVisibility(View.VISIBLE);
        }
    }

    private void addGroupChip(String label, final String value, int count) {
        TextView t = new TextView(this);
        t.setText(count >= 0 ? label + " " + count : label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setFocusable(true);
        t.setClickable(true);
        boolean on = value.equals(group);
        t.setBackgroundResource(id(on ? "alg818_row_selected_bg" : "control_chip_bg", "drawable"));
        t.setTextColor(on ? colorAttr("app_lit_text_4f46e5", 0xFF4F46E5)
                : colorAttr("app_text_primary", 0xFF111827));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (value.equals(group)) {
                    return;
                }
                group = value;
                SPUtils.getInstance().put(groupKey(), value);
                Log.i(TAG, "分组筛选 → " + (value.length() == 0 ? "全部" : value));
                setCategory(cat);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
        lp.setMarginStart(dp(6));
        groupList.addView(t, lp);
    }

    private int countInGroup(String g) {
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            if (g.equals(all.get(i).cat)) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 卡片

    private Case current() {
        return cursor >= 0 && cursor < queue.size() ? queue.get(cursor) : null;
    }

    private void showCurrent() {
        Case c = current();
        if (c == null) {
            showEmpty();
            return;
        }
        revealed = false;
        expanded = false;
        emptyBox.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        card.setVisibility(View.VISIBLE);
        backBox.setVisibility(View.GONE);
        tvHint.setVisibility(View.VISIBLE);

        tvBadge.setText(typeName(c));
        setWordWithRetry(c);
        tvCase.setText(displayOf(c));
        tvMeta.setText("");
        algList.removeAllViews();
        setGradeAlpha(false);
        scroll.scrollTo(0, 0);
        updateHeader();
    }

    private void reveal() {
        Case c = current();
        if (c == null || revealed) {
            return;
        }
        revealed = true;
        tvHint.setVisibility(View.GONE);
        backBox.setVisibility(View.VISIBLE);
        renderAlgs();
        updateMeta(c);
        setGradeAlpha(true);
    }

    private void renderAlgs() {
        Case c = current();
        if (c == null || algList == null) {
            return;
        }
        algList.removeAllViews();
        String best = bestOf(c.key);
        int show = expanded ? c.algs.length : Math.min(DEFAULT_ROWS, c.algs.length);
        for (int i = 0; i < show; i++) {
            algList.addView(makeRow(c, i, best));
        }
        boolean more = c.algs.length > DEFAULT_ROWS;
        if (tvExpand != null) {
            tvExpand.setVisibility(more ? View.VISIBLE : View.GONE);
            if (more) {
                tvExpand.setText(expanded ? str("alg818_collapse")
                        : fmt1(str("alg818_expand"), Integer.valueOf(c.algs.length)));
            }
        }
        if (tvAlgHead != null) {
            tvAlgHead.setText(fmt1(str("alg818_head"), Integer.valueOf(c.algs.length)));
        }
    }

    private View makeRow(final Case c, final int index, final String best) {
        final String alg = c.algs[index];
        final boolean selected = alg.equals(best);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(6), dp(6));
        if (selected) {
            row.setBackgroundResource(id("alg818_row_selected_bg", "drawable"));
        }

        TextView idx = new TextView(this);
        idx.setText(String.valueOf(index + 1));
        idx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        idx.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        idx.setGravity(Gravity.CENTER);
        row.addView(idx, new LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        // 交换子在公式里的位置高亮（token 级最长连续匹配，命中 ≥3 才上色）
        tv.setText(AlgHighlight.decorate(alg, c.comm, colorAttr("app_colorPrimary", 0xFF4F46E5)));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(colorAttr("app_text_primary", 0xFF111827));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(dp(2), 1f);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (index == c.rec) {
            TextView rec = new TextView(this);
            rec.setText(str("alg818_rec"));
            rec.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            rec.setTextColor(colorAttr("app_lit_text_4f46e5", 0xFF4F46E5));
            rec.setGravity(Gravity.CENTER);
            rec.setBackgroundResource(id("control_chip_bg", "drawable"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(16));
            lp.setMarginStart(dp(4));
            row.addView(rec, lp);
        }

        TextView len = new TextView(this);
        len.setText(fmt1(str("alg818_moves"), Integer.valueOf(moveCount(alg))));
        len.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        len.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        LinearLayout.LayoutParams lpLen = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpLen.setMarginStart(dp(6));
        row.addView(len, lpLen);

        TextView star = new TextView(this);
        star.setText(selected ? "★" : "☆");
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        star.setTextColor(selected ? colorAttr("app_colorPrimary", 0xFF4F46E5)
                : colorAttr("app_text_secondary", 0xFF9CA3AF));
        star.setGravity(Gravity.CENTER);
        star.setClickable(true);
        star.setFocusable(true);
        star.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBest(c, alg);
            }
        });
        LinearLayout.LayoutParams lpStar = new LinearLayout.LayoutParams(dp(36), dp(36));
        lpStar.setMarginStart(dp(4));
        row.addView(star, lpStar);

        return row;
    }

    /** 把 alg 选为该 case 的最优；再点一次同一条则取消。顺手记下所属分组，清单页要显示。 */
    private void toggleBest(Case c, String alg) {
        String key = c.key;
        SPUtils sp = SPUtils.getInstance();
        String cur = sp.getString(K_BEST + key, "");
        if (alg.equals(cur)) {
            sp.remove(K_BEST + key);
            sp.remove(K_BGRP + key);
            Toast.makeText(this, str("alg818_best_clear"), Toast.LENGTH_SHORT).show();
            Log.i(TAG, "取消最优 " + key);
        } else {
            sp.put(K_BEST + key, alg);
            if (c.cat != null && c.cat.length() > 0) {
                sp.put(K_BGRP + key, c.cat);
            } else {
                sp.remove(K_BGRP + key);
            }
            Toast.makeText(this, str("alg818_best_set"), Toast.LENGTH_SHORT).show();
            Log.i(TAG, "最优 " + key + " = " + alg + "（分组 " + (c.cat == null ? "" : c.cat) + "）");
        }
        renderAlgs();
    }

    private String bestOf(String key) {
        try {
            String s = SPUtils.getInstance().getString(K_BEST + key, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 步数：公式里以空格分隔的 token 数（R U R' U' 记 4 步） */
    private int moveCount(String alg) {
        if (alg == null || alg.length() == 0) {
            return 0;
        }
        String s = alg.trim();
        if (s.length() == 0) {
            return 0;
        }
        return s.split("\\s+").length;
    }

    private void updateMeta(Case c) {
        SPUtils sp = SPUtils.getInstance();
        int box = sp.getInt(K_BOX + c.key, 0);
        StringBuilder sb = new StringBuilder();
        if (box <= 0) {
            sb.append(str("alg818_meta_new"));
        } else {
            int reps = sp.getInt(K_REPS + c.key, 0);
            int laps = sp.getInt(K_LAPS + c.key, 0);
            sb.append(fmt3(str("alg818_meta"), levelName(box),
                    Integer.valueOf(reps), Integer.valueOf(laps)));
        }
        // 浅梦集额外带交换子 / 起手 / 备注
        if (c.comm.length() > 0) {
            sb.append('\n').append(fmt1(str("alg818_qm_comm"), c.comm));
        }
        if (c.hand.length() > 0) {
            sb.append('\n').append(fmt1(str("alg818_qm_hand"), c.hand));
        }
        if (c.note.length() > 0) {
            sb.append('\n').append(fmt1(str("alg818_qm_note"), c.note));
        }
        tvMeta.setText(AlgHighlight.decorateComm(sb.toString(), c.comm,
                colorAttr("app_colorPrimary", 0xFF4F46E5)));
    }

    private String levelName(int box) {
        if (box <= 0) {
            return str("alg818_level_new");
        }
        if (box >= MASTERED_BOX) {
            return str("alg818_level_3");
        }
        return box >= 2 ? str("alg818_level_2") : str("alg818_level_1");
    }

    /** 词典还没加载好时先显示占位，稍后再补一次（深链直达 818 时常见）。 */
    private void setWordWithRetry(final Case c) {
        final String none = str("lx_study_no_word");
        String w = wordOf(c);
        tvWord.setText(w);
        if (!none.equals(w)) {
            return;
        }
        final String key = c.key;
        tvWord.postDelayed(new Runnable() {
            @Override
            public void run() {
                Case now = current();
                if (now == null || !key.equals(now.key)) {
                    return;
                }
                String again = wordOf(now);
                if (!none.equals(again)) {
                    tvWord.setText(again);
                }
            }
        }, 400);
    }

    private String wordOf(Case c) {        String w = null;
        try {
            w = LxWordStorage.currentWord(c.pair());
        } catch (Throwable t) {
            Log.w(TAG, "取联想词失败: " + c.key, t);
        }
        if (w == null || w.length() == 0) {
            return str("lx_study_no_word");
        }
        return w;
    }

    private String typeName(Case c) {
        return str(c.isCorner() ? "alg818_badge_corner" : "alg818_badge_edge");
    }

    /** 卡片背面第一行：编码 · 角/棱 [· 分类]；内置集用 3 字母键（带缓冲位），其余用原始编码 */
    private String displayOf(Case c) {
        StringBuilder sb = new StringBuilder(SRC_BUILTIN.equals(src) ? c.key : c.code);
        sb.append(" · ").append(typeName(c));
        if (c.cat.length() > 0) {
            sb.append(" · ").append(c.cat);
        }
        return sb.toString();
    }

    private void setGradeAlpha(boolean on) {
        float a = on ? 1f : 0.45f;
        if (btnAgain != null) {
            btnAgain.setAlpha(a);
        }
        if (btnHard != null) {
            btnHard.setAlpha(a);
        }
        if (btnGood != null) {
            btnGood.setAlpha(a);
        }
    }

    // ---------------------------------------------------------------- 评分

    private void grade(int g) {
        Case c = current();
        if (c == null) {
            return;
        }
        if (!revealed) {
            Toast.makeText(this, str("alg818_reveal_first"), Toast.LENGTH_SHORT).show();
            return;
        }
        // 先记统计（内部读评分前的 box，用来区分新卡/复习）
        StudyStats.record(StudyStats.SRC_ALG, c.key, g);
        SPUtils sp = SPUtils.getInstance();
        int box = sp.getInt(K_BOX + c.key, 0);
        int reps = sp.getInt(K_REPS + c.key, 0);
        int laps = sp.getInt(K_LAPS + c.key, 0);
        long now = System.currentTimeMillis();
        boolean isNew = box <= 0;

        if (g == GRADE_AGAIN) {
            box = 1;
            sp.put(K_DUE + c.key, now + 10 * 60 * 1000L);
            if (!isNew) {
                laps++;
                // 本会话再排一次，让用户这次就再看一眼（每卡限一次，避免死循环）
                // 随机练习一轮里同一张卡不重复出现（忘记的交给 SRS，10 分钟后再来）
                if (cat != CAT_MIXED && !requeued.contains(c.key)) {
                    requeued.add(c.key);
                    queue.add(c);
                }
            }
        } else if (g == GRADE_HARD) {
            if (box < 1) {
                box = 1;
            }
            long half = INTERVALS[Math.min(box, INTERVALS.length - 1)] / 2;
            sp.put(K_DUE + c.key, now + Math.max(60 * 60 * 1000L, half));
        } else {
            box = Math.min(6, box + 1);
            reps++;
            sp.put(K_DUE + c.key, now + INTERVALS[Math.min(box, INTERVALS.length - 1)]);
        }
        sp.put(K_BOX + c.key, box);
        sp.put(K_REPS + c.key, reps);
        sp.put(K_LAPS + c.key, laps);
        if (isNew) {
            sp.put(K_DAY_NEW, sp.getInt(K_DAY_NEW, 0) + 1);
        } else {
            sp.put(K_DAY_REV, sp.getInt(K_DAY_REV, 0) + 1);
        }

        long due = sp.getLong(K_DUE + c.key, now);
        Log.i(TAG, "评分 " + c.key + " g=" + g + " box=" + box + " reps=" + reps
                + " laps=" + laps + " due=+" + ((due - now) / 60000L) + "min");

        done++;
        cursor++;
        showCurrent();
    }

    // ---------------------------------------------------------------- 头部 / 空态

    private void updateHeader() {
        SPUtils sp = SPUtils.getInstance();
        int remainDue = 0;
        int remainNew = 0;
        for (int i = cursor; i < queue.size(); i++) {
            if (sp.getInt(K_BOX + queue.get(i).key, 0) > 0) {
                remainDue++;
            } else {
                remainNew++;
            }
        }
        if (tvProgress != null) {
            tvProgress.setText(fmt2(str("alg818_progress"), Integer.valueOf(done),
                    Integer.valueOf(queue.size())));
        }
        if (tvCounts != null) {
            if (cat == CAT_MIXED) {
                int rc = 0;
                int re = 0;
                for (int i = cursor; i < queue.size(); i++) {
                    if (queue.get(i).isCorner()) {
                        rc++;
                    } else {
                        re++;
                    }
                }
                tvCounts.setText(fmt2(str("alg818_random_counts"), Integer.valueOf(rc),
                        Integer.valueOf(re)));
            } else {
                tvCounts.setText(fmt2(str("alg818_counts"), Integer.valueOf(remainDue),
                        Integer.valueOf(remainNew)));
            }
        }
        if (bar != null) {
            bar.setProgress(queue.size() == 0 ? 100
                    : (int) (done * 100L / Math.max(1, queue.size())));
        }
    }

    private void showEmpty() {
        scroll.setVisibility(View.GONE);
        card.setVisibility(View.GONE);
        emptyBox.setVisibility(View.VISIBLE);

        SPUtils sp = SPUtils.getInstance();
        int mastered = 0;
        int familiar = 0;
        int weak = 0;
        int fresh = 0;
        for (int i = 0; i < scope.size(); i++) {
            int box = sp.getInt(K_BOX + scope.get(i).key, 0);
            if (box <= 0) {
                fresh++;
            } else if (box >= MASTERED_BOX) {
                mastered++;
            } else if (box >= 2) {
                familiar++;
            } else {
                weak++;
            }
        }
        int allowance = Math.max(0, NEW_PER_DAY - sp.getInt(K_DAY_NEW, 0));
        // 「再学」是手动加餐，不受每日额度限制（与 LxStudy 一致）；额度只用来限制自动入队
        emptyMore = Math.min(StudyPrefs.batch(StudyPrefs.SRC_ALG), fresh);
        Log.i(TAG, "空态可用额度=" + allowance);

        if (tvEmptyTitle != null) {
            tvEmptyTitle.setText(str(cat == CAT_MIXED ? "alg818_random_done_title"
                    : "alg818_empty_title"));
        }
        if (tvEmptyDesc != null) {
            tvEmptyDesc.setText(fmt4(str("alg818_empty_desc"), Integer.valueOf(mastered),
                    Integer.valueOf(familiar), Integer.valueOf(weak), Integer.valueOf(fresh)));
        }
        if (tvNewBtn != null && cat == CAT_MIXED) {
            tvNewBtn.setVisibility(View.VISIBLE);
            tvNewBtn.setText(str("alg818_random_again"));
        } else if (tvNewBtn != null) {
            if (emptyMore > 0) {
                tvNewBtn.setVisibility(View.VISIBLE);
                tvNewBtn.setText(fmt1(str("alg818_new_action"), Integer.valueOf(emptyMore)));
            } else {
                tvNewBtn.setVisibility(View.GONE);
            }
        }
        if (tvProgress != null) {
            tvProgress.setText(fmt2(str("alg818_progress"), Integer.valueOf(done),
                    Integer.valueOf(queue.size())));
        }
        if (tvCounts != null) {
            tvCounts.setText(cat == CAT_MIXED
                    ? fmt2(str("alg818_random_counts"), Integer.valueOf(0), Integer.valueOf(0))
                    : fmt2(str("alg818_counts"), Integer.valueOf(0), Integer.valueOf(0)));
        }
        if (bar != null) {
            bar.setProgress(100);
        }
        Log.i(TAG, "空态 " + catName() + " 熟练=" + mastered + " 熟悉=" + familiar
                + " 生疏=" + weak + " 未学=" + fresh + " 可补=" + emptyMore);
    }

    /** 空态「再学 N 个」：手动加一批新卡，不受每日额度限制（同 LxStudy 的做法） */
    private void pullMore() {
        if (cat == CAT_MIXED) {
            buildQueue();
            showCurrent();
            Log.i(TAG, "混合随机练习重新抽一批");
            return;
        }
        if (emptyMore <= 0) {
            Toast.makeText(this, str("alg818_empty_toast"), Toast.LENGTH_SHORT).show();
            return;
        }
        SPUtils sp = SPUtils.getInstance();
        List<Case> fresh = new ArrayList<Case>();
        for (int i = 0; i < scope.size(); i++) {
            Case c = scope.get(i);
            if (sp.getInt(K_BOX + c.key, 0) <= 0) {
                fresh.add(c);
            }
        }
        sortFresh(fresh);
        int take = Math.min(emptyMore, fresh.size());        for (int i = 0; i < take; i++) {
            queue.add(fresh.get(i));
        }
        Log.i(TAG, "手动补新卡 " + take + " 个");
        showCurrent();
    }

    // ---------------------------------------------------------------- 公式集管理 / 说明

    /** 公式集管理页：多套自定义集（命名 / 切换 / 删除）。 */
    private void openSets() {
        try {
            startActivityForResult(new Intent(this, Alg818SetListActivity.class), REQ_SETS);
        } catch (Throwable t) {
            Log.e(TAG, "打开公式集管理失败", t);
        }
    }

    /** 浅梦公式集说明（两份 Excel 的「说明」sheet + 各分页整页备注）。 */
    private void openReadme() {
        try {
            startActivity(new Intent(this, QmReadmeActivity.class));
        } catch (Throwable t) {
            Log.e(TAG, "打开说明页失败", t);
        }
    }

    // ---------------------------------------------------------------- 自定义集导入

    /** 先选这套公式是角块 / 棱块 / 混合（自动判断），再去挑文件。 */
    private void pickImportType() {
        final String[] labels = {
                str("alg818_import_type_corner"),
                str("alg818_import_type_edge"),
                str("alg818_import_type_auto")
        };
        final String[] vals = {
                Alg818CustomSet.TYPE_CORNER,
                Alg818CustomSet.TYPE_EDGE,
                Alg818CustomSet.TYPE_AUTO
        };
        try {
            new AlertDialog.Builder(this)
                    .setTitle(str("alg818_import_type_title"))
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            startImport(vals[which]);
                        }
                    })
                    .setNegativeButton(str("alg818_import_cancel"), null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "打开类型选择失败", t);
        }
    }

    private void startImport(String type) {
        importType = type;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            Log.e(TAG, "打开文件选择器失败", t);
            Toast.makeText(this, str("alg818_import_read_fail"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETS) {
            boolean imp = data != null
                    && data.getBooleanExtra(Alg818SetListActivity.EXTRA_START_IMPORT, false);
            if (imp) {
                pickImportType();
                return;
            }
            if (Alg818CustomSet.exists(this)) {
                // 用户从「公式集管理」里点了一套：不管当前是什么源，都切到自定义源并重新装载
                src = SRC_CUSTOM;
                SPUtils.getInstance().put(K_SRC, SRC_CUSTOM);
                group = "";
                SPUtils.getInstance().put(groupKey(), "");
                loadCases();
                setCategory(autoCategory(cat));
                Alg818CustomSet.SetInfo cur = Alg818CustomSet.current(this);
                Log.i(TAG, "公式集已切换 → 「" + (cur == null ? "?" : cur.name) + "」，装载 "
                        + all.size() + " 个 case（角 " + cornerCases.size() + " / 棱 "
                        + edgeCases.size() + "），分组 " + groups.size());
            } else if (SRC_CUSTOM.equals(src)) {
                switchSrc(SRC_BUILTIN);
            }
            return;
        }
        if (requestCode != REQ_IMPORT) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String text = null;
        try {
            text = readText(uri);
        } catch (Throwable t) {
            Log.e(TAG, "读取导入文件失败: " + uri, t);
        }
        if (text == null || text.trim().length() == 0) {
            Toast.makeText(this, str("alg818_import_empty"), Toast.LENGTH_SHORT).show();
            return;
        }
        showImportPreview(text, importType, defaultSetName(uri));
    }

    private String readText(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > Alg818CustomSet.MAX_BYTES) {
                    Log.w(TAG, "导入文件过大: >" + Alg818CustomSet.MAX_BYTES);
                    Toast.makeText(this, str("alg818_import_big"), Toast.LENGTH_LONG).show();
                    return null;
                }
                bo.write(buf, 0, n);
            }
            return Alg818CustomSet.decode(bo.toByteArray());
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 预览：识别出多少编码 / 公式 / 分组；顺便让用户给这一套起个名字。 */
    private void showImportPreview(final String text, final String type, String defName) {
        final Alg818CustomSet.Result r = Alg818CustomSet.parse(text, type);
        final EditText nameEt = new EditText(this);
        nameEt.setSingleLine(true);
        nameEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameEt.setHint(str("alg818_import_name"));
        nameEt.setText(defName == null ? "" : defName);
        nameEt.setSelection(nameEt.getText().length());
        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(20);
        nameBox.setPadding(padH, dp(8), padH, 0);
        TextView nameLab = new TextView(this);
        nameLab.setText(str("alg818_import_name"));
        nameLab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        nameLab.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        nameBox.addView(nameLab);
        nameBox.addView(nameEt, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Log.i(TAG, "导入预览: " + r.brief() + " 跳过 " + r.skipped + " 重复 " + r.duplicated
                + " 问题 " + r.errors.size());
        if (!r.ok()) {
            StringBuilder sb = new StringBuilder(str("alg818_import_fail"));
            appendProblems(sb, r);
            new AlertDialog.Builder(this)
                    .setTitle(str("alg818_import_title"))
                    .setMessage(sb.toString())
                    .setPositiveButton(str("alg818_import_cancel"), null)
                    .show();
            return;
        }
        StringBuilder sb = new StringBuilder(fmt5(str("alg818_import_msg"),
                Integer.valueOf(r.items.size()), Integer.valueOf(r.formulaCount),
                Integer.valueOf(r.groups.size()), Integer.valueOf(r.cornerCount),
                Integer.valueOf(r.edgeCount)));
        if (r.skipped > 0 || r.duplicated > 0) {
            sb.append('\n').append(fmt2(str("alg818_import_warn"),
                    Integer.valueOf(r.skipped), Integer.valueOf(r.duplicated)));
        }
        if (r.suspectCount > 0) {
            StringBuilder ex = new StringBuilder();
            for (int i = 0; i < r.suspectExamples.size(); i++) {
                if (i > 0) {
                    ex.append("；");
                }
                ex.append(r.suspectExamples.get(i));
            }
            sb.append('\n').append(fmt2(str("alg818_import_suspect"),
                    Integer.valueOf(r.suspectCount), ex.toString()));
        }
        appendProblems(sb, r);
        new AlertDialog.Builder(this)
                .setTitle(str("alg818_import_title"))
                .setMessage(sb.toString())
                .setView(nameBox)
                .setPositiveButton(str("alg818_import_ok"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String nm = nameEt.getText() == null ? "" : nameEt.getText().toString();
                        applyImported(text, type, nm);
                    }
                })
                .setNegativeButton(str("alg818_import_cancel"), null)
                .show();
    }

    private void appendProblems(StringBuilder sb, Alg818CustomSet.Result r) {
        int n = Math.min(5, r.errors.size());
        for (int i = 0; i < n; i++) {
            sb.append('\n').append(r.errors.get(i));
        }
        if (r.errors.size() > n) {
            sb.append('\n').append("…还有 ").append(r.errors.size() - n).append(" 条问题");
        }
    }

    private void applyImported(String text, String type, String name) {
        if (Alg818CustomSet.list(this).size() >= 20) {
            Toast.makeText(this, str("alg818_set_limit"), Toast.LENGTH_LONG).show();
            return;
        }
        Alg818CustomSet.Result r = Alg818CustomSet.parse(text, type);
        Alg818CustomSet.SetInfo si = Alg818CustomSet.create(this, name, type, text, r);
        if (si == null) {
            Toast.makeText(this, str("alg818_import_read_fail"), Toast.LENGTH_SHORT).show();
            return;
        }
        SPUtils.getInstance().put(K_CUSTOM_TYPE, type);
        SPUtils.getInstance().put(K_SRC, SRC_CUSTOM);
        src = SRC_CUSTOM;
        loadCases();
        group = "";
        SPUtils.getInstance().put(groupKey(), "");
        Log.i(TAG, "自定义集导入完成: " + all.size() + " 个 case，分组 " + groups.size()
                + "，套 " + si.id + "「" + si.name + "」");
        setCategory(autoCategory(CAT_CORNER));
        Toast.makeText(this, fmt2(str("alg818_set_saved"), si.name, Integer.valueOf(all.size())),
                Toast.LENGTH_SHORT).show();
    }

    /** 从文件名猜一个公式集名字（去掉扩展名，太长截断）。 */
    private String defaultSetName(Uri uri) {
        String name = null;
        android.database.Cursor cur = null;
        try {
            cur = getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cur.getString(idx);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读文件名失败", t);
        } finally {
            if (cur != null) {
                try {
                    cur.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (name == null || name.trim().length() == 0) {
            name = str("alg818_src_custom");
        }
        name = name.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if (name.length() > 24) {
            name = name.substring(0, 24);
        }
        return name;
    }

    // ---------------------------------------------------------------- 小工具

    private View find(String idName) {
        int i = id(idName, "id");
        if (i == 0) {
            Log.w(TAG, "找不到 id: " + idName);
            return null;
        }
        return findViewById(i);
    }

    private int id(String name, String type) {
        String key = type + "/" + name;
        Integer cached = idCache.get(key);
        if (cached != null) {
            return cached.intValue();
        }
        int v = 0;
        try {
            v = getResources().getIdentifier(name, type, getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "getIdentifier 失败: " + key, t);
        }
        idCache.put(key, Integer.valueOf(v));
        return v;
    }

    private String str(String name) {
        int i = id(name, "string");
        if (i == 0) {
            return name;
        }
        try {
            return getString(i);
        } catch (Throwable t) {
            return name;
        }
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

    private String fmt4(String f, Object a, Object b, Object c, Object d) {
        try {
            return String.format(f, a, b, c, d);
        } catch (Throwable t) {
            return f;
        }
    }

    private String fmt5(String f, Object a, Object b, Object c, Object d, Object e) {
        try {
            return String.format(f, a, b, c, d, e);
        } catch (Throwable t) {
            return f;
        }
    }

    private void bindClick(String idName, final Runnable r) {
        View v = find(idName);
        if (v == null) {
            return;
        }
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.e(TAG, "点击处理失败", t);
                }
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int colorAttr(String name, int fallback) {
        try {
            int a = id(name, "attr");
            if (a == 0) {
                return fallback;
            }
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(a, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
                if (tv.resourceId != 0) {
                    return getResources().getColor(tv.resourceId);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "取颜色失败: " + name, t);
        }
        return fallback;
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
