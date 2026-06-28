package com.moekoe.xposed.kugoulite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import de.robv.android.xposed.XposedBridge;

/**
 * 通知工具（使用原生 Notification.Builder，不依赖 AndroidX）
 */
public class NotifyUtil {

    private static final String TAG = "[KuGouLiteVip]";
    private static final String CHANNEL_ID = "kugoulite_vip_channel";
    private static final String CHANNEL_NAME = "酷狗概念版VIP领取";
    private static final int NOTIFICATION_ID = 10086;

    private static volatile boolean channelCreated = false;

    public static void notifyResult(Context ctx, KugouApi.ClaimResult result) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            ensureChannel(nm);

            String content = result.message != null ? result.message : "未知结果";

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(ctx, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(ctx);
            }
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("酷狗概念版VIP")
                    .setContentText(content)
                    .setStyle(new Notification.BigTextStyle().bigText(content))
                    .setAutoCancel(true);

            nm.notify(NOTIFICATION_ID, builder.build());

        } catch (Exception e) {
            XposedBridge.log(TAG + " 发送通知失败: " + e);
        }
    }

    private static void ensureChannel(NotificationManager nm) {
        if (channelCreated) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("每日VIP领取结果通知");
            nm.createNotificationChannel(channel);
        }
        channelCreated = true;
    }
}
