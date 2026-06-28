package com.moekoe.xposed.kugoulite;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 统一日志工具，写入酷狗私有目录
 */
public class ModuleLog {
    private static final String TAG = "[KuGouLiteVip]";
    private static String LOG_FILE = null;

    public static void init(android.content.Context ctx) {
        try {
            LOG_FILE = ctx.getFilesDir().getAbsolutePath() + "/kugou_vip_module.log";
            // 启动时写入分隔线
            File f = new File(LOG_FILE);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // 如果文件超过 1MB，清空
            if (f.exists() && f.length() > 1024 * 1024) {
                f.delete();
            }
        } catch (Exception e) {
            LOG_FILE = "/data/data/com.kugou.android.lite/files/kugou_vip_module.log";
        }
    }

    public static void log(String msg) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date()) + "] " + msg;
        XposedBridge.log(TAG + " " + line);
        if (LOG_FILE == null) {
            LOG_FILE = "/data/data/com.kugou.android.lite/files/kugou_vip_module.log";
        }
        try {
            File file = new File(LOG_FILE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(file, true);
            fos.write((line + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
