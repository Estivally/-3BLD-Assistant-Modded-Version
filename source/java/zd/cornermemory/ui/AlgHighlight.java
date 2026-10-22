package zd.cornermemory.ui;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * opt25：把「交换子」在公式里高亮出来。
 *
 * <p>公式和交换子的写法通常对不上：交换子 {@code R' D R U':[R' D' R,U']} 完整展开成
 * {@code R' D R U' R' D' R U' R' D R U}（14 个 token），而公式可能是
 * {@code R' D R U' R' D' R U' R' D R U2 R' D' R}（15 个），整段展开并不是公式的连续子串。
 * 所以这里不做「整段匹配」，而是取 <b>token 级最长连续公共片段</b>，
 * 命中 token 数 ≥ {@link #MIN_RUN} 才高亮 —— 宁缺毋滥，匹配不到就不上色。
 */
public final class AlgHighlight {

    private static final String TAG = "AlgHi";

    /** 至少命中这么多个 token 才高亮 */
    private static final int MIN_RUN = 3;

    private AlgHighlight() {
    }

    /** 公式里该高亮的字符区间 {start,end}；匹配不到返回 null。 */
    public static int[] span(String alg, String comm) {
        if (alg == null || comm == null || comm.length() == 0) {
            return null;
        }
        List<String> want = expandComm(comm);
        if (want.size() < MIN_RUN) {
            return null;
        }
        List<String> have = tokens(alg);
        List<int[]> pos = spans(alg);
        if (have.size() < MIN_RUN || pos.size() != have.size()) {
            return null;
        }
        int bestLen = 0;
        int bestAt = -1;
        for (int i = 0; i < have.size(); i++) {
            for (int j = 0; j < want.size(); j++) {
                int k = 0;
                while (i + k < have.size() && j + k < want.size()
                        && have.get(i + k).equals(want.get(j + k))) {
                    k++;
                }
                if (k > bestLen) {
                    bestLen = k;
                    bestAt = i;
                }
            }
        }
        if (bestLen < MIN_RUN || bestAt < 0) {
            return null;
        }
        int start = pos.get(bestAt)[0];
        int end = pos.get(bestAt + bestLen - 1)[1];
        Log.i(TAG, "高亮命中 " + bestLen + "/" + want.size() + " token → [" + start + "," + end + ")");
        return new int[]{start, end};
    }

    /** 给公式上色（命中才上色，否则原样返回）。 */
    public static CharSequence decorate(String alg, String comm, int color) {
        if (alg == null) {
            return "";
        }
        int[] s = span(alg, comm);
        if (s == null) {
            return alg;
        }
        SpannableString ss = new SpannableString(alg);
        ss.setSpan(new ForegroundColorSpan(color), s[0], s[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new StyleSpan(Typeface.BOLD), s[0], s[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** 把整段文本里出现的 comm 原文上色（用于卡片背面的「交换子 …」那一行）。 */
    public static CharSequence decorateComm(String text, String comm, int color) {
        if (text == null) {
            return "";
        }
        if (comm == null || comm.length() == 0) {
            return text;
        }
        int i = text.indexOf(comm);
        if (i < 0) {
            return text;
        }
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(color), i, i + comm.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    // ------------------------------------------------------------------ 拆词

    /** 交换子 → token 序列（setup + 展开后的交换子本体） */
    public static List<String> expandComm(String comm) {
        List<String> out = new ArrayList<String>();
        String s = comm == null ? "" : comm.trim();
        if (s.length() == 0) {
            return out;
        }
        int c = s.indexOf(':');
        if (c < 0) {
            c = s.indexOf('：');
        }
        String setup = c >= 0 ? s.substring(0, c) : "";
        String body = c >= 0 ? s.substring(c + 1) : s;
        addTokens(out, setup);
        addTokens(out, expand(body));
        return out;
    }

    /** 把 {@code [A,B]} / {@code (A)} / 普通序列展开成 token 串（交换子按 A B A' B' 展开）。 */
    static String expand(String body) {
        StringBuilder sb = new StringBuilder();
        String s = body == null ? "" : body;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '[') {
                int close = s.indexOf(']', i);
                if (close < 0) {
                    sb.append(' ');
                    continue;
                }
                String inner = s.substring(i + 1, close);
                int comma = inner.indexOf(',');
                if (comma < 0) {
                    comma = inner.indexOf('，');
                }
                if (comma < 0) {
                    sb.append(' ').append(expand(inner)).append(' ');
                } else {
                    String a = inner.substring(0, comma);
                    String b = inner.substring(comma + 1);
                    sb.append(' ').append(expand(a));
                    sb.append(' ').append(expand(b));
                    sb.append(' ').append(invert(expand(a)));
                    sb.append(' ').append(invert(expand(b)));
                    sb.append(' ');
                }
                i = close;
            } else if (ch == '(' || ch == ')' || ch == '{' || ch == '}'
                    || ch == '（' || ch == '）' || ch == '，') {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 逆序取逆：{@code R U R'} → {@code R U' R'} */
    static String invert(String seq) {
        List<String> t = tokens(seq);
        StringBuilder sb = new StringBuilder();
        for (int i = t.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(inv(t.get(i)));
        }
        return sb.toString();
    }

    static String inv(String tok) {
        if (tok.length() == 0) {
            return tok;
        }
        char last = tok.charAt(tok.length() - 1);
        if (last == '\'') {
            return tok.substring(0, tok.length() - 1);
        }
        if (last == '2') {
            return tok; // 180° 自逆
        }
        return tok + "'";
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<String>();
        addTokens(out, s);
        return out;
    }

    private static void addTokens(List<String> out, String s) {
        if (s == null) {
            return;
        }
        String t = s.replace('\u3000', ' ').trim();
        if (t.length() == 0) {
            return;
        }
        String[] parts = t.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String p = trimPunct(parts[i]);
            if (p.length() > 0) {
                out.add(p);
            }
        }
    }

    /** 每个 token 在原文里的字符区间（用于把 token 下标换算回字符下标） */
    private static List<int[]> spans(String s) {
        List<int[]> out = new ArrayList<int[]>();
        if (s == null) {
            return out;
        }
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            int end = i;
            String tok = trimPunct(s.substring(start, end));
            if (tok.length() == 0) {
                continue;
            }
            int lead = 0;
            while (lead < end - start && isPunct(s.charAt(start + lead))) {
                lead++;
            }
            int trail = 0;
            while (trail < end - start - lead && isPunct(s.charAt(end - 1 - trail))) {
                trail++;
            }
            out.add(new int[]{start + lead, end - trail});
        }
        return out;
    }

    private static boolean isPunct(char c) {
        return c == ',' || c == '.' || c == ';' || c == '。' || c == '，' || c == '、';
    }

    private static String trimPunct(String tok) {
        int start = 0;
        int end = tok.length();
        while (start < end && isPunct(tok.charAt(start))) {
            start++;
        }
        while (end > start && isPunct(tok.charAt(end - 1))) {
            end--;
        }
        return tok.substring(start, end);
    }
}
