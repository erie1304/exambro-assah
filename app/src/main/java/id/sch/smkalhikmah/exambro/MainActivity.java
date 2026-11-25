package id.sch.smkalhikmah.exambro;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // =========================================================================
    // PENGATURAN UTAMA SEKOLAH (UBAH URL INI SAAT SUDAH NAIK KE HOSTING HTTPS)
    // =========================================================================
    private static final String DEFAULT_EXAM_URL = "http://192.168.1.5/master/app";
    private static final String SECRET_USER_AGENT_TAG = "ASSAH-ALHIKMAH-CBT/1.0";
    private static final String PROKTOR_PIN = "2026";
    // =========================================================================

    private static final String PREF_NAME = "assah_exambro_pref";
    private static final String KEY_URL = "saved_exam_url";

    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout splashOverlay;
    private LinearLayout offlineOverlay;
    private TextView txtStatusInfo;
    private TextView txtErrorDetail;

    private int batteryPct = -1;
    private int logoTapCount = 0;
    private long lastLogoTapMs = 0;

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatusText();
            clockHandler.postDelayed(this, 15000);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                batteryPct = (int) ((level * 100f) / scale);
                updateStatusText();
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. BLOKIR SCREENSHOT & REKAM LAYAR + LAYAR TETAP MENYALA
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        hideSystemUI();

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        splashOverlay = findViewById(R.id.splashOverlay);
        offlineOverlay = findViewById(R.id.offlineOverlay);
        txtStatusInfo = findViewById(R.id.txtStatusInfo);
        txtErrorDetail = findViewById(R.id.txtErrorDetail);

        Button btnRefresh = findViewById(R.id.btnRefresh);
        Button btnPin = findViewById(R.id.btnPin);
        Button btnExit = findViewById(R.id.btnExit);
        Button btnRetry = findViewById(R.id.btnRetry);
        Button btnOpenUrlSetting = findViewById(R.id.btnOpenUrlSetting);
        View imgLogoTop = findViewById(R.id.imgLogoTop);

        // Ketuk Logo 5x di topbar untuk membuka menu Ubah Alamat Server (dilindungi PIN Panitia)
        imgLogoTop.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastLogoTapMs > 2500) {
                logoTapCount = 0;
            }
            lastLogoTapMs = now;
            logoTapCount++;
            if (logoTapCount >= 5) {
                logoTapCount = 0;
                promptProktorUrlSetting();
            }
        });

        btnRefresh.setOnClickListener(v -> {
            offlineOverlay.setVisibility(View.GONE);
            webView.reload();
        });

        btnPin.setOnClickListener(v -> {
            try {
                startLockTask();
                Toast.makeText(this, "Mode Kunci Layar (Screen Pinning) diminta.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Aktifkan Sematkan Aplikasi di Pengaturan Keamanan HP.", Toast.LENGTH_LONG).show();
            }
        });

        btnExit.setOnClickListener(v -> showExitDialog());

        btnRetry.setOnClickListener(v -> {
            offlineOverlay.setVisibility(View.GONE);
            webView.loadUrl(getExamUrl());
        });

        btnOpenUrlSetting.setOnClickListener(v -> promptProktorUrlSetting());

        // Kunci tombol Back Android agar siswa tidak keluar tidak sengaja
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Toast.makeText(MainActivity.this,
                        "Tombol Kembali dinonaktifkan selama ujian. Gunakan tombol di layar.",
                        Toast.LENGTH_SHORT).show();
            }
        });

        // Konfigurasi WebView ASSAH CBT AKM
        setupWebView();

        // Muat Portal Login ASSAH
        webView.loadUrl(getExamUrl());

        // Tutup Splash Screen setelah 1.4 detik
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (splashOverlay != null) {
                splashOverlay.setVisibility(View.GONE);
            }
        }, 1400);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true); // Wajib untuk localStorage auto-save jawaban CBT
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Sisipkan User-Agent Rahasia SMK Al-Hikmah Curug
        String baseUa = ws.getUserAgentString();
        ws.setUserAgentString(baseUa + " " + SECRET_USER_AGENT_TAG);

        // Pastikan Cookie Session SSO antar folder (/master dan /akademik) tersimpan mulus
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        // Matikan tekan-lama (blokir copy-paste teks soal)
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setHapticFeedbackEnabled(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Info Ujian ASSAH")
                        .setMessage(message)
                        .setPositiveButton("OK", (dialog, which) -> result.confirm())
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Konfirmasi Ujian")
                        .setMessage(message)
                        .setPositiveButton("Ya, Lanjutkan", (dialog, which) -> result.confirm())
                        .setNegativeButton("Batal", (dialog, which) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Pertahankan seluruh navigasi di dalam WebView Exambro (jangan lempar ke Chrome)
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                offlineOverlay.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    offlineOverlay.setVisibility(View.VISIBLE);
                    String errDesc = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && error != null)
                            ? String.valueOf(error.getDescription()) : "Server tidak terjangkau";
                    txtErrorDetail.setText("Alamat: " + getExamUrl() + "\nDetail: " + errDesc
                            + "\n\nPastikan Wi-Fi / data aktif. Jawaban Anda tetap tersimpan aman di HP.");
                }
            }
        });
    }

    private String getExamUrl() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String saved = sp.getString(KEY_URL, DEFAULT_EXAM_URL);
        if (saved == null || saved.trim().isEmpty()) {
            return DEFAULT_EXAM_URL;
        }
        return saved.trim();
    }

    private void promptProktorUrlSetting() {
        final EditText inputPin = new EditText(this);
        inputPin.setHint("Masukkan PIN Panitia");
        inputPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        new AlertDialog.Builder(this)
                .setTitle("Pengaturan Server (Khusus Panitia)")
                .setMessage("Masukkan PIN Proktor untuk mengubah alamat portal ASSAH:")
                .setView(inputPin)
                .setPositiveButton("Lanjut", (d, w) -> {
                    if (PROKTOR_PIN.equals(inputPin.getText().toString().trim())) {
                        showUrlEditorDialog();
                    } else {
                        Toast.makeText(this, "PIN Panitia salah!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showUrlEditorDialog() {
        final EditText inputUrl = new EditText(this);
        inputUrl.setText(getExamUrl());
        inputUrl.setHint("https://domainsekolah.sch.id/master/app");
        inputUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
                .setTitle("Alamat Portal Login ASSAH")
                .setMessage("Masukkan alamat URL server ujian (contoh lokal: http://192.168.1.5/master/app):")
                .setView(inputUrl)
                .setPositiveButton("Simpan & Muat", (d, w) -> {
                    String newUrl = inputUrl.getText().toString().trim();
                    if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                        newUrl = "https://" + newUrl;
                    }
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_URL, newUrl)
                            .apply();
                    offlineOverlay.setVisibility(View.GONE);
                    webView.loadUrl(newUrl);
                    Toast.makeText(this, "URL diperbarui ke: " + newUrl, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Reset Default", (d, w) -> {
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().remove(KEY_URL).apply();
                    offlineOverlay.setVisibility(View.GONE);
                    webView.loadUrl(DEFAULT_EXAM_URL);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showExitDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Keluar dari Exambro ASSAH?")
                .setMessage("Pastikan Anda sudah menekan tombol 'Selesai & Kumpulkan Ujian' sebelum keluar.\n\nYakin ingin menutup aplikasi?")
                .setPositiveButton("Ya, Keluar", (dialog, which) -> {
                    try {
                        stopLockTask();
                    } catch (Exception ignored) {}
                    finishAffinity();
                })
                .setNegativeButton("Lanjut Ujian", null)
                .show();
    }

    private void updateStatusText() {
        String jam = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String bat = (batteryPct >= 0) ? (batteryPct + "%") : "--%";
        txtStatusInfo.setText("SMK Al-Hikmah Curug • " + jam + " • Baterai " + bat);
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        clockHandler.post(clockRunnable);

        // Deteksi Split-Screen / Multi-Window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
            new AlertDialog.Builder(this)
                    .setTitle("Mode Belah Layar Dilarang!")
                    .setMessage("Exambro ASSAH tidak dapat digunakan dalam mode Split-Screen / Floating Window. Kembalikan ke layar penuh.")
                    .setCancelable(false)
                    .setPositiveButton("Tutup Aplikasi", (d, w) -> finishAffinity())
                    .show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {}
        clockHandler.removeCallbacks(clockRunnable);
    }
}
