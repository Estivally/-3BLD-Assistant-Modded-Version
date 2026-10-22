package zd.cornermemory.ui;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;

/**
 * 「记忆」tab 顶部两个学习入口的接线（818 公式熟练度 / 联想词间隔重复）。
 *
 * <p>由 {@code RememberFragment.onCreateView} 在 inflate 完 tab1 布局后调用
 * {@code Alg818Entry.attach(rootView)}，按名字找 chip 并挂跳转：
 * {@code alg818_entry} → {@link Alg818Activity}、{@code lx_study_entry} → {@link LxStudyActivity}。
 * 找不到某个 chip 只记日志，不影响另一个，也不影响记忆页本身。</p>
 *
 * <p>opt24：两个入口都从「其他 → 资源管理」弹窗挪到「记忆」tab 顶部（成绩列表按钮左侧）。
 * 类名沿用最初只接 818 时的名字，避免动 smali 里的调用点。</p>
 */
public final class Alg818Entry {

    private static final String TAG = "MemoryEntry";

    private static final String ID_ALG818 = "alg818_entry";

    private static final String ID_LX_STUDY = "lx_study_entry";

    private static final String ID_STATS = "study_stats_entry";

    private Alg818Entry() {
    }

    /** 由 smali 调用。任何一步失败都只记日志。 */
    public static void attach(View root) {
        if (root == null) {
            Log.w(TAG, "attach 参数为空，跳过接线");
            return;
        }
        wire(root, ID_ALG818, Alg818Activity.class, "818 公式熟练度");
        wire(root, ID_LX_STUDY, LxStudyActivity.class, "联想词学习");
        wire(root, ID_STATS, StudyStatsActivity.class, "学习统计");
    }

    /** 按名字找 chip 并挂「点开目标页」。找不到就安静返回。 */
    private static void wire(View root, String idName, final Class<?> target, final String label) {
        try {
            final Context ctx = root.getContext();
            if (ctx == null) {
                Log.w(TAG, "context 为空，跳过 " + idName);
                return;
            }
            int id = root.getResources().getIdentifier(idName, "id", ctx.getPackageName());
            if (id == 0) {
                Log.w(TAG, "找不到 " + idName + " 的 id");
                return;
            }
            View chip = root.findViewById(id);
            if (chip == null) {
                Log.w(TAG, idName + " 视图为空（可能布局未更新）");
                return;
            }
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent it = new Intent(ctx, target);
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(it);
                        Log.i(TAG, "打开 " + label);
                    } catch (Throwable t) {
                        Log.e(TAG, "打开 " + label + " 失败", t);
                    }
                }
            });
            Log.i(TAG, "记忆页入口已接线: " + idName);
        } catch (Throwable t) {
            Log.e(TAG, "接线 " + idName + " 失败", t);
        }
    }
}
