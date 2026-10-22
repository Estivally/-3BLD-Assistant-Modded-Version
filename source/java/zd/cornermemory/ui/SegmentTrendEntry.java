package zd.cornermemory.ui;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import zd.cornermemory.view.CustomToolbar;

/**
 * 把「分段趋势」入口接到成绩页（{@link CjActivity}）的 CustomToolbar 右侧。
 *
 * <p>为什么要单独一个类做接线：CjActivity 是原版 smali，没法在里面直接写 Java。
 * 做法是在 {@code CjActivity.onCreate} 末尾插一条 {@code invoke-static}，
 * 这里再按名字找 toolbar / 图标 / 背景，挂一个 ImageButton。
 * <b>任何一步失败都只记日志、绝不抛异常</b>（否则成绩页直接崩）。
 *
 * <p>按钮样式照抄 CjActivity 自己的「成绩管理」按钮：CENTER_INSIDE +
 * {@code score_toolbar_button_bg} + 12dp/10dp 内边距，只是图标换成
 * {@code ic_trend_chart}（项目里原本没有图表图标）。
 */
public final class SegmentTrendEntry {

    private static final String TAG = "SegmentTrendEntry";

    private SegmentTrendEntry() {
    }

    /**
     * 由 smali 调用：{@code invoke-static {p0}, Lzd/cornermemory/ui/SegmentTrendEntry;->attach(Landroid/app/Activity;)V}
     *
     * @param act 宿主 Activity（成绩页）
     */
    public static void attach(final Activity act) {
        if (act == null) {
            return;
        }
        try {
            int tbId = act.getResources().getIdentifier("toolbar", "id", act.getPackageName());
            if (tbId == 0) {
                Log.w(TAG, "找不到 toolbar");
                return;
            }
            View v = act.findViewById(tbId);
            if (!(v instanceof CustomToolbar)) {
                Log.w(TAG, "toolbar 不是 CustomToolbar: " + v);
                return;
            }
            LinearLayout right = ((CustomToolbar) v).getRightButtonContainer();
            if (right == null) {
                Log.w(TAG, "rightButtonContainer 为空");
                return;
            }

            ImageButton btn = new ImageButton(act);
            int icon = res(act, "ic_trend_chart", "drawable");
            if (icon != 0) {
                btn.setImageResource(icon);
            }
            int bg = res(act, "score_toolbar_button_bg", "drawable");
            if (bg != 0) {
                btn.setBackgroundResource(bg);
            }
            btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int padH = dp(act, 12);
            int padV = dp(act, 10);
            btn.setPadding(padH, padV, padH, padV);
            int titleId = res(act, "segment_trend_title", "string");
            if (titleId != 0) {
                btn.setContentDescription(act.getString(titleId));
            }
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    try {
                        act.startActivity(new Intent(act, SegmentTrendActivity.class));
                    } catch (Throwable t) {
                        Log.e(TAG, "启动分段趋势页失败", t);
                    }
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMarginStart(dp(act, 2));
            right.addView(btn, lp);
            Log.i(TAG, "分段趋势入口已接线");
        } catch (Throwable t) {
            Log.w(TAG, "分段趋势入口接线失败（忽略）", t);
        }
    }

    private static int res(Activity act, String name, String type) {
        try {
            return act.getResources().getIdentifier(name, type, act.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int dp(Activity act, int v) {
        try {
            return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable t) {
            return v;
        }
    }
}
