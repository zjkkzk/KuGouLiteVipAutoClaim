package com.moekoe.xposed.kugoulite;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自动点击器
 *
 * 酷狗概念版启动时会弹出活动领取弹窗，需要用户手动点击"领取"按钮。
 * 本模块自动识别并点击弹窗中的领取按钮，实现全自动领取。
 *
 * 覆盖三种弹窗类型：
 * 1. 原生 Dialog / AlertDialog（含 MaterialDialog 等第三方库）
 * 2. WebView 加载的 H5 活动页面
 * 3. Activity 形式的活动页面
 */
public class AutoClicker {

    private static final String TAG = "[KuGouLiteVip]";

    // 自动点击关键词（匹配到则 performClick）
    private static final String[] CLICK_KEYWORDS = {
            "领取", "签到", "立即领取", "去领取", "马上领取",
            "一键签到", "立即签到", "领取会员", "领取VIP", "领VIP",
            "立即领取", "点击领取", "免费领取"
    };

    // 活动 URL 关键词
    private static final String[] ACTIVITY_URL_KEYWORDS = {
            "getvips", "receive_vip", "vip", "activity", "signin", "sign_in",
            "welfare", "listen_song", "recharge", "concept"
    };

    // 活动 Activity 类名关键词
    private static final String[] ACTIVITY_CLASS_KEYWORDS = {
            "Sign", "Vip", "Welfare", "Receive", "Recharge", "WebActivity",
            "H5Activity", "BrowserActivity"
    };

    private static volatile boolean installed = false;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 防止重复点击：记录5秒内已点击的按钮文本
    private static final Set<String> clickedSet = new HashSet<>();
    private static long lastCleanTime = 0;

    public static void install(ClassLoader classLoader) {
        if (installed) return;
        installed = true;

        hookDialogShow();
        hookWebView(classLoader);
        hookActivityOnResume();
        hookWebViewClientOnPageFinished(classLoader);

        XposedBridge.log(TAG + " AutoClicker 安装完成");
    }

    // ===== 1. Hook Dialog.show() — 自动点击原生弹窗 =====

    private static void hookDialogShow() {
        try {
            XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Dialog dialog = (Dialog) param.thisObject;
                    // 延迟 500ms 等待布局加载完成
                    mainHandler.postDelayed(() -> tryAutoClickDialog(dialog), 500);
                    // 二次尝试，防止布局延迟加载
                    mainHandler.postDelayed(() -> tryAutoClickDialog(dialog), 1500);
                }
            });
            XposedBridge.log(TAG + " Dialog.show Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Dialog.show Hook 失败: " + e);
        }
    }

    /**
     * 自动点击 Dialog 中的领取按钮，点击后关闭弹窗
     */
    private static void tryAutoClickDialog(Dialog dialog) {
        try {
            if (dialog == null || !dialog.isShowing()) return;

            Window window = dialog.getWindow();
            if (window == null) return;
            View decorView = window.getDecorView();
            if (decorView == null) return;

            List<View> clickableViews = new ArrayList<>();
            findClickableViews(decorView, clickableViews);

            for (View view : clickableViews) {
                if (view instanceof TextView) {
                    CharSequence text = ((TextView) view).getText();
                    if (text == null) continue;
                    String buttonText = text.toString().trim();

                    for (String keyword : CLICK_KEYWORDS) {
                        if (buttonText.contains(keyword)) {
                            if (shouldClick(buttonText)) {
                                XposedBridge.log(TAG + " 自动点击弹窗按钮: " + buttonText);
                                view.performClick();
                                // 点击后延迟关闭弹窗
                                mainHandler.postDelayed(() -> {
                                    try {
                                        if (dialog.isShowing()) {
                                            dialog.dismiss();
                                            XposedBridge.log(TAG + " 弹窗已关闭(dismiss)");
                                        }
                                    } catch (Exception e) {
                                        try { dialog.cancel(); } catch (Exception ignored) {}
                                    }
                                }, 500);
                            }
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 静默处理
        }
    }

    // ===== 2. Hook WebView — 注入JS自动点击H5活动页面 =====

    private static void hookWebView(ClassLoader classLoader) {
        // Hook loadUrl(String)
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String url = (String) param.args[0];
                    if (url == null || url.startsWith("javascript:")) return;
                    if (isActivityUrl(url)) {
                        XposedBridge.log(TAG + " 检测到活动WebView: " + url);
                        final WebView webView = (WebView) param.thisObject;
                        // 多次延迟注入，确保页面已加载完成
                        webView.postDelayed(() -> injectClickJs(webView), 2000);
                        webView.postDelayed(() -> injectClickJs(webView), 4000);
                        webView.postDelayed(() -> injectClickJs(webView), 6000);
                    }
                }
            });
            XposedBridge.log(TAG + " WebView.loadUrl Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " WebView.loadUrl Hook 失败: " + e);
        }

        // Hook loadUrl(String, Map) 重载
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl",
                    String.class, java.util.Map.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String url = (String) param.args[0];
                    if (url == null || url.startsWith("javascript:")) return;
                    if (isActivityUrl(url)) {
                        final WebView webView = (WebView) param.thisObject;
                        webView.postDelayed(() -> injectClickJs(webView), 2000);
                        webView.postDelayed(() -> injectClickJs(webView), 4000);
                    }
                }
            });
        } catch (Exception e) {
            // 某些 Android 版本可能没有此重载
        }
    }

    /**
     * Hook WebViewClient.onPageFinished — 页面加载完成后注入JS
     */
    private static void hookWebViewClientOnPageFinished(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", classLoader,
                    "onPageFinished", WebView.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    WebView webView = (WebView) param.args[0];
                    String url = (String) param.args[1];
                    if (url != null && isActivityUrl(url)) {
                        XposedBridge.log(TAG + " 活动页面加载完成: " + url);
                        webView.postDelayed(() -> injectClickJs(webView), 1000);
                        webView.postDelayed(() -> injectClickJs(webView), 3000);
                    }
                }
            });
            XposedBridge.log(TAG + " WebViewClient.onPageFinished Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " WebViewClient.onPageFinished Hook 失败: " + e);
        }
    }

    /**
     * 判断 URL 是否为活动页面
     */
    private static boolean isActivityUrl(String url) {
        String lowerUrl = url.toLowerCase();
        for (String keyword : ACTIVITY_URL_KEYWORDS) {
            if (lowerUrl.contains(keyword.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * 注入 JS 自动点击页面上的领取按钮
     */
    private static void injectClickJs(WebView webView) {
        try {
            if (webView == null) return;
            String js = "javascript:(function(){"
                + "var kws=['领取','签到','立即领取','去领取','马上领取','一键签到','立即签到',"
                + "'领取会员','领取VIP','领VIP','点击领取','免费领取','确定','sign','receive','getVip'];"
                + "var sels='button,a,[class*=btn],[class*=submit],[class*=receive],[class*=sign],"
                + "[class*=get],[class*=vip],[role=button],input[type=button],input[type=submit],div[onclick]';"
                + "var els=document.querySelectorAll(sels);"
                + "for(var i=0;i<els.length;i++){"
                + "  var t=(els[i].innerText||els[i].textContent||els[i].value||'').trim();"
                + "  for(var j=0;j<kws.length;j++){"
                + "    if(t.indexOf(kws[j])>=0){"
                + "      els[i].click();"
                + "      return true;"
                + "    }"
                + "  }"
                + "}"
                + "})();";
            webView.loadUrl(js);
            XposedBridge.log(TAG + " 已注入自动点击JS");
        } catch (Exception e) {
            XposedBridge.log(TAG + " 注入JS失败: " + e);
        }
    }

    // ===== 3. Hook Activity.onResume() — 自动点击Activity中的领取按钮 =====

    private static void hookActivityOnResume() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    String className = activity.getClass().getName();

                    boolean isActivityPage = false;
                    for (String keyword : ACTIVITY_CLASS_KEYWORDS) {
                        if (className.contains(keyword)) {
                            isActivityPage = true;
                            break;
                        }
                    }

                    if (isActivityPage) {
                        XposedBridge.log(TAG + " 检测到活动Activity: " + className);
                        View decorView = activity.getWindow().getDecorView();
                        mainHandler.postDelayed(() -> {
                            boolean clicked = tryAutoClickView(decorView);
                            if (clicked) {
                                // 点击成功后延迟关闭 Activity
                                mainHandler.postDelayed(() -> {
                                    try {
                                        activity.finish();
                                        XposedBridge.log(TAG + " 活动Activity已关闭");
                                    } catch (Exception e) {
                                        // 静默
                                    }
                                }, 800);
                            }
                        }, 1000);
                        mainHandler.postDelayed(() -> tryAutoClickView(decorView), 2500);
                    }
                }
            });
            XposedBridge.log(TAG + " Activity.onResume Hook 安装成功");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Activity.onResume Hook 失败: " + e);
        }
    }

    /**
     * 自动点击 View 树中的领取按钮
     * @return true 表示点击成功
     */
    private static boolean tryAutoClickView(View rootView) {
        try {
            if (rootView == null) return false;
            List<View> clickableViews = new ArrayList<>();
            findClickableViews(rootView, clickableViews);

            for (View view : clickableViews) {
                if (view instanceof TextView) {
                    CharSequence text = ((TextView) view).getText();
                    if (text == null) continue;
                    String buttonText = text.toString().trim();

                    for (String keyword : CLICK_KEYWORDS) {
                        if (buttonText.contains(keyword)) {
                            if (shouldClick(buttonText)) {
                                XposedBridge.log(TAG + " 自动点击按钮: " + buttonText);
                                view.performClick();
                            }
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 静默处理
        }
        return false;
    }

    // ===== 工具方法 =====

    /**
     * 递归查找所有可点击的 View（Button 或可点击的 TextView）
     */
    private static void findClickableViews(View view, List<View> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;

        if (view instanceof Button || (view instanceof TextView && view.isClickable())) {
            out.add(view);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findClickableViews(group.getChildAt(i), out);
            }
        }
    }

    /**
     * 检查是否应该点击（5秒内去重，防止重复点击）
     */
    private static boolean shouldClick(String buttonText) {
        long now = System.currentTimeMillis();
        // 每30秒清理一次
        if (now - lastCleanTime > 30000) {
            clickedSet.clear();
            lastCleanTime = now;
        }

        String key = buttonText + "_" + (now / 5000); // 5秒窗口
        if (clickedSet.contains(key)) {
            return false;
        }
        clickedSet.add(key);
        return true;
    }
}
