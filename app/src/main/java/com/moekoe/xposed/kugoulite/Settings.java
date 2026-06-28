package com.moekoe.xposed.kugoulite;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块配置读写
 *
 * 双通道读取策略：
 * 1. 优先：直接读取模块APP的 SharedPreferences 文件（不依赖 ContentProvider）
 * 2. 备用：通过 ContentProvider 跨进程调用
 *
 * 回写状态仍通过 ContentProvider（模块APP主动读取）
 */
public class Settings {

    private static final String TAG = "[KuGouLiteVip]";
    private static final String AUTHORITY = "com.moekoe.xposed.kugoulite.state";
    private static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    // 模块APP包名
    private static final String MODULE_PACKAGE = "com.moekoe.xposed.kugoulite";
    private static final String PREFS_NAME = "module_state";

    // 配置键
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_AUTO_UPGRADE = "auto_upgrade";

    // 状态键
    public static final String KEY_STATUS_MSG = "status_msg";
    public static final String KEY_STATUS_CODE = "status_code";
    public static final String KEY_STATUS_TIME = "status_time";

    /**
     * 直接读取模块APP的 SharedPreferences 文件
     * Hook 运行在酷狗进程中，但可以读取其他APP的 SharedPreferences 文件
     * （在同一用户空间下，/data/data/包名/shared_prefs/ 可读）
     */
    private static SharedPreferences getModulePrefs(Context ctx) {
        try {
            // 构建模块APP的 SharedPreferences 文件路径
            File prefsFile = new File(
                    "/data/data/" + MODULE_PACKAGE + "/shared_prefs/" + PREFS_NAME + ".xml");
            if (!prefsFile.exists()) {
                // 尝试通过 createPackageContext 获取
                try {
                    Context moduleCtx = ctx.createPackageContext(MODULE_PACKAGE,
                            Context.CONTEXT_IGNORE_SECURITY);
                    return moduleCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                } catch (Exception e) {
                    XposedBridge.log(TAG + " createPackageContext失败: " + e);
                    return null;
                }
            }
            // 直接用文件路径读取
            // SharedPreferences 只能通过 Context 获取，这里用反射
            // 更简单的方式：用 XML 解析
            return readPrefsFromFile(prefsFile);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 读取模块配置文件失败: " + e);
            return null;
        }
    }

    /**
     * 从 XML 文件直接读取 SharedPreferences 值
     */
    private static SharedPreferences readPrefsFromFile(File file) {
        try {
            // 优先尝试 createPackageContext
            return null; // 占位，实际用 fallback 逻辑
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取布尔配置（双通道）
     */
    private static boolean getBooleanConfig(Context ctx, String key, boolean defaultValue) {
        // 通道1：ContentProvider
        try {
            Bundle result = ctx.getContentResolver().call(CONTENT_URI, "get_config", key, null);
            if (result != null) {
                return result.getBoolean("value", defaultValue);
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " ContentProvider读取失败(" + key + "): " + e);
        }

        // 通道2：直接读取文件
        try {
            Context moduleCtx = ctx.createPackageContext(MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences sp = moduleCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean val = sp.getBoolean(key, defaultValue);
            XposedBridge.log(TAG + " 直接读取配置(" + key + ")=" + val);
            return val;
        } catch (Exception e) {
            XposedBridge.log(TAG + " 直接读取失败(" + key + "): " + e);
        }

        return defaultValue;
    }

    /**
     * 是否启用自动领取（默认启用）
     */
    public static boolean isEnabled(Context ctx) {
        return getBooleanConfig(ctx, KEY_ENABLED, true);
    }

    /**
     * 是否自动升级概念版VIP（默认不启用）
     */
    public static boolean isAutoUpgrade(Context ctx) {
        return getBooleanConfig(ctx, KEY_AUTO_UPGRADE, false);
    }

    /**
     * 回写运行状态到模块 APP（供 MainActivity 显示）
     */
    public static void putStatus(Context ctx, String message, int code) {
        try {
            Bundle extras = new Bundle();
            extras.putString("message", message);
            extras.putInt("code", code);
            extras.putLong("time", System.currentTimeMillis());
            ctx.getContentResolver().call(CONTENT_URI, "put_status", null, extras);
        } catch (Exception e) {
            XposedBridge.log(TAG + " 回写状态失败: " + e);
        }
    }
}
