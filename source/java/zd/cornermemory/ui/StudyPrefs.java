package zd.cornermemory.ui;

import zd.cornermemory.utils.SPUtils;

/**
 * 学习限额设置：联想词与 818 各一套「每日新卡 / 每日复习 / 每批数量」。
 *
 * <p>复习上限 0 表示不限（按到期时间全部排进队列）。所有值钳制在 0–999（新卡与批量至少 1），
 * 免得手滑输入导致队列为空或一次补几千张。
 */
public final class StudyPrefs {

    public static final String SRC_LX = "lx";
    public static final String SRC_ALG = "alg818";

    public static final int KIND_NEW = 0;
    public static final int KIND_REV = 1;
    public static final int KIND_BATCH = 2;

    private static final int DEF_NEW = 20;
    private static final int DEF_REV = 0;
    private static final int DEF_BATCH = 10;

    private StudyPrefs() {
    }

    private static String key(String src, int kind) {
        String s = SRC_ALG.equals(src) ? "alg818" : "lx";
        if (kind == KIND_NEW) {
            return s + "_study_new_limit";
        }
        if (kind == KIND_REV) {
            return s + "_study_rev_limit";
        }
        return s + "_study_batch";
    }

    public static int get(String src, int kind) {
        int def = kind == KIND_NEW ? DEF_NEW : (kind == KIND_REV ? DEF_REV : DEF_BATCH);
        int v;
        try {
            v = SPUtils.getInstance().getInt(key(src, kind), def);
        } catch (Throwable t) {
            v = def;
        }
        return clamp(v, kind);
    }

    public static void set(String src, int kind, int value) {
        SPUtils.getInstance().put(key(src, kind), clamp(value, kind));
    }

    private static int clamp(int v, int kind) {
        if (kind == KIND_REV) {
            if (v < 0) {
                return 0;
            }
            return v > 999 ? 999 : v;
        }
        if (v < 1) {
            return 1;
        }
        return v > 999 ? 999 : v;
    }

    public static int newLimit(String src) {
        return get(src, KIND_NEW);
    }

    /** 0 = 不限 */
    public static int revLimit(String src) {
        return get(src, KIND_REV);
    }

    public static int batch(String src) {
        return get(src, KIND_BATCH);
    }
}
