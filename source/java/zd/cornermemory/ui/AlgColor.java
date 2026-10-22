package zd.cornermemory.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.List;

import zd.cornermemory.utils.ThemeManager;

/**
 * 818 公式配色：照搬浅梦公式集的颜色约定。
 *
 * <p>图例（浅梦「说明」页）：<b>红色</b>=交换步骤部分、<b>紫色</b>=直达层部分、
 * <b>蓝色</b>=消步合并部分、<b>黑色</b>=setup 部分。浅梦源的颜色来自 xlsx 富文本，
 * 生成资产时已经转成逐字符角色掩码（{@code 0}setup / {@code 1}交换 / {@code 2}直达层 /
 * {@code 3}消步合并），这里只负责按掩码上色。
 *
 * <p>自定义公式集和内置集没有掩码，用交换子
 * {@code C:[A,B] = C A B A' B' C'} 推导：setup→黑、A/A'→红、B/B'→紫。
 */
public final class AlgColor {

    private AlgColor() {
    }

    /** 角色码（与资产里的掩码字符一一对应） */
    public static final int ROLE_SETUP = 0;
    public static final int ROLE_EXCH = 1;
    public static final int ROLE_LAYER = 2;
    public static final int ROLE_MERGE = 3;

    /** 一套配色：只给需要变色的角色；setup 保持 TextView 自己的文字色 */
    public static final class Palette {
        final int exch;
        final int layer;
        final int merge;

        Palette(int exch, int layer, int merge) {
            this.exch = exch;
            this.layer = layer;
            this.merge = merge;
        }

        int of(int role) {
            if (role == ROLE_EXCH) {
                return exch;
            }
            if (role == ROLE_LAYER) {
                return layer;
            }
            if (role == ROLE_MERGE) {
                return merge;
            }
            return 0;
        }
    }

    /** 浅色主题用 xlsx 原色（照搬），深色主题换成同色相的提亮版（原色在深底上看不清） */
    public static Palette palette(Context ctx) {
        if (ThemeManager.isNight(ctx)) {
            return new Palette(0xFFFF6B6B, 0xFFC79BF0, 0xFF4FC3F7);
        }
        return new Palette(0xFFFF0000, 0xFF7030A0, 0xFF00B0F0);
    }

    /**
     * 按掩码给公式上色。
     *
     * @param alg   公式原文
     * @param mask  逐字符角色掩码，可为空
     * @param comm  交换子（掩码为空时用来推导）
     */
    public static CharSequence decorate(String alg, String mask, String comm, Palette p) {
        if (alg == null) {
            return "";
        }
        if (mask == null || mask.length() != alg.length()) {
            mask = maskFromComm(alg, comm);
        }
        return decorateMasked(alg, mask, p);
    }

    /** 按掩码上色（掩码长度不符或全 setup 时原样返回） */
    public static CharSequence decorateMasked(String text, String mask, Palette p) {
        if (text == null) {
            return "";
        }
        if (mask == null || mask.length() != text.length() || p == null) {
            return text;
        }
        SpannableString ss = null;
        int i = 0;
        int n = mask.length();
        while (i < n) {
            int role = mask.charAt(i) - '0';
            int j = i + 1;
            while (j < n && mask.charAt(j) - '0' == role) {
                j++;
            }
            int color = p.of(role);
            if (color != 0 && j > i) {
                if (ss == null) {
                    ss = new SpannableString(text);
                }
                ss.setSpan(new ForegroundColorSpan(color), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = j;
        }
        return ss == null ? text : ss;
    }

    /** 卡片背面「交换子 …」那一行也按同一套图例上色 */
    public static CharSequence decorateComm(String text, Palette p) {
        if (text == null || text.length() == 0 || p == null) {
            return text == null ? "" : text;
        }
        return decorateMasked(text, maskForCommText(text), p);
    }

    /** 只给 text 里出现的 comm 那一段上色（meta 多行文本用，避免误伤别的方括号） */
    public static CharSequence decorateCommWithin(String text, String comm, Palette p) {
        if (text == null) {
            return "";
        }
        if (comm == null || comm.length() == 0 || p == null) {
            return text;
        }
        int at = text.indexOf(comm);
        if (at < 0) {
            return text;
        }
        StringBuilder mask = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            mask.append('0');
        }
        mask.replace(at, at + comm.length(), maskForCommText(comm));
        return decorateMasked(text, mask.toString(), p);
    }

    /** 交换子原文的角色掩码：`:` 之前 = setup，`[a,b]` 里 a→交换、b→直达层 */
    public static String maskForCommText(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        int depth = 0;
        boolean afterComma = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '[') {
                depth++;
                afterComma = false;
                sb.append('0');
                continue;
            }
            if (ch == ']') {
                if (depth > 0) {
                    depth--;
                }
                sb.append('0');
                continue;
            }
            if (depth > 0 && (ch == ',' || ch == '，')) {
                afterComma = true;
                sb.append('0');
                continue;
            }
            if (depth > 0) {
                sb.append(afterComma ? '2' : '1');
            } else {
                sb.append('0');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 推导

    /** 没有掩码时用交换子推导角色掩码；推不出来返回空串 */
    public static String maskFromComm(String alg, String comm) {
        if (alg == null || alg.length() == 0 || comm == null || comm.length() == 0) {
            return "";
        }
        List<String> want = new ArrayList<String>();
        List<Integer> roles = new ArrayList<Integer>();
        if (!splitComm(comm, want, roles) || want.isEmpty()) {
            return "";
        }
        List<String> have = AlgHighlight.tokens(alg);
        if (have.isEmpty()) {
            return "";
        }
        int[] roleOf = new int[have.size()];
        int j = 0;
        for (int i = 0; i < have.size(); i++) {
            String t = have.get(i);
            int hit = -1;
            for (int k = j; k < want.size(); k++) {
                if (want.get(k).equals(t)) {
                    hit = k;
                    break;
                }
            }
            if (hit >= 0) {
                roleOf[i] = roles.get(hit).intValue();
                j = hit + 1;
            }
        }
        // 按 token 边界把角色铺回字符
        StringBuilder sb = new StringBuilder(alg.length());
        int ti = 0;
        int i = 0;
        int n = alg.length();
        while (i < n) {
            if (Character.isWhitespace(alg.charAt(i))) {
                sb.append('0');
                i++;
                continue;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(alg.charAt(i))) {
                i++;
            }
            int role = ti < roleOf.length ? roleOf[ti] : ROLE_SETUP;
            char c = (char) ('0' + role);
            for (int k = start; k < i; k++) {
                sb.append(c);
            }
            ti++;
        }
        return sb.toString();
    }

    /** 交换子 → (token 序列, 角色) */
    private static boolean splitComm(String comm, List<String> want, List<Integer> roles) {
        String s = comm.trim();
        int colon = s.indexOf(':');
        if (colon < 0) {
            colon = s.indexOf('：');
        }
        String setup = colon >= 0 ? s.substring(0, colon) : "";
        String body = colon >= 0 ? s.substring(colon + 1) : s;
        int lb = body.indexOf('[');
        int rb = body.lastIndexOf(']');
        if (lb < 0 || rb <= lb) {
            return false;
        }
        String inner = body.substring(lb + 1, rb);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            comma = inner.indexOf('，');
        }
        if (comma < 0) {
            return false;
        }
        String a = inner.substring(0, comma);
        String b = inner.substring(comma + 1);
        addTokens(want, roles, setup, ROLE_SETUP);
        addTokens(want, roles, a, ROLE_EXCH);
        addTokens(want, roles, b, ROLE_LAYER);
        addTokens(want, roles, AlgHighlight.invert(a), ROLE_EXCH);
        addTokens(want, roles, AlgHighlight.invert(b), ROLE_LAYER);
        return true;
    }

    private static void addTokens(List<String> want, List<Integer> roles, String s, int role) {
        List<String> t = AlgHighlight.tokens(s);
        for (int i = 0; i < t.size(); i++) {
            want.add(t.get(i));
            roles.add(Integer.valueOf(role));
        }
    }

    /** 给 TextView 用：粗体保留原样，仅上色 */
    public static CharSequence bold(CharSequence text) {
        if (text == null) {
            return "";
        }
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new StyleSpan(Typeface.BOLD), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }
}
