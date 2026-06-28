package com.moekoe.xposed.kugoulite;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 模块入口
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[KuGouLiteVip]";
    private static final String TARGET_PACKAGE = "com.kugou.android.lite";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        ModuleLog.log("========== 模块已加载 ==========");
        ModuleLog.log("目标包名: " + lpparam.packageName);

        // 安装 OkHttp 凭证捕获
        NetCapture.install(lpparam.classLoader);

        // 安装弹窗阻止器
        PopupBlocker.install(lpparam.classLoader);

        // 安装自动点击器
        AutoClicker.install(lpparam.classLoader);

        // Hook Application.onCreate
        hookApplicationOnCreate(lpparam);
    }

    private static void hookApplicationOnCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context context = (Context) param.thisObject;
                        ModuleLog.init(context);
                        ModuleLog.log("Application.onCreate 触发");
                        ModuleLog.log("Application类: " + context.getClass().getName());
                        ClaimWorker.start(context, lpparam.classLoader);
                    } catch (Exception e) {
                        ModuleLog.log("启动失败: " + e);
                    }
                }
            });
            XposedBridge.log(TAG + " Application.onCreate Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Application.onCreate Hook 安装失败: " + e);
        }
    }
}
