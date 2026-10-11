package com.escadaledapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private BluetoothBridge btBridge;

    // ── Launchers ─────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            result -> handlePermissionResult(result));

    private final ActivityResultLauncher<Intent> enableBtLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> requestBluetoothPermissions());

    // ── Lifecycle ─────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupWebView();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;

        if (adapter == null) {
            Toast.makeText(this, "Bluetooth não disponível neste dispositivo", Toast.LENGTH_LONG).show();
            loadApp();
            return;
        }

        if (!adapter.isEnabled()) {
            enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } else {
            requestBluetoothPermissions();
        }
    }

    // ── WebView ───────────────────────────────────────────────
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        btBridge = new BluetoothBridge(this, webView);
        webView.addJavascriptInterface(btBridge, "AndroidBT");
        webView.setWebViewClient(new WebViewClient());
    }

    // ── Permissões ────────────────────────────────────────────
    private void requestBluetoothPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+): BLUETOOTH_SCAN + BLUETOOTH_CONNECT
            // Android 13/14 requer que o usuário aprove via dialog "Nearby devices"
            addIfMissing(needed, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(needed, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            // Android < 12: BLE scan exige localização
            addIfMissing(needed, Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!needed.isEmpty()) {
            // Android 13/14: mostra explicação antes do dialog do sistema
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                shouldShowRationale(Manifest.permission.BLUETOOTH_SCAN)) {
                new AlertDialog.Builder(this)
                    .setTitle("Permissão necessária")
                    .setMessage("O app precisa de permissão para encontrar dispositivos Bluetooth próximos (BLE-Nano). Nenhum dado de localização é coletado.")
                    .setPositiveButton("Permitir", (d, w) -> permLauncher.launch(needed.toArray(new String[0])))
                    .setNegativeButton("Cancelar", (d, w) -> loadApp())
                    .show();
            } else {
                permLauncher.launch(needed.toArray(new String[0]));
            }
        } else {
            loadApp();
        }
    }

    private void addIfMissing(List<String> list, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
            list.add(permission);
    }

    private boolean shouldShowRationale(String permission) {
        return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permission);
    }

    private void handlePermissionResult(@NonNull Map<String, Boolean> result) {
        boolean allGranted = true;
        for (Map.Entry<String, Boolean> e : result.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            Toast.makeText(this,
                "Sem permissão de Bluetooth — o scan BLE não funcionará.\nAcesse Configurações → Apps → Escada LED → Permissões.",
                Toast.LENGTH_LONG).show();
        }
        loadApp(); // carrega o app mesmo sem permissão (usuário vê mensagem no log)
    }

    private void loadApp() {
        webView.loadUrl("file:///android_asset/app.html");
    }

    // ── Ciclo de vida ─────────────────────────────────────────
    @Override
    protected void onDestroy() {
        if (btBridge != null) btBridge.disconnect();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
