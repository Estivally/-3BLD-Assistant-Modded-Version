package zd.cornermemory.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import zd.cornermemory.utils.SPUtils;

/**
 * 学习进度导出 / 导入（联想词 SRS + 818 SRS + 每日统计 + 818 最优公式）。
 *
 * <p>导出：把 SP 里带学习前缀的键按类型分桶写成 JSON，落到
 * {@code getExternalFilesDir("study")}，再通过 FileProvider 分享出去。
 *
 * <p>导入：只认 {@code v==1 && kind=="study-progress"}，逐条覆盖写回，并写 {@code study_data_ver}
 * 让两个学习页在 onResume 时重建队列。
 */
public final class StudyDataIO {

    private static final String TAG = "StudyData";

    private static final String KIND = "study-progress";
    private static final String K_VER = "study_data_ver";

    /** 允许导出/导入的键前缀：联想词 SRS、818 SRS（含最优公式）、两边的每日统计。 */
    private static final String[] PREFIXES = {"lx_srs_", "alg818_", "lx_h_"};

    private StudyDataIO() {
    }

    private static boolean accept(String key) {
        if (key == null) {
            return false;
        }
        for (String p : PREFIXES) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 导出

    /** 生成导出 JSON 文本；countOut[0] 写入条数（可为 null）。 */
    public static String buildJson(int[] countOut) throws Exception {
        SPUtils sp = SPUtils.getInstance();
        Map<String, ?> all = sp.getAll();

        JSONObject ints = new JSONObject();
        JSONObject longs = new JSONObject();
        JSONObject bools = new JSONObject();
        JSONObject strings = new JSONObject();
        int count = 0;

        if (all != null) {
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                if (!accept(k)) {
                    continue;
                }
                Object v = e.getValue();
                if (v instanceof Integer) {
                    ints.put(k, ((Integer) v).intValue());
                    count++;
                } else if (v instanceof Long) {
                    longs.put(k, ((Long) v).longValue());
                    count++;
                } else if (v instanceof Boolean) {
                    bools.put(k, ((Boolean) v).booleanValue());
                    count++;
                } else if (v instanceof String) {
                    strings.put(k, (String) v);
                    count++;
                }
            }
        }

        JSONObject root = new JSONObject();
        root.put("v", 1);
        root.put("app", "zd.cornermemory");
        root.put("kind", KIND);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("count", count);
        root.put("ints", ints);
        root.put("longs", longs);
        root.put("bools", bools);
        root.put("strings", strings);

        if (countOut != null && countOut.length > 0) {
            countOut[0] = count;
        }
        return root.toString(2);
    }

    /** 导出文件名：study-progress-yyyyMMdd-HHmmss.json。 */
    public static String exportName() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return "study-progress-" + fmt.format(new Date()) + ".json";
    }

    /**
     * API 29+ 写到公共「下载/三盲助手」目录，用户能在文件管理器里看到，导入时也能从系统文件选择器选到。
     * 低于 29 或写入失败返回 null，调用方回退到 {@link #exportToFile}。
     */
    public static Uri exportToDownloads(Context ctx, String name, String content) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            return null;
        }
        Uri uri = null;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/三盲助手");
            uri = ctx.getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                return null;
            }
            java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) {
                return null;
            }
            try {
                os.write(content.getBytes("UTF-8"));
                os.flush();
            } finally {
                os.close();
            }
            Log.i(TAG, "导出到下载目录 → " + uri);
            return uri;
        } catch (Throwable t) {
            Log.w(TAG, "写下载目录失败，回退到应用私有目录", t);
            if (uri != null) {
                try {
                    ctx.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    public static File exportToFile(Context ctx) throws Exception {
        int[] n = new int[1];
        String content = buildJson(n);
        int count = n[0];
        File dir = ctx.getExternalFilesDir("study");
        if (dir == null) {
            dir = new File(ctx.getFilesDir(), "study");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建导出目录: " + dir.getAbsolutePath());
        }
        File out = new File(dir, exportName());
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } finally {
            fos.close();
        }
        Log.i(TAG, "导出 " + count + " 条 → " + out.getAbsolutePath());
        return out;
    }

    /** 调系统分享面板把文件发出去；失败返回 false（文件已经落盘，调用方提示路径即可）。 */
    public static boolean share(Context ctx, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", file);
            if (uri == null) {
                return false;
            }
            return shareUri(ctx, uri);
        } catch (Throwable t) {
            Log.w(TAG, "分享失败", t);
            return false;
        }
    }

    /** 直接分享一个 Uri（MediaStore 导出的文件用它）。 */
    public static boolean shareUri(Context ctx, Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, "三盲助手学习进度");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // 分享面板预览缩略图只能通过 clipData 拿到读权限，只给 EXTRA_STREAM + flag 会报 SecurityException。
            i.setClipData(android.content.ClipData.newRawUri("", uri));
            ctx.startActivity(Intent.createChooser(i, "导出学习进度"));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "分享失败", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 导入

    /** 预读条数，用来在确认框里显示「将覆盖 N 条」；解析失败返回 -1。 */
    public static int peekCount(Context ctx, Uri uri) {
        try {
            JSONObject root = parse(ctx, uri);
            return root.optInt("count", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int importFrom(Context ctx, Uri uri) throws Exception {
        JSONObject root = parse(ctx, uri);
        if (root.optInt("v", 0) != 1) {
            throw new Exception("不是本应用导出的进度文件（版本不符）");
        }
        if (!KIND.equals(root.optString("kind", ""))) {
            throw new Exception("文件类型不对，缺少 kind=study-progress");
        }

        SPUtils sp = SPUtils.getInstance();
        int n = 0;

        JSONObject ints = root.optJSONObject("ints");
        if (ints != null) {
            Iterator<String> it = ints.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!accept(k)) {
                    continue;
                }
                sp.put(k, ints.optInt(k));
                n++;
            }
        }
        JSONObject longs = root.optJSONObject("longs");
        if (longs != null) {
            Iterator<String> it = longs.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!accept(k)) {
                    continue;
                }
                sp.put(k, longs.optLong(k));
                n++;
            }
        }
        JSONObject bools = root.optJSONObject("bools");
        if (bools != null) {
            Iterator<String> it = bools.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!accept(k)) {
                    continue;
                }
                sp.put(k, bools.optBoolean(k));
                n++;
            }
        }
        JSONObject strings = root.optJSONObject("strings");
        if (strings != null) {
            Iterator<String> it = strings.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!accept(k)) {
                    continue;
                }
                String v = strings.optString(k, "");
                if (v.length() == 0) {
                    sp.remove(k);
                } else {
                    sp.put(k, v);
                }
                n++;
            }
        }

        sp.put(K_VER, System.currentTimeMillis());
        Log.i(TAG, "导入 " + n + " 条");
        return n;
    }

    private static JSONObject parse(Context ctx, Uri uri) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("打不开文件");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
            return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 当前数据版本戳；学习页 onResume 时比对，变了就重建队列。 */
    public static long version() {
        try {
            return SPUtils.getInstance().getLong(K_VER, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
