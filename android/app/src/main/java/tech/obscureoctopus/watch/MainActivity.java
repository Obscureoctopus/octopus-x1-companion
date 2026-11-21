package tech.obscureoctopus.watch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.app.Activity;

import org.json.JSONObject;

import java.util.ArrayList;

public final class MainActivity extends Activity implements WatchBle.Listener {
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBle");
        WatchBle.get(this).setListener(this);
        web.loadUrl("file:///android_asset/index.html");
        SmsBridge.get().setEnabled(true);
        requestSms();
        startForegroundService(new Intent(this, KeepAliveService.class));
    }

    @Override
    public void onWatchBytes(byte[] data) {
        String b64 = WatchBle.b64(data);
        web.evaluateJavascript("window.__ooNativeNotify && window.__ooNativeNotify('" + b64 + "')", null);
    }

    @Override
    public void onStatus(String s) {
        String q = s.replace("\\", "\\\\").replace("'", "\\'");
        web.evaluateJavascript("window.__ooNativeSms && window.__ooNativeSms('" + q + "')", null);
    }

    final class Bridge {
        @JavascriptInterface
        public void invoke(String method, int id, String arg) {
            runOnUiThread(() -> dispatch(method, id, arg == null ? "" : arg));
        }

        private void ok(int id, String data) {
            String q = (data == null ? "" : data).replace("\\", "\\\\").replace("'", "\\'");
            runOnUiThread(() -> web.evaluateJavascript("window.__ooNativeOk && window.__ooNativeOk(" + id + ",'" + q + "')", null));
        }
        private void err(int id, String msg) {
            String q = (msg == null ? "error" : msg).replace("\\", "\\\\").replace("'", "\\'");
            runOnUiThread(() -> web.evaluateJavascript("window.__ooNativeErr && window.__ooNativeErr(" + id + ",'" + q + "')", null));
        }

        private void dispatch(String method, int id, String arg) {
            switch (method) {
                case "connect" -> WatchBle.get(MainActivity.this).connect(new WatchBle.Callback() {
                    public void ok(String s) { Bridge.this.ok(id, s); }
                    public void err(String s) { Bridge.this.err(id, s); }
                });
                case "write" -> {
                    try {
                        JSONObject o = new JSONObject(arg);
                        byte[] data = android.util.Base64.decode(o.getString("data"), android.util.Base64.DEFAULT);
                        WatchBle.get(MainActivity.this).write(o.getString("ch"), data, new WatchBle.Callback() {
                            public void ok(String s) { Bridge.this.ok(id, s); }
                            public void err(String s) { Bridge.this.err(id, s); }
                        });
                    } catch (Exception e) {
                        err(id, e.getMessage());
                    }
                }
                case "enableSms" -> {
                    requestSms();
                    SmsBridge.get().setEnabled(true);
                    ok(id, WatchBle.get(MainActivity.this).isReady()
                            ? "SMS bridge on — incoming texts go to the watch; wrist replies send SMS"
                            : "SMS permissions requested — Connect the watch too");
                }
                case "enableNotifs" -> {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    ok(id, "turn on OCTOPUS in Notification access");
                }
                case "smsReply" -> {
                    try {
                        JSONObject o = new JSONObject(arg);
                        String r = SmsBridge.get().send(o.optString("from"), o.optString("text"));
                        ok(id, r);
                    } catch (Exception e) {
                        err(id, e.getMessage());
                    }
                }
                case "callAction" -> ok(id, answerOrEnd("1".equals(arg.trim())));
                default -> err(id, "unknown " + method);
            }
        }
    }

    private void requestSms() {
        ArrayList<String> need = new ArrayList<>();
        String[] perms = new String[] {
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
        };
        if (Build.VERSION.SDK_INT >= 33) {
            perms = new String[] {
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ANSWER_PHONE_CALLS,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 7);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        startService(new Intent(this, KeepAliveService.class));
    }

    @SuppressWarnings("deprecation")
    private String answerOrEnd(boolean answer) {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.ANSWER_PHONE_CALLS }, 8);
            return "allow answer-calls, then tap the watch again";
        }
        TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
        if (tm == null) return "no phone service";
        try {
            if (answer) {
                tm.acceptRingingCall();
                return "answered";
            }
            boolean ended = tm.endCall();
            return ended ? "ended" : "could not end the call";
        } catch (SecurityException e) {
            return e.getMessage() == null ? "call permission denied" : e.getMessage();
        }
    }
}
