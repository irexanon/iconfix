package dev.frozenkeeper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Standalone LSPosed hook for Oplus Launcher 15.8.21.
 *
 * Ice Box disables packages with DISABLED_USER. Oplus Launcher then removes the
 * corresponding favorites row. This hook snapshots only main-user app rows before
 * that removal and restores them after the package is available again.
 */
public final class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "[FrozenIconKeeper] ";
    private static final String LOG_TAG = "FrozenIconKeeper";
    private static final String PREFS = "frozen_icon_keeper";
    private static final String MODEL_PACKAGE = "com.android.launcher3.model.";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher";
    private static final int OP_ADD = 1;
    private static final int OP_UPDATE = 2;
    private static final int OP_UNAVAILABLE = 4;
    private static final int OP_SUSPEND = 5;
    private static final int OP_UNSUSPEND = 6;
    private static ClassLoader classLoader;
    private static boolean attached;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if ("dev.frozenkeeper".equals(lp.packageName)) {
            try {
                XposedHelpers.findAndHookMethod("dev.frozenkeeper.DiagnosticActivity",
                        lp.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) { log(t); }
            return;
        }
        if (!LAUNCHER_PACKAGE.equals(lp.packageName)) return;
        if (lp.processName != null && !LAUNCHER_PACKAGE.equals(lp.processName)) return;
        if (attached) return;
        attached = true;
        classLoader = lp.classLoader;

        hookDeletePath();
        hookLoaderMarkDeleted();
        hookPackageTasks();
        log("attached to " + lp.packageName + " process=" + lp.processName);
    }

    private static void hookDeletePath() {
        try {
            Class<?> base = XposedHelpers.findClass(
                    MODEL_PACKAGE + "BaseModelUpdateTask", classLoader);
            Set<?> hooked = XposedBridge.hookAllMethods(base,
                    "deleteAndBindComponentsRemoved", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object task = param.thisObject;
                                if (!isPackageTask(task) || taskUserId(task) != 0) {
                                    log("delete hook skipped: non-package task or non-owner user");
                                    return;
                                }
                                for (String pkg : packagesOf(task)) {
                                    int op = operation(task);
                                    boolean frozen = isFrozen(pkg);
                                    log("delete path pkg=" + pkg + " op=" + op + " frozen=" + frozen);
                                    if (frozen) snapshotFromProvider(pkg);
                                }
                            } catch (Throwable t) {
                                log(t);
                            }
                        }
                    });
            log("hooked deleteAndBindComponentsRemoved: " + hooked.size());
        } catch (Throwable t) {
            log(t);
        }
    }

    private static void hookLoaderMarkDeleted() {
        try {
            Class<?> loaderCursor = XposedHelpers.findClass(
                    MODEL_PACKAGE + "LoaderCursor", classLoader);
            Set<?> hooked = XposedBridge.hookAllMethods(loaderCursor, "markDeleted",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                                Object receiver = param.thisObject;
                                Object user = XposedHelpers.getObjectField(receiver, "user");
                                if (userId(user) != 0) {
                                    log("loader markDeleted skipped: user=" + userId(user));
                                    return;
                                }
                                String reason = (String) param.args[0];
                                if (!isPackageInvalidationReason(reason)) {
                                    log("loader markDeleted ignored reason=" + reason);
                                    return;
                                }
                                Cursor cursor = (Cursor) receiver;
                                String pkg = packageOfRow(cursor);
                                if (pkg == null || !isMainProfileRow(cursor) || !isFrozen(pkg)) {
                                    log("loader row not saved pkg=" + pkg + " frozen=" + (pkg != null && isFrozen(pkg)));
                                    return;
                                }
                                saveRow(pkg, rowToJson(cursor));
                                log("saved from loader: " + pkg + " reason=" + reason);
                            } catch (Throwable t) {
                                log(t);
                            }
                        }
                    });
            log("hooked LoaderCursor.markDeleted: " + hooked.size());
        } catch (Throwable t) {
            log(t);
        }
    }

    private static void hookPackageTasks() {
        XC_MethodHook afterExecute = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object task = param.thisObject;
                    int op = operation(task);
                    log("task complete op=" + op + " user=" + taskUserId(task)
                            + " packages=" + packagesOf(task).length);
                    if (!isRestoreEvent(task) || taskUserId(task) != 0) return;
                    boolean restored = false;
                    for (String pkg : packagesOf(task)) {
                        boolean saved = hasSaved(pkg);
                        boolean frozen = isFrozen(pkg);
                        log("restore candidate pkg=" + pkg + " frozen=" + frozen + " saved=" + saved);
                        if (!frozen && saved) restored |= restore(pkg);
                    }
                    if (restored) forceReload(task);
                } catch (Throwable t) {
                    log(t);
                }
            }
        };

        boolean hookedOplus = false;
        try {
            Class<?> taskClass = XposedHelpers.findClass(
                    MODEL_PACKAGE + "OplusPackageUpdatedTask", classLoader);
            Set<?> hooked = XposedBridge.hookAllMethods(taskClass, "execute", afterExecute);
            hookedOplus = !hooked.isEmpty();
            log("hooked OplusPackageUpdatedTask.execute: " + hooked.size());
        } catch (Throwable t) {
            log(t);
        }
        if (!hookedOplus) {
            try {
                Class<?> taskClass = XposedHelpers.findClass(
                        MODEL_PACKAGE + "PackageUpdatedTask", classLoader);
                Set<?> hooked = XposedBridge.hookAllMethods(taskClass, "execute", afterExecute);
                log("hooked PackageUpdatedTask.execute: " + hooked.size());
            } catch (Throwable t) {
                log(t);
            }
        }
    }

    private static boolean isPackageTask(Object task) {
        try {
            Class<?> type = XposedHelpers.findClass(MODEL_PACKAGE + "PackageUpdatedTask", classLoader);
            return type.isInstance(task);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isRestoreEvent(Object task) {
        if (!isPackageTask(task)) return false;
        int op = operation(task);
        return op == OP_ADD || op == OP_UPDATE || op == OP_UNSUSPEND;
    }

    private static int operation(Object task) {
        try {
            return XposedHelpers.getIntField(task, "mOp");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int taskUserId(Object task) {
        try {
            return userId(XposedHelpers.getObjectField(task, "mUser"));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String[] packagesOf(Object task) {
        try {
            String[] packages = (String[]) XposedHelpers.getObjectField(task, "mPackages");
            return packages == null ? new String[0] : packages;
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static int userId(Object user) {
        try {
            return (int) XposedHelpers.callMethod(user, "getIdentifier");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Context context() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) { }
        try {
            Object app = Class.forName("android.app.AndroidAppHelper")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) { }
        return null;
    }

    /** disabled by a freezer (Ice Box etc.) */
    private static boolean isDisabled(String pkg) {
        Context context = context();
        if (context == null || pkg == null || pkg.isEmpty()) return false;
        try {
            int state = context.getPackageManager().getApplicationEnabledSetting(pkg);
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Hidden for user 0 via setApplicationHiddenSettingAsUser (Zorken etc.): the package
     * still exists when MATCH_UNINSTALLED_PACKAGES is used, but not for normal queries.
     * A real uninstall removes the package completely, so both lookups fail -> false.
     */
    private static boolean isHidden(String pkg) {
        Context context = context();
        if (context == null || pkg == null || pkg.isEmpty()) return false;
        PackageManager pm = context.getPackageManager();
        try {
            pm.getApplicationInfo(pkg, 0x00002000); // MATCH_UNINSTALLED_PACKAGES
        } catch (Throwable t) {
            return false;
        }
        try {
            pm.getApplicationInfo(pkg, 0);
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** package is currently kept away from the launcher by a freezer or a hider */
    private static boolean isFrozen(String pkg) {
        return isDisabled(pkg) || isHidden(pkg);
    }

    private static Uri favoritesUri() {
        try {
            Class<?> favorites = XposedHelpers.findClass(
                    "com.android.launcher3.LauncherSettings$Favorites", classLoader);
            return (Uri) XposedHelpers.getStaticObjectField(favorites, "CONTENT_URI");
        } catch (Throwable t) {
            log(t);
            return null;
        }
    }

    private static long mainProfileSerial() {
        // Android assigns the owner/main profile serial 0. The task-level user check
        // above prevents this module from handling secondary users or work profiles.
        return 0L;
    }

    private static boolean isMainProfileRow(Cursor cursor) {
        try {
            int index = cursor.getColumnIndex("profileId");
            return index < 0 || cursor.isNull(index) || cursor.getLong(index) == mainProfileSerial();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String packageOfRow(Cursor cursor) {
        try {
            int itemType = cursor.getColumnIndex("itemType");
            if (itemType >= 0 && !cursor.isNull(itemType) && cursor.getInt(itemType) != 0) return null;
            String encoded = cursor.getString(cursor.getColumnIndexOrThrow("intent"));
            Intent intent = Intent.parseUri(encoded, 0);
            if (intent.getComponent() != null) return intent.getComponent().getPackageName();
            return intent.getPackage();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isPackageInvalidationReason(String reason) {
        return reason.startsWith("Invalid package removed")
                || reason.startsWith("Invalid component")
                || reason.startsWith("Unrestored app removed")
                || reason.startsWith("Unable to find a launch target")
                || reason.startsWith("Missing app");
    }

    private static JSONObject rowToJson(Cursor cursor) throws Exception {
        JSONObject row = new JSONObject();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            String name = cursor.getColumnName(i);
            switch (cursor.getType(i)) {
                case Cursor.FIELD_TYPE_NULL: break;
                case Cursor.FIELD_TYPE_INTEGER: row.put(name, cursor.getLong(i)); break;
                case Cursor.FIELD_TYPE_FLOAT: row.put(name, cursor.getDouble(i)); break;
                case Cursor.FIELD_TYPE_BLOB:
                    row.put(name, "b64:" + Base64.encodeToString(cursor.getBlob(i), Base64.NO_WRAP));
                    break;
                default: row.put(name, cursor.getString(i)); break;
            }
        }
        return row;
    }

    private static ContentValues jsonToValues(JSONObject row) throws Exception {
        ContentValues values = new ContentValues();
        for (Iterator<String> it = row.keys(); it.hasNext();) {
            String key = it.next();
            Object value = row.get(key);
            if (value instanceof Integer || value instanceof Long) values.put(key, ((Number) value).longValue());
            else if (value instanceof Double) values.put(key, (Double) value);
            else if (value instanceof String && ((String) value).startsWith("b64:"))
                values.put(key, Base64.decode(((String) value).substring(4), Base64.NO_WRAP));
            else if (value instanceof String) values.put(key, (String) value);
        }
        return values;
    }

    private static SharedPreferences preferences() {
        Context context = context();
        if (context == null) throw new IllegalStateException("launcher context unavailable");
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean hasSaved(String pkg) {
        try { return preferences().contains(pkg); }
        catch (Throwable t) { return false; }
    }

    private static void saveRow(String pkg, JSONObject row) throws Exception {
        SharedPreferences prefs = preferences();
        JSONArray old = new JSONArray(prefs.getString(pkg, "[]"));
        JSONArray updated = new JSONArray();
        long id = row.optLong("_id", -1L);
        for (int i = 0; i < old.length(); i++) {
            JSONObject previous = old.optJSONObject(i);
            if (previous == null || previous.optLong("_id", -2L) != id) updated.put(previous);
        }
        updated.put(row);
        prefs.edit().putString(pkg, updated.toString()).commit();
    }

    private static String where(JSONObject r) {
        return "id=" + r.optLong("_id", -1) + " c=" + r.optLong("container", 0)
                + " s=" + r.optLong("screen", 0) + " x=" + r.optLong("cellX", 0)
                + " y=" + r.optLong("cellY", 0) + " rank=" + r.optLong("rank", -1);
    }

    /** A fresh snapshot of the live DB rows REPLACES any older snapshot of this package. */
    private static void snapshotFromProvider(String pkg) throws Exception {
        Uri uri = favoritesUri();
        Context context = context();
        if (uri == null || context == null) return;
        ContentResolver resolver = context.getContentResolver();
        JSONArray fresh = new JSONArray();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                if (!isMainProfileRow(cursor)) continue;
                if (pkg.equals(packageOfRow(cursor))) {
                    JSONObject r = rowToJson(cursor);
                    fresh.put(r);
                    log("snapshot " + pkg + " " + where(r));
                }
            }
        }
        if (fresh.length() > 0) {
            preferences().edit().putString(pkg, fresh.toString()).commit();
        } else {
            log("snapshot " + pkg + ": no live row (keeping older snapshot if any)");
        }
    }

    private static boolean exists(ContentResolver resolver, Uri uri, String selection, String... args) {
        try (Cursor cursor = resolver.query(uri, new String[]{"_id"}, selection, args, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean restore(String pkg) {
        boolean inserted = false;
        boolean transientFailure = false;
        JSONArray remaining = new JSONArray();
        try {
            Uri uri = favoritesUri();
            Context context = context();
            if (uri == null || context == null) return false;
            ContentResolver resolver = context.getContentResolver();
            JSONArray saved = new JSONArray(preferences().getString(pkg, "[]"));
            long serial = mainProfileSerial();

            for (int i = 0; i < saved.length(); i++) {
                JSONObject row = saved.optJSONObject(i);
                if (row == null || row.optLong("profileId", serial) != serial) continue;
                ContentValues values = jsonToValues(row);
                long id = row.optLong("_id", -1L);
                String intent = row.optString("intent", "");
                String profile = String.valueOf(serial);

                log("restore try " + pkg + " " + where(row));
                if (exists(resolver, uri, "intent=? AND profileId=?", intent, profile)) {
                    log("restore skip: row for this intent already exists (launcher re-added it)");
                    continue;
                }
                if (id >= 0 && exists(resolver, uri, "_id=?", String.valueOf(id))) {
                    remaining.put(row);
                    transientFailure = true;
                    continue;
                }

                long container = row.optLong("container", -1L);
                String cellX = String.valueOf(row.optLong("cellX", 0L));
                String cellY = String.valueOf(row.optLong("cellY", 0L));
                String screen = String.valueOf(row.optLong("screen", 0L));
                if (container > 0 && !exists(resolver, uri,
                        "_id=? AND itemType=2", String.valueOf(container))) continue;

                boolean occupied = container > 0
                        ? exists(resolver, uri, "container=? AND cellX=? AND cellY=?",
                        String.valueOf(container), cellX, cellY)
                        : exists(resolver, uri, "container=? AND screen=? AND cellX=? AND cellY=?",
                        String.valueOf(container), screen, cellX, cellY);
                if (occupied) { log("restore skip: target cell occupied"); continue; }

                try {
                    if (resolver.insert(uri, values) != null) { inserted = true; log("restore inserted " + pkg + " " + where(row)); }
                    else { remaining.put(row); transientFailure = true; }
                } catch (Throwable insertFailure) {
                    remaining.put(row);
                    transientFailure = true;
                    log(insertFailure);
                }
            }

            if (transientFailure && remaining.length() > 0) {
                preferences().edit().putString(pkg, remaining.toString()).commit();
            } else {
                preferences().edit().remove(pkg).commit();
            }
        } catch (Throwable t) {
            log(t);
        }
        log("restore result pkg=" + pkg + " inserted=" + inserted
                + " retryRows=" + remaining.length() + " transientFailure=" + transientFailure);
        return inserted;
    }

    private static void forceReload(Object task) {
        try {
            Object model;
            try { model = XposedHelpers.getObjectField(task, "mModel"); }
            catch (Throwable ignored) {
                Object app = XposedHelpers.getObjectField(task, "mApp");
                model = XposedHelpers.callMethod(app, "getModel");
            }
            XposedHelpers.callMethod(model, "forceReload");
            log("forceReload called");
        } catch (Throwable t) { log(t); }
    }

    private static final String LOG_FILE = "/data/data/com.android.launcher/files/fik.log";

    private static synchronized void toFile(String line, boolean truncate) {
        try {
            Context cx = context();
            java.io.File f = cx != null ? new java.io.File(cx.getFilesDir(), "fik.log") : new java.io.File(LOG_FILE);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            if (f.length() > 200 * 1024) truncate = true;
            try (java.io.FileWriter w = new java.io.FileWriter(f, !truncate)) {
                w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " " + line + "\n");
            }
            f.setReadable(true, false);
        } catch (Throwable ignored) { }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
        Log.i(LOG_TAG, message);
        toFile(message, message.startsWith("attached"));
    }

    private static void log(Throwable throwable) {
        XposedBridge.log(TAG + throwable);
        Log.e(LOG_TAG, throwable.toString(), throwable);
        toFile("ERROR " + throwable, false);
    }
}
