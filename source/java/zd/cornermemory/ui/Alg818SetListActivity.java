package zd.cornermemory.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import zd.cornermemory.view.CustomToolbar;

/**
 * 公式集管理页：自定义公式集的多套管理（命名 / 切换 / 删除）。
 *
 * <p>数据在 {@link Alg818CustomSet}：每套一个 {@code filesDir/alg818_sets/<id>.txt}，
 * 索引是 SP 里的 JSON（{@code alg818_sets_index}），当前套 {@code alg818_cur_set}。
 *
 * <p>点一套 = 切到那一套（{@code setCurrent}）并回 818 页；「✎」改名、「×」删除；
 * 底部「导入新公式集」把控制权交回 818 页的导入流程（{@code start_import}）。
 */
public class Alg818SetListActivity extends BaseActivity {

    private static final String TAG = "Alg818SetPage";

    public static final String EXTRA_START_IMPORT = "start_import";

    private LinearLayout body;
    private TextView head;
    private TextView empty;
    private ScrollView scroll;
    private TextView importBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_alg818_set_list", "layout"));

        body = (LinearLayout) findViewById(id("alg818_set_body", "id"));
        head = (TextView) findViewById(id("alg818_set_head", "id"));
        empty = (TextView) findViewById(id("alg818_set_empty", "id"));
        scroll = (ScrollView) findViewById(id("alg818_set_scroll", "id"));
        importBtn = (TextView) findViewById(id("alg818_set_import", "id"));

        CustomToolbar tb = (CustomToolbar) findViewById(id("toolbar", "id"));
        if (tb != null) {
            tb.setTitle(str("alg818_set_title"));
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

        if (importBtn != null) {
            importBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent data = new Intent();
                    data.putExtra(EXTRA_START_IMPORT, true);
                    setResult(RESULT_OK, data);
                    finish();
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        if (body == null) {
            return;
        }
        List<Alg818CustomSet.SetInfo> all = Alg818CustomSet.list(this);
        String cur = Alg818CustomSet.currentId(this);
        body.removeAllViews();
        if (head != null) {
            head.setText(fmt1(str("alg818_set_head"), Integer.valueOf(all.size())));
        }
        boolean none = all.isEmpty();
        if (empty != null) {
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
        }
        if (scroll != null) {
            scroll.setVisibility(none ? View.GONE : View.VISIBLE);
        }
        for (int i = 0; i < all.size(); i++) {
            Alg818CustomSet.SetInfo si = all.get(i);
            body.addView(row(si, si.id.equals(cur), i > 0));
        }
        Log.i(TAG, "公式集列表: " + all.size() + " 套，当前 " + cur);
    }

    private LinearLayout row(final Alg818CustomSet.SetInfo si, boolean current, boolean divider) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(divider ? 10 : 0);
        box.setLayoutParams(lp);

        if (divider) {
            View line = new View(this);
            line.setBackgroundColor(colorAttr("app_lit_bg_1a000000", 0x1A000000));
            box.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        }

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(10), 0, 0);
        box.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText(si.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(colorAttr("app_text_primary", 0xFF111827));
        top.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (current) {
            TextView badge = new TextView(this);
            badge.setText(str("alg818_set_current"));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setTextColor(colorAttr("app_lit_text_4f46e5", 0xFF4F46E5));
            badge.setBackgroundResource(id("control_chip_bg", "drawable"));
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.setMarginStart(dp(6));
            top.addView(badge, blp);
        }

        TextView rename = icon("✎", str("alg818_set_rename"));
        rename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renameDialog(si);
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(32), dp(32));
        rlp.setMarginStart(dp(8));
        top.addView(rename, rlp);

        TextView del = icon("×", str("alg818_set_delete"));
        del.setTextColor(colorAttr("app_lit_text_dc2626", 0xFFDC2626));
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteDialog(si);
            }
        });
        top.addView(del, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView meta = new TextView(this);
        meta.setText(si.brief());
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        meta.setPadding(0, dp(2), 0, dp(8));
        box.addView(meta, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                use(si);
            }
        });
        return box;
    }

    private TextView icon(String text, String desc) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTextColor(colorAttr("app_text_secondary", 0xFF6B7280));
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setContentDescription(desc);
        return t;
    }

    private void use(Alg818CustomSet.SetInfo si) {
        Alg818CustomSet.setCurrent(this, si.id);
        Log.i(TAG, "切换到公式集 " + si.id + " " + si.name);
        setResult(RESULT_OK, new Intent());
        finish();
    }

    private void renameDialog(final Alg818CustomSet.SetInfo si) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(si.name);
        et.setSelection(et.getText().length());
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(str("alg818_set_rename_title"))
                    .setView(box)
                    .setPositiveButton(str("alg818_set_rename_ok"), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            String name = et.getText() == null ? "" : et.getText().toString();
                            if (Alg818CustomSet.rename(Alg818SetListActivity.this, si.id, name)) {
                                render();
                            }
                        }
                    })
                    .setNegativeButton(str("alg818_import_cancel"), null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "打开重命名弹窗失败", t);
        }
    }

    private void deleteDialog(final Alg818CustomSet.SetInfo si) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(str("alg818_set_delete_title"))
                    .setMessage(fmt1(str("alg818_set_delete_msg"), si.name))
                    .setPositiveButton(str("alg818_set_delete"), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            if (Alg818CustomSet.delete(Alg818SetListActivity.this, si.id)) {
                                Toast.makeText(Alg818SetListActivity.this,
                                        fmt1(str("alg818_set_deleted"), si.name),
                                        Toast.LENGTH_SHORT).show();
                                render();
                            }
                        }
                    })
                    .setNegativeButton(str("alg818_import_cancel"), null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "打开删除确认失败", t);
        }
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

    private String fmt1(String f, Object a) {
        try {
            return String.format(f, a);
        } catch (Throwable t) {
            return f;
        }
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
