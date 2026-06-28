package com.moekoe.xposed.kugoulite;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/**
 * 模块配置界面（纯原生，不依赖 AndroidX）
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "module_state";
    private static final String TARGET_PACKAGE = "com.kugou.android.lite";

    private Switch switchEnabled;
    private Switch switchUpgrade;
    private Button btnTrigger;
    private Button btnRefresh;
    private TextView textStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        switchEnabled = findViewById(R.id.switch_enabled);
        switchUpgrade = findViewById(R.id.switch_upgrade);
        btnTrigger = findViewById(R.id.btn_trigger);
        btnRefresh = findViewById(R.id.btn_refresh);
        textStatus = findViewById(R.id.text_status);

        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        switchEnabled.setChecked(sp.getBoolean(Settings.KEY_ENABLED, true));
        switchUpgrade.setChecked(sp.getBoolean(Settings.KEY_AUTO_UPGRADE, false));

        switchEnabled.setOnCheckedChangeListener((button, checked) ->
                sp.edit().putBoolean(Settings.KEY_ENABLED, checked).apply());

        switchUpgrade.setOnCheckedChangeListener((button, checked) ->
                sp.edit().putBoolean(Settings.KEY_AUTO_UPGRADE, checked).apply());

        btnTrigger.setOnClickListener(v -> triggerClaim());
        btnRefresh.setOnClickListener(v -> updateStatus());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void triggerClaim() {
        if (!isKuGouInstalled()) {
            Toast.makeText(this, R.string.trigger_no_app, Toast.LENGTH_SHORT).show();
            return;
        }
        // 通过 ContentProvider 设置重置标记
        try {
            android.net.Uri uri = android.net.Uri.parse("content://com.moekoe.xposed.kugoulite.state");
            getContentResolver().call(uri, "reset_claim", null, null);
        } catch (Exception e) {
            // ignore
        }
        // 发送广播触发酷狗进程
        Intent intent = new Intent(ClaimWorker.ACTION_TRIGGER);
        intent.setPackage(TARGET_PACKAGE);
        sendBroadcast(intent);
        Toast.makeText(this, R.string.trigger_sent, Toast.LENGTH_LONG).show();
    }

    private void updateStatus() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String message = sp.getString(Settings.KEY_STATUS_MSG, getString(R.string.status_default));
        int code = sp.getInt(Settings.KEY_STATUS_CODE, -1);
        long time = sp.getLong(Settings.KEY_STATUS_TIME, 0);

        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(message);

        if (time > 0) {
            String timeStr = DateFormat.format("yyyy-MM-dd HH:mm:ss", new Date(time)).toString();
            sb.append("\n更新时间: ").append(timeStr);
        }

        switch (code) {
            case KugouApi.CODE_SUCCESS:
                sb.append("\n(领取成功)");
                break;
            case KugouApi.CODE_ALREADY:
                sb.append("\n(今日已领取)");
                break;
            case KugouApi.CODE_RISK:
                sb.append("\n(账号风控)");
                break;
            case KugouApi.CODE_ERROR:
                if (time > 0) sb.append("\n(领取失败)");
                break;
        }

        textStatus.setText(sb.toString());
    }

    private boolean isKuGouInstalled() {
        try {
            getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
