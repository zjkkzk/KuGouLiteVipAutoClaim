package com.moekoe.xposed.kugoulite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import de.robv.android.xposed.XposedBridge;

/**
 * 领取编排器
 *
 * 工作流程：
 * 1. 酷狗概念版 Application.onCreate 时启动
 * 2. 安装 OkHttp Hook 等待捕获凭证
 * 3. 同时尝试从缓存 / 酷狗 SP 读取已有凭证
 * 4. 凭证就绪后检查配置与今日状态，执行领取
 * 5. 领取成功后可选升级概念版VIP
 * 6. 通过通知和 ContentProvider 回写结果
 */
public class ClaimWorker {

    public static final String ACTION_TRIGGER = "com.moekoe.xposed.kugoulite.ACTION_TRIGGER";

    private static volatile boolean started = false;
    private static volatile boolean attempting = false;
    private static Context appContext;
    private static KugouApi.Credentials cachedCreds;

    /**
     * 启动领取工作器（由 MainHook 在 Application.onCreate 后调用）
     */
    public static void start(Context context, ClassLoader classLoader) {
        if (started) return;
        started = true;
        appContext = context.getApplicationContext();

        ModuleLog.log("ClaimWorker 启动");

        // 检查是否需要重置领取状态（模块APP手动触发时设置）
        try {
            android.net.Uri uri = android.net.Uri.parse("content://com.moekoe.xposed.kugoulite.state");
            android.os.Bundle result = appContext.getContentResolver().call(uri, "check_reset_claim", null, null);
            if (result != null && result.getBoolean("value", false)) {
                ModuleLog.log("检测到重置标记，清除领取状态");
                PrefStore.setClaimedToday(appContext, false);
                PrefStore.setClaimedSuccessToday(appContext, false);
            }
        } catch (Exception e) {
            // ContentProvider 调用失败，尝试直接读取文件
            try {
                Context moduleCtx = appContext.createPackageContext(
                        "com.moekoe.xposed.kugoulite", Context.CONTEXT_IGNORE_SECURITY);
                SharedPreferences sp = moduleCtx.getSharedPreferences("module_state", Context.MODE_PRIVATE);
                if (sp.getBoolean("reset_claim_pending", false)) {
                    sp.edit().remove("reset_claim_pending").apply();
                    ModuleLog.log("检测到重置标记(文件)，清除领取状态");
                    PrefStore.setClaimedToday(appContext, false);
                    PrefStore.setClaimedSuccessToday(appContext, false);
                }
            } catch (Exception e2) {
                // ignore
            }
        }

        // 设置 NetCapture 回调
        NetCapture.setCallback(creds -> onCredsCaptured(creds));

        // 注册手动触发广播接收器
        registerTriggerReceiver();

        // 尝试从缓存读取凭证
        cachedCreds = PrefStore.loadCreds(appContext);
        if (cachedCreds != null && cachedCreds.isValid()) {
            ModuleLog.log("使用缓存凭证: userid=" + cachedCreds.userid);
            attemptClaim();
            return;
        }

        // 缓存没有，尝试从酷狗 SP 读取
        cachedCreds = PrefStore.tryReadFromAppPrefs(appContext);
        if (cachedCreds != null && cachedCreds.isValid()) {
            ModuleLog.log("使用酷狗SP凭证: userid=" + cachedCreds.userid);
            PrefStore.saveCreds(appContext, cachedCreds);
            attemptClaim();
            return;
        }

        ModuleLog.log("暂无凭证，等待 OkHttp 捕获...");
    }

    /**
     * NetCapture 捕获到凭证时回调
     */
    private static void onCredsCaptured(KugouApi.Credentials creds) {
        ModuleLog.log("NetCapture 捕获到凭证: userid=" + creds.userid);
        PrefStore.saveCreds(appContext, creds);
        cachedCreds = creds;
        attemptClaim();
    }

    /**
     * 执行领取（如果条件满足）
     */
    private static void attemptClaim() {
        if (attempting) {
            ModuleLog.log("已有领取任务进行中，跳过");
            return;
        }
        if (cachedCreds == null || !cachedCreds.isValid()) {
            ModuleLog.log("凭证无效，跳过领取");
            return;
        }

        // 检查是否启用
        boolean enabled = Settings.isEnabled(appContext);
        ModuleLog.log("模块启用状态: " + enabled);
        if (!enabled) {
            ModuleLog.log("模块已禁用");
            Settings.putStatus(appContext, "模块已禁用", KugouApi.CODE_ERROR);
            return;
        }

        // 检查今天是否已成功领取
        String localDay = KugouApi.getLocalDayKey();
        String utcDay = KugouApi.getTodayKey();
        boolean claimedSuccess = PrefStore.isClaimedSuccessToday(appContext);
        ModuleLog.log("日期检查: 本地=" + localDay + " UTC=" + utcDay + " 今日已成功领取=" + claimedSuccess);
        if (claimedSuccess) {
            ModuleLog.log("今日已成功领取，跳过");
            Settings.putStatus(appContext, "今日已领取", KugouApi.CODE_ALREADY);
            return;
        }

        ModuleLog.log("凭证有效，开始领取: userid=" + cachedCreds.userid);
        attempting = true;

        // 在子线程执行网络请求
        new Thread(() -> {
            try {
                ModuleLog.log("开始领取每日VIP...");

                // 第一步：领取听歌VIP
                KugouApi.ClaimResult result = KugouApi.claimVip(cachedCreds);
                ModuleLog.log("领取结果: code=" + result.code + " msg=" + result.message);

                // 只有成功或今日已领取才标记为今日已成功领取
                if (result.code == KugouApi.CODE_SUCCESS
                        || result.code == KugouApi.CODE_ALREADY) {
                    PrefStore.setClaimedSuccessToday(appContext, true);
                    PrefStore.setClaimedToday(appContext, true);
                }

                // 第二步：如果领取成功或今日已领取，且开启自动升级，则升级概念版VIP
                // code=0(成功) 或 code=1(今日已领取) 都尝试升级
                if (result.code == KugouApi.CODE_SUCCESS
                        || result.code == KugouApi.CODE_ALREADY) {
                    boolean autoUpgrade = Settings.isAutoUpgrade(appContext);
                    ModuleLog.log("自动升级开关: " + autoUpgrade);
                    if (autoUpgrade) {
                        ModuleLog.log("开始升级概念版VIP...");
                        Thread.sleep(500);
                        KugouApi.ClaimResult upgradeResult = KugouApi.upgradeVip(cachedCreds);
                        ModuleLog.log("升级结果: code=" + upgradeResult.code + " msg=" + upgradeResult.message);
                        if (upgradeResult.code == KugouApi.CODE_SUCCESS) {
                            result.message = result.message + "，已升级概念版VIP";
                        } else if (upgradeResult.code == KugouApi.CODE_ALREADY) {
                            result.message = result.message + "，概念版VIP今日已升级";
                        }
                    }
                }

                // 回写状态
                Settings.putStatus(appContext, result.message, result.code);

                // 发送通知
                NotifyUtil.notifyResult(appContext, result);

            } catch (Exception e) {
                ModuleLog.log("领取过程异常: " + e);
                Settings.putStatus(appContext, "领取异常: " + e.getMessage(), KugouApi.CODE_ERROR);
            } finally {
                attempting = false;
            }
        }, "KuGouLiteVip-Claim").start();
    }

    /**
     * 注册手动触发广播接收器
     */
    private static void registerTriggerReceiver() {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_TRIGGER.equals(intent.getAction())) {
                        ModuleLog.log("收到手动触发广播");
                        PrefStore.setClaimedToday(appContext, false);
                        PrefStore.setClaimedSuccessToday(appContext, false);
                        if (cachedCreds == null || !cachedCreds.isValid()) {
                            cachedCreds = PrefStore.loadCreds(appContext);
                            if (cachedCreds == null || !cachedCreds.isValid()) {
                                cachedCreds = PrefStore.tryReadFromAppPrefs(appContext);
                            }
                        }
                        if (cachedCreds != null && cachedCreds.isValid()) {
                            attemptClaim();
                        } else {
                            Settings.putStatus(appContext, "无可用凭证，请先登录酷狗", KugouApi.CODE_ERROR);
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter(ACTION_TRIGGER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, filter);
            }
            ModuleLog.log("广播接收器注册成功");
        } catch (Exception e) {
            ModuleLog.log("注册广播接收器失败: " + e);
        }
    }
}
