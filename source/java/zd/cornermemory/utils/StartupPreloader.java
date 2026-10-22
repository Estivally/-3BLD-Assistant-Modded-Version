package zd.cornermemory.utils;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import greendao.CjDao;
import zd.cornermemory.App;
import zd.cornermemory.db.Cj;
import zd.cornermemory.db.GreenDaoManager;

/**
 * opt12：启动预载。
 *
 * <p>原先这段逻辑在 {@code SplashActivity$4} 里，由启动页的后台线程执行。
 * 现在启动页（含 708 KB 的 splash.gif）被移除、点图标直接进 MainActivity，
 * 预载就搬到这里，由 {@code MainActivity.onCreate()} 用 {@link #start(Context)} 拉起。
 *
 * <p>行为与原实现**逐条对齐**（顺序、状态码、广播都一致）：
 * <pre>
 *   setDataLoading(true) / setDataLoaded(false) / setStartupStatus(1)
 *   clearGroupLoadedState() / allCjMap.clear() / broadcast(LOADING_STATE)
 *   ── try ──
 *   dao.detachAll() / setStartupStatus(2) / broadcast(LOADING_STATE)
 *   currentPos = SP("currentPos")
 *   preloadPriorityGroups(最多 4 组，含当前组)
 *   preloadRemainingGroups(其余未加载的组)
 *   setDataLoaded(true) / broadcast(DATA_LOADED)
 *   ── finally ──
 *   setDataLoading(false) / setStartupStatus(0) / broadcast(LOADING_STATE)
 * </pre>
 *
 * <p>之所以用 {@code getApplicationContext()} 而不是 Activity：广播用应用上下文发即可，
 * 接收方（MainActivity 里注册的 BroadcastReceiver）照样收得到，且线程不会持有 Activity。
 *
 * <p>幂等：{@link #start} 只认第一次调用，Activity 因换主题 recreate() 时不会重复预载。
 */
public final class StartupPreloader implements Runnable {

    private static final String TAG = "StartupPreloader";

    /** 优先预载的组数上限（含当前组），与原 SplashActivity.PRELOAD_GROUP_LIMIT 一致。 */
    private static final int PRELOAD_GROUP_LIMIT = 4;

    /**
     * {@code R.array.scr} 的资源 ID。这个值被 res/values/public.xml 钉死：
     * {@code <public type="array" name="scr" id="0x7f030038"/>}。
     * 只用来取「一共多少个成绩分组」。
     */
    private static final int ARRAY_SCR = 0x7f030038;

    private static volatile boolean sStarted;

    private final Context mApp;

    private StartupPreloader(Context ctx) {
        mApp = ctx.getApplicationContext();
    }

    /** 只在第一次调用时真正起线程。 */
    public static void start(Context ctx) {
        if (sStarted) {
            return;
        }
        synchronized (StartupPreloader.class) {
            if (sStarted) {
                return;
            }
            sStarted = true;
        }
        Thread t = new Thread(new StartupPreloader(ctx), "startup-preload");
        t.start();
    }

    @Override
    public void run() {
        App app = App.getInstance();
        app.setDataLoading(true);
        app.setDataLoaded(false);
        app.setStartupStatus(1);
        app.clearGroupLoadedState();
        app.getAllCjMap().clear();
        notifyLoadingStateChanged();

        try {
            long begin = System.currentTimeMillis();

            CjDao dao = GreenDaoManager.getInstance().getNewSession().getCjDao();
            dao.detachAll();
            app.setStartupStatus(2);
            notifyLoadingStateChanged();

            int currentPos = SPUtils.getInstance().getInt("currentPos", 0);
            Map<Integer, List<Cj>> allCjMap = app.getAllCjMap();

            preloadPriorityGroups(dao, allCjMap, currentPos);
            Log.d(TAG, "priority preload finished in " + (System.currentTimeMillis() - begin) + "ms");

            preloadRemainingGroups(dao, allCjMap, currentPos);
            Log.d(TAG, "full preload finished in " + (System.currentTimeMillis() - begin) + "ms");

            app.setDataLoaded(true);
            mApp.sendBroadcast(new Intent("ACTION_DATA_LOADED"));
        } catch (Throwable e) {
            Log.e(TAG, "preload failed", e);
        } finally {
            app.setDataLoading(false);
            app.setStartupStatus(0);
            notifyLoadingStateChanged();
        }
    }

    /**
     * 当前组 + 从头数最多 {@link #PRELOAD_GROUP_LIMIT} 组（跳过当前组）。
     * 组数取自 {@code R.array.scr} 的长度。
     */
    private void preloadPriorityGroups(CjDao dao, Map<Integer, List<Cj>> allCjMap, int currentPos) {
        int groupCount = groupCount();
        addGroupToCache(dao, allCjMap, currentPos);

        int added = 1;
        for (int group = 0; group < groupCount && added < PRELOAD_GROUP_LIMIT; group++) {
            if (group == currentPos) {
                continue;
            }
            addGroupToCache(dao, allCjMap, group);
            added++;
        }
    }

    /** 剩下所有还没进缓存、且不是当前组的组。 */
    private void preloadRemainingGroups(CjDao dao, Map<Integer, List<Cj>> allCjMap, int currentPos) {
        int groupCount = groupCount();
        for (int group = 0; group < groupCount; group++) {
            if (group == currentPos) {
                continue;
            }
            if (App.getInstance().isGroupLoaded(group)) {
                continue;
            }
            addGroupToCache(dao, allCjMap, group);
        }
    }

    /**
     * 按组号查库、按日期倒序，放进 App 的内存缓存。
     * 查出来的是 greenDAO 的托管 List，必须包一层 ArrayList 再存
     * —— 否则后续 detachAll() 会让它失效（原实现同样如此）。
     */
    private void addGroupToCache(CjDao dao, Map<Integer, List<Cj>> allCjMap, int group) {
        List<Cj> rows = dao.queryBuilder()
                .where(CjDao.Properties.Group.eq(Integer.valueOf(group)), new org.greenrobot.greendao.query.WhereCondition[0])
                .orderDesc(CjDao.Properties.Date)
                .list();

        ArrayList<Cj> detached = new ArrayList<Cj>(rows);
        allCjMap.put(Integer.valueOf(group), detached);
        App.getInstance().cacheGroupData(group, detached);
    }

    private int groupCount() {
        String[] groups = mApp.getResources().getStringArray(ARRAY_SCR);
        if (groups == null || groups.length == 0) {
            // 正常不会发生。真发生了说明资源 ID 漂了，早报错好过默默少加载。
            throw new IllegalStateException("R.array.scr (0x7f030038) is empty");
        }
        return groups.length;
    }

    private void notifyLoadingStateChanged() {
        mApp.sendBroadcast(new Intent("ACTION_DATA_LOADING_STATE"));
    }
}
