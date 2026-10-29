package com.example.recentslock;

import android.content.ComponentName;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs inside system_server ("android" scope).
 *
 * 1) Swipe on a card  -> launcher calls ATMS.removeTask() -> ActivityTaskSupervisor.removeTaskById().
 *    We make that a no-op (return false) when the task belongs to a locked package.
 * 2) "Clear all"      -> RecentTasks.removeAllVisibleTasks(). We let it run, but veto
 *    Task.removeIfPossible() for locked tasks and put those tasks back into the recents list.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[RecentsLock] ";
    private XSharedPreferences prefs;

    /** Set while removeAllVisibleTasks() is running on this thread. */
    private static final ThreadLocal<Boolean> clearingAll = ThreadLocal.withInitial(() -> false);
    /** Locked tasks removed from the list during Clear all, to be restored afterwards. */
    private static final ThreadLocal<List<Object[]>> saved = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName)) return;

        prefs = new XSharedPreferences("com.example.recentslock", MainActivity.PREFS);
        prefs.makeWorldReadable();

        ClassLoader cl = lpp.classLoader;
        try {
            hookSwipe(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "swipe hook failed: " + t);
        }
        try {
            hookClearAll(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "clear-all hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- swipe
    private void hookSwipe(ClassLoader cl) {
        // Primary: ActivityTaskSupervisor.removeTaskById (ActivityStackSupervisor on Android 10/11)
        Class<?> sup = XposedHelpers.findClassIfExists("com.android.server.wm.ActivityTaskSupervisor", cl);
        if (sup == null)
            sup = XposedHelpers.findClassIfExists("com.android.server.wm.ActivityStackSupervisor", cl);
        if (sup != null) {
            XposedBridge.hookAllMethods(sup, "removeTaskById", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object root = tryField(p.thisObject, "mRootWindowContainer");
                    if (root == null) root = tryField(p.thisObject, "mRootActivityContainer");
                    blockIfLocked(root, p);
                }
            });
        }

        // Fallback: ActivityTaskManagerService.removeTask(int) - the Binder entry point the launcher calls.
        Class<?> atms = XposedHelpers.findClassIfExists("com.android.server.wm.ActivityTaskManagerService", cl);
        if (atms != null) {
            XposedBridge.hookAllMethods(atms, "removeTask", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    blockIfLocked(tryField(p.thisObject, "mRootWindowContainer"), p);
                }
            });
        }
        XposedBridge.log(TAG + "swipe hooks installed (supervisor=" + (sup != null)
                + ", atms=" + (atms != null) + ")");
    }

    private void blockIfLocked(Object root, XC_MethodHook.MethodHookParam p) {
        try {
            if (root == null || p.args.length == 0 || !(p.args[0] instanceof Integer)) return;
            Object task = XposedHelpers.callMethod(root, "anyTaskForId", (Integer) p.args[0]);
            if (task != null && isLocked(pkgOf(task))) {
                XposedBridge.log(TAG + "blocked removal of " + pkgOf(task));
                p.setResult(false);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "blockIfLocked: " + t);
        }
    }

    // ------------------------------------------------------------ clear all
    private void hookClearAll(ClassLoader cl) {
        Class<?> recents = XposedHelpers.findClass("com.android.server.wm.RecentTasks", cl);
        Class<?> taskCls = XposedHelpers.findClassIfExists("com.android.server.wm.Task", cl);
        if (taskCls == null)  // Android 10
            taskCls = XposedHelpers.findClass("com.android.server.wm.TaskRecord", cl);

        XposedBridge.hookAllMethods(recents, "removeAllVisibleTasks", new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void beforeHookedMethod(MethodHookParam p) {
                clearingAll.set(true);
                List<Object[]> keep = new ArrayList<>();
                try {
                    List<Object> tasks = (List<Object>) XposedHelpers.getObjectField(p.thisObject, "mTasks");
                    for (int i = 0; i < tasks.size(); i++) {
                        Object t = tasks.get(i);
                        if (isLocked(pkgOf(t))) keep.add(new Object[]{i, t});
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "collect locked: " + t);
                }
                saved.set(keep);
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    List<Object[]> keep = saved.get();
                    if (keep == null || keep.isEmpty()) return;
                    List<Object> tasks = (List<Object>) XposedHelpers.getObjectField(p.thisObject, "mTasks");
                    for (Object[] e : keep) {               // ascending original index
                        Object t = e[1];
                        if (!tasks.contains(t)) tasks.add(Math.min((Integer) e[0], tasks.size()), t);
                        try { XposedHelpers.callMethod(p.thisObject, "notifyTaskAdded", t); }
                        catch (Throwable ignored) { }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "restore: " + t);
                } finally {
                    clearingAll.set(false);
                    saved.remove();
                }
            }
        });

        // Veto actual destruction of locked tasks while "Clear all" is running.
        XposedBridge.hookAllMethods(taskCls, "removeIfPossible", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (clearingAll.get() && isLocked(pkgOf(p.thisObject))) p.setResult(null);
            }
        });
        XposedBridge.log(TAG + "clear-all hook installed");
    }

    // -------------------------------------------------------------- helpers
    private boolean isLocked(String pkg) {
        if (pkg == null) return false;
        if (prefs.hasFileChanged()) prefs.reload();
        Set<String> s = prefs.getStringSet(MainActivity.KEY, Collections.emptySet());
        return s.contains(pkg);
    }

    private static Object tryField(Object o, String name) {
        try { return XposedHelpers.getObjectField(o, name); } catch (Throwable t) { return null; }
    }

    private static String pkgOf(Object task) {
        try {
            ComponentName cn = (ComponentName) tryField(task, "realActivity");
            if (cn == null) cn = (ComponentName) tryField(task, "origActivity");
            if (cn != null) return cn.getPackageName();
        } catch (Throwable ignored) { }
        try {
            Intent i = (Intent) XposedHelpers.callMethod(task, "getBaseIntent");
            if (i != null && i.getComponent() != null) return i.getComponent().getPackageName();
            if (i != null) return i.getPackage();
        } catch (Throwable ignored) { }
        return null;
    }
}
