package zd.cornermemory.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「学习设置」弹窗：联想词与 818 各三项（每日新卡 / 每日复习 / 每批数量）。
 *
 * <p>统计页、联想词学习页、818 学习页共用这一个实现，改一处三处同步。
 * 视图全部代码拼装（不给弹窗单独写 layout）。
 */
public final class StudySettingsDialog {

    private StudySettingsDialog() {
    }

    /** @param onSaved 保存成功后的回调，可为 null */
    public static void show(final Activity ctx, final Runnable onSaved) {
        final Resources res = ctx.getResources();
        final String pkg = ctx.getPackageName();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), 0);

        final EditText[] fields = new EditText[6];
        String[] labels = {
                str(res, pkg, "study_stats_src_lx") + " · " + str(res, pkg, "study_prefs_new"),
                str(res, pkg, "study_stats_src_lx") + " · " + str(res, pkg, "study_prefs_rev"),
                str(res, pkg, "study_stats_src_lx") + " · " + str(res, pkg, "study_prefs_batch"),
                str(res, pkg, "study_stats_src_alg") + " · " + str(res, pkg, "study_prefs_new"),
                str(res, pkg, "study_stats_src_alg") + " · " + str(res, pkg, "study_prefs_rev"),
                str(res, pkg, "study_stats_src_alg") + " · " + str(res, pkg, "study_prefs_batch"),
        };
        int[] values = {
                StudyPrefs.newLimit(StudyPrefs.SRC_LX),
                StudyPrefs.revLimit(StudyPrefs.SRC_LX),
                StudyPrefs.batch(StudyPrefs.SRC_LX),
                StudyPrefs.newLimit(StudyPrefs.SRC_ALG),
                StudyPrefs.revLimit(StudyPrefs.SRC_ALG),
                StudyPrefs.batch(StudyPrefs.SRC_ALG),
        };
        for (int i = 0; i < 6; i++) {
            TextView lab = new TextView(ctx);
            lab.setText(labels[i]);
            lab.setTextSize(12f);
            lab.setPadding(0, i == 0 ? dp(ctx, 4) : dp(ctx, 12), 0, dp(ctx, 4));
            box.addView(lab);

            EditText et = new EditText(ctx);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setTextSize(14f);
            et.setText(String.valueOf(values[i]));
            fields[i] = et;
            box.addView(et);
        }

        new AlertDialog.Builder(ctx)
                .setTitle(str(res, pkg, "study_prefs_title"))
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        save(ctx, fields, onSaved);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void save(Activity ctx, EditText[] fields, Runnable onSaved) {
        String[] srcs = {StudyPrefs.SRC_LX, StudyPrefs.SRC_LX, StudyPrefs.SRC_LX,
                StudyPrefs.SRC_ALG, StudyPrefs.SRC_ALG, StudyPrefs.SRC_ALG};
        int[] kinds = {StudyPrefs.KIND_NEW, StudyPrefs.KIND_REV, StudyPrefs.KIND_BATCH,
                StudyPrefs.KIND_NEW, StudyPrefs.KIND_REV, StudyPrefs.KIND_BATCH};
        for (int i = 0; i < 6; i++) {
            int v;
            try {
                v = Integer.parseInt(fields[i].getText().toString().trim());
            } catch (Throwable t) {
                Toast.makeText(ctx, str(ctx.getResources(), ctx.getPackageName(),
                        "study_prefs_invalid"), Toast.LENGTH_SHORT).show();
                return;
            }
            if (v < 0 || v > 999) {
                Toast.makeText(ctx, str(ctx.getResources(), ctx.getPackageName(),
                        "study_prefs_invalid"), Toast.LENGTH_SHORT).show();
                return;
            }
            StudyPrefs.set(srcs[i], kinds[i], v);
        }
        Toast.makeText(ctx, str(ctx.getResources(), ctx.getPackageName(),
                "study_prefs_saved"), Toast.LENGTH_SHORT).show();
        if (onSaved != null) {
            onSaved.run();
        }
    }

    private static String str(Resources res, String pkg, String name) {
        int id = res.getIdentifier(name, "string", pkg);
        return id == 0 ? name : res.getString(id);
    }

    private static int dp(Activity ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 在工具栏右侧加一个「⚙」，点开学习设置。传 null 的 toolbar 直接返回。 */
    public static void attachGear(Activity ctx, View toolbar) {
        if (!(toolbar instanceof zd.cornermemory.view.CustomToolbar)) {
            return;
        }
        zd.cornermemory.view.CustomToolbar tb = (zd.cornermemory.view.CustomToolbar) toolbar;
        LinearLayout right = tb.getRightButtonContainer();
        if (right == null) {
            return;
        }
        TextView gear = new TextView(ctx);
        gear.setText("⚙");
        gear.setTextSize(20f);
        gear.setTextColor(0xFFFFFFFF);
        gear.setGravity(android.view.Gravity.CENTER);
        gear.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(ctx, null);
            }
        });
        // 原版 CustomToolbar 的右侧容器默认 GONE，塞了子 view 也不会自己显示
        right.setVisibility(View.VISIBLE);
        right.addView(gear);
    }
}
