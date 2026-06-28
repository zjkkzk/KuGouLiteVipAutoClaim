package com.moekoe.xposed.kugoulite;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 弹窗阻止器
 *
 * 核心思路：在弹窗显示前拦截，阻止其出现。
 * 同时 ClaimWorker 通过 API 独立完成领取，不依赖弹窗。
 *
 * 三层拦截防线：
 * 1. Dialog.setContentView — 检查内容，标记领取弹窗
 * 2. Dialog.show() — before 阻止已标记弹窗；after 强制关闭漏网弹窗
 * 3. WindowManagerImpl.addView — 底层拦截，阻止任何领取浮层添加到窗口
 * 4. Activity.startActivity — 阻止活动页 Activity 启动
 */
public class PopupBlocker {

    private static final String TAG = "[KuGouLiteVip]";

    // 阻止关键词：Dialog/View 内容含这些词则阻止
    private static final String[] BLOCK_KEYWORDS = {
            "领取", "签到", "立即领取", "去领取", "马上领取",
            "一键签到", "领取会员", "领取VIP", "领VIP",
            "听歌会员", "概念版VIP", "免费领取", "点击领取",
            "每日签到", "每日领取",
            "每日福利", "会员福利", "免费听歌", "立即开通",
            "去使用", "免费VIP", "赠送", "福利", "活动",
            "receive_vip", "listen_song", "充值", "续费"
    };

    // 活动 Activity 类名关键词
    private static final String[] ACTIVITY_CLASS_KEYWORDS = {
            "Sign", "Vip", "Welfare", "Receive", "Recharge",
            "WebActivity", "H5Activity", "BrowserActivity", "AdActivity"
    };

    // 活动 URL 关键词
    private static final String[] ACTIVITY_URL_KEYWORDS = {
            "getvips", "receive_vip", "signin", "sign_in",
            "welfare", "listen_song", "recharge"
    };

    private static volatile boolean installed = false;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 标记已识别为领取弹窗的 Dialog
    private static final WeakHashMap<Dialog, Boolean> flaggedDialogs = new WeakHashMap<>();

    public static void install(ClassLoader classLoader) {
        if (installed) return;
        installed = true;

        hookDialogSetContentView();
        hookDialogShow();
        hookWindowManagerAddView(classLoader);
        hookActivityStartActivity();
        hookPopupWindow(classLoader);

        XposedBridge.log(TAG + " PopupBlocker 安装完成");
    }

    // ===== 1. Hook Dialog.setContentView — 标记领取弹窗 =====

    private static void hookDialogSetContentView() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Dialog dialog = (Dialog) param.thisObject;
                    if (containsClaimKeyword(dialog)) {
                        flaggedDialogs.put(dialog, true);
                        XposedBridge.log(TAG + " 标记领取弹窗(setContentView)");
                    }
                } catch (Exception e) {
                    // 静默
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "setContentView", int.class, hook);
            XposedHelpers.findAndHookMethod(Dialog.class, "setContentView", View.class, hook);
            XposedHelpers.findAndHookMethod(Dialog.class, "setContentView",
                    View.class, ViewGroup.LayoutParams.class, hook);
            XposedBridge.log(TAG + " Dialog.setContentView Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Dialog.setContentView Hook 失败: " + e);
        }
    }

    // ===== 2. Hook Dialog.show() — 阻止领取弹窗 =====

    private static void hookDialogShow() {
        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Dialog dialog = (Dialog) param.thisObject;

                        // 检查1：是否已标记
                        if (Boolean.TRUE.equals(flaggedDialogs.get(dialog))) {
                            XposedBridge.log(TAG + " 阻止领取弹窗显示(标记)");
                            param.setResult(null);
                            return;
                        }

                        // 检查2：DecorView 是否含领取关键词
                        if (containsClaimKeyword(dialog)) {
                            XposedBridge.log(TAG + " 阻止领取弹窗显示(内容)");
                            flaggedDialogs.put(dialog, true);
                            param.setResult(null);
                            return;
                        }
                    } catch (Exception e) {
                        // 静默
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 防线2：如果 show() 已执行（未被 before 阻止），强制关闭
                    try {
                        Dialog dialog = (Dialog) param.thisObject;
                        if (containsClaimKeyword(dialog) && dialog.isShowing()) {
                            XposedBridge.log(TAG + " 强制关闭漏网领取弹窗");
                            forceCloseDialog(dialog);
                        }
                    } catch (Exception e) {
                        // 静默
                    }
                }
            });
            XposedBridge.log(TAG + " Dialog.show Hook(PopupBlocker) 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Dialog.show Hook(PopupBlocker) 失败: " + e);
        }
    }

    /**
     * 强制关闭 Dialog（多种方式）
     */
    private static void forceCloseDialog(Dialog dialog) {
        mainHandler.post(() -> {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Exception e) {
                // 静默
            }
            try {
                if (dialog.isShowing()) dialog.cancel();
            } catch (Exception e) {
                // 静默
            }
            try {
                Window window = dialog.getWindow();
                if (window != null) {
                    View decor = window.getDecorView();
                    if (decor != null) {
                        decor.setVisibility(View.GONE);
                    }
                }
            } catch (Exception e) {
                // 静默
            }
        });
    }

    // ===== 3. Hook WindowManagerImpl.addView — 底层拦截 =====

    private static void hookWindowManagerAddView(ClassLoader classLoader) {
        try {
            Class<?> wmClass = XposedHelpers.findClass("android.view.WindowManagerImpl", classLoader);
            XposedHelpers.findAndHookMethod(wmClass, "addView",
                    View.class, ViewGroup.LayoutParams.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View view = (View) param.args[0];
                        if (view == null) return;

                        // 检查 View 是否含领取关键词
                        if (containsClaimKeyword(view)) {
                            XposedBridge.log(TAG + " 底层拦截领取浮层(WindowManager)");
                            param.setResult(null);
                        }
                    } catch (Exception e) {
                        // 静默
                    }
                }
            });
            XposedBridge.log(TAG + " WindowManagerImpl.addView Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " WindowManagerImpl.addView Hook 失败: " + e);
        }
    }

    // ===== 4. Hook Activity.startActivity — 阻止活动页 =====

    private static void hookActivityStartActivity() {
        try {
            XposedHelpers.findAndHookMethod(android.app.Activity.class,
                    "startActivity", Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Intent intent = (Intent) param.args[0];
                        if (intent == null) return;

                        ComponentName cn = intent.getComponent();
                        if (cn == null) return;

                        String className = cn.getClassName();
                        for (String kw : ACTIVITY_CLASS_KEYWORDS) {
                            if (className.contains(kw)) {
                                XposedBridge.log(TAG + " 阻止活动Activity启动: " + className);
                                param.setResult(null);
                                return;
                            }
                        }

                        // 检查 Intent 的 data URL
                        if (intent.getData() != null) {
                            String url = intent.getData().toString().toLowerCase();
                            for (String kw : ACTIVITY_URL_KEYWORDS) {
                                if (url.contains(kw.toLowerCase())) {
                                    XposedBridge.log(TAG + " 阻止活动URL跳转: " + url);
                                    param.setResult(null);
                                    return;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // 静默
                    }
                }
            });
            XposedBridge.log(TAG + " Activity.startActivity Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Activity.startActivity Hook 失败: " + e);
        }
    }

    // ===== 5. Hook PopupWindow — 阻止 PopupWindow 弹窗 =====

    private static void hookPopupWindow(ClassLoader classLoader) {
        try {
            // Hook showAtLocation
            XposedHelpers.findAndHookMethod("android.widget.PopupWindow", classLoader,
                    "showAtLocation", View.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        android.widget.PopupWindow pw =
                                (android.widget.PopupWindow) param.thisObject;
                        View contentView = pw.getContentView();
                        if (contentView != null && containsClaimKeyword(contentView)) {
                            XposedBridge.log(TAG + " 阻止 PopupWindow 领取弹窗");
                            param.setResult(null);
                        }
                    } catch (Exception e) {
                        // 静默
                    }
                }
            });

            // Hook showAsDropDown
            XposedHelpers.findAndHookMethod("android.widget.PopupWindow", classLoader,
                    "showAsDropDown", View.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        android.widget.PopupWindow pw =
                                (android.widget.PopupWindow) param.thisObject;
                        View contentView = pw.getContentView();
                        if (contentView != null && containsClaimKeyword(contentView)) {
                            XposedBridge.log(TAG + " 阻止 PopupWindow(showAsDropDown) 领取弹窗");
                            param.setResult(null);
                        }
                    } catch (Exception e) {
                        // 静默
                    }
                }
            });

            XposedBridge.log(TAG + " PopupWindow Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " PopupWindow Hook 失败: " + e);
        }
    }

    // ===== 工具方法 =====

    /**
     * 检查 Dialog 是否含领取关键词
     */
    private static boolean containsClaimKeyword(Dialog dialog) {
        try {
            Window window = dialog.getWindow();
            if (window == null) return false;
            View decorView = window.getDecorView();
            if (decorView == null) return false;
            return containsClaimKeyword(decorView);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 递归遍历 View 树，检查是否含领取关键词
     */
    private static boolean containsClaimKeyword(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;

        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                String s = text.toString();
                for (String kw : BLOCK_KEYWORDS) {
                    if (s.contains(kw)) return true;
                }
            }
            // 检查 hint
            CharSequence hint = ((TextView) view).getHint();
            if (hint != null) {
                String s = hint.toString();
                for (String kw : BLOCK_KEYWORDS) {
                    if (s.contains(kw)) return true;
                }
            }
        }

        // 检查 contentDescription
        CharSequence desc = view.getContentDescription();
        if (desc != null) {
            String s = desc.toString();
            for (String kw : BLOCK_KEYWORDS) {
                if (s.contains(kw)) return true;
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsClaimKeyword(group.getChildAt(i))) return true;
            }
        }

        return false;
    }
}
