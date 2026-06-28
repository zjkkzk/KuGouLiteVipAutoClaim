package com.moekoe.xposed.kugoulite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * OkHttp 网络请求捕获器
 *
 * 通过 Hook okhttp3.Request$Builder.build() 方法，
 * 从酷狗概念版的网络请求 URL 中提取登录凭证
 * （token, userid, dfid, mid, appid, clientver）
 *
 * 这些凭证用于后续调用酷狗官方接口领取每日VIP。
 */
public class NetCapture {

    private static final String TAG = "[KuGouLiteVip]";
    private static final String OKHTTP_BUILDER = "okhttp3.Request$Builder";

    private static volatile boolean installed = false;
    private static volatile String lastToken = null;
    private static CredsCallback callback;

    /**
     * 凭证捕获回调
     */
    public interface CredsCallback {
        void onCredsCaptured(KugouApi.Credentials creds);
    }

    /**
     * 设置凭证捕获回调
     */
    public static void setCallback(CredsCallback cb) {
        callback = cb;
    }

    /**
     * 安装 OkHttp Hook
     *
     * @param classLoader 酷狗概念版的 ClassLoader
     */
    public static void install(ClassLoader classLoader) {
        if (installed) return;

        try {
            Class<?> builderClass = XposedHelpers.findClass(OKHTTP_BUILDER, classLoader);
            XposedHelpers.findAndHookMethod(builderClass, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object request = param.getResult();
                        if (request == null) return;

                        // 获取 URL
                        Object urlObj = XposedHelpers.callMethod(request, "url");
                        if (urlObj == null) return;
                        String urlStr = String.valueOf(urlObj);

                        // 只处理酷狗域名请求
                        if (!urlStr.contains("kugou.com")) return;

                        // 从 URL query 提取凭证
                        String token = getQueryParam(urlObj, "token");
                        String userid = getQueryParam(urlObj, "userid");
                        String dfid = getQueryParam(urlObj, "dfid");
                        String mid = getQueryParam(urlObj, "mid");
                        String appid = getQueryParam(urlObj, "appid");
                        String clientver = getQueryParam(urlObj, "clientver");

                        // 如果 URL 中没有，尝试从请求头获取
                        if (token == null || userid == null) {
                            try {
                                Object headers = XposedHelpers.callMethod(request, "headers");
                                if (headers != null) {
                                    if (token == null)
                                        token = getHeader(headers, "token");
                                    if (userid == null)
                                        userid = getHeader(headers, "userid");
                                    if (dfid == null)
                                        dfid = getHeader(headers, "dfid");
                                    if (mid == null)
                                        mid = getHeader(headers, "mid");
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        // 需要至少有 token 和 userid
                        if (token == null || token.isEmpty() || userid == null || userid.isEmpty()) {
                            return;
                        }

                        // 去重：如果 token 没有变化则跳过
                        if (token.equals(lastToken)) return;
                        lastToken = token;

                        KugouApi.Credentials creds = new KugouApi.Credentials();
                        creds.token = token;
                        creds.userid = userid;
                        creds.dfid = dfid;
                        creds.mid = mid;
                        creds.appid = appid;
                        creds.clientver = clientver;

                        XposedBridge.log(TAG + " 捕获到凭证: userid=" + userid
                                + " dfid=" + (dfid != null ? dfid.substring(0, Math.min(8, dfid.length())) + "..." : "null")
                                + " mid=" + (mid != null ? "yes" : "null"));

                        if (callback != null) {
                            callback.onCredsCaptured(creds);
                        }

                    } catch (Exception e) {
                        // 静默处理，避免影响酷狗正常运行
                    }
                }
            });

            installed = true;
            XposedBridge.log(TAG + " OkHttp Hook 安装成功");

        } catch (Exception e) {
            XposedBridge.log(TAG + " OkHttp Hook 安装失败（酷狗可能未使用标准OkHttp）: " + e);
        }
    }

    /**
     * 从 okhttp3.HttpUrl 提取 query 参数
     */
    private static String getQueryParam(Object urlObj, String key) {
        try {
            Object value = XposedHelpers.callMethod(urlObj, "queryParameter", key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 okhttp3.Headers 提取 header 值
     */
    private static String getHeader(Object headers, String name) {
        try {
            Object value = XposedHelpers.callMethod(headers, "get", name);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
