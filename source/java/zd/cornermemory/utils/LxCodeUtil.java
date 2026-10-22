package zd.cornermemory.utils;

import java.util.Locale;

/**
 * 联想词编码的归一化与校验。**全工程唯一的权威实现。**
 *
 * <p>为什么必须归一化：App 内部一律用 <b>2 字母</b> 编码（{@code Dictionary.dic} 的 key、
 * SPUtils 的 {@code <CODE>_LX} / {@code <CODE>_LXPIC}）。而所有对外的文件都用 <b>1 字母</b>
 * 表示「对子」——工作区的 {@code 所有牌组.txt}、Anki 包里的 {@code media} 文件名
 * （{@code A.jpg}）、导出的 CSV 都是这样。UI 侧 {@code NormalAdapter} 会把 1 字母补成
 * 2 字母后才拼 key，所以外部数据不补全就会写成 {@code "A_LXPIC"} 而永远读不到。
 *
 * <p>编码集合实测：24 个字母 {@code ABCDEFGHIJKLMNOPQRSTWXYZ}（<b>无 U、V</b>），
 * 24 个单字母 + 552 个双字母 = 576，恰好等于「{@code a==b} 时写单字母，否则写两字母」。
 */
public final class LxCodeUtil {

    private LxCodeUtil() {
    }

    /** 彳亍方案的 24 个字母（U、V 不用）。仅当 {@code Dictionary.dic} 不可用时作为兜底校验。 */
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTWXYZ";

    /**
     * 归一化一个编码。
     *
     * <p>规则：去掉 BOM / 零宽字符 / 空白 → 只保留字母并转大写 → 长度为 1 则补成 2 →
     * 必须命中 {@code Dictionary.dic}（或兜底的 24 字母表）。
     *
     * @return 2 字母大写编码；无法识别时返回 {@code null}（调用方负责计数与报告，不要抛异常）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace("\uFEFF", "").replace("\u200B", "").trim();
        if (s.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            // 只收字母：丢掉空格、标点、数字、emoji —— "A1" / "A." / "A 组" 都应归一化成 AA
            if (c >= 'A' && c <= 'Z') {
                sb.append(c);
            }
        }
        s = sb.toString();
        if (s.length() == 1) {
            s = s + s;
        }
        if (s.length() != 2) {
            return null;
        }
        return isValid(s) ? s : null;
    }

    /** 编码是否合法（必须是 2 字母且属于 576 集合）。 */
    public static boolean isValid(String code) {
        if (code == null || code.length() != 2) {
            return false;
        }
        try {
            if (Dictionary.dic != null && !Dictionary.dic.isEmpty()) {
                return Dictionary.dic.containsKey(code);
            }
        } catch (Throwable ignored) {
            // Dictionary 还没 init 时落到下面的字母表兜底
        }
        return LETTERS.indexOf(code.charAt(0)) >= 0 && LETTERS.indexOf(code.charAt(1)) >= 0;
    }

    /**
     * 转成对外展示 / 导出的形式：{@code AA → A}、{@code AB → AB}。
     *
     * <p>与工作区 {@code 所有牌组.txt}、Anki 包 media 名、{@code 对照表-全量.tsv} 完全一致，
     * 所以「导出 → 用户编辑 → 导入」是双射无损的。
     */
    public static String display(String code) {
        if (code == null || code.length() != 2) {
            return code;
        }
        return code.charAt(0) == code.charAt(1) ? code.substring(0, 1) : code;
    }

    /** 取当前内置词（{@code Dictionary.dic} 里那条）；取不到返回 null。 */
    public static String builtinWord(String code) {
        try {
            if (Dictionary.dic != null) {
                return Dictionary.dic.get(code);
            }
        } catch (Throwable ignored) {
            // 忽略：调用方按 null 处理
        }
        return null;
    }

    /** 小写文件名（去掉扩展名后的 basename）→ 归一化编码。 */
    public static String fromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String s = fileName;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        return normalize(s);
    }

    /** 便于日志：把编码列表拼成 "AA,AB,..."（最多 20 个）。 */
    public static String join(java.util.List<String> codes, int max) {
        if (codes == null || codes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(codes.size(), max);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(display(codes.get(i)));
        }
        if (codes.size() > n) {
            sb.append("…");
        }
        return sb.toString();
    }

    /** 供单元测试/调试用：Locale 无关的大写化。 */
    static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.US);
    }
}
