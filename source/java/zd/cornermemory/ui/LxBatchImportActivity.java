package zd.cornermemory.ui;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashMap;
import java.util.List;

import zd.cornermemory.utils.BatchExportCsv;
import zd.cornermemory.utils.BatchImportParser;
import zd.cornermemory.utils.LxPicImportExportUtils;

/**
 * 「批量导入」页：4 种来源（Anki .apkg / CSV·TSV / ZIP 图片包 / 文件夹）+ 导出 CSV。
 *
 * <p><b>统一管线</b>：不管哪种来源，{@link BatchImportParser} 都先把它解析成一个临时
 * {@code .lxres}，再交给现有的 {@link LxPicImportExportUtils#importCustomAssets} 应用。
 * 这样「批量导入」和「导入资源包」写出来的 SPUtils 必然一致，不会出现两套语义。
 *
 * <p><b>视图 id 一律按名字取</b>（{@code getIdentifier}）：本页是新增资源，
 * 反编译树里没有它的 id，写死数值会和重打包时 aapt2 的分配结果脱节。
 *
 * <p><b>线程</b>：解析、写盘、导入全部在后台线程；UI 操作一律 {@code runOnUiThread}。
 */
public class LxBatchImportActivity extends BaseActivity {

    private static final String TAG = "LxBatchImport";

    private static final int REQ_APKG = 0x2101;
    private static final int REQ_CSV = 0x2102;
    private static final int REQ_ZIP = 0x2103;
    private static final int REQ_TREE = 0x2104;

    /** 与 LxPicResourceManager.sendAssetUpdatedBroadcast 用的是同一个 action */
    private static final String ACTION_ASSET_UPDATED = "lx_asset_updated";

    /** getIdentifier 结果缓存：只缓存命中，避免早期查询失败被永久记住 */
    private static final HashMap<String, Integer> IDS = new HashMap<String, Integer>();

    private Dialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;
    private TextView progressCounter;

    /** 有后台任务在跑时禁止再次触发选择器，避免并发写 SPUtils */
    private volatile boolean busy;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_lx_batch_import", "layout"));

        bind("action_import_apkg", BatchImportParser.KIND_APKG, REQ_APKG);
        bind("action_import_csv", BatchImportParser.KIND_CSV, REQ_CSV);
        bind("action_import_zip", BatchImportParser.KIND_ZIP, REQ_ZIP);
        bind("action_import_folder", BatchImportParser.KIND_FOLDER, REQ_TREE);
        bindHelp("action_format_help");
        bindExport("action_export_csv");
    }

    @Override
    protected void onDestroy() {
        hideProgress();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 接线

    private void bind(String idName, final String kind, final int req) {
        View row = findViewById(id(idName, "id"));
        if (row == null) {
            Log.w(TAG, "找不到行: " + idName);
            return;
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(kind, req);
            }
        });
    }

    private void bindExport(String idName) {
        View row = findViewById(id(idName, "id"));
        if (row == null) {
            Log.w(TAG, "找不到行: " + idName);
            return;
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });
    }

    private void bindHelp(String idName) {
        View row = findViewById(id(idName, "id"));
        if (row == null) {
            Log.w(TAG, "找不到行: " + idName);
            return;
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelpDialog();
            }
        });
    }

    /**
     * 格式说明弹窗。
     *
     * <p>内容整篇放在 {@code lx_batch_help_body} 一个字符串里（带 \n 分段），
     * 由一个 TextView 承载 —— 拆成十几个 TextView 既难维护也没法整段复制。
     *
     * <p>取不到 layout / id 时静默降级成 Toast，绝不让「看说明」这个动作把页面搞崩。
     */
    private void showHelpDialog() {
        try {
            int layoutId = id("dialog_lx_batch_help", "layout");
            if (layoutId == 0) {
                Log.w(TAG, "找不到 dialog_lx_batch_help 布局");
                toast(str("lx_batch_help_title"));
                return;
            }
            View v = LayoutInflater.from(this).inflate(layoutId, null);
            final Dialog d = new Dialog(this, id("CardOnlyDialog", "style"));
            d.setContentView(v);
            d.setCancelable(true);
            d.setCanceledOnTouchOutside(true);

            setText(v, "batch_help_title", str("lx_batch_help_title"));
            TextView body = (TextView) v.findViewById(id("batch_help_text", "id"));
            if (body != null) {
                body.setText(str("lx_batch_help_body"));
            }
            View close = v.findViewById(id("batch_help_close", "id"));
            if (close != null) {
                close.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        safeDismiss(d);
                    }
                });
            }
            d.show();
            Log.i(TAG, "格式说明已弹出");
        } catch (Throwable t) {
            Log.e(TAG, "格式说明弹窗失败", t);
            toast(str("lx_batch_help_title"));
        }
    }

    // ------------------------------------------------------------------ 选文件

    private void pick(String kind, int req) {
        if (busy) {
            return;
        }
        Intent intent;
        if (BatchImportParser.KIND_FOLDER.equals(kind)) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            // .apkg / .lxres 都没有注册 MIME 类型，用 */* 才不会把用户的文件挡在门外；
            // 格式合法性在解析阶段按内容判断。
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(intent, req);
        } catch (Throwable t) {
            Log.e(TAG, "无法打开选择器 kind=" + kind, t);
            toast(str("lx_batch_no_picker"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        // ACTION_GET_CONTENT 是允许选中「文件夹」的（实测在模拟器上很容易误选到目录，
        // 结果就是 ZIP 流里一个条目都没有、报「没有找到可导入的内容」，很误导）。
        try {
            String mime = getContentResolver().getType(uri);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                Log.w(TAG, "选中的是文件夹，已拒绝: " + uri);
                toast(str("lx_batch_pick_file_only"));
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "取 MIME 失败（继续尝试解析）", t);
        }
        String kind;
        if (requestCode == REQ_APKG) {
            kind = BatchImportParser.KIND_APKG;
        } else if (requestCode == REQ_CSV) {
            kind = BatchImportParser.KIND_CSV;
        } else if (requestCode == REQ_ZIP) {
            kind = BatchImportParser.KIND_ZIP;
        } else if (requestCode == REQ_TREE) {
            kind = BatchImportParser.KIND_FOLDER;
            // 目录选择器返回的授权默认只在本次会话有效，取一次持久化授权，
            // 免得进程重启后同一个目录读不了。
            try {
                int take = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, take);
            } catch (Throwable t) {
                Log.w(TAG, "takePersistableUriPermission 失败（不影响本次导入）", t);
            }
        } else {
            return;
        }
        Log.i(TAG, "选中 kind=" + kind + " uri=" + uri);
        startImport(kind, uri);
    }

    // ------------------------------------------------------------------ 导入

    private void startImport(final String kind, final Uri uri) {
        busy = true;
        showProgress(str("lx_batch_parse_title"), str("lx_batch_parse_message"), true, 0);
        bg(new Runnable() {
            @Override
            public void run() {
                BatchImportParser.Result result = null;
                Throwable error = null;
                try {
                    result = BatchImportParser.parse(LxBatchImportActivity.this, uri, kind,
                            new BatchImportParser.StepListener() {
                                @Override
                                public void onStep(final String message) {
                                    ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            setProgressMessage(message);
                                        }
                                    });
                                }
                            });
                } catch (Throwable t) {
                    error = t;
                }

                if (error instanceof BatchImportParser.NewAnkiFormatException) {
                    Log.w(TAG, "检测到新版 Anki 格式: " + error.getMessage());
                    final Throwable e = error;
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            finishJob();
                            showMessageDialog(str("lx_batch_new_anki_title"), str("lx_batch_new_anki"));
                        }
                    });
                    return;
                }
                if (error != null) {
                    Log.e(TAG, "解析失败 kind=" + kind, error);
                    final Throwable e = error;
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            finishJob();
                            showMessageDialog(str("lx_batch_failed_title"), safeMessage(e));
                        }
                    });
                    return;
                }

                final BatchImportParser.Result r = result;
                ui(new Runnable() {
                    @Override
                    public void run() {
                        hideProgress();
                        busy = false;
                        if (r == null || r.isEmpty()) {
                            showMessageDialog(str("lx_batch_confirm_title"), str("lx_batch_no_valid"));
                            return;
                        }
                        showConfirm(r);
                    }
                });
            }
        });
    }

    private void showConfirm(final BatchImportParser.Result r) {
        String msg = getString(strId("lx_batch_confirm_message"),
                r.sourceLabel, Integer.valueOf(r.total),
                Integer.valueOf(r.wordCount), Integer.valueOf(r.imageCount));
        if (r.invalidCount > 0) {
            msg += getString(strId("lx_batch_confirm_invalid"),
                    Integer.valueOf(r.invalidCount), joinSamples(r.invalidSamples, 20));
        }
        if (!r.notes.isEmpty()) {
            msg += "\n\n" + joinSamples(r.notes, 3);
        }
        showConfirmDialog(str("lx_batch_confirm_title"), msg, str("lx_batch_confirm_go"), new Runnable() {
            @Override
            public void run() {
                apply(r);
            }
        });
    }

    private void apply(final BatchImportParser.Result r) {
        busy = true;
        showProgress(str("lx_batch_importing_title"), str("lx_batch_importing_message"), false, 100);
        bg(new Runnable() {
            @Override
            public void run() {
                LxPicImportExportUtils.ImportResult ir = null;
                Throwable error = null;
                try {
                    ir = LxPicImportExportUtils.importCustomAssets(
                            LxBatchImportActivity.this,
                            Uri.fromFile(new File(r.lxresPath)),
                            new LxPicImportExportUtils.ProgressListener() {
                                @Override
                                public void onProgress(final int done, final int total) {
                                    ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            setProgress(done, total);
                                        }
                                    });
                                }
                            });
                } catch (Throwable t) {
                    error = t;
                } finally {
                    deleteQuietly(r.lxresPath);
                }

                if (error != null) {
                    Log.e(TAG, "导入失败", error);
                    final Throwable e = error;
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            finishJob();
                            showMessageDialog(str("lx_batch_failed_title"), safeMessage(e));
                        }
                    });
                    return;
                }

                final LxPicImportExportUtils.ImportResult fir = ir;
                ui(new Runnable() {
                    @Override
                    public void run() {
                        finishJob();
                        // 让记忆页 / 联想图页立刻刷新（它们监听同一个 action）
                        try {
                            sendBroadcast(new Intent(ACTION_ASSET_UPDATED));
                        } catch (Throwable t) {
                            Log.w(TAG, "刷新广播发送失败", t);
                        }
                        if (fir == null) {
                            showMessageDialog(str("lx_batch_done_title"), str("lx_batch_no_valid"));
                            return;
                        }
                        showMessageDialog(str("lx_batch_done_title"),
                                getString(strId("lx_batch_done_message"),
                                        Integer.valueOf(fir.totalItems),
                                        Integer.valueOf(fir.updatedCount),
                                        Integer.valueOf(fir.imageUpdatedCount),
                                        Integer.valueOf(fir.remarkUpdatedCount),
                                        Integer.valueOf(fir.skippedCount)));
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ 导出

    private void startExport() {
        if (busy) {
            return;
        }
        busy = true;
        showProgress(str("lx_batch_export_title"), str("lx_batch_parse_message"), true, 0);
        bg(new Runnable() {
            @Override
            public void run() {
                BatchExportCsv.Result er = null;
                Throwable error = null;
                try {
                    er = BatchExportCsv.export(LxBatchImportActivity.this,
                            new BatchImportParser.StepListener() {
                                @Override
                                public void onStep(final String message) {
                                    ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            setProgressMessage(message);
                                        }
                                    });
                                }
                            });
                } catch (Throwable t) {
                    error = t;
                }

                if (error != null) {
                    Log.e(TAG, "导出失败", error);
                    final Throwable e = error;
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            finishJob();
                            showMessageDialog(str("lx_batch_export_failed_title"), safeMessage(e));
                        }
                    });
                    return;
                }

                final BatchExportCsv.Result fer = er;
                ui(new Runnable() {
                    @Override
                    public void run() {
                        finishJob();
                        showMessageDialog(str("lx_batch_export_done_title"),
                                getString(strId("lx_batch_export_done_message"),
                                        Integer.valueOf(fer.rows),
                                        Integer.valueOf(fer.images),
                                        fer.csvPath));
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ 对话框

    private void showProgress(String title, String message, boolean indeterminate, int max) {
        hideProgress();
        try {
            View v = LayoutInflater.from(this).inflate(id("dialog_lx_pic_progress", "layout"), null);
            Dialog d = new Dialog(this, id("CardOnlyDialog", "style"));
            d.setContentView(v);
            d.setCancelable(false);
            d.setCanceledOnTouchOutside(false);

            TextView t = (TextView) v.findViewById(id("progress_dialog_title", "id"));
            if (t != null) {
                t.setText(title);
            }
            progressMessage = (TextView) v.findViewById(id("progress_dialog_message", "id"));
            if (progressMessage != null) {
                progressMessage.setText(message);
            }
            progressBar = (ProgressBar) v.findViewById(id("progress_dialog_bar", "id"));
            if (progressBar != null) {
                progressBar.setIndeterminate(indeterminate);
                progressBar.setMax(max <= 0 ? 100 : max);
                progressBar.setProgress(0);
            }
            progressCounter = (TextView) v.findViewById(id("progress_dialog_counter", "id"));
            if (progressCounter != null) {
                progressCounter.setText("");
            }
            progressDialog = d;
            d.show();
        } catch (Throwable t) {
            Log.w(TAG, "进度对话框创建失败", t);
        }
    }

    private void setProgressMessage(String message) {
        if (progressMessage != null && !TextUtils.isEmpty(message)) {
            progressMessage.setText(message);
        }
    }

    private void setProgress(int done, int total) {
        try {
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                if (total > 0) {
                    progressBar.setMax(total);
                }
                progressBar.setProgress(done);
            }
            if (progressCounter != null) {
                progressCounter.setText(done + " / " + total);
            }
        } catch (Throwable ignored) {
            // 进度更新失败不影响导入本身
        }
    }

    private void hideProgress() {
        Dialog d = progressDialog;
        progressDialog = null;
        progressBar = null;
        progressMessage = null;
        progressCounter = null;
        if (d != null) {
            try {
                if (d.isShowing()) {
                    d.dismiss();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 后台任务收尾：关进度条、解除忙碌标记。 */
    private void finishJob() {
        hideProgress();
        busy = false;
    }

    private void showConfirmDialog(String title, String message, String confirmText, final Runnable onConfirm) {
        try {
            View v = LayoutInflater.from(this).inflate(id("dialog_lx_batch_confirm", "layout"), null);
            final Dialog d = new Dialog(this, id("CardOnlyDialog", "style"));
            d.setContentView(v);
            d.setCancelable(true);
            d.setCanceledOnTouchOutside(false);

            setText(v, "batch_dialog_title", title);
            setText(v, "batch_dialog_message", message);
            setText(v, "batch_dialog_confirm", confirmText);

            View cancel = v.findViewById(id("batch_dialog_cancel", "id"));
            if (cancel != null) {
                cancel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        safeDismiss(d);
                    }
                });
            }
            View confirm = v.findViewById(id("batch_dialog_confirm", "id"));
            if (confirm != null) {
                confirm.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        safeDismiss(d);
                        if (onConfirm != null) {
                            onConfirm.run();
                        }
                    }
                });
            }
            d.show();
        } catch (Throwable t) {
            Log.e(TAG, "确认对话框创建失败", t);
            // 弹不出来就直接执行，别把用户卡住
            if (onConfirm != null) {
                onConfirm.run();
            }
        }
    }

    private void showMessageDialog(String title, String message) {
        try {
            View v = LayoutInflater.from(this).inflate(id("dialog_lx_batch_confirm", "layout"), null);
            final Dialog d = new Dialog(this, id("CardOnlyDialog", "style"));
            d.setContentView(v);
            d.setCancelable(true);
            d.setCanceledOnTouchOutside(true);

            setText(v, "batch_dialog_title", title);
            setText(v, "batch_dialog_message", message);
            setText(v, "batch_dialog_confirm", str("lx_batch_ok"));

            // 只展示结果时不需要「取消」，让确定按钮占满整行
            View cancel = v.findViewById(id("batch_dialog_cancel", "id"));
            if (cancel != null) {
                cancel.setVisibility(View.GONE);
            }
            View confirm = v.findViewById(id("batch_dialog_confirm", "id"));
            if (confirm != null) {
                confirm.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        safeDismiss(d);
                    }
                });
            }
            d.show();
        } catch (Throwable t) {
            Log.w(TAG, "提示对话框创建失败", t);
            toast(title + "：" + message);
        }
    }

    private void setText(View root, String idName, String text) {
        TextView tv = (TextView) root.findViewById(id(idName, "id"));
        if (tv != null) {
            tv.setText(text);
        }
    }

    private void safeDismiss(Dialog d) {
        try {
            if (d != null && d.isShowing()) {
                d.dismiss();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 工具

    private void bg(Runnable r) {
        Thread t = new Thread(r, "lx-batch");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void ui(Runnable r) {
        try {
            runOnUiThread(r);
        } catch (Throwable t) {
            Log.w(TAG, "runOnUiThread 失败", t);
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static String joinSamples(List<String> list, int max) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(list.size(), max);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(list.get(i));
        }
        if (list.size() > n) {
            sb.append("…");
        }
        return sb.toString();
    }

    /** 异常 → 用户能看懂的一句话。 */
    private String safeMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        if (TextUtils.isEmpty(m)) {
            m = t.getClass().getSimpleName();
        }
        return m;
    }

    private static void deleteQuietly(String path) {
        if (path == null) {
            return;
        }
        try {
            File f = new File(path);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
            // 临时文件删不掉不影响结果
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

    /** 取字符串 id（取不到返回 0） */
    private int strId(String name) {
        int v = id(name, "string");
        if (v == 0) {
            Log.w(TAG, "找不到字符串: " + name);
        }
        return v;
    }

    /** 取字符串内容；取不到时回退成资源名，保证界面不会出现空白 */
    private String str(String name) {
        int v = strId(name);
        if (v == 0) {
            return name;
        }
        try {
            return getString(v);
        } catch (Throwable t) {
            return name;
        }
    }
}
