package zd.cornermemory.ui;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import zd.cornermemory.utils.SPUtils;

/**
 * 学习数据统计：只读写 SP，不解析资产。
 *
 * <p>每日键：{@code lx_h_<yyyyMMdd>_new|rev|again|hard|good}（818 同构 {@code alg818_h_...}）。
 * 队列与掌握度直接扫 SRS 状态键（{@code *_b_ / *_d_ / *_l_}），所以历史数据（加这个功能之前练过的卡）
 * 也能立刻统计出来，不需要回填。
 */
public final class StudyStats {

    public static final String SRC_LX = StudyPrefs.SRC_LX;
    public static final String SRC_ALG = StudyPrefs.SRC_ALG;

    private static final String[] GRADES = {"again", "hard", "good"};
    private static final int MASTERED_BOX = 4;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private StudyStats() {
    }

    private static boolean isAlg(String src) {
        return SRC_ALG.equals(src);
    }

    private static String prefix(String src) {
        return isAlg(src) ? "alg818_" : "lx_srs_";
    }

    private static String histPrefix(String src) {
        return isAlg(src) ? "alg818_h_" : "lx_h_";
    }

    private static String boxKey(String src) {
        return prefix(src) + "b_";
    }

    private static String dueKey(String src) {
        return prefix(src) + "d_";
    }

    private static String lapKey(String src) {
        return prefix(src) + "l_";
    }

    private static String histKey(String src, int day) {
        return histPrefix(src) + day + "_";
    }

    /** 评分时调一次（必须在写入新 box 之前），自动判定新卡/复习。 */
    public static void record(String src, String code, int grade) {
        if (code == null) {
            return;
        }
        try {
            SPUtils sp = SPUtils.getInstance();
            boolean isNew = sp.getInt(boxKey(src) + code, 0) <= 0;
            String base = histKey(src, todayKey());
            String cntKey = base + (isNew ? "new" : "rev");
            sp.put(cntKey, sp.getInt(cntKey, 0) + 1);
            int g = grade < 0 ? 0 : (grade > 2 ? 2 : grade);
            String gk = base + GRADES[g];
            sp.put(gk, sp.getInt(gk, 0) + 1);
        } catch (Throwable ignored) {
        }
    }

    public static int todayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    private static int addDays(int dayKey, int delta) {
        Calendar c = Calendar.getInstance();
        c.set(dayKey / 10000, (dayKey / 100) % 100 - 1, dayKey % 100, 12, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, delta);
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 今天某一项：new / rev / again / hard / good。 */
    public static int today(String src, String kind) {
        try {
            return SPUtils.getInstance().getInt(histKey(src, todayKey()) + kind, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 连续天数：从今天往前数；今天还没练则从昨天算起，不提前打断。 */
    public static int streak(String src) {
        SPUtils sp = SPUtils.getInstance();
        int day = todayKey();
        int n = 0;
        for (int i = 0; i < 400; i++) {
            int d = addDays(day, -i);
            String base = histKey(src, d);
            int act = sp.getInt(base + "new", 0) + sp.getInt(base + "rev", 0);
            if (act > 0) {
                n++;
                continue;
            }
            if (i == 0) {
                continue;
            }
            break;
        }
        return n;
    }

    /** 多来源合并的连续天数（任一来源当天有活动就算）。 */
    public static int streakOf(String... srcs) {
        SPUtils sp = SPUtils.getInstance();
        int day = todayKey();
        int n = 0;
        for (int i = 0; i < 400; i++) {
            int d = addDays(day, -i);
            int act = 0;
            for (String s : srcs) {
                String base = histKey(s, d);
                act += sp.getInt(base + "new", 0) + sp.getInt(base + "rev", 0);
            }
            if (act > 0) {
                n++;
                continue;
            }
            if (i == 0) {
                continue;
            }
            break;
        }
        return n;
    }

    /** 多来源合并的今日某项。 */
    public static int todayOf(String kind, String... srcs) {
        int n = 0;
        for (String s : srcs) {
            n += today(s, kind);
        }
        return n;
    }

    /** 近 n 天：[2i]=新学、[2i+1]=复习，i = n-1 是今天。 */
    public static int[] lastDays(String src, int n) {
        int[] out = new int[n * 2];
        SPUtils sp = SPUtils.getInstance();
        int day = todayKey();
        for (int i = 0; i < n; i++) {
            int d = addDays(day, -(n - 1 - i));
            String base = histKey(src, d);
            out[i * 2] = sp.getInt(base + "new", 0);
            out[i * 2 + 1] = sp.getInt(base + "rev", 0);
        }
        return out;
    }

    private static Map<String, ?> safeAll() {
        try {
            return SPUtils.getInstance().getAll();
        } catch (Throwable t) {
            return null;
        }
    }

    /** box 0–6 的卡数（0 = 未学）。 */
    public static int[] boxHistogram(String src) {
        int[] h = new int[7];
        Map<String, ?> all = safeAll();
        if (all == null) {
            return h;
        }
        String p = boxKey(src);
        SPUtils sp = SPUtils.getInstance();
        for (String k : all.keySet()) {
            if (k == null || !k.startsWith(p)) {
                continue;
            }
            int box = sp.getInt(k, 0);
            if (box < 0) {
                box = 0;
            }
            if (box > 6) {
                box = 6;
            }
            h[box]++;
        }
        return h;
    }

    /** {已掌握, 学习中, 未学}；total 是这一类卡的总数。 */
    public static int[] counts(String src, int total) {
        int learned = 0;
        int mastered = 0;
        Map<String, ?> all = safeAll();
        if (all != null) {
            String p = boxKey(src);
            SPUtils sp = SPUtils.getInstance();
            for (String k : all.keySet()) {
                if (k == null || !k.startsWith(p)) {
                    continue;
                }
                int box = sp.getInt(k, 0);
                if (box <= 0) {
                    continue;
                }
                learned++;
                if (box >= MASTERED_BOX) {
                    mastered++;
                }
            }
        }
        int unseen = total - learned;
        if (unseen < 0) {
            unseen = 0;
        }
        return new int[]{mastered, learned - mastered, unseen};
    }

    /** 未来 7 天每天到期张数：[0]=已到期/今天，[6]=第 6 天。 */
    public static int[] dueNext7(String src) {
        int[] out = new int[7];
        Map<String, ?> all = safeAll();
        if (all == null) {
            return out;
        }
        String p = dueKey(src);
        SPUtils sp = SPUtils.getInstance();
        long now = System.currentTimeMillis();
        for (String k : all.keySet()) {
            if (k == null || !k.startsWith(p)) {
                continue;
            }
            long t = sp.getLong(k, 0L);
            if (t <= now) {
                out[0]++;
                continue;
            }
            int idx = (int) ((t - now) / DAY_MS) + 1;
            if (idx < 7) {
                out[idx]++;
            }
        }
        return out;
    }

    /** 薄弱榜：遗忘次数多的排前面，同次数看 box 低的。 */
    public static List<String> weakTop(final String src, int n) {
        final SPUtils sp = SPUtils.getInstance();
        List<String> codes = new ArrayList<String>();
        Map<String, ?> all = safeAll();
        if (all != null) {
            String p = lapKey(src);
            for (String k : all.keySet()) {
                if (k == null || !k.startsWith(p)) {
                    continue;
                }
                String code = k.substring(p.length());
                if (code.length() > 0 && sp.getInt(k, 0) > 0) {
                    codes.add(code);
                }
            }
        }
        Collections.sort(codes, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int la = sp.getInt(lapKey(src) + a, 0);
                int lb = sp.getInt(lapKey(src) + b, 0);
                if (la != lb) {
                    return la > lb ? -1 : 1;
                }
                int ba = sp.getInt(boxKey(src) + a, 0);
                int bb = sp.getInt(boxKey(src) + b, 0);
                if (ba != bb) {
                    return ba < bb ? -1 : 1;
                }
                return a.compareTo(b);
            }
        });
        if (codes.size() > n) {
            return new ArrayList<String>(codes.subList(0, n));
        }
        return codes;
    }

    public static int boxOf(String src, String code) {
        return SPUtils.getInstance().getInt(boxKey(src) + code, 0);
    }

    public static int lapsOf(String src, String code) {
        return SPUtils.getInstance().getInt(lapKey(src) + code, 0);
    }
}
