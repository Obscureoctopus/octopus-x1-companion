package tech.obscureoctopus.watch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.ArrayList;

public final class MainActivity extends AppCompatActivity implements WatchBle.Listener {
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
            web.evaluateJavascript("window.__ooNativeOk && window.__ooNativeOk(" + id + ",'" + q + "')", null);
        }
        private void err(int id, String msg) {
            String q = (msg == null ? "error" : msg).replace("\\", "\\\\").replace("'", "\\'");
            web.evaluateJavascript("window.__ooNativeErr && window.__ooNativeErr(" + id + ",'" + q + "')", null);
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
                case "callAction" -> ok(id, "call " + arg);
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
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
        };
        if (Build.VERSION.SDK_INT >= 33) {
            perms = new String[] {
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), 7);
        }
    }
}
