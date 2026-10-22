package zd.cornermemory.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zd.cornermemory.utils.Dictionary;
import zd.cornermemory.utils.LxPicStorageUtils;
import zd.cornermemory.utils.LxWordStorage;
import zd.cornermemory.utils.SPUtils;

/**
 * 「联想词学习」页：Anki 式间隔重复（SRS）。
 *
 * <p><b>卡片</b>：正面只显示编码，点一下才翻出联想词与图片（图片是记忆法，提前看到等于泄题）。
 *
 * <p><b>评分三档</b>：
 * <ul>
 *   <li>忘记 —— box 归 1，10 分钟后到期；本次会话里还会再排一次（同一张卡最多补一次，防死循环）。</li>
 *   <li>模糊 —— box 不变，间隔取当前档的一半（至少 1 小时）。</li>
 *   <li>记得 —— box 进一档，间隔按 1/3/7/15/30/60 天递进。</li>
 * </ul>
 * 于是「回忆成功率低」的编码会自然被压回短间隔、反复出现，成功率高的编码被推到 60 天档，
 * 复习量自动向薄弱编码倾斜。
 *
 * <p><b>队列</b>：先排所有到期复习（按到期时间升序），再补新词（每天上限 {@link #NEW_PER_DAY}，
 * 当天已学数记在 SP 里，跨天自动清零）。空态可手动「再学 N 个新词」。
 *
 * <p><b>存储</b>：全部走 SPUtils，键前缀 {@code lx_srs_}，与 {@link LxWordStorage} 的
 * {@code <code>_LX} 词条互不干扰。评分即时落盘，退出不丢。
 *
 * <p><b>视图 id 一律按名字取</b>（{@code getIdentifier}）：新资源不进 public.xml。
 */
public class LxStudyActivity extends BaseActivity {

    private static final String TAG = "LxStudy";

    /** 自定义图片路径的 SP 键后缀（与原版一致，别改） */
    private static final String PIC_SUFFIX = "_LXPIC";

    /** 卡片方向：正向（看编码想联想词）/ 反向（看联想词想编码） */
    private static final String K_DIR = "lx_study_dir";
    private static final String DIR_FWD = "fwd";
    private static final String DIR_REV = "rev";

    /** 选图（卡片内换图） */
    private static final int REQ_PIC = 4301;

    /** SRS 状态键：box 档位 / 到期时间 / 成功次数 / 遗忘次数 */
    private static final String K_BOX = "lx_srs_b_";
    private static final String K_DUE = "lx_srs_d_";
    private static final String K_REPS = "lx_srs_r_";
    private static final String K_LAPS = "lx_srs_l_";
    /** 当天计数：日期戳 yyyyMMdd / 新学数 / 复习数 */
    private static final String K_DAY = "lx_srs_day";
    private static final String K_DAY_NEW = "lx_srs_day_new";
    private static final String K_DAY_REV = "lx_srs_day_rev";

    private static final long MIN_MS = 60L * 1000L;
    private static final long HOUR_MS = 60L * MIN_MS;
    private static final long DAY_MS = 24L * HOUR_MS;

    /** box → 间隔；下标 0 表示未学 */
    private static final long[] INTERVALS = {
            0L,
            DAY_MS,
            3L * DAY_MS,
            7L * DAY_MS,
            15L * DAY_MS,
            30L * DAY_MS,
            60L * DAY_MS,
    };

    /** 每天新词上限 */
    private static final int NEW_PER_DAY = 20;
    /** 空态点「再学」一次补几张 */
    private static final int NEW_BATCH = 10;
    /** 掌握判定：box >= 4 即间隔 15 天以上 */
    private static final int MASTERED_BOX = 4;

    private static final int GRADE_AGAIN = 0;
    private static final int GRADE_HARD = 1;
    private static final int GRADE_GOOD = 2;

    private static final HashMap<String, Integer> IDS = new HashMap<String, Integer>();

    /** 全部可学编码（有内置联想词的），已排序 */
    private final List<String> allCodes = new ArrayList<String>();
    /** 本次会话队列 */
    private final List<String> queue = new ArrayList<String>();
    /** 本次会话里因「忘记」补排的次数，每张卡最多 1 次 */
    private final HashMap<String, Integer> requeued = new HashMap<String, Integer>();

    private int cursor;
    private int done;
    private boolean revealed;

    private TextView tvProgress;
    private TextView tvCounts;
    private TextView tvDir;
    private TextView tvEdit;
    private TextView tvCode;
    private TextView tvHint;
    private TextView tvWord;
    private TextView tvMeta;
    private TextView tvEmptyTitle;
    private TextView tvEmptyDesc;
    private TextView tvNewBtn;
    private TextView btnAgain;
    private TextView btnHard;
    private TextView btnGood;
    private ImageView ivPic;
    private ProgressBar bar;
    private View card;
    private View empty;
    private View actions;

    /** 数据版本戳：导入学习进度后 onResume 重建队列。 */
    private long dataVer;

    /** 反向卡：正面给联想词、藏编码 */
    private boolean reverse;

    /** 换图时记住是哪个编码（选图是异步的） */
    private String editCode;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_lx_study", "layout"));

        tvProgress = (TextView) findViewById(id("lx_study_progress", "id"));
        tvCounts = (TextView) findViewById(id("lx_study_counts", "id"));
        tvDir = (TextView) findViewById(id("lx_study_dir", "id"));
        tvEdit = (TextView) findViewById(id("lx_study_edit", "id"));
        tvCode = (TextView) findViewById(id("lx_study_code", "id"));
        tvHint = (TextView) findViewById(id("lx_study_hint", "id"));
        tvWord = (TextView) findViewById(id("lx_study_word", "id"));
        tvMeta = (TextView) findViewById(id("lx_study_meta", "id"));
        tvEmptyTitle = (TextView) findViewById(id("lx_study_empty_title", "id"));
        tvEmptyDesc = (TextView) findViewById(id("lx_study_empty_desc", "id"));
        tvNewBtn = (TextView) findViewById(id("lx_study_new_btn", "id"));
        btnAgain = (TextView) findViewById(id("lx_study_again", "id"));
        btnHard = (TextView) findViewById(id("lx_study_hard", "id"));
        btnGood = (TextView) findViewById(id("lx_study_good", "id"));
        ivPic = (ImageView) findViewById(id("lx_study_img", "id"));
        bar = (ProgressBar) findViewById(id("lx_study_bar", "id"));
        card = findViewById(id("lx_study_card", "id"));
        empty = findViewById(id("lx_study_empty", "id"));
        actions = findViewById(id("lx_study_actions", "id"));
        reverse = DIR_REV.equals(SPUtils.getInstance().getString(K_DIR, DIR_FWD));
        bindToolbar();

        bindClick("lx_study_card", new Runnable() {
            @Override public void run() { reveal(); }
        });
        bindClick("lx_study_again", new Runnable() {
            @Override public void run() { grade(GRADE_AGAIN); }
        });
        bindClick("lx_study_hard", new Runnable() {
            @Override public void run() { grade(GRADE_HARD); }
        });
        bindClick("lx_study_good", new Runnable() {
            @Override public void run() { grade(GRADE_GOOD); }
        });
        bindClick("lx_study_new_btn", new Runnable() {
            @Override public void run() { addNewCards(); }
        });
        bindClick("lx_study_dir", new Runnable() {
            @Override public void run() { toggleDir(); }
        });
        bindClick("lx_study_edit", new Runnable() {
            @Override public void run() { openEdit(); }
        });
        renderDirChip();

        bootStudy();
    }

    /**
     * 冷启动时 {@code Dictionary.dic} 可能还在异步加载，先重试若干次再放弃，
     * 免得用户刚开 App 就点「联想词」时页面直接退出。
     */
    private void bootStudy() {
        // 先记下当前数据版本，避免 onCreate 之后的 onResume 把「版本变了」误判成导入，
        // 在词典还没加载完时就用空编码表重建队列。
        dataVer = StudyDataIO.version();
        if (loadCodes()) {
            startStudy();
            return;
        }
        retryLoad(0);
    }

    private void retryLoad(final int attempt) {
        if (attempt >= 12) {
            Log.w(TAG, "Dictionary.dic 为空，无法学习（重试 " + attempt + " 次后放弃）");
            Toast.makeText(this, str("lx_batch_edit_empty"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) {
                    return;
                }
                if (loadCodes()) {
                    Log.i(TAG, "Dictionary 延迟加载完成，第 " + (attempt + 1) + " 次重试成功");
                    startStudy();
                } else {
                    retryLoad(attempt + 1);
                }
            }
        }, 250);
    }

    private void startStudy() {
        resetDayIfNeeded();
        buildQueue();
        dataVer = StudyDataIO.version();
        applyTarget(getIntent());
        showCurrent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allCodes == null || allCodes.isEmpty()) {
            // 词典还没加载好（bootStudy 的重试链会负责后续初始化）。
            return;
        }
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

    /** 从统计页的薄弱榜跳进来时，把那张卡拉到队首。 */
    private void applyTarget(android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        String code = intent.getStringExtra("target_code");
        intent.removeExtra("target_code");
        if (TextUtils.isEmpty(code) || allCodes.indexOf(code) < 0) {
            return;
        }
        int idx = queue.indexOf(code);
        if (idx >= 0) {
            cursor = idx;
            done = idx;
        } else {
            queue.add(cursor, code);
        }
        Log.i(TAG, "定位到 " + code + " index=" + cursor + " total=" + queue.size());
    }

    // ------------------------------------------------------------------ 数据装载

    /** 从 Dictionary.dic 收集有联想词的编码（与批量编辑页同一过滤口径）。 */
    private boolean loadCodes() {
        Map<String, String> dic = Dictionary.dic;
        if (dic == null || dic.isEmpty()) {
            return false;
        }
        List<String> codes = new ArrayList<String>(dic.keySet());
        Collections.sort(codes);
        for (String code : codes) {
            if (LxWordStorage.builtinWord(code).length() == 0) {
                continue;
            }
            allCodes.add(code);
        }
        Log.i(TAG, "可学编码 " + allCodes.size() + " 个");
        return !allCodes.isEmpty();
    }

    /** 跨天清零「今日新学/复习」计数。 */
    private void resetDayIfNeeded() {
        SPUtils sp = SPUtils.getInstance();
        int today = todayKey();
        int stored = sp.getInt(K_DAY, 0);
        if (stored != today) {
            sp.put(K_DAY, today);
            sp.put(K_DAY_NEW, 0);
            sp.put(K_DAY_REV, 0);
            Log.i(TAG, "跨天重置今日计数: " + stored + " → " + today);
        }
    }

    private static int todayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 到期复习（按到期时间升序）+ 今日剩余额度内的新词。 */
    private void buildQueue() {
        queue.clear();
        requeued.clear();
        cursor = 0;
        done = 0;

        SPUtils sp = SPUtils.getInstance();
        long now = System.currentTimeMillis();
        final HashMap<String, Long> dueAt = new HashMap<String, Long>();
        List<String> due = new ArrayList<String>();
        List<String> fresh = new ArrayList<String>();

        for (String code : allCodes) {
            int box = sp.getInt(K_BOX + code, 0);
            if (box <= 0) {
                fresh.add(code);
                continue;
            }
            long t = sp.getLong(K_DUE + code, 0L);
            if (t <= now) {
                due.add(code);
                dueAt.put(code, Long.valueOf(t));
            }
        }

        Collections.sort(due, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                Long x = dueAt.get(a);
                Long y = dueAt.get(b);
                long lx = x == null ? 0L : x.longValue();
                long ly = y == null ? 0L : y.longValue();
                if (lx != ly) {
                    return lx < ly ? -1 : 1;
                }
                return a.compareTo(b);
            }
        });

        int revLimit = StudyPrefs.revLimit(StudyPrefs.SRC_LX);
        if (revLimit > 0 && due.size() > revLimit) {
            due = new ArrayList<String>(due.subList(0, revLimit));
        }
        queue.addAll(due);
        int allowance = Math.max(0, StudyPrefs.newLimit(StudyPrefs.SRC_LX) - sp.getInt(K_DAY_NEW, 0));
        int added = 0;
        for (int i = 0; i < fresh.size() && added < allowance; i++) {
            queue.add(fresh.get(i));
            added++;
        }
        Log.i(TAG, "队列 due=" + due.size() + " new=" + added + " total=" + queue.size()
                + " (今日已新学 " + sp.getInt(K_DAY_NEW, 0) + "，上限 new="
                + StudyPrefs.newLimit(StudyPrefs.SRC_LX) + " rev="
                + (revLimit == 0 ? "不限" : String.valueOf(revLimit)) + ")");
    }

    // ------------------------------------------------------------------ 卡片

    private String currentCode() {
        return cursor < queue.size() ? queue.get(cursor) : null;
    }

    /** 显示当前卡片正面（未翻面）。队列空则切空态。 */
    private void showCurrent() {
        String code = currentCode();
        if (code == null) {
            showEmpty();
            return;
        }
        card.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        actions.setVisibility(View.VISIBLE);
        revealed = false;
        renderFront(code);
        setGradeAlpha(false);
        updateHeader();
    }

    /** 正面：正向露编码，反向露联想词（都不带图片，图片是记忆法，提前看到等于泄题）。 */
    private void renderFront(String code) {
        tvHint.setVisibility(View.VISIBLE);
        tvWord.setVisibility(View.GONE);
        tvMeta.setVisibility(View.GONE);
        ivPic.setVisibility(View.GONE);
        ivPic.setImageDrawable(null);
        if (reverse) {
            String w = wordOf(code);
            tvCode.setText(TextUtils.isEmpty(w) ? str("lx_study_no_word") : w);
            tvCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            tvHint.setText(str("lx_study_rev_hint"));
        } else {
            tvCode.setText(code);
            tvCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
            tvHint.setText(str("lx_study_tap_hint"));
        }
    }

    /** 翻面：露出答案（正向补联想词，反向补编码）、图片与历史。 */
    private void reveal() {
        if (revealed) {
            return;
        }
        String code = currentCode();
        if (code == null) {
            return;
        }
        revealed = true;
        renderReveal(code);
    }

    private void renderReveal(String code) {
        tvHint.setVisibility(View.GONE);
        if (reverse) {
            // 反向：正面是词，翻面把编码打出来，同时保留词对照
            tvCode.setText(code);
            tvCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        }
        String word = wordOf(code);
        tvWord.setText(TextUtils.isEmpty(word) ? str("lx_study_no_word") : word);
        tvWord.setVisibility(View.VISIBLE);
        bindPic(code);
        tvMeta.setText(metaText(code));
        tvMeta.setVisibility(View.VISIBLE);
        setGradeAlpha(true);
    }

    private String wordOf(String code) {
        try {
            return LxWordStorage.currentWord(code);
        } catch (Throwable t) {
            Log.w(TAG, "读联想词失败: " + code, t);
            return "";
        }
    }

    /** 改词/换图之后把当前卡重画一遍（已翻面才重画背面，避免提前泄题）。 */
    private void refreshCurrentCard() {
        String code = currentCode();
        if (code == null) {
            return;
        }
        if (revealed) {
            renderReveal(code);
        } else {
            renderFront(code);
        }
    }

    private void renderDirChip() {
        if (tvDir == null) {
            return;
        }
        tvDir.setText(str(reverse ? "lx_study_dir_rev" : "lx_study_dir_fwd"));
    }

    /** 正向 ↔ 反向：同一张卡换提示方向，SRS 进度共用一份，不拆成两套。 */
    private void toggleDir() {
        reverse = !reverse;
        SPUtils.getInstance().put(K_DIR, reverse ? DIR_REV : DIR_FWD);
        Log.i(TAG, "卡片方向 → " + (reverse ? "反向（看词猜码）" : "正向（看码想词）"));
        renderDirChip();
        if (currentCode() == null) {
            showEmpty();
        } else {
            showCurrent();
        }
    }

    /** 自定义图优先，其次内置图；都没有就把 ImageView 藏起来。 */
    private void bindPic(String code) {
        int resId = 0;
        try {
            Integer r = Dictionary.pic == null ? null : Dictionary.pic.get(code);
            if (r != null) {
                resId = r.intValue();
            }
        } catch (Throwable ignored) {
        }
        String custom = null;
        try {
            String p = SPUtils.getInstance().getString(code + PIC_SUFFIX, "");
            if (!TextUtils.isEmpty(p) && new File(p).exists()) {
                custom = p;
            }
        } catch (Throwable ignored) {
        }
        Bitmap bmp = custom == null ? null : decodeThumb(custom, 480);
        if (bmp != null) {
            ivPic.setImageBitmap(bmp);
            ivPic.setVisibility(View.VISIBLE);
        } else if (resId != 0) {
            ivPic.setImageResource(resId);
            ivPic.setVisibility(View.VISIBLE);
        } else {
            ivPic.setImageDrawable(null);
            ivPic.setVisibility(View.GONE);
        }
    }

    private String metaText(String code) {
        SPUtils sp = SPUtils.getInstance();
        int box = sp.getInt(K_BOX + code, 0);
        if (box <= 0) {
            return str("lx_study_meta_new");
        }
        int reps = sp.getInt(K_REPS + code, 0);
        int laps = sp.getInt(K_LAPS + code, 0);
        return fmt2(str("lx_study_meta"), reps, laps);
    }

    // ------------------------------------------------------------------ 评分与调度

    private void grade(int g) {
        String code = currentCode();
        if (code == null) {
            return;
        }
        if (!revealed) {
            Toast.makeText(this, str("lx_study_reveal_first"), Toast.LENGTH_SHORT).show();
            return;
        }

        // 先记统计（内部读的是评分前的 box，用来区分新卡/复习），再改 SRS 状态
        StudyStats.record(StudyStats.SRC_LX, code, g);

        SPUtils sp = SPUtils.getInstance();
        int box = sp.getInt(K_BOX + code, 0);
        int reps = sp.getInt(K_REPS + code, 0);
        int laps = sp.getInt(K_LAPS + code, 0);
        boolean isNew = box <= 0;
        long now = System.currentTimeMillis();
        long due;

        if (g == GRADE_AGAIN) {
            if (!isNew) {
                laps++;
            }
            box = 1;
            due = now + 10L * MIN_MS;
            // 本次会话里再排一次，趁热打铁；同一张卡最多补一次
            if (!isNew) {
                int n = requeuedCount(code);
                if (n < 1) {
                    requeued.put(code, Integer.valueOf(n + 1));
                    queue.add(code);
                }
            }
        } else if (g == GRADE_HARD) {
            if (box < 1) {
                box = 1;
            }
            long iv = INTERVALS[Math.min(box, INTERVALS.length - 1)];
            due = now + Math.max(HOUR_MS, iv / 2L);
        } else {
            box = Math.min(INTERVALS.length - 1, box + 1);
            reps++;
            due = now + INTERVALS[box];
        }

        sp.put(K_BOX + code, box);
        sp.put(K_DUE + code, due);
        sp.put(K_REPS + code, reps);
        sp.put(K_LAPS + code, laps);
        if (isNew) {
            sp.put(K_DAY_NEW, sp.getInt(K_DAY_NEW, 0) + 1);
        } else {
            sp.put(K_DAY_REV, sp.getInt(K_DAY_REV, 0) + 1);
        }

        Log.i(TAG, "评分 " + code + " g=" + g + " box=" + box + " reps=" + reps + " laps=" + laps
                + " due=+" + ((due - now) / MIN_MS) + "min");

        done++;
        cursor++;
        showCurrent();
    }

    private int requeuedCount(String code) {
        Integer n = requeued.get(code);
        return n == null ? 0 : n.intValue();
    }

    /** 空态点「再学」：把还没学过的编码补进队列。 */
    private void addNewCards() {
        SPUtils sp = SPUtils.getInstance();
        List<String> fresh = new ArrayList<String>();
        for (String code : allCodes) {
            if (sp.getInt(K_BOX + code, 0) <= 0 && !queue.contains(code)) {
                fresh.add(code);
            }
        }
        if (fresh.isEmpty()) {
            Toast.makeText(this, str("lx_study_empty_toast"), Toast.LENGTH_SHORT).show();
            return;
        }
        int n = Math.min(StudyPrefs.batch(StudyPrefs.SRC_LX), fresh.size());
        for (int i = 0; i < n; i++) {
            queue.add(fresh.get(i));
        }
        Toast.makeText(this, fmt1(str("lx_study_new_action"), n), Toast.LENGTH_SHORT).show();
        Log.i(TAG, "手动补新词 " + n + " 个");
        showCurrent();
    }

    // ------------------------------------------------------------------ 空态与头部

    private void showEmpty() {
        card.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        actions.setVisibility(View.GONE);

        int mastered = 0;
        int learning = 0;
        int unseen = 0;
        SPUtils sp = SPUtils.getInstance();
        for (String code : allCodes) {
            int box = sp.getInt(K_BOX + code, 0);
            if (box <= 0) {
                unseen++;
            } else if (box >= MASTERED_BOX) {
                mastered++;
            } else {
                learning++;
            }
        }

        tvEmptyTitle.setText(str("lx_study_empty_title"));
        tvEmptyDesc.setText(fmt3(str("lx_study_empty_desc"), mastered, learning, unseen));
        if (unseen > 0) {
            tvNewBtn.setEnabled(true);
            tvNewBtn.setAlpha(1f);
            tvNewBtn.setText(fmt1(str("lx_study_new_action"),
                    Math.min(StudyPrefs.batch(StudyPrefs.SRC_LX), unseen)));
        } else {
            tvNewBtn.setEnabled(false);
            tvNewBtn.setAlpha(0.5f);
            tvNewBtn.setText(str("lx_study_all_done"));
        }

        tvProgress.setText(fmt2(str("lx_study_progress"), done, done));
        tvCounts.setText(fmt2(str("lx_study_counts"), 0, unseen));
        if (bar != null) {
            bar.setProgress(100);
        }
        Log.i(TAG, "空态: mastered=" + mastered + " learning=" + learning + " unseen=" + unseen
                + " done=" + done);
    }

    private void updateHeader() {
        int total = queue.size();
        tvProgress.setText(fmt2(str("lx_study_progress"), done, total));
        if (bar != null) {
            bar.setProgress(total <= 0 ? 0 : Math.min(100, done * 100 / total));
        }
        int dueNow = 0;
        int unseen = 0;
        SPUtils sp = SPUtils.getInstance();
        long now = System.currentTimeMillis();
        for (String code : allCodes) {
            int box = sp.getInt(K_BOX + code, 0);
            if (box <= 0) {
                unseen++;
            } else if (sp.getLong(K_DUE + code, 0L) <= now) {
                dueNow++;
            }
        }
        tvCounts.setText(fmt2(str("lx_study_counts"), dueNow, unseen));
    }

    /** 未翻面时把三档按钮调淡（点了只提示先翻面，不做静默失效）。 */
    private void setGradeAlpha(boolean on) {
        float a = on ? 1f : 0.45f;
        if (btnAgain != null) btnAgain.setAlpha(a);
        if (btnHard != null) btnHard.setAlpha(a);
        if (btnGood != null) btnGood.setAlpha(a);
    }

    // ------------------------------------------------------------------ 卡片内改词 / 换图

    /** 编辑当前卡：改联想词、换自定义图、恢复默认。 */
    private void openEdit() {
        final String code = currentCode();
        if (code == null) {
            return;
        }
        editCode = code;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(wordOf(code));
        String builtin = "";
        try {
            builtin = LxWordStorage.builtinWord(code);
        } catch (Throwable ignored) {
        }
        input.setHint(TextUtils.isEmpty(builtin) ? str("lx_study_edit_word_hint") : builtin);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        TextView btnPic = chip(str("lx_study_edit_pic"));
        TextView btnReset = chip(str("lx_study_edit_reset"));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        row.addView(btnPic, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        lp2.setMarginStart(dp(8));
        row.addView(btnReset, lp2);
        box.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(str("lx_study_edit_title") + " · " + code)
                .setView(box)
                .setPositiveButton(str("lx_study_edit_save"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        saveWord(code, input.getText().toString());
                    }
                })
                .setNegativeButton(str("lx_study_edit_cancel"), null)
                .create();
        btnPic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                pickImage();
            }
        });
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                resetCard(code);
            }
        });
        dlg.show();
    }

    private void saveWord(String code, String value) {
        String v = value == null ? "" : value.trim();
        try {
            if (v.length() == 0) {
                LxWordStorage.resetWord(code);
            } else {
                LxWordStorage.putWord(code, v);
            }
        } catch (Throwable t) {
            Log.e(TAG, "保存联想词失败: " + code, t);
            Toast.makeText(this, str("lx_study_edit_fail"), Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "改词 " + code + " = " + v);
        Toast.makeText(this, str("lx_study_edit_saved"), Toast.LENGTH_SHORT).show();
        refreshCurrentCard();
    }

    private void resetCard(String code) {
        try {
            LxWordStorage.resetWord(code);
            SPUtils.getInstance().remove(code + PIC_SUFFIX);
        } catch (Throwable t) {
            Log.w(TAG, "恢复默认失败: " + code, t);
        }
        Log.i(TAG, "恢复默认 " + code);
        Toast.makeText(this, str("lx_study_edit_reset_done"), Toast.LENGTH_SHORT).show();
        refreshCurrentCard();
    }

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_PIC);
        } catch (Throwable t) {
            Log.e(TAG, "打开选图失败", t);
            Toast.makeText(this, str("lx_study_pic_fail"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PIC || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        String code = editCode;
        if (code == null) {
            return;
        }
        try {
            byte[] bytes = readBytes(data.getData());
            if (bytes == null || bytes.length == 0) {
                Toast.makeText(this, str("lx_study_pic_fail"), Toast.LENGTH_SHORT).show();
                return;
            }
            String path = LxPicStorageUtils.saveCustomImageFromBytes(this, code, bytes);
            if (TextUtils.isEmpty(path)) {
                Toast.makeText(this, str("lx_study_pic_fail"), Toast.LENGTH_SHORT).show();
                return;
            }
            SPUtils.getInstance().put(code + PIC_SUFFIX, path);
            Log.i(TAG, "换图 " + code + " → " + path + " (" + bytes.length + " B)");
            Toast.makeText(this, str("lx_study_pic_saved"), Toast.LENGTH_SHORT).show();
            refreshCurrentCard();
        } catch (Throwable t) {
            Log.e(TAG, "换图失败: " + code, t);
            Toast.makeText(this, str("lx_study_pic_fail"), Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] readBytes(Uri uri) throws Exception {
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
                if (total > 8 * 1024 * 1024) {
                    return null;
                }
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private TextView chip(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setFocusable(true);
        t.setClickable(true);
        int bg = id("control_chip_bg", "drawable");
        if (bg != 0) {
            t.setBackgroundResource(bg);
        }
        t.setTextColor(colorAttr("app_colorPrimary", 0xFF4F46E5));
        return t;
    }

    // ------------------------------------------------------------------ 小工具

    /** 自定义图缩略图（两段解码 + inSampleSize）。失败返回 null。 */
    private Bitmap decodeThumb(String path, int target) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 工具栏：标题 + 返回 + 学习设置齿轮。 */
    private void bindToolbar() {
        View v = findViewById(id("toolbar", "id"));
        if (!(v instanceof zd.cornermemory.view.CustomToolbar)) {
            return;
        }
        zd.cornermemory.view.CustomToolbar tb = (zd.cornermemory.view.CustomToolbar) v;
        tb.setTitle(str("lx_study_title"));
        View back = tb.getBackButton();
        if (back != null) {
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { finish(); }
            });
        }
        StudySettingsDialog.attachGear(this, tb);
    }

    private void bindClick(String idName, final Runnable action) {
        View v = findViewById(id(idName, "id"));
        if (v == null) {
            Log.w(TAG, "找不到控件: " + idName);
            return;
        }
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
    }

    private String str(String name) {
        int v = id(name, "string");
        return v == 0 ? name : getString(v);
    }

    private String fmt1(String pattern, int a) {
        return String.format(pattern, Integer.valueOf(a));
    }

    private String fmt2(String pattern, int a, int b) {
        return String.format(pattern, Integer.valueOf(a), Integer.valueOf(b));
    }

    private String fmt3(String pattern, int a, int b, int c) {
        return String.format(pattern, Integer.valueOf(a), Integer.valueOf(b), Integer.valueOf(c));
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int colorAttr(String name, int fallback) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            int attr = id(name, "attr");
            if (attr != 0 && getTheme().resolveAttribute(attr, tv, true)) {
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
