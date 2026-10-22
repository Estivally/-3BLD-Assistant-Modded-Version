# -*- coding: utf-8 -*-
"""批量编辑页加「换图」：逐行换图 + 统一保存。

改动对象：src_java/zd/cornermemory/ui/LxBatchEditActivity.java
语义：
  - 点行内缩略图 → 弹「选择图片 / 清除自定义图」
  - 选好的图先放内存（Row.pendingPic），点底部「保存修改」才写盘 + 写 SP
  - 顶部/底部新增「恢复默认图」：把当前列表里带自定义图的行标记为清除
  - 提交沿用 LxPicStorageUtils.saveCustomImageFromBytes（与单卡换图同一套目录）
"""
import io
import sys

TARGET = r"D:\Program\dsh\rebuild\src_java\zd\cornermemory\ui\LxBatchEditActivity.java"

with io.open(TARGET, "r", encoding="utf-8") as f:
    src = f.read()

applied = []


def rep(old, new, count=1, tag=""):
    global src
    n = src.count(old)
    if n != count:
        print("FAIL [%s] 命中 %d 次（期望 %d）" % (tag, n, count))
        sys.exit(1)
    src = src.replace(old, new, count)
    applied.append(tag)


# ---------------------------------------------------------------- 1. import
rep("""import android.graphics.BitmapFactory;
import android.os.Bundle;""",
    """import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;""",
    1, "import/Uri")

rep("""import android.app.Dialog;
import android.content.Intent;""",
    """import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;""",
    1, "import/AlertDialog")

rep("""import java.io.File;
import java.util.ArrayList;""",
    """import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;""",
    1, "import/io")

rep("""import zd.cornermemory.utils.Dictionary;
import zd.cornermemory.utils.LxWordStorage;""",
    """import zd.cornermemory.utils.Dictionary;
import zd.cornermemory.utils.LxPicStorageUtils;
import zd.cornermemory.utils.LxWordStorage;""",
    1, "import/LxPicStorageUtils")

# ---------------------------------------------------------------- 2. 常量 + 字段
rep("""    /** 与 LxPicResourceManager.sendAssetUpdatedBroadcast 同一个 action */
    private static final String ACTION_ASSET_UPDATED = "lx_asset_updated";""",
    """    /** 与 LxPicResourceManager.sendAssetUpdatedBroadcast 同一个 action */
    private static final String ACTION_ASSET_UPDATED = "lx_asset_updated";

    /** 选图请求码 */
    private static final int REQ_PIC = 4501;""",
    1, "const/REQ_PIC")

rep("""    private RowAdapter adapter;
    private EditText searchInput;
    /** 有未保存编辑 */
    private boolean dirty;""",
    """    private RowAdapter adapter;
    private EditText searchInput;
    /** 选图请求发起时对应的编码 */
    private String pickTargetCode;
    /** 有未保存编辑 */
    private boolean dirty;""",
    1, "field/pickTargetCode")

# ---------------------------------------------------------------- 3. Row 模型
rep("""    /** 一行 = 一个编码 */
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
    }""",
    """    /** 一行 = 一个编码 */
    static final class Row {
        final String code;
        final String builtin;
        final int resId;
        String value;
        /** 当前生效的自定义图路径（null = 用内置图） */
        String picPath;
        /** 已选好但还没保存的新图字节 */
        byte[] pendingPic;
        /** 已标记「清除自定义图」，保存时删 SP 键 */
        boolean picCleared;
        /** 这一行有未保存的图片改动 */
        boolean picDirty;

        Row(String code, String builtin, int resId, String picPath, String value) {
            this.code = code;
            this.builtin = builtin;
            this.resId = resId;
            this.picPath = picPath;
            this.value = value;
        }
    }""",
    1, "model/Row")

# ---------------------------------------------------------------- 4. onCreate 绑定
rep("""        bindClick("lx_edit_reset_all", new Runnable() {
            @Override public void run() { confirmResetAll(); }
        });""",
    """        bindClick("lx_edit_reset_all", new Runnable() {
            @Override public void run() { confirmResetAll(); }
        });
        bindClick("lx_edit_reset_pics", new Runnable() {
            @Override public void run() { confirmResetPics(); }
        });""",
    1, "bind/reset_pics")

# ---------------------------------------------------------------- 5. save() 提交图片
rep("""                if (!cur.equals(v)) {
                    LxWordStorage.putWord(r.code, v);
                    changed++;
                }
            }
        }
        dirty = false;""",
    """                if (!cur.equals(v)) {
                    LxWordStorage.putWord(r.code, v);
                    changed++;
                }
            }
            if (r.picDirty) {
                if (commitPic(r)) {
                    changed++;
                }
                r.picDirty = false;
                r.pendingPic = null;
                r.picCleared = false;
            }
        }
        dirty = false;""",
    1, "save/pic")

# ---------------------------------------------------------------- 6. 图片提交 + 选图 + 恢复默认图
rep("""    /** 让可能开着的联想页 / 记忆页刷新（与批量导入同一个广播） */
    private void notifyAssetUpdated() {
        try {
            sendBroadcast(new Intent(ACTION_ASSET_UPDATED));
        } catch (Throwable t) {
            Log.w(TAG, "发送刷新广播失败", t);
        }
    }""",
    """    /** 让可能开着的联想页 / 记忆页刷新（与批量导入同一个广播） */
    private void notifyAssetUpdated() {
        try {
            sendBroadcast(new Intent(ACTION_ASSET_UPDATED));
        } catch (Throwable t) {
            Log.w(TAG, "发送刷新广播失败", t);
        }
    }

    // ------------------------------------------------------------------ 联想图

    /** 提交一行的图片改动；返回是否真的写了 SP。 */
    private boolean commitPic(Row r) {
        try {
            SPUtils sp = SPUtils.getInstance();
            String old = sp.getString(r.code + PIC_SUFFIX, "");
            String target;
            if (r.picCleared) {
                target = "";
            } else if (r.pendingPic != null && r.pendingPic.length > 0) {
                target = LxPicStorageUtils.saveCustomImageFromBytes(this, r.code, r.pendingPic);
                if (TextUtils.isEmpty(target)) {
                    Log.w(TAG, "图片写入失败: " + r.code);
                    return false;
                }
            } else {
                return false;
            }
            if (target.length() == 0) {
                sp.remove(r.code + PIC_SUFFIX);
            } else {
                sp.put(r.code + PIC_SUFFIX, target);
            }
            if (!TextUtils.isEmpty(old) && !old.equals(target)) {
                deleteOldPic(old);
            }
            r.picPath = target.length() == 0 ? null : target;
            Log.i(TAG, "图片 " + r.code + ": "
                    + (old.length() == 0 ? "(内置)" : old) + " → "
                    + (target.length() == 0 ? "(内置)" : target));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "图片保存失败: " + r.code, t);
            return false;
        }
    }

    /** 只删托管目录里的旧图，用户自选的外部路径不动。 */
    private void deleteOldPic(String path) {
        try {
            if (LxPicStorageUtils.isManagedLocalPath(this, path)) {
                LxPicStorageUtils.deleteManagedImage(this, path);
            }
        } catch (Throwable t) {
            Log.w(TAG, "删除旧图失败: " + path, t);
        }
    }

    private void showPicMenu(final Row r) {
        final boolean hasCustom = r.pendingPic != null || r.picPath != null;
        String[] items = hasCustom
                ? new String[]{str("lx_batch_edit_pic_pick"), str("lx_batch_edit_pic_clear")}
                : new String[]{str("lx_batch_edit_pic_pick")};
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(r.code + " · " + str("lx_batch_edit_pic_title"));
        b.setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    pickImage(r);
                } else {
                    clearPic(r);
                }
            }
        });
        b.setNegativeButton(str("lx_batch_edit_cancel"), null);
        b.show();
    }

    private void pickImage(Row r) {
        pickTargetCode = r.code;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_PIC);
        } catch (Throwable t) {
            Log.e(TAG, "打开选图失败", t);
            Toast.makeText(this, str("lx_batch_edit_pic_fail"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PIC || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        String code = pickTargetCode;
        pickTargetCode = null;
        Row r = rowByCode(code);
        if (r == null) {
            return;
        }
        try {
            byte[] bytes = readBytes(data.getData());
            if (bytes == null || bytes.length == 0) {
                Toast.makeText(this, str("lx_batch_edit_pic_fail"), Toast.LENGTH_SHORT).show();
                return;
            }
            r.pendingPic = bytes;
            r.picCleared = false;
            r.picDirty = true;
            r.picPath = null;
            dirty = true;
            if (adapter != null) adapter.notifyDataSetChanged();
            Log.i(TAG, "选图 " + code + " ← " + bytes.length + " B（待保存）");
            Toast.makeText(this, str("lx_batch_edit_pic_picked"), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "读取图片失败: " + code, t);
            Toast.makeText(this, str("lx_batch_edit_pic_fail"), Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] readBytes(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 8 * 1024 * 1024) {
                    Log.w(TAG, "图片超过 8MB，放弃: " + uri);
                    return null;
                }
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearPic(Row r) {
        r.pendingPic = null;
        r.picCleared = true;
        r.picDirty = true;
        r.picPath = null;
        dirty = true;
        if (adapter != null) adapter.notifyDataSetChanged();
        Toast.makeText(this, format(str("lx_batch_edit_pic_cleared"), r.code),
                Toast.LENGTH_SHORT).show();
    }

    private Row rowByCode(String code) {
        if (code == null) {
            return null;
        }
        for (Row r : allRows) {
            if (r.code.equals(code)) {
                return r;
            }
        }
        return null;
    }

    private void confirmResetPics() {
        int n = 0;
        for (Row r : visibleRows) {
            if (r.pendingPic != null || r.picPath != null) n++;
        }
        if (n == 0) {
            Toast.makeText(this, str("lx_batch_edit_pic_reset_none"), Toast.LENGTH_SHORT).show();
            return;
        }
        showConfirm(str("lx_batch_edit_pic_reset_title"),
                format(str("lx_batch_edit_pic_reset_msg"), n),
                new Runnable() {
                    @Override public void run() { resetPicsVisible(); }
                });
    }

    private void resetPicsVisible() {
        int n = 0;
        for (Row r : visibleRows) {
            if (r.pendingPic != null || r.picPath != null) {
                r.pendingPic = null;
                r.picCleared = true;
                r.picDirty = true;
                r.picPath = null;
                n++;
            }
        }
        dirty = true;
        if (adapter != null) adapter.notifyDataSetChanged();
        Toast.makeText(this, format(str("lx_batch_edit_pic_reset_done"), n),
                Toast.LENGTH_SHORT).show();
    }""",
    1, "methods/pic")

# ---------------------------------------------------------------- 7. Adapter：缩略图点击 + 待保存图预览
rep("""                if (h.reset != null) {""",
    """                if (h.img != null) {
                    h.img.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (h.row == null) return;
                            showPicMenu(h.row);
                        }
                    });
                }
                if (h.reset != null) {""",
    1, "adapter/imgClick")

rep("""        private void bindImage(ImageView img, Row r) {
            if (img == null) return;
            Bitmap bmp = decodeCustomThumb(r.customPicPath);""",
    """        private void bindImage(ImageView img, Row r) {
            if (img == null) return;
            Bitmap bmp = r.pendingPic != null
                    ? decodeBytesThumb(r.pendingPic)
                    : decodeCustomThumb(r.picPath);""",
    1, "adapter/bindImage")

rep("""                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                return BitmapFactory.decodeFile(path, o2);
            } catch (Throwable t) {
                return null;
            }
        }
    }""",
    """                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                return BitmapFactory.decodeFile(path, o2);
            } catch (Throwable t) {
                return null;
            }
        }

        /** 还没保存的新图（内存字节）缩略图。 */
        private Bitmap decodeBytesThumb(byte[] data) {
            if (data == null || data.length == 0) return null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (o.outWidth <= 0 || o.outHeight <= 0) return null;
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 72 && o.outHeight / (sample * 2) >= 72) {
                    sample *= 2;
                }
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            } catch (Throwable t) {
                return null;
            }
        }
    }""",
    1, "adapter/decodeBytesThumb")

# ---------------------------------------------------------------- 8. 类注释补一句
rep(""" * <p><b>提交语义</b>：所有编辑先落在内存 model（{@link Row#value}），
 * 底部「保存修改」才写 SP —— 单一提交点，退出前有脏数据拦截。""",
    """ * <p><b>提交语义</b>：所有编辑先落在内存 model（{@link Row#value}），
 * 底部「保存修改」才写 SP —— 单一提交点，退出前有脏数据拦截。
 *
 * <p><b>联想图</b>：点行内缩略图弹「选择图片 / 清除自定义图」，选好的字节先挂在
 * {@link Row#pendingPic} 里只做预览，同样等「保存修改」才经
 * {@code LxPicStorageUtils.saveCustomImageFromBytes} 落盘并写 {@code <code>_LXPIC}。""",
    1, "doc")

with io.open(TARGET, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)

print("OK %d steps: %s" % (len(applied), ", ".join(applied)))
