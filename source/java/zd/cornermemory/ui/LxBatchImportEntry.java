package zd.cornermemory.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;

/**
 * 把「资源管理」对话框里新加的「批量导入」行接到 {@link LxBatchImportActivity}。
 *
 * <p>为什么要单独一个类做接线：那个对话框是 smali 里 inflate 的
 * （{@code LxPicResourceManager.showManageDialog()}），没法在里面直接写 Java。
 * 做法是在 smali 的 {@code setContentView} 之后插一条 {@code invoke-static}，
 * 把 dialog 的 root View 交过来，这里再按 <b>名字</b> 找 id 并挂点击 ——
 * 名字取不到就安静返回，绝不抛异常（否则会崩在弹对话框这一步）。
 *
 * <p>用名字而不是写死 id：新加的行是 {@code @+id/} 声明的，id 由 aapt2 构建时分配，
 * 写死会随资源增删而悄悄错位。
 */
public final class LxBatchImportEntry {

    private static final String TAG = "LxBatchImportEntry";

    /** 对话框里新加的行的 id 名 */
    private static final String ROW_BATCH_IMPORT = "action_batch_import";

    /** opt21：批量编辑联想词入口行的 id 名（与批量导入同一处接线） */
    private static final String ROW_BATCH_EDIT = "action_batch_edit";

    /** opt23：联想词 SRS 学习入口已改到「记忆」tab（见 Alg818Entry），此处不再接线 */

    /** opt24：818 公式熟练度入口已改到「记忆」tab（见 Alg818Entry），此处不再接线 */

    private LxBatchImportEntry() {
    }

    /**
     * 由 smali 调用。任何一步失败都只记日志、不影响对话框正常弹出。
     *
     * @param ctx    宿主 Activity（用作 Context）
     * @param root   对话框的 root View
     * @param dialog 对话框本身，点击后先关掉它
     */
    public static void attach(final Context ctx, final View root, final Dialog dialog) {
        if (ctx == null || root == null) {
            Log.w(TAG, "attach 参数为空，跳过接线");
            return;
        }
        wire(ctx, root, dialog, ROW_BATCH_IMPORT, LxBatchImportActivity.class);
        wire(ctx, root, dialog, ROW_BATCH_EDIT, LxBatchEditActivity.class);
    }

    /** 按名字找行并挂「关弹窗 → 跳目标页」。找不到就安静返回。 */
    private static void wire(final Context ctx, final View root, final Dialog dialog,
                             String rowIdName, final Class<?> target) {
        int id = 0;
        try {
            id = ctx.getResources().getIdentifier(rowIdName, "id", ctx.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "getIdentifier 失败: " + rowIdName, t);
        }
        if (id == 0) {
            Log.w(TAG, "找不到 id: " + rowIdName);
            return;
        }
        View row = null;
        try {
            row = root.findViewById(id);
        } catch (Throwable t) {
            Log.w(TAG, "findViewById 失败", t);
        }
        if (row == null) {
            Log.w(TAG, "找不到 view: " + rowIdName);
            return;
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dialog != null) {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {
                        // 关不掉也不影响下一步
                    }
                }
                try {
                    Context c = v.getContext();
                    c.startActivity(new Intent(c, target));
                } catch (Throwable t) {
                    Log.e(TAG, "启动 " + target.getSimpleName() + " 失败", t);
                }
            }
        });
        Log.i(TAG, "入口已接线: " + rowIdName);
    }
}
