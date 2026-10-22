package zd.cornermemory.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;

import zd.cornermemory.utils.ThemeManager;

/**
 * 「主题与外观」设置子页：4 套配色方案 × 浅色 / 深色 / 跟随系统。
 *
 * <p>视图 id 一律用 {@code getIdentifier} 按名字取，不写死数值——
 * 本页是新加的资源，反编译树里没有它的 id，写死会与重打包时 aapt2 的分配结果脱节。
 *
 * <p>切换后直接 {@code recreate()}：改 uiMode 必须换 Context，配色也顺带重来一遍，
 * 单一代码路径最稳。
 */
public class ThemeSettingActivity extends BaseActivity {

    /** 预览色 [方案][0=浅色 1=深色] = {主色, 强调色, 页面底色} */
    private static final int[][][] PREVIEW = {
            {{0xFF6366F1, 0xFFF97316, 0xFFFAFAFA}, {0xFF818CF8, 0xFFFB923C, 0xFF121417}},
            {{0xFF0D9488, 0xFFF59E0B, 0xFFF5FAF9}, {0xFF2DD4BF, 0xFFFBBF24, 0xFF0D1A18}},
            {{0xFF7C3AED, 0xFFEC4899, 0xFFFAF8FD}, {0xFFC084FC, 0xFFF472B6, 0xFF150E1E}},
            {{0xFF475569, 0xFFD97706, 0xFFF6F7F8}, {0xFF94A3B8, 0xFFFBBF24, 0xFF101215}},
    };

    private static final int WHITE = 0xFFFFFFFF;
    private static final int TRANSPARENT = 0x00000000;

    /** getIdentifier 结果缓存：只缓存命中，避免早期查询失败被永久记住 */
    private static final HashMap<String, Integer> IDS = new HashMap<String, Integer>();

    private LinearLayout schemeBox;
    private TextView[] modeSeg;
    private View[] schemeCards;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_theme_setting", "layout"));

        schemeBox = (LinearLayout) findViewById(id("theme_scheme_box", "id"));
        schemeCards = new View[ThemeManager.SCHEMES.length];
        buildSchemeCards();

        modeSeg = new TextView[ThemeManager.MODES.length];
        for (int i = 0; i < modeSeg.length; i++) {
            modeSeg[i] = (TextView) findViewById(id("theme_mode_" + i, "id"));
            if (modeSeg[i] == null) {
                continue;
            }
            modeSeg[i].setText(ThemeManager.MODE_LABELS[i]);
            final int idx = i;
            modeSeg[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onModeClick(idx);
                }
            });
        }

        refresh();
    }

    // ------------------------------------------------------------------ 构建

    private void buildSchemeCards() {
        if (schemeBox == null) {
            return;
        }
        LayoutInflater inf = LayoutInflater.from(this);
        int itemLayout = id("item_theme_scheme", "layout");
        int nameId = id("theme_name", "id");
        int descId = id("theme_desc", "id");
        int swatchRadius = dim("radius_sm");

        for (int i = 0; i < ThemeManager.SCHEMES.length; i++) {
            View card = inf.inflate(itemLayout, schemeBox, false);

            TextView name = (TextView) card.findViewById(nameId);
            TextView desc = (TextView) card.findViewById(descId);
            if (name != null) {
                name.setText(ThemeManager.SCHEME_LABELS[i]);
            }
            if (desc != null) {
                desc.setText(ThemeManager.SCHEME_DESCS[i]);
            }
            card.setContentDescription(ThemeManager.SCHEME_LABELS[i] + "，" + ThemeManager.SCHEME_DESCS[i]);

            final int idx = i;
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onSchemeClick(idx);
                }
            });

            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) card.getLayoutParams();
            if (lp != null && i == ThemeManager.SCHEMES.length - 1) {
                lp.bottomMargin = 0;
                card.setLayoutParams(lp);
            }

            schemeBox.addView(card);
            schemeCards[i] = card;
            paintSwatch(card, i, swatchRadius);
        }
    }

    private void paintSwatch(View card, int schemeIndex, int radius) {
        int[] p = PREVIEW[schemeIndex][ThemeManager.isNight(this) ? 1 : 0];
        paintChip(card.findViewById(id("theme_swatch_0", "id")), p[0], radius);
        paintChip(card.findViewById(id("theme_swatch_1", "id")), p[1], radius);
        paintChip(card.findViewById(id("theme_swatch_2", "id")), p[2], radius);
    }

    private static void paintChip(View v, int color, int radius) {
        if (v == null) {
            return;
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        v.setBackground(g);
    }

    // ------------------------------------------------------------------ 交互

    private void onSchemeClick(int idx) {
        if (ThemeManager.getSchemeIndex() == idx) {
            return;
        }
        ThemeManager.setScheme(ThemeManager.SCHEMES[idx]);
        toast(ThemeManager.SCHEME_LABELS[idx]);
        recreate();
    }

    private void onModeClick(int idx) {
        if (ThemeManager.getModeIndex() == idx) {
            return;
        }
        ThemeManager.setMode(ThemeManager.MODES[idx]);
        toast(ThemeManager.MODE_LABELS[idx]);
        recreate();
    }

    private void toast(String label) {
        try {
            Toast.makeText(this, getString(id("theme_applied", "string"), label), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 提示失败不影响切换本身
        }
    }

    // ------------------------------------------------------------------ 刷新

    private void refresh() {
        int schemeIdx = ThemeManager.getSchemeIndex();
        int modeIdx = ThemeManager.getModeIndex();

        int on = id("bg_theme_card_selected", "drawable");
        int off = id("bg_theme_card", "drawable");
        for (int i = 0; i < schemeCards.length; i++) {
            if (schemeCards[i] == null) {
                continue;
            }
            schemeCards[i].setSelected(i == schemeIdx);
            schemeCards[i].setBackgroundResource(i == schemeIdx ? on : off);
        }

        int primary = attr("app_colorPrimary");
        int secondary = attr("app_text_secondary");
        int segRadius = dim("radius_md");
        for (int i = 0; i < modeSeg.length; i++) {
            if (modeSeg[i] == null) {
                continue;
            }
            boolean sel = i == modeIdx;
            modeSeg[i].setSelected(sel);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setColor(sel ? primary : TRANSPARENT);
            g.setCornerRadius(segRadius);
            modeSeg[i].setBackground(g);
            modeSeg[i].setTextColor(sel ? WHITE : secondary);
        }
    }

    // ------------------------------------------------------------------ 取值

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

    /** 取当前主题下某个 app_xxx 属性的颜色值 */
    private int attr(String name) {
        int a = id(name, "attr");
        if (a == 0) {
            return 0xFF000000;
        }
        try {
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(a, tv, true)) {
                if (tv.resourceId != 0) {
                    return getResources().getColor(tv.resourceId);
                }
                return tv.data;
            }
        } catch (Throwable ignored) {
            // 落到默认色
        }
        return 0xFF000000;
    }

    private int dim(String name) {
        int d = id(name, "dimen");
        if (d == 0) {
            return 0;
        }
        try {
            return getResources().getDimensionPixelSize(d);
        } catch (Throwable t) {
            return 0;
        }
    }
}
