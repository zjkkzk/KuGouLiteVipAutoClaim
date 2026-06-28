package com.moekoe.xposed.kugoulite;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import de.robv.android.xposed.XposedBridge;

/**
 * 凭证与每日状态存储
 *
 * 数据存储在酷狗概念版的 SharedPreferences 中（Hook 运行在酷狗进程内，可直接访问）。
 * - 凭证缓存：避免每次启动都依赖 OkHttp 捕获
 * - 每日领取状态：防止一天内重复领取
 */
public class PrefStore {

    private static final String TAG = "[KuGouLiteVip]";
    private static final String PREFS_NAME = "kugoulite_vip_module";

    private static final String KEY_TOKEN = "token";
    private static final String KEY_USERID = "userid";
    private static final String KEY_DFID = "dfid";
    private static final String KEY_MID = "mid";
    private static final String KEY_APPID = "appid";
    private static final String KEY_CLIENTVER = "clientver";
    private static final String KEY_LAST_CLAIM_DATE = "last_claim_date";
    private static final String KEY_LAST_CLAIM_SUCCESS = "last_claim_success_date";

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 保存凭证到缓存
     */
    public static void saveCreds(Context ctx, KugouApi.Credentials creds) {
        SharedPreferences.Editor editor = getPrefs(ctx).edit();
        editor.putString(KEY_TOKEN, creds.token);
        editor.putString(KEY_USERID, creds.userid);
        editor.putString(KEY_DFID, creds.dfid);
        editor.putString(KEY_MID, creds.mid);
        editor.putString(KEY_APPID, creds.appid);
        editor.putString(KEY_CLIENTVER, creds.clientver);
        editor.apply();
    }

    /**
     * 从缓存加载凭证
     */
    public static KugouApi.Credentials loadCreds(Context ctx) {
        SharedPreferences sp = getPrefs(ctx);
        String token = sp.getString(KEY_TOKEN, null);
        if (token == null || token.isEmpty()) return null;

        KugouApi.Credentials creds = new KugouApi.Credentials();
        creds.token = token;
        creds.userid = sp.getString(KEY_USERID, null);
        creds.dfid = sp.getString(KEY_DFID, null);
        creds.mid = sp.getString(KEY_MID, null);
        creds.appid = sp.getString(KEY_APPID, null);
        creds.clientver = sp.getString(KEY_CLIENTVER, null);
        return creds;
    }

    /**
     * 检查今天是否已领取（按北京时间自然日）
     */
    public static boolean isClaimedToday(Context ctx) {
        String today = KugouApi.getLocalDayKey();
        String lastClaim = getPrefs(ctx).getString(KEY_LAST_CLAIM_DATE, "");
        return today.equals(lastClaim);
    }

    /**
     * 标记今天已尝试领取/未领取（按北京时间自然日）
     */
    public static void setClaimedToday(Context ctx, boolean claimed) {
        SharedPreferences.Editor editor = getPrefs(ctx).edit();
        if (claimed) {
            editor.putString(KEY_LAST_CLAIM_DATE, KugouApi.getLocalDayKey());
        } else {
            editor.remove(KEY_LAST_CLAIM_DATE);
        }
        editor.apply();
    }

    /**
     * 检查今天是否已成功领取（按北京时间自然日）
     */
    public static boolean isClaimedSuccessToday(Context ctx) {
        String today = KugouApi.getLocalDayKey();
        String lastSuccess = getPrefs(ctx).getString(KEY_LAST_CLAIM_SUCCESS, "");
        return today.equals(lastSuccess);
    }

    /**
     * 标记今天已成功领取（按北京时间自然日）
     */
    public static void setClaimedSuccessToday(Context ctx, boolean success) {
        SharedPreferences.Editor editor = getPrefs(ctx).edit();
        if (success) {
            editor.putString(KEY_LAST_CLAIM_SUCCESS, KugouApi.getLocalDayKey());
        } else {
            editor.remove(KEY_LAST_CLAIM_SUCCESS);
        }
        editor.apply();
    }

    /**
     * 从酷狗概念版自身的 SharedPreferences 中尝试读取凭证（备用方案）
     *
     * 当 OkHttp Hook 尚未捕获到凭证时，遍历酷狗的 SP 文件查找 token/userid。
     */
    public static KugouApi.Credentials tryReadFromAppPrefs(Context ctx) {
        try {
            String dataDir = ctx.getApplicationInfo().dataDir;
            File prefsDir = new File(dataDir, "shared_prefs");
            if (!prefsDir.exists()) return null;

            File[] files = prefsDir.listFiles();
            if (files == null) return null;

            String token = null, userid = null, dfid = null, mid = null;

            for (File file : files) {
                if (!file.getName().endsWith(".xml")) continue;
                // 跳过模块自身的 SP
                if (file.getName().startsWith(PREFS_NAME)) continue;

                String prefsName = file.getName().replace(".xml", "");
                SharedPreferences sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);

                if (token == null) {
                    token = sp.getString("token", null);
                }
                if (userid == null) {
                    userid = sp.getString("userid", null);
                    if (userid == null) userid = sp.getString("uid", null);
                    if (userid == null) userid = sp.getString("kugouid", null);
                }
                if (dfid == null) {
                    dfid = sp.getString("dfid", null);
                }
                if (mid == null) {
                    mid = sp.getString("mid", null);
                    if (mid == null) mid = sp.getString("KUGOU_API_MID", null);
                }

                if (token != null && userid != null) break;
            }

            if (token != null && !token.isEmpty() && userid != null && !userid.isEmpty()) {
                KugouApi.Credentials creds = new KugouApi.Credentials();
                creds.token = token;
                creds.userid = userid;
                creds.dfid = dfid;
                creds.mid = mid;
                XposedBridge.log(TAG + " 从酷狗SP读取到凭证: userid=" + userid);
                return creds;
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " 读取酷狗SP失败: " + e);
        }
        return null;
    }
}
