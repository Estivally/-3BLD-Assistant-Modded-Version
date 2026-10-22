package zd.cornermemory.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * 主题管理：4 套配色 × 浅色/深色/跟随系统。
 *
 * <p>资源侧由 opt11_apply.py 改造：168 个颜色被抽成主题属性 {@code app_xxx}，
 * 8 个覆盖样式 {@code ThemeOverlay.App.Scheme.<方案>[.Night]} 携带绝对色值。
 *
 * <p>三个入口：
 * <ul>
 *   <li>{@link #wrap(Context)} —— 在 Activity.attachBaseContext 里调用，
 *       按用户选择强制 uiMode，使 values-night 生效（本应用所有 Activity 都直接继承
 *       FragmentActivity，没有 AppCompatDelegate，所以不能用 setDefaultNightMode）。</li>
 *   <li>{@link #apply(Context, Resources.Theme)} —— 在 onCreate 里 setContentView 之前调用，
 *       把配色覆盖样式打到主题上。</li>
 *   <li>{@link #setScheme}/{@link #setMode} —— 设置页写入。</li>
 * </ul>
 */
public final class ThemeManager {

    /** 配色方案 id，顺序与覆盖样式、设置页一致 */
    public static final String[] SCHEMES = {"indigo", "teal", "violet", "graphite"};
    /** 外观模式 id */
    public static final String[] MODES = {"light", "dark", "system"};

    /** 设置页显示名，与 SCHEMES 一一对应 */
    public static final String[] SCHEME_LABELS = {"靛蓝·精修", "青碧·纸感", "紫玫瑰·活力", "石墨·琥珀"};
    public static final String[] SCHEME_DESCS = {"延续现有品牌色", "清爽低饱和", "高饱和对比", "专业计时器"};
    /** 设置页显示名，与 MODES 一一对应 */
    public static final String[] MODE_LABELS = {"浅色", "深色", "跟随系统"};

    private static final String KEY_SCHEME = "opt11_ui_scheme";
    private static final String KEY_MODE = "opt11_ui_mode";

    private static final String DEFAULT_SCHEME = "indigo";
    private static final String DEFAULT_MODE = "system";

    private ThemeManager() {
    }

    // ------------------------------------------------------------------ 读写

    public static String getScheme() {
        String v = pref(KEY_SCHEME, DEFAULT_SCHEME);
        return indexOf(SCHEMES, v) >= 0 ? v : DEFAULT_SCHEME;
    }

    public static void setScheme(String scheme) {
        if (indexOf(SCHEMES, scheme) >= 0) {
            save(KEY_SCHEME, scheme);
        }
    }

    public static String getMode() {
        String v = pref(KEY_MODE, DEFAULT_MODE);
        return indexOf(MODES, v) >= 0 ? v : DEFAULT_MODE;
    }

    public static void setMode(String mode) {
        if (indexOf(MODES, mode) >= 0) {
            save(KEY_MODE, mode);
        }
    }

    public static int getSchemeIndex() {
        int i = indexOf(SCHEMES, getScheme());
        return i < 0 ? 0 : i;
    }

    public static int getModeIndex() {
        int i = indexOf(MODES, getMode());
        return i < 0 ? MODES.length - 1 : i;
    }

    /**
     * 主题指纹：方案 / 模式 / 实际明暗。
     * Activity 在 onResume 比对它，变了就 recreate() 自刷新——
     * 这样从设置页改完主题返回时，上一页也会立刻换成新配色。
     */
    public static String currentKey(Context ctx) {
        return getScheme() + "/" + getMode() + "/" + (isNight(ctx) ? "n" : "d");
    }

    private static String pref(String key, String def) {
        try {
            String v = SPUtils.getInstance().getString(key, def);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    private static void save(String key, String val) {
        try {
            SPUtils.getInstance().put(key, val);
        } catch (Throwable ignored) {
            // 存不上就用默认值，不影响本次会话
        }
    }

    private static int indexOf(String[] arr, String v) {
        if (v == null) {
            return -1;
        }
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------- 明暗模式

    /** 用户是否要求深色（"system" 时看系统） */
    public static boolean isNight(Context ctx) {
        String m = getMode();
        if ("light".equals(m)) {
            return false;
        }
        if ("dark".equals(m)) {
            return true;
        }
        try {
            Configuration c = ctx.getResources().getConfiguration();
            return (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在 Activity.attachBaseContext 里调用。
     * "跟随系统" 时原样返回；否则返回一个 uiMode 被覆盖的 Context，
     * 让 values-night 资源按用户选择生效。
     */
    public static Context wrap(Context base) {
        if (base == null) {
            return null;
        }
        String m = getMode();
        if ("system".equals(m)) {
            return base;
        }
        int want = "dark".equals(m)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        try {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            if ((c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == want) {
                return base;
            }
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | want;
            return base.createConfigurationContext(c);
        } catch (Throwable t) {
            return base;
        }
    }

    // -------------------------------------------------------------- 应用配色

    /** 在 setContentView 之前调用，把配色覆盖样式打到 Activity 主题上 */
    public static void apply(Context ctx, Resources.Theme theme) {
        if (ctx == null || theme == null) {
            return;
        }
        String name = "ThemeOverlay.App.Scheme." + getScheme() + (isNight(ctx) ? ".Night" : "");
        int id = styleId(ctx, name);
        if (id != 0) {
            theme.applyStyle(id, true);
        }
    }

    /** 覆盖样式名（给设置页做预览用） */
    public static String overlayName(String scheme, boolean night) {
        return "ThemeOverlay.App.Scheme." + scheme + (night ? ".Night" : "");
    }

    private static final java.util.HashMap<String, Integer> STYLE_CACHE =
            new java.util.HashMap<String, Integer>();

    private static int styleId(Context ctx, String name) {
        Integer hit = STYLE_CACHE.get(name);
        if (hit != null) {
            return hit.intValue();
        }
        int id = 0;
        try {
            id = ctx.getResources().getIdentifier(name, "style", ctx.getPackageName());
        } catch (Throwable ignored) {
            id = 0;
        }
        if (id != 0) {
            // 只缓存成功结果，避免早期查询失败被永久缓存
            STYLE_CACHE.put(name, Integer.valueOf(id));
        }
        return id;
    }

    // ------------------------------------------------- 运行时按「属性名」取主题色

    private static final java.util.HashMap<String, Integer> ATTR_CACHE =
            new java.util.HashMap<String, Integer>();

    /**
     * 取当前主题下某个 {@code app_*} 属性的颜色值。
     *
     * <p>为什么传**属性名**而不是资源 id：opt11 新增的 {@code app_*} 属性不在
     * public.xml 里，id 是 aapt2 构建时按声明顺序分配的 —— 写死 id 会随 attrs.xml
     * 改动而悄悄失效。设置页里的控件 id 出于同样理由也是按名字解析。
     *
     * <p>调用点：底栏选中/未选中配色（MainActivity.resetImgs / selectTab）、
     * CustomToolbar 的默认标题色。
     *
     * <p>解析失败时兜底到 framework 的 {@code ?android:attr/textColorPrimary}，
     * 最后再兜底到不透明黑 —— 绝不给调用方返回 0（透明），否则会出现
     * 「文字突然看不见」这种最难查的问题。
     */
    public static int themeColor(Context ctx, String attrName) {
        if (ctx == null || attrName == null) {
            return 0xFF000000;
        }
        try {
            Resources.Theme theme = ctx.getTheme();
            if (theme == null) {
                return 0xFF000000;
            }
            android.util.TypedValue tv = new android.util.TypedValue();
            int attrId = attrId(ctx, attrName);
            if (attrId != 0 && theme.resolveAttribute(attrId, tv, true)) {
                int c = valueToColor(ctx, tv);
                if (c != 0) {
                    return c;
                }
            }
            // 兜底：framework 的 textColorPrimary
            if (theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
                int c = valueToColor(ctx, tv);
                if (c != 0) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的兜底
        }
        return 0xFF000000;
    }

    private static int attrId(Context ctx, String attrName) {
        Integer hit = ATTR_CACHE.get(attrName);
        if (hit != null) {
            return hit.intValue();
        }
        int id = 0;
        try {
            id = ctx.getResources().getIdentifier(attrName, "attr", ctx.getPackageName());
        } catch (Throwable ignored) {
            id = 0;
        }
        if (id != 0) {
            ATTR_CACHE.put(attrName, Integer.valueOf(id));
        }
        return id;
    }

    /** TypedValue → ARGB。颜色可能已经被 resolveRefs 展开，也可能还挂在资源 id 上。 */
    private static int valueToColor(Context ctx, android.util.TypedValue tv) {
        if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        if (tv.resourceId != 0) {
            try {
                return ctx.getResources().getColor(tv.resourceId);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        return 0;
    }
}
