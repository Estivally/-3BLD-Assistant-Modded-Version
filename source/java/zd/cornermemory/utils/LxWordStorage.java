package zd.cornermemory.utils;

import java.util.Map;

/**
 * 联想词覆盖层的读写工具。
 *
 * <p><b>机制</b>：与原版「改词」完全同一套存储 —— SPUtils 默认实例里
 * {@code <code>_LX} 键 = 该编码的自定义联想词，缺省回退 {@link Dictionary#dic}
 * 的内置值（见 {@code LxPicActivity.currentRemark} 的读法）。批量编辑页
 * 只是把这个键的读/写/删做成 576 行的表格操作，不引入第二套语义。
 *
 * <p><b>异常安全</b>：所有 SP 访问都包 try-catch —— 词表覆盖属于增强功能，
 * 任何一步失败都不允许影响主流程（对齐 {@code LxBatchImportEntry} 的防御风格）。
 */
public final class LxWordStorage {

    /** 自定义联想词的 SP 键后缀（与原版 LxPicActivity / NormalAdapter 一致，别改） */
    private static final String SUFFIX = "_LX";

    private LxWordStorage() {
    }

    /** 构造某编码的 SP 键名 */
    public static String wordKey(String code) {
        return code + SUFFIX;
    }

    /** 内置默认词（Dictionary.dic 的值；取不到返回空串） */
    public static String builtinWord(String code) {
        Map<String, String> dic = Dictionary.dic;
        if (dic != null) {
            String v = dic.get(code);
            if (v != null) {
                return v;
            }
        }
        return "";
    }

    /** 当前生效词：SP 覆盖优先，回退内置词表（与 LxPicActivity 的读法一致） */
    public static String currentWord(String code) {
        String builtin = builtinWord(code);
        try {
            String v = SPUtils.getInstance().getString(wordKey(code), builtin);
            return v == null ? builtin : v;
        } catch (Throwable t) {
            return builtin;
        }
    }

    /** 该编码是否存在自定义覆盖 */
    public static boolean hasOverride(String code) {
        try {
            return SPUtils.getInstance().contains(wordKey(code));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 写覆盖。value 为空 / 等于内置值时删除覆盖（回到默认），
     * 保证 SP 里永远只存「真正偏离默认」的条目，导出/备份体积最小。
     */
    public static void putWord(String code, String value) {
        String builtin = builtinWord(code);
        try {
            SPUtils sp = SPUtils.getInstance();
            String key = wordKey(code);
            String v = value == null ? "" : value.trim();
            if (v.length() == 0 || v.equals(builtin)) {
                sp.remove(key);
            } else {
                sp.put(key, v);
            }
        } catch (Throwable t) {
            // 忽略：保存失败由调用方通过回读校验发现
        }
    }

    /** 恢复单个编码的内置默认词（= 删除覆盖） */
    public static void resetWord(String code) {
        try {
            SPUtils.getInstance().remove(wordKey(code));
        } catch (Throwable t) {
            // 忽略
        }
    }
}
