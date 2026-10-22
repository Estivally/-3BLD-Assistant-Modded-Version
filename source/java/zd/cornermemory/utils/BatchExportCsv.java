package zd.cornermemory.utils;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把当前全部联想词（内置 + 自定义覆盖）导出成 CSV，并把**自定义图片**一并导出，
 * 形成「导出 → 在 Excel 里批量改 → 再导入」的闭环。
 *
 * <p>导出用 <b>1 字母</b>编码（{@code AA → A}），与工作区 {@code 所有牌组.txt}、
 * Anki 包的 media 名（{@code A.jpg}）完全一致；导入侧 {@link LxCodeUtil#normalize} 会补全成
 * 2 字母，所以来回一趟无损。
 *
 * <p><b>只导自定义图</b>：内置图已经在 APK 里，重复导出 576 张既慢又没意义。
 *
 * <p><b>落盘位置的兜底链</b>（scoped storage 下 {@code /sdcard/Download} 不一定可写）：
 * <ol>
 *   <li>{@code /sdcard/Download/3BLD_Helper}（与 App 现有「导出资源包」一致，最好找）</li>
 *   <li>{@code /sdcard/Android/data/&lt;pkg&gt;/files/3BLD_Helper}（一定可写，无需任何权限）</li>
 * </ol>
 * 实际落点会在结果对话框里告诉用户。
 */
public final class BatchExportCsv {

    private static final String TAG = "BatchExportCsv";
    private static final String DIR_NAME = "3BLD_Helper";
    private static final String BOM = "\ufeff";
    private static final String CRLF = "\r\n";

    public static class Result {
        public String dir;
        public String csvPath;
        public String zipPath;
        public int rows;
        public int images;
        public boolean fellBack;
        public final List<String> notes = new ArrayList<String>();
    }

    private BatchExportCsv() {
    }

    public static Result export(Context ctx, BatchImportParser.StepListener cb) throws Exception {
        Result r = new Result();
        if (cb != null) {
            cb.onStep("正在收集词表…");
        }
        List<String> codes = new ArrayList<String>();
        try {
            if (Dictionary.dic != null) {
                codes.addAll(Dictionary.dic.keySet());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Dictionary 不可用", t);
        }
        Collections.sort(codes);
        if (codes.isEmpty()) {
            throw new IllegalStateException("词表为空，无法导出");
        }

        File dir = resolveDir(ctx, r);
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        SPUtils sp = SPUtils.getInstance();
        StringBuilder csv = new StringBuilder();
        csv.append(BOM);
        csv.append("编码,联想词,图片文件名").append(CRLF);

        // 先算出要导哪些图，再统一写（CSV 与 ZIP 共用同一份清单）
        List<String[]> imageRows = new ArrayList<String[]>();   // {display, srcPath}
        int words = 0;
        for (String code : codes) {
            String builtin = LxCodeUtil.builtinWord(code);
            String word = builtin;
            String picPath = "";
            try {
                if (sp != null) {
                    word = sp.getString(code + "_LX", builtin);
                    picPath = sp.getString(code + "_LXPIC", "");
                }
            } catch (Throwable t) {
                Log.w(TAG, "读 SPUtils 失败 code=" + code, t);
            }
            if (TextUtils.isEmpty(word)) {
                word = builtin;
            }
            if (!TextUtils.isEmpty(word)) {
                words++;
            }
            String display = LxCodeUtil.display(code);
            String imgName = "";
            if (!TextUtils.isEmpty(picPath)) {
                File img = new File(picPath);
                if (img.isFile() && img.length() > 0) {
                    imgName = display + ".jpg";
                    imageRows.add(new String[]{display, picPath});
                }
            }
            csv.append(csvField(display)).append(',')
                    .append(csvField(word == null ? "" : word)).append(',')
                    .append(csvField(imgName)).append(CRLF);
        }
        r.rows = codes.size();

        String base = "lx_words_" + ts;
        r.csvPath = new File(dir, base + ".csv").getAbsolutePath();
        writeBytes(new File(r.csvPath), csv.toString().getBytes("UTF-8"));

        // 自定义图片：另存到 <base>_images/，方便用户直接用图片查看器看
        File imgDir = new File(dir, base + "_images");
        if (!imageRows.isEmpty()) {
            if (!imgDir.exists() && !imgDir.mkdirs()) {
                r.notes.add("图片子目录创建失败，已跳过图片导出");
                imageRows.clear();
            }
        }
        for (String[] row : imageRows) {
            if (cb != null) {
                cb.onStep("正在导出图片 " + r.images + "/" + imageRows.size() + "…");
            }
            if (copy(new File(row[1]), new File(imgDir, row[0] + ".jpg"))) {
                r.images++;
            }
        }

        // 闭环包：一个 zip 就能被「ZIP 图片包」一步导回来
        if (cb != null) {
            cb.onStep("正在打包…");
        }
        r.zipPath = new File(dir, base + ".zip").getAbsolutePath();
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(r.zipPath)));
            zos.putNextEntry(new ZipEntry("words.csv"));
            zos.write(csv.toString().getBytes("UTF-8"));
            zos.closeEntry();
            for (String[] row : imageRows) {
                File src = new File(row[1]);
                InputStream in = null;
                try {
                    in = new FileInputStream(src);
                    zos.putNextEntry(new ZipEntry("images/" + row[0] + ".jpg"));
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                } finally {
                    closeQuietly(in);
                }
            }
        } finally {
            closeQuietly(zos);
        }

        r.dir = dir.getAbsolutePath();
        Log.i(TAG, "导出完成 rows=" + r.rows + " words=" + words + " images=" + r.images
                + " dir=" + r.dir + " fellBack=" + r.fellBack);
        return r;
    }

    // ------------------------------------------------------------------ 目录解析

    private static File resolveDir(Context ctx, Result r) {
        // 1) 公共下载目录（与 App 现有导出行为一致）
        try {
            File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (pub != null) {
                File dir = new File(pub, DIR_NAME);
                if (ensureWritable(dir)) {
                    return dir;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "公共下载目录不可用", t);
        }
        // 2) 应用专属外部目录：scoped storage 下一定可写，且不需要任何权限
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                File dir = new File(ext, DIR_NAME);
                if (ensureWritable(dir)) {
                    r.fellBack = true;
                    return dir;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "外部私有目录不可用", t);
        }
        // 3) 最后兜底：内部私有目录
        File dir = new File(ctx.getFilesDir(), DIR_NAME);
        if (ensureWritable(dir)) {
            r.fellBack = true;
            return dir;
        }
        throw new IllegalStateException("找不到可写的导出目录");
    }

    /** 真的写一个探测文件，避免 mkdirs() 成功但写入被拒的情况。 */
    private static boolean ensureWritable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".write_probe");
            OutputStream out = null;
            try {
                out = new FileOutputStream(probe);
                out.write('x');
            } finally {
                closeQuietly(out);
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 工具

    /** Excel 友好：含逗号/引号/换行时用引号包起来，内部引号翻倍。 */
    private static String csvField(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        OutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(f));
            out.write(data);
        } finally {
            closeQuietly(out);
        }
    }

    private static boolean copy(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new BufferedOutputStream(new FileOutputStream(dst));
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "复制失败 " + src, t);
            return false;
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
