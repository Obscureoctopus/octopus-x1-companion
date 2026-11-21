package tech.obscureoctopus.watch;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

@SuppressLint("MissingPermission")
public final class WatchBle {
    private static final String TAG = "WatchBle";
    public static final UUID SVC = UUID.fromString("a1000000-5333-5741-5443-484553570001");
    public static final UUID CFG = UUID.fromString("a1000000-5333-5741-5443-484553570002");
    public static final UUID PHONE = UUID.fromString("a1000000-5333-5741-5443-484553570004");
    public static final UUID WATCH = UUID.fromString("a1000000-5333-5741-5443-484553570005");
    public static final UUID CTS = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb");
    public static final UUID CTS_CHR = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb");
    public static final UUID ANS = UUID.fromString("00001811-0000-1000-8000-00805f9b34fb");
    public static final UUID ANS_CHR = UUID.fromString("00002a46-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onWatchBytes(byte[] data);
        void onStatus(String s);
    }

    private static WatchBle inst;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic phone, cfg, time, alert, watch;
    private Listener listener;
    private boolean connecting;
    private Runnable scanTimeout;

    public static synchronized WatchBle get(Context c) {
        if (inst == null) inst = new WatchBle(c.getApplicationContext());
        return inst;
    }

    private WatchBle(Context app) { this.app = app; }

    public void setListener(Listener l) { listener = l; }

    public boolean isReady() { return phone != null && gatt != null; }

    public void connect(Callback cb) {
        if (isReady()) { cb.ok("already connected"); return; }
        if (connecting) { cb.err("scan already running"); return; }
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) { cb.err("turn Bluetooth on"); return; }
        connecting = true;
        status("scanning InfiniTime…");
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        ScanCallback scanCb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                String n = d.getName();
                if (n == null) return;
                if (!(n.equals("InfiniTime") || n.startsWith("OCTOPUS") || n.startsWith("Infini"))) return;
                sc.stopScan(this);
                connecting = false;
                if (scanTimeout != null) main.removeCallbacks(scanTimeout);
                status("connecting " + n);
                gatt = d.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE);
                pending = cb;
            }
        };
        try {
            sc.startScan(Collections.singletonList(
                    new ScanFilter.Builder().setServiceUuid(new ParcelUuid(ANS)).build()
            ), settings, scanCb);
        } catch (Exception e) {
            sc.startScan(null, settings, scanCb);
        }
        scanTimeout = () -> {
            try { sc.stopScan(scanCb); } catch (Exception ignored) {}
            connecting = false;
            if (!isReady()) cb.err("no InfiniTime found");
        };
        main.postDelayed(scanTimeout, 12000);
    }

    public interface Callback { void ok(String s); void err(String s); }
    private Callback pending;

    public void write(String ch, byte[] data, Callback cb) {
        BluetoothGattCharacteristic c = charFor(ch);
        if (gatt == null || c == null) { cb.err("not connected"); return; }
        c.setValue(data);
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        if (!gatt.writeCharacteristic(c)) cb.err("write failed");
        else cb.ok("ok");
    }

    public void sendNotify(String from, String text) {
        byte[] a = from.getBytes(StandardCharsets.UTF_8);
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        byte[] pkt = new byte[1 + a.length + 1 + b.length];
        pkt[0] = 1;
        System.arraycopy(a, 0, pkt, 1, a.length);
        pkt[1 + a.length] = 0;
        System.arraycopy(b, 0, pkt, 2 + a.length, b.length);
        write("phone", pkt, new Callback() {
            public void ok(String s) { status("SMS → watch " + from); }
            public void err(String s) { status("SMS write fail " + s); }
        });
        SmsBridge.get().remember(from);
    }

    private BluetoothGattCharacteristic charFor(String ch) {
        if ("phone".equals(ch)) return phone;
        if ("cfg".equals(ch)) return cfg;
        if ("time".equals(ch)) return time;
        if ("alert".equals(ch)) return alert;
        if ("watch".equals(ch)) return watch;
        return null;
    }

    private void status(String s) {
        Log.i(TAG, s);
        if (listener != null) main.post(() -> listener.onStatus(s));
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(185);
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                phone = cfg = time = alert = watch = null;
                WatchBle.this.gatt = null;
                status("disconnected");
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) {
                if (pending != null) pending.err("watch GATT missing Octopus service");
                pending = null;
                return;
            }
            phone = svc.getCharacteristic(PHONE);
            cfg = svc.getCharacteristic(CFG);
            watch = svc.getCharacteristic(WATCH);
            BluetoothGattService ts = g.getService(CTS);
            time = ts != null ? ts.getCharacteristic(CTS_CHR) : null;
            BluetoothGattService as = g.getService(ANS);
            alert = as != null ? as.getCharacteristic(ANS_CHR) : null;
            if (watch != null) {
                g.setCharacteristicNotification(watch, true);
                BluetoothGattDescriptor d = watch.getDescriptor(CCCD);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            status("connected");
            if (pending != null) pending.ok("connected");
            pending = null;
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null || v.length == 0) return;
            SmsBridge.get().onWatchPacket(v);
            if (listener != null) {
                byte[] copy = v.clone();
                main.post(() -> listener.onWatchBytes(copy));
            }
        }
    };

    public static String b64(byte[] d) {
        return Base64.encodeToString(d, Base64.NO_WRAP);
    }
}
