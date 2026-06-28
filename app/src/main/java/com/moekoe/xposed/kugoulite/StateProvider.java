package com.moekoe.xposed.kugoulite;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 跨进程状态共享 ContentProvider
 *
 * 运行在模块 APP 进程中，供 Hook（运行在酷狗概念版进程）通过
 * ContentResolver.call() 读写配置与状态。
 *
 * 调用方法：
 * - get_config:  arg=配置键, 返回 Bundle{value:boolean}
 * - put_config:  arg=配置键, extras={value:boolean}
 * - get_status:  返回 Bundle{message, code, time}
 * - put_status:  extras={message, code, time}
 */
public class StateProvider extends ContentProvider {

    private static final String PREFS_NAME = "module_state";

    private SharedPreferences getPrefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        SharedPreferences sp = getPrefs();
        Bundle result = new Bundle();

        switch (method) {
            case "get_config":
                if (arg == null) return null;
                boolean defaultValue = Settings.KEY_ENABLED.equals(arg); // enabled 默认 true
                result.putBoolean("value", sp.getBoolean(arg, defaultValue));
                return result;

            case "put_config":
                if (arg != null && extras != null) {
                    sp.edit().putBoolean(arg, extras.getBoolean("value", true)).apply();
                }
                return null;

            case "get_status":
                result.putString("message", sp.getString(Settings.KEY_STATUS_MSG, "未运行"));
                result.putInt("code", sp.getInt(Settings.KEY_STATUS_CODE, -1));
                result.putLong("time", sp.getLong(Settings.KEY_STATUS_TIME, 0));
                return result;

            case "put_status":
                if (extras != null) {
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putString(Settings.KEY_STATUS_MSG, extras.getString("message", ""));
                    editor.putInt(Settings.KEY_STATUS_CODE, extras.getInt("code", -1));
                    editor.putLong(Settings.KEY_STATUS_TIME, extras.getLong("time", 0));
                    editor.apply();
                }
                return null;

            case "reset_claim":
                // 设置重置标记，酷狗进程下次启动时读取并清除领取状态
                sp.edit().putBoolean("reset_claim_pending", true).apply();
                return null;

            case "check_reset_claim":
                // 酷狗进程检查是否需要重置
                boolean pending = sp.getBoolean("reset_claim_pending", false);
                if (pending) {
                    sp.edit().remove("reset_claim_pending").apply();
                }
                result.putBoolean("value", pending);
                return result;

            default:
                return null;
        }
    }

    // ===== 以下方法为 ContentProvider 必需实现，本 Provider 仅使用 call() =====

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
