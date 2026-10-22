package zd.cornermemory.ui;

import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import greendao.CjDao;
import zd.cornermemory.db.Cj;
import zd.cornermemory.db.GreenDaoManager;
import zd.cornermemory.utils.ThemeManager;

/**
 * 「分段趋势」页：把带分段计时（p1/p2）的成绩画成双折线图。
 *
 * <p><b>为什么用 WebView + canvas</b>：项目里没有任何图表库，也不想为了两张折线图
 * 塞进 AAR（体积 + d8 方法数）。页面是 {@code assets/segment_trend.html} 里的
 * 纯 vanilla canvas 绘制，不依赖网络、不依赖 Chart.js。
 *
 * <p><b>数据</b>：{@link Cj#getP1()} 是记忆时长(ms)、{@link Cj#getP2()} 是复原时长(ms)
 * —— 与 TimerFragment 保存时一致（{@code setP1(p1Time)} / {@code setP2(total-p1Time)}）。
 * 只统计 p1 &gt; 0 且 {@code STATUS == 0} 的记录（老版本没有分段字段，p1 为 null/0，直接跳过；
 * +2 罚时与 DNF 的罚时无法归到某一段，只计数不画线），
 * 取最近 {@link #MAX_POINTS} 条、按时间正序交给页面。
 *
 * <p><b>深色</b>：{@link ThemeManager#isNight(android.content.Context)} 的结果随数据一起
 * 传进页面（{@code dark:1}）。页面不依赖 {@code prefers-color-scheme}：那玩意儿在
 * WebView 里跟着系统走，不跟 App 内的主题切换走。
 *
 * <p><b>视图 id 按名字取</b>：新资源不进 public.xml，写死数值会和 aapt2 分配脱节
 * （同 LxBatchEditActivity 的既有做法）。
 */
public class SegmentTrendActivity extends BaseActivity {

    private static final String TAG = "SegmentTrend";

    /** 最多画多少个点（再多屏幕上就糊成一团） */
    private static final int MAX_POINTS = 200;

    private static final String PAGE = "file:///android_asset/segment_trend.html";

    private static final HashMap<String, Integer> IDS = new HashMap<String, Integer>();

    private WebView web;
    /** 页面 onPageFinished 之后再注入数据，否则 window.LX 还不存在 */
    private boolean pageReady;
    private String pendingJs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(id("activity_segment_trend", "layout"));

        web = (WebView) findViewById(id("trend_web", "id"));
        if (web == null) {
            Log.w(TAG, "找不到 trend_web，退出");
            finish();
            return;
        }

        try {
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            s.setDefaultTextEncodingName("utf-8");
        } catch (Throwable t) {
            Log.w(TAG, "WebSettings 设置失败", t);
        }

        web.setBackgroundColor(0x00000000);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingJs != null) {
                    evaluate(pendingJs);
                    pendingJs = null;
                }
            }
        });

        web.loadUrl(PAGE);
        pendingJs = buildPayload();
        if (pageReady && pendingJs != null) {
            evaluate(pendingJs);
            pendingJs = null;
        }
    }

    // ------------------------------------------------------------------ 数据

    /** 组装 {@code LX.setData({...})} 的 JS 调用串。任何异常都退化成空数据，不崩页面。 */
    private String buildPayload() {
        JSONArray rows = new JSONArray();
        int excluded = 0;
        try {
            List<Cj> list = GreenDaoManager.getInstance().getNewSession()
                    .getCjDao().queryBuilder().orderDesc(CjDao.Properties.Date).list();
            if (list != null) {
                // list 是新→旧；先筛出有分段数据的，再取最近 MAX_POINTS 条
                ArrayList<Cj> picked = new ArrayList<Cj>();
                for (int i = 0; i < list.size(); i++) {
                    Cj c = list.get(i);
                    if (c == null) continue;
                    Long p1 = c.getP1();
                    Long p2 = c.getP2();
                    if (p1 == null || p1.longValue() <= 0) continue;
                    if (p2 == null || p2.longValue() < 0) continue;
                    // STATUS：0=正常，1=+2 罚时（显示时补 2000ms），2=DNF。
                    // 罚时加在总时间上、归不到记忆或复原某一段；DNF 不是有效成绩，
                    // 所以两者都不入图，只统计条数并在页面上说明。
                    int status = 0;
                    try {
                        status = c.getStatus();
                    } catch (Throwable ignored) {
                        status = 0;
                    }
                    if (status != 0) {
                        excluded++;
                        continue;
                    }
                    picked.add(c);
                }
                // picked[0] 最新 → 从窗口末端往回走，得到时间正序
                int last = Math.min(picked.size() - 1, MAX_POINTS - 1);
                for (int i = last; i >= 0; i--) {
                    Cj c = picked.get(i);
                    Long d = c.getDate();
                    JSONObject o = new JSONObject();
                    o.put("m", c.getP1().longValue());
                    o.put("e", c.getP2().longValue());
                    o.put("d", d == null ? 0L : d.longValue());
                    rows.put(o);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取成绩失败", t);
        }

        JSONObject root = new JSONObject();
        try {
            root.put("dark", ThemeManager.isNight(this) ? 1 : 0);
            root.put("rows", rows);
            root.put("excluded", excluded);
        } catch (Throwable t) {
            Log.w(TAG, "组装 JSON 失败", t);
        }
        Log.i(TAG, "趋势数据: rows=" + rows.length() + ", excluded=" + excluded);
        return "LX.setData(" + root.toString() + ")";
    }

    private void evaluate(String js) {
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable t) {
            try {
                web.loadUrl("javascript:" + js);
            } catch (Throwable ignored) {
                // 页面已销毁之类，忽略
            }
        }
    }

    // ------------------------------------------------------------------ 小工具

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
