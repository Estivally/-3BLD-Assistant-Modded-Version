package zd.cornermemory.ui;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zd.cornermemory.utils.Dictionary;
import zd.cornermemory.utils.LxWordStorage;
import zd.cornermemory.utils.SPUtils;

/**
 * 「批量编辑联想词」页：576 个编码的表格化编辑。
 *
 * <p><b>存储</b>：与原版「改词」同一机制 —— SPUtils 的 {@code <code>_LX} 键，
 * 读走 {@link LxWordStorage#currentWord}（SP 优先、内置词表回退）。
 * 页面只是把单个改词做成表格：搜索过滤 / 批量替换 / 单行恢复默认 / 全部恢复默认。
 *
 * <p><b>提交语义</b>：所有编辑先落在内存 model（{@link Row#value}），
 * 底部「保存修改」才写 SP —— 单一提交点，退出前有脏数据拦截。
 *
 * <p><b>视图 id 一律按名字取</b>（{@code getIdentifier}）：新资源不进 public.xml，
 * 写死数值会和 aapt2 的分配结果脱节（同 {@link LxBatchImportActivity} 既有做法）。
 *
 * <p><b>ListView + EditText</b>：TextWatcher 挂在 Holder 上、用 binding 标志
 * 区分「程序回填」和「用户输入」；行被回收时值已在 model 里，不丢字。
 */
public class LxBatchEditActivity extends BaseActivity {

    private static final String TAG = "LxBatchEdit";

    /** 自定义图片路径的 SP 键后缀（与原版一致，别改） */
    private static final String PIC_SUFFIX = "_LXPIC";

    /** 与 LxPicResourceManager.sendAssetUpdatedBroadcast 同一个 action */
    private static final String ACTION_ASSET_UPDATED = "lx_asset_updated";

    private static final HashMap<String, Integer> IDS = new HashMap<String, Integer>();

    /** 全量行（按编码排序） */
    private final List<Row> allRows = new ArrayList<Row>();
    /** 当前过滤后可见行 */
    private final List<Row> visibleRows = new ArrayList<Row>();

    private RowAdapter adapter;
    private EditText searchInput;
    /** 有未保存编辑 */
    private boolean dirty;

    /** 一行 = 一个编码 */
    static final class Row {
        final String code;
        final String builtin;
        final int resId;
        final String customPicPath;
        String value;

        Row(String code, String builtin, int resId, String customPicPath, String value) {
            this.code = code;
            this.builtin = builtin;
            this.resId = resId;
            this.customPicPath = customPicPath;
            this.value = value;
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_lx_batch_edit", "layout"));

        if (!loadRows()) {
            Toast.makeText(this, str("lx_batch_edit_empty"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ListView list = (ListView) findViewById(id("lx_edit_list", "id"));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        // 允许 item 里的 EditText 拿焦点（ListView 默认会让 item 整体可焦点）
        list.setItemsCanFocus(true);

        searchInput = (EditText) findViewById(id("lx_edit_search", "id"));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                applyFilter(s.toString());
            }
        });

        bindClick("lx_edit_replace", new Runnable() {
            @Override public void run() { showReplaceDialog(); }
        });
        bindClick("lx_edit_reset_all", new Runnable() {
            @Override public void run() { confirmResetAll(); }
        });
        bindClick("lx_edit_save", new Runnable() {
            @Override public void run() { save(); }
        });
    }

    @Override
    public void onBackPressed() {
        if (dirty) {
            showConfirm(str("lx_batch_edit_dirty_title"),
                    str("lx_batch_edit_dirty_msg"),
                    new Runnable() {
                        @Override public void run() { finish(); }
                    });
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 数据

    /** 从 Dictionary.dic 装载全量行。词表没初始化时返回 false。 */
    private boolean loadRows() {
        Map<String, String> dic = Dictionary.dic;
        if (dic == null || dic.isEmpty()) {
            Log.w(TAG, "Dictionary.dic 为空，无法批量编辑");
            return false;
        }
        List<String> codes = new ArrayList<String>(dic.keySet());
        Collections.sort(codes);
        SPUtils sp = SPUtils.getInstance();
        for (String code : codes) {
            String builtin = LxWordStorage.builtinWord(code);
            if (builtin.length() == 0) {
                // 词表里没词的编码没有可编辑的东西
                continue;
            }
            int resId = 0;
            try {
                Integer r = Dictionary.pic == null ? null : Dictionary.pic.get(code);
                if (r != null) resId = r.intValue();
            } catch (Throwable ignored) {
            }
            String picPath = null;
            try {
                String p = sp.getString(code + PIC_SUFFIX, "");
                if (!TextUtils.isEmpty(p) && new File(p).exists()) {
                    picPath = p;
                }
            } catch (Throwable ignored) {
            }
            allRows.add(new Row(code, builtin, resId, picPath, LxWordStorage.currentWord(code)));
        }
        visibleRows.addAll(allRows);
        Log.i(TAG, "装载联想词 " + allRows.size() + " 行");
        return !allRows.isEmpty();
    }

    /** 按编码或联想词过滤 */
    private void applyFilter(String q) {
        visibleRows.clear();
        String query = q == null ? "" : q.trim().toLowerCase();
        if (query.length() == 0) {
            visibleRows.addAll(allRows);
        } else {
            for (Row r : allRows) {
                if (r.code.toLowerCase().contains(query)
                        || r.value.toLowerCase().contains(query)) {
                    visibleRows.add(r);
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------------ 保存

    private void save() {
        int changed = 0;
        for (Row r : allRows) {
            String v = r.value == null ? "" : r.value.trim();
            String cur = LxWordStorage.currentWord(r.code);
            if (v.length() == 0 || v.equals(r.builtin)) {
                // 空 = 回到内置默认
                if (!cur.equals(r.builtin)) {
                    LxWordStorage.resetWord(r.code);
                    changed++;
                }
            } else {
                if (!cur.equals(v)) {
                    LxWordStorage.putWord(r.code, v);
                    changed++;
                }
            }
        }
        dirty = false;
        Toast.makeText(this,
                changed == 0 ? str("lx_batch_edit_no_change")
                        : format(str("lx_batch_edit_saved"), changed),
                Toast.LENGTH_SHORT).show();
        if (changed > 0) {
            notifyAssetUpdated();
        }
    }

    /** 让可能开着的联想页 / 记忆页刷新（与批量导入同一个广播） */
    private void notifyAssetUpdated() {
        try {
            sendBroadcast(new Intent(ACTION_ASSET_UPDATED));
        } catch (Throwable t) {
            Log.w(TAG, "发送刷新广播失败", t);
        }
    }

    // ------------------------------------------------------------------ 批量替换

    private void showReplaceDialog() {
        final Dialog d = new Dialog(this);
        d.setContentView(id("dialog_lx_batch_replace", "layout"));
        final EditText from = (EditText) d.findViewById(id("replace_from", "id"));
        final EditText to = (EditText) d.findViewById(id("replace_to", "id"));
        final TextView preview = (TextView) d.findViewById(id("replace_preview", "id"));
        final Runnable doPreview = new Runnable() {
            @Override public void run() {
                int n = countMatch(from.getText().toString());
                preview.setText(n == 0 ? str("lx_batch_edit_replace_none")
                        : format(str("lx_batch_edit_replace_preview"), n));
            }
        };
        View btnPreview = d.findViewById(id("replace_btn_preview", "id"));
        if (btnPreview != null) {
            btnPreview.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { doPreview.run(); }
            });
        }
        View btnApply = d.findViewById(id("replace_btn_apply", "id"));
        if (btnApply != null) {
            btnApply.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String f = from.getText().toString();
                    String t = to.getText().toString();
                    if (f.length() == 0) {
                        Toast.makeText(LxBatchEditActivity.this,
                                str("lx_batch_edit_replace_none"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int n = 0;
                    for (Row r : visibleRows) {
                        if (r.value != null && r.value.contains(f)) {
                            r.value = r.value.replace(f, t);
                            n++;
                        }
                    }
                    if (n > 0) {
                        dirty = true;
                        adapter.notifyDataSetChanged();
                        Toast.makeText(LxBatchEditActivity.this,
                                format(str("lx_batch_edit_replace_applied"), n),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(LxBatchEditActivity.this,
                                str("lx_batch_edit_replace_none"), Toast.LENGTH_SHORT).show();
                    }
                    d.dismiss();
                }
            });
        }
        View btnCancel = d.findViewById(id("replace_btn_cancel", "id"));
        if (btnCancel != null) {
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
        }
        d.show();
    }

    private int countMatch(String f) {
        if (f == null || f.length() == 0) return 0;
        int n = 0;
        for (Row r : visibleRows) {
            if (r.value != null && r.value.contains(f)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 恢复默认

    private void confirmResetAll() {
        showConfirm(str("lx_batch_edit_reset_all_title"),
                format(str("lx_batch_edit_reset_all_msg"), visibleRows.size()),
                new Runnable() {
                    @Override public void run() { resetVisible(); }
                });
    }

    private void resetVisible() {
        for (Row r : visibleRows) {
            r.value = r.builtin;
        }
        dirty = true;
        if (adapter != null) adapter.notifyDataSetChanged();
        Toast.makeText(this, str("lx_batch_edit_reset_done"), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 通用弹窗

    private void showConfirm(String title, String msg, final Runnable onOk) {
        final Dialog d = new Dialog(this);
        d.setContentView(id("dialog_lx_confirm", "layout"));
        TextView tvTitle = (TextView) d.findViewById(id("lx_confirm_title", "id"));
        TextView tvMsg = (TextView) d.findViewById(id("lx_confirm_msg", "id"));
        if (tvTitle != null) tvTitle.setText(title);
        if (tvMsg != null) tvMsg.setText(msg);
        View ok = d.findViewById(id("lx_confirm_ok", "id"));
        View cancel = d.findViewById(id("lx_confirm_cancel", "id"));
        if (ok != null) {
            ok.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    if (onOk != null) onOk.run();
                }
            });
        }
        if (cancel != null) {
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
        }
        d.show();
    }

    // ------------------------------------------------------------------ Adapter

    private final class RowAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(LxBatchEditActivity.this);

        @Override public int getCount() { return visibleRows.size(); }
        @Override public Object getItem(int position) { return visibleRows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Holder h;
            if (convertView == null) {
                convertView = inflater.inflate(id("item_lx_batch_edit", "layout"), parent, false);
                h = new Holder();
                h.code = (TextView) convertView.findViewById(id("lx_row_code", "id"));
                h.img = (ImageView) convertView.findViewById(id("lx_row_img", "id"));
                h.edit = (EditText) convertView.findViewById(id("lx_row_word", "id"));
                h.reset = convertView.findViewById(id("lx_row_reset", "id"));
                convertView.setTag(h);

                h.edit.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void afterTextChanged(Editable s) {
                        if (h.binding || h.row == null) return;
                        h.row.value = s.toString();
                        dirty = true;
                    }
                });
                if (h.reset != null) {
                    h.reset.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (h.row == null) return;
                            h.row.value = h.row.builtin;
                            dirty = true;
                            h.binding = true;
                            h.edit.setText(h.row.builtin);
                            h.binding = false;
                            Toast.makeText(LxBatchEditActivity.this,
                                    format(str("lx_batch_edit_reset_one"), h.row.code),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } else {
                h = (Holder) convertView.getTag();
            }

            Row r = visibleRows.get(position);
            h.binding = true;
            h.row = r;
            h.code.setText(r.code);
            bindImage(h.img, r);
            h.edit.setText(r.value == null ? "" : r.value);
            h.binding = false;
            return convertView;
        }

        private void bindImage(ImageView img, Row r) {
            if (img == null) return;
            Bitmap bmp = decodeCustomThumb(r.customPicPath);
            if (bmp != null) {
                img.setImageBitmap(bmp);
            } else if (r.resId != 0) {
                img.setImageResource(r.resId);
            } else {
                img.setImageDrawable(null);
            }
        }

        /** 自定义图缩略图（两段解码 + inSampleSize，目标 ~72px）。失败返回 null。 */
        private Bitmap decodeCustomThumb(String path) {
            if (path == null) return null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                if (o.outWidth <= 0 || o.outHeight <= 0) return null;
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 72 && o.outHeight / (sample * 2) >= 72) {
                    sample *= 2;
                }
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                return BitmapFactory.decodeFile(path, o2);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static final class Holder {
        TextView code;
        ImageView img;
        EditText edit;
        View reset;
        Row row;
        /** 程序回填期间为 true，TextWatcher 据此忽略 */
        boolean binding;
    }

    // ------------------------------------------------------------------ 小工具

    private void bindClick(String idName, final Runnable action) {
        View v = findViewById(id(idName, "id"));
        if (v == null) {
            Log.w(TAG, "找不到控件: " + idName);
            return;
        }
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
    }

    private String str(String name) {
        int v = id(name, "string");
        return v == 0 ? name : getString(v);
    }

    /** %s 版格式化（词表文案里用 %d 不行：getString(format, args) 走的是 %1$s 风格） */
    private String format(String pattern, int n) {
        return String.format(pattern, Integer.valueOf(n));
    }

    private String format(String pattern, String s) {
        return String.format(pattern, s);
    }

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
}
