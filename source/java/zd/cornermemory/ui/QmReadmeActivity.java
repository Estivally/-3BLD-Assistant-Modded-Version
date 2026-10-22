package zd.cornermemory.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import zd.cornermemory.view.CustomToolbar;

/**
 * 浅梦公式集说明页：把两份 Excel 的「说明」sheet 与各分页的整页备注渲染出来。
 *
 * <p>资产 {@code assets/qm_readme.txt}，行协议（由 {@code tools/qm_readme_import.py} 生成）：
 * <pre>
 * #V1            首行版本
 * [S] 标题        一级标题
 * [G] 分组        分页名
 * [P] 段落
 * [I] 条目
 * [L] 链接
 * </pre>
 * 每行都不含换行符；段落内的换行在生成时已拆成多条 {@code [P]}。
 */
public class QmReadmeActivity extends BaseActivity {

    private static final String TAG = "QmReadme";

    private static final String ASSET = "qm_readme.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_qm_readme", "layout"));

        CustomToolbar tb = (CustomToolbar) findViewById(id("toolbar", "id"));
        if (tb != null) {
            tb.setTitle(str("alg818_readme_title"));
            View back = tb.getBackButton();
            if (back != null) {
                back.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
            }
        }

        LinearLayout body = (LinearLayout) findViewById(id("qm_readme_body", "id"));
        TextView empty = (TextView) findViewById(id("qm_readme_empty", "id"));
        ScrollView scroll = (ScrollView) findViewById(id("qm_readme_scroll", "id"));
        if (body == null) {
            return;
        }
        String text = readAsset();
        if (text == null || text.trim().length() == 0) {
            Log.w(TAG, "说明资产为空: " + ASSET);
            if (empty != null) {
                empty.setVisibility(View.VISIBLE);
            }
            if (scroll != null) {
                scroll.setVisibility(View.GONE);
            }
            return;
        }
        if (empty != null) {
            empty.setVisibility(View.GONE);
        }
        if (scroll != null) {
            scroll.setVisibility(View.VISIBLE);
        }
        render(body, text);
    }

    private String readAsset() {
        InputStream in = null;
        try {
            in = getAssets().open(ASSET);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            String s = new String(bo.toByteArray(), "UTF-8");
            if (s.length() > 0 && s.charAt(0) == '\uFEFF') {
                s = s.substring(1);
            }
            return s;
        } catch (Throwable t) {
            Log.e(TAG, "读说明资产失败", t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void render(LinearLayout body, String text) {
        String[] lines = text.split("\r\n|\n|\r");
        int sections = 0;
        int groups = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) {
                continue;
            }
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[S] ")) {
                sections++;
                body.addView(sectionView(line.substring(4).trim(), sections > 1));
            } else if (line.startsWith("[G] ")) {
                groups++;
                body.addView(groupView(line.substring(4).trim()));
            } else if (line.startsWith("[P] ")) {
                body.addView(paraView(line.substring(4).trim()));
            } else if (line.startsWith("[I] ")) {
                body.addView(itemView(line.substring(4).trim()));
            } else if (line.startsWith("[L] ")) {
                body.addView(linkView(line.substring(4).trim()));
            } else {
                body.addView(paraView(line));
            }
        }
        Log.i(TAG, "说明渲染: " + sections + " 个标题 / " + groups + " 个分页");
    }

    private TextView sectionView(String title, boolean space) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(colorAttr("app_text_primary", 0xFF111827));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(space ? 20 : 4);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView groupView(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(colorAttr("app_lit_text_4f46e5", 0xFF4F46E5));
        t.setBackgroundResource(id("control_chip_bg", "drawable"));
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView paraView(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(colorAttr("app_text_primary", 0xFF111827));
        t.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView itemView(String s) {
        TextView t = new TextView(this);
        t.setText("· " + s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(colorAttr("app_text_primary", 0xFF111827));
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(10), 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView linkView(final String url) {
        TextView t = new TextView(this);
        SpannableString sp = new SpannableString(url);
        sp.setSpan(new UnderlineSpan(), 0, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sp);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(colorAttr("app_lit_text_4f46e5", 0xFF4F46E5));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable e) {
                    Log.w(TAG, "打开链接失败: " + url, e);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    // ---------------------------------------------------------------- 小工具

    private int id(String name, String type) {
        try {
            return getResources().getIdentifier(name, type, getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private String str(String name) {
        int i = id(name, "string");
        return i == 0 ? name : getString(i);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int colorAttr(String name, int fallback) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(id(name, "attr"), tv, true)) {
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
