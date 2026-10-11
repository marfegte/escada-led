package com.escadaledapp;

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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Bridge Java → JavaScript para BLE-Nano (emakefun/keywish CC2540).
 * Serviço: 0xFFE0  |  Característica TX/RX: 0xFFE1
 *
 * API JavaScript (window.AndroidBT):
 *   AndroidBT.startScan()        → scan BLE real via BluetoothLeScanner
 *   AndroidBT.stopScan()         → para scan
 *   AndroidBT.connect(address)   → conecta por MAC
 *   AndroidBT.disconnect()
 *   AndroidBT.send(text)
 *   AndroidBT.isConnected()
 *   AndroidBT.getPairedDevices() → dispositivos clássicos pareados (fallback)
 *
 * Callbacks JS:
 *   btOnScanResult(json)   → {name, address, rssi} a cada dispositivo encontrado
 *   btOnScanDone()         → scan encerrado (timeout 10s)
 *   btOnConnect()
 *   btOnDisconnect()
 *   btOnData(line)
 *   btOnError(msg)
 */
public class BluetoothBridge {

    private static final UUID SERVICE_UUID =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID =
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int SCAN_TIMEOUT_MS = 10_000; // 10 segundos

    private final Context context;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txChar;
    private volatile boolean connected = false;
    private final StringBuilder lineBuf = new StringBuilder();

    // queue para envio em chunks
    private final List<byte[]> sendQueue = new ArrayList<>();
    private volatile boolean sending = false;

    // scan BLE
    private BluetoothLeScanner leScanner;
    private ScanCallback scanCallback;
    private boolean scanning = false;

    public BluetoothBridge(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
    }

    // ── Scan BLE real via BluetoothLeScanner ──────────────────
    @JavascriptInterface
    public void startScan() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) { jsCall("btOnError", escJson("Bluetooth não disponível")); return; }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            jsCall("btOnError", escJson("Bluetooth desligado")); return;
        }

        leScanner = adapter.getBluetoothLeScanner();
        if (leScanner == null) {
            jsCall("btOnError", escJson("BLE scanner não disponível"));
            return;
        }

        if (scanning) stopScan();
        scanning = true;

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

        // sem filtro — lista todos os dispositivos BLE nas proximidades
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                try {
                    JSONObject obj = new JSONObject();
                    String name = d.getName();
                    obj.put("name", name != null ? name : "BLE Device");
                    obj.put("address", d.getAddress());
                    obj.put("rssi", result.getRssi());
                    final String json = obj.toString();
                    webView.post(() -> webView.evaluateJavascript(
                        "window.btOnScanResult && window.btOnScanResult(" + escJson(json) + ")", null));
                } catch (Exception ignored) {}
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                jsCall("btOnError", escJson("Scan falhou — código " + errorCode));
            }
        };

        leScanner.startScan(Collections.emptyList(), settings, scanCallback);

        // para automaticamente após SCAN_TIMEOUT_MS
        mainHandler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                jsCall("btOnScanDone", null);
            }
        }, SCAN_TIMEOUT_MS);
    }

    @JavascriptInterface
    public void stopScan() {
        scanning = false;
        if (leScanner != null && scanCallback != null) {
            try { leScanner.stopScan(scanCallback); } catch (Exception ignored) {}
            scanCallback = null;
        }
    }

    // ── Dispositivos pareados classicamente (fallback/histórico) ─
    @JavascriptInterface
    public String getPairedDevices() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return "[]";
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null) return "[]";
        try {
            JSONArray arr = new JSONArray();
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                JSONObject obj = new JSONObject();
                obj.put("name", d.getName() != null ? d.getName() : "Unknown");
                obj.put("address", d.getAddress());
                obj.put("rssi", -999); // pareado, não tem RSSI
                arr.put(obj);
            }
            return arr.toString();
        } catch (Exception e) { return "[]"; }
    }

    // ── Conecta por MAC BLE (GATT) ────────────────────────────
    @JavascriptInterface
    public void connect(String address) {
        stopScan(); // garante que o scan parou antes de conectar
        disconnect();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) { jsCall("btOnError", "\"No BT manager\""); return; }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null) { jsCall("btOnError", "\"No adapter\""); return; }

        BluetoothDevice device = adapter.getRemoteDevice(address);
        webView.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            else
                gatt = device.connectGatt(context, false, gattCallback);
        });
    }

    // ── Desconecta ────────────────────────────────────────────
    @JavascriptInterface
    public void disconnect() {
        connected = false; txChar = null;
        sendQueue.clear(); sending = false;
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
    }

    // ── Envia texto dividido em chunks de 20 bytes ────────────
    @JavascriptInterface
    public void send(String text) {
        if (!connected || txChar == null) return;
        byte[] bytes;
        try { bytes = (text + "\n").getBytes("UTF-8"); } catch (Exception e) { return; }

        synchronized (sendQueue) {
            for (int i = 0; i < bytes.length; i += 20) {
                int end = Math.min(i + 20, bytes.length);
                byte[] chunk = new byte[end - i];
                System.arraycopy(bytes, i, chunk, 0, chunk.length);
                sendQueue.add(chunk);
            }
        }
        drainQueue();
    }

    private void drainQueue() {
        if (sending) return;
        synchronized (sendQueue) {
            if (sendQueue.isEmpty()) return;
            byte[] chunk = sendQueue.remove(0);
            sending = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(txChar, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                txChar.setValue(chunk);
                txChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                gatt.writeCharacteristic(txChar);
            }
        }
    }

    @JavascriptInterface
    public boolean isConnected() { return connected; }

    // ── GATT Callback ─────────────────────────────────────────
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        private int reconnectCount = 0;

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectCount = 0;
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; txChar = null;
                if (reconnectCount < 4 && gatt != null) {
                    reconnectCount++;
                    jsCall("btOnError", escJson("GATT caiu — reconectando (" + reconnectCount + "/4)"));
                    try { Thread.sleep(600L * reconnectCount); } catch (InterruptedException ignored) {}
                    g.connect();
                } else {
                    reconnectCount = 0;
                    jsCall("btOnDisconnect", null);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                jsCall("btOnError", "\"Service discovery failed\""); return;
            }
            BluetoothGattService svc = g.getService(SERVICE_UUID);
            if (svc == null) { jsCall("btOnError", "\"FFE0 service not found\""); return; }
            txChar = svc.getCharacteristic(CHAR_UUID);
            if (txChar == null) { jsCall("btOnError", "\"FFE1 char not found\""); return; }

            g.setCharacteristicNotification(txChar, true);
            BluetoothGattDescriptor desc = txChar.getDescriptor(CCCD_UUID);
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                }
            } else {
                connected = true;
                jsCall("btOnConnect", null);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            connected = true;
            jsCall("btOnConnect", null);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] val = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ? c.getValue() : c.getValue();
            if (val == null) return;
            String chunk;
            try { chunk = new String(val, "UTF-8"); } catch (Exception e) { return; }
            synchronized (lineBuf) {
                for (char ch : chunk.toCharArray()) {
                    if (ch == '\n' || ch == '\r') {
                        String line = lineBuf.toString().trim();
                        lineBuf.setLength(0);
                        if (!line.isEmpty()) {
                            final String l = line;
                            webView.post(() -> webView.evaluateJavascript(
                                "window.btOnData && window.btOnData(" + escJson(l) + ")", null));
                        }
                    } else { lineBuf.append(ch); }
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int s) {
            sending = false;
            drainQueue();
        }
    };

    // ── Helpers ───────────────────────────────────────────────
    private String escJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private void jsCall(String fn, String arg) {
        String js = "window." + fn + " && window." + fn + "(" + (arg != null ? arg : "") + ")";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }
}
