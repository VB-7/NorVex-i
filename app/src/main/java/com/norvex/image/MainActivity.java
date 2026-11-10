package com.norvex.image;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NorVex-I — wadah native baru untuk generator norvex-i.
 * Bukan turunan MIT App Inventor: tidak ada WebViewString/file_bridge.
 * Web bicara lewat window.__imageStudio (getGallery/restoreItems),
 * native yang aktif menarik/mendorong backup.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://perchance.org/norvex-i";
    // Relatif terhadap root storage eksternal (jangan hardcode /storage/emulated/0:
    // tiap HP bisa beda — pola NorVex-R pakai Environment API).
    private static final String PRIMARY_ROOT = "perchance_app/Generete_Image";
    // Repo GitHub untuk update otomatis (pola NorVex-R). GANTI dengan repo kamu,
    // mis. "USERNAME_KAMU/NorVex-I". Tag rilis "b<N>" dibaca sebagai versionCode N.
    private static final String UPDATE_REPO = "VB-7/NorVex-i";
    private static final String UPD_PREF_TAG = "upd_tag"; // tag APK update yg sudah terunduh
    private static long lastUpdateCheckAt = 0; // throttle: maks 1x per 10 mnt (anti spam API)
    private static final int REQ_STORAGE = 8; // izin tulis (HP Android 9/10)

    // Elemen Perchance yang disembunyikan (menu/iklan/konsol) — rules AdBlock norvex-r.
    private static final String[] HIDE_IDS = {
            "minimalModeMenuBtn", "backToMinimalModeBtn", "adCtn",
            "adsSandboxIframeEl", "adShieldEl", "whyAdsButton1El", "menuBarEl"
    };
    // Host iklan/tracker yang diblok di level network (pola norvex-r).
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "ads.yahoo.com", "adnxs.com", "criteo.com"
    };

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger fileCounter = new AtomicInteger(0);
    private android.widget.FrameLayout webRoot = null; // wadah WebView (buat ulang saat renderer mati)
    private boolean pageDone = false;

    // ---- update otomatis (pola NorVex-R) ----
    private long updateDownloadId = -1;
    private static final int REQ_NOTIF = 9; // izin notifikasi (Android 13+)
    private AlertDialog retryDlg = null; // dialog "coba lagi" saat offline/gagal muat
    private boolean pageFailed = false;
    private final java.util.Map<Long, String> httpNames = new java.util.HashMap<Long, String>();
    // Manifest backup chunk (di memori): dibaca sekali saat start.
    private String manifestSig = null;
    private int manifestCount = 0;
    private int manifestRev = 0;

    private File rootDir;       // Generete_Image/ (primer atau fallback)
    private File generationDir; // Generation/
    private File backupDir;     // Backup_Gallery/
    private boolean usingFallback;

    // ---------------------------------------------------------------- storage

    private void resolveStorage() {
        // Basis folder: <external>/perchance_app/Generete_Image (WAJIB user).
        // Gagal (scoped storage / tanpa izin) -> folder khusus aplikasi (selalu bisa).
        // Pola NorVex-R: Environment API + mkdirs + isDirectory + canWrite.
        File base = null;
        try {
            File ext = new File(Environment.getExternalStorageDirectory(), PRIMARY_ROOT);
            //noinspection ResultOfMethodCallIgnored
            ext.mkdirs();
            if (ext.isDirectory() && ext.canWrite()) base = ext;
        } catch (Exception ignored) {}
        if (base != null) {
            rootDir = base;
            usingFallback = false;
        } else {
            // Fallback: ASD, pola yang sama seperti NorVex-r.
            File asd = new File(getExternalFilesDir(null), "NorVex-I");
            //noinspection ResultOfMethodCallIgnored
            asd.mkdirs();
            rootDir = asd;
            usingFallback = true;
        }
        generationDir = new File(rootDir, "Generation");
        backupDir = new File(rootDir, "Backup_Gallery");
        ensureNoMedia(generationDir); // hasil generate jangan menyampah ke galeri HP
        ensureNoMedia(backupDir);
    }

    private void ensureNoMedia(File dir) {
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File nm = new File(dir, ".nomedia");
            if (!nm.exists()) {
                try (OutputStream o = new FileOutputStream(nm)) {
                    o.write(0);
                }
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        resolveStorage();
        ensureStoragePerm(); // minta izin tulis SEKALI (HP Android 9/10)
        ensureNotifPerm(); // izin notifikasi SEKALI (Android 13+)
        hookCrashLogger(); // catat force-close ke error/

        // Anti-blackscreen (pola norvex-r): semua lapis hitam, bar sistem tampil normal.
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        try { getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000)); } catch (Exception ignored) {}
        try { getWindow().getDecorView().setBackgroundColor(0xFF000000); } catch (Exception ignored) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                getWindow().setStatusBarColor(0xFF000000);
                getWindow().setNavigationBarColor(0xFF000000);
            }
        } catch (Exception ignored) {}
        try { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN); } catch (Exception ignored) {}
        // Keyboard: cukup adjustResize sistem (tanpa ganjal manual = tanpa jarak hitam ganda).
        try { getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); } catch (Exception ignored) {}
        webRoot = root;
        setupFreshWebView(); // buat WebView + langsung buka halaman (tanpa tirai)

        // Penerima unduhan update (pola NorVex-R): verifikasi status beneran,
        // bukan sekadar event selesai.
        try {
            IntentFilter dlFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(dlReceiver, dlFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dlReceiver, dlFilter);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Cek update tiap buka (di-throttle 10 mnt di dalam checkUpdate).
        try { checkUpdate(); } catch (Exception ignored) {}
    }

    /** Bikin WebView segar + langsung buka halaman. Dipakai saat dibuka dan
     *  saat bangkit dari renderer mati. Tanpa splash: user melihat proses muat apa adanya. */
    private void setupFreshWebView() {
        try {
            web = new WebView(this);
            if (webRoot != null) webRoot.addView(web, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            // Diam (background): posisi storage hanya dicatat ke trace.
            trace(usingFallback ? "storage: ASD (utama gagal ditulis)" : "storage: perchance_app/Generete_Image");

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);

        web.addJavascriptInterface(new NativeBridge(), "NorVexI");

        // WebView adalah isi utama app: jangan matikan proses tampilannya selagi dipakai.
        if (Build.VERSION.SDK_INT >= 26) {
            try { web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true); } catch (Exception ignored) {}
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                try {
                    String host = request.getUrl().getHost();
                    if (host != null) {
                        String h = host.toLowerCase();
                        for (String ad : AD_HOSTS) {
                            if (h.contains(ad)) {
                                return new android.webkit.WebResourceResponse(
                                        "text/plain", "utf-8", 204, "No Content", null,
                                        new java.io.ByteArrayInputStream(new byte[0]));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Hanya bingkai utama yang boleh pindah: iframe iklan jangan buka browser.
                boolean isMain = true;
                try { isMain = request == null || request.isForMainFrame(); } catch (Exception ignored) {}
                if (!isMain) return false;
                Uri u = request.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                // Tetap di dalam generator; tautan perchance lain dibuka di browser.
                if (host.equals("perchance.org")) {
                    String path = u.getPath() == null ? "" : u.getPath();
                    if (path.equals("/norvex-i") || path.startsWith("/norvex-i/")) return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectCleanCss(); // suntik awal agar menu/iklan tak sempat tampil
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageDone = true;
                pageFailed = false;
                try { if (retryDlg != null) retryDlg.dismiss(); } catch (Exception ignored) {}
                injectCleanCss();
                setNativeTotal();
                pullBackupFromWeb();
            }

            // Gagal muat (offline/kuota/sinyal): dialog jujur + tombol coba lagi.
            // Tanpa ini user mentok di halaman error dan harus restart aplikasi.
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                try {
                    boolean isMain = true;
                    try { isMain = request == null || request.isForMainFrame(); } catch (Exception ignored) {}
                    if (!isMain || pageDone) return;
                    pageFailed = true;
                    main.post(() -> showRetryDialog());
                } catch (Exception ignored) {}
            }

            // Proses tampil MATI (tanpa dialog/crash): jangan diam hitam.
            // WebView yang mati TAK BISA dipakai lagi -> buat baru + muat ulang.
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                boolean crash = false;
                try { crash = detail != null && detail.didCrash(); } catch (Exception ignored) {}
                final boolean fCrash = crash;
                main.post(() -> recoverRenderer(fCrash));
                return true; // kita yang tangani
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                handleDownload(url, mimeType);
            }
        });

        // Webview tanpa state: tiap buka selalu halaman generator yang segar.
        web.loadUrl(START_URL);
        } catch (Throwable t) {
            trace("setup web gagal: " + t);
        }
    }

    /** Bangkit dari renderer mati: cabut + hancurkan + buat BARU yang segar. */
    private void recoverRenderer(boolean crash) {
        try {
            pageDone = false;
            if (web != null) {
                try { ((android.view.ViewGroup) web.getParent()).removeView(web); } catch (Exception ignored) {}
                try { web.destroy(); } catch (Exception ignored) {}
                web = null;
            }
            setupFreshWebView();
            if (crash) toast("Web dimuat ulang (memori penuh)");
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { pullBackupFromWeb(); } catch (Exception ignored) {} // checkpoint: hash-gated, murah
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        io.shutdown();
        try { unregisterReceiver(dlReceiver); } catch (Exception ignored) {}
        try { if (retryDlg != null) retryDlg.dismiss(); } catch (Exception ignored) {}
        try {
            web.destroy();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Dialog retry paling atas: tutup dulu.
        try {
            if (retryDlg != null && retryDlg.isShowing()) {
                try { retryDlg.dismiss(); } catch (Exception ignored) {}
                return;
            }
        } catch (Exception ignored) {}
        // Tutup popup web dulu bila ada, baru konfirmasi keluar.
        try {
            web.evaluateJavascript(
                    "(function(){var els=document.querySelectorAll('.overlay.open,.drawer.open');"
                            + "if(els.length){els[els.length-1].classList.remove('open');return 'closed';}"
                            + "return 'none';})();",
                    v -> {
                        if (v != null && v.contains("closed")) return;
                        if (web.canGoBack()) web.goBack();
                        else confirmExit();
                    });
        } catch (Exception e) {
            super.onBackPressed();
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setMessage("Keluar dari NorVex-I?")
                .setPositiveButton("Keluar", (d, w) -> finish())
                .setNegativeButton("Batal", null)
                .show();
    }

    // Izin tulis (HP Android 9/10, API<=29): tanpa ini folder
    // perchance_app/Generete_Image tak bisa ditulis, semua diam-diam jatuh
    // ke folder aplikasi (user tak menemukan file di file manager + tak ada
    // izin di Setting aplikasi). Minta SEKALI saja (dicatat): kalau user
    // menolak permanen, jangan nagih tiap buka. Pola NorVex-R.
    private void ensureStoragePerm() {
        try {
            if (Build.VERSION.SDK_INT <= 29
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                SharedPreferences sp = getSharedPreferences("nv-i", MODE_PRIVATE);
                if (sp.getBoolean("storage_asked", false)) return;
                try { sp.edit().putBoolean("storage_asked", true).apply(); } catch (Exception ignored) {}
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        try {
            if (req == REQ_STORAGE) {
                boolean ok = grants != null && grants.length > 0
                        && grants[0] == PackageManager.PERMISSION_GRANTED;
                if (ok) {
                    resolveStorage(); // coba lagi folder utama, sekarang berizin
                    trace("izin tulis diberikan");
                } else {
                    // Satu-satunya toast izin: user perlu tahu nasib filenya.
                    toast("Tanpa izin, file tersimpan di folder aplikasi (bukan perchance_app)");
                    trace("izin tulis ditolak");
                }
            } else if (req == REQ_NOTIF) {
                boolean ok = grants != null && grants.length > 0
                        && grants[0] == PackageManager.PERMISSION_GRANTED;
                trace(ok ? "izin notifikasi diberikan" : "izin notifikasi ditolak (diam)");
            }
        } catch (Exception ignored) {}
    }

    // Izin notifikasi (Android 13+): tanpa ini notifikasi progres unduhan
    // tidak tampil. Minta SEKALI saja, selebihnya diam.
    private void ensureNotifPerm() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                SharedPreferences sp = getSharedPreferences("nv-i", MODE_PRIVATE);
                if (sp.getBoolean("notif_asked", false)) return;
                try { sp.edit().putBoolean("notif_asked", true).apply(); } catch (Exception ignored) {}
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- web <-> native

    /** Sembunyikan menu/iklan/konsol Perchance via CSS. */
    private void injectCleanCss() {
        StringBuilder sel = new StringBuilder();
        for (String id : HIDE_IDS) {
            if (sel.length() > 0) sel.append(",");
            sel.append("#").append(id);
        }
        String css = sel + "{display:none!important}";
        try {
            web.evaluateJavascript(
                    "(function(){var s=document.getElementById('norvexi-hide');"
                            + "if(!s){s=document.createElement('style');s.id='norvexi-hide';"
                            + "document.head.appendChild(s);}"
                            + "s.textContent=" + JSONObject.quote(css) + ";})();",
                    null);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- backup lazy
    // Arsip: part-000.json, part-001.json, ... @100 item + manifest.json
    // {count,rev,sig,parts} + manifest.bak (fallback WAJIB dipakai).
    // Sinkron hash-gated: sig sama -> lewati total (tidak tarik/upload ulang).
    // Transport 5 item per panggil (batas binder) + fallback 1-per-1.
    private static final int BACKUP_PART_SIZE = 100;
    private static final int BRIDGE_PAGE = 5;

    private File manifestFile() { return new File(backupDir, "manifest.json"); }
    private File manifestBakFile() { return new File(backupDir, "manifest.bak"); }
    private File partFile(int i) { return new File(backupDir, String.format(java.util.Locale.US, "part-%03d.json", i)); }

    /** Kirim total arsip ke web agar scroll lazy tahu batasnya. */
    private void setNativeTotal() {
        readManifest();
        final int total = manifestCount;
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                            + "if(window.__imageStudio&&window.__imageStudio.setNativeTotal)"
                            + "window.__imageStudio.setNativeTotal(" + total + ");"
                            + "}catch(e){}})();",
                    null);
        } catch (Exception ignored) {}
    }

    /** Checkpoint: start + jeda. Murah bila sig sama (lewati). */
    private void pullBackupFromWeb() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                            + "if(!window.__imageStudio||!window.__imageStudio.getStateMeta)return 'null';"
                            + "return JSON.stringify(window.__imageStudio.getStateMeta());"
                            + "}catch(e){return 'null';}})();",
                    value -> io.execute(() -> onStateMeta(unescapeJsString(value))));
        } catch (Exception ignored) {
        }
    }

    private void onStateMeta(String metaJson) {
        try {
            if (metaJson == null) return;
            JSONObject m = new JSONObject(metaJson);
            int webLen = m.optInt("len", -1);
            int webRev = m.optInt("rev", 0);
            String webSig = m.optString("sig", "");
            if (webLen < 0 || webSig.isEmpty()) return;
            readManifest();
            if (webSig.equals(manifestSig)) {
                trace("backup sama, lewati (" + webLen + ")");
                // Arsip lebih baru dari web (jarang): dorong 10 pertama.
                if (manifestRev > webRev && manifestCount > 0) pushFirstItems();
                return;
            }
            syncAllPaged(webLen, webRev, webSig);
        } catch (Exception e) {
            trace("backup meta gagal: " + e);
        }
    }

    /** Tarik SEMUA item 5-per-panggil, tulis per 100 ke part file. Kosong pun ditulis (anti hidup-lagi). */
    private void syncAllPaged(int webLen, int webRev, String webSig) {
        try {
            java.util.ArrayList<JSONObject> batch = new java.util.ArrayList<JSONObject>(BACKUP_PART_SIZE);
            int part = 0;
            int got = 0;
            for (int off = 0; off < webLen; off += BRIDGE_PAGE) {
                JSONArray arr = fetchPage(off, BRIDGE_PAGE);
                if (arr == null || arr.length() == 0) break;
                for (int i = 0; i < arr.length(); i++) {
                    try { batch.add(arr.getJSONObject(i)); } catch (Exception ignored) {}
                }
                got += arr.length();
                while (batch.size() >= BACKUP_PART_SIZE) {
                    writePart(part++, new JSONArray(batch.subList(0, BACKUP_PART_SIZE)));
                    batch = new java.util.ArrayList<JSONObject>(batch.subList(BACKUP_PART_SIZE, batch.size()));
                }
                if (arr.length() < BRIDGE_PAGE) break;
            }
            if (!batch.isEmpty() || got == 0) writePart(part++, new JSONArray(batch));
            // Bersihkan sisa part basi + tulis manifest (bak = manifest lama).
            int parts = Math.max(1, part);
            cleanupParts(parts);
            writeManifest(webLen == 0 ? 0 : got, webRev, webSig, parts);
            readManifest();
            setNativeTotal();
            trace("backup tulis: " + got + " item, " + parts + " part");
        } catch (Exception e) {
            trace("backup sync gagal: " + e);
        }
    }

    /** Ambil 1 halaman dari web; gagal -> coba 1-per-1; tetap gagal -> null. */
    private JSONArray fetchPage(int off, int lim) {
        try {
            String raw = evalBridge(
                    "(function(){try{return window.__imageStudio.getItemsJSON(" + off + "," + lim + ");}catch(e){return '[]';}})();");
            if (raw == null) return null;
            JSONArray arr = new JSONArray(raw);
            if (arr.length() > 0) return arr;
            return arr;
        } catch (Exception e) {
            // Fallback wajib: 1-per-1.
            try {
                JSONArray out = new JSONArray();
                for (int k = 0; k < lim; k++) {
                    try {
                        String one = evalBridge(
                                "(function(){try{return window.__imageStudio.getItemsJSON(" + (off + k) + ",1);}catch(e){return '[]';}})();");
                        if (one == null) continue;
                        JSONArray a = new JSONArray(one);
                        if (a.length() > 0) out.put(a.get(0));
                    } catch (Exception ignored) {}
                }
                return out;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** Evaluasi JS sinkron di thread IO (tunggu hasil via latch). */
    private String evalBridge(final String js) {
        final String[] box = new String[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try {
            main.post(() -> {
                try {
                    web.evaluateJavascript(js, v -> {
                        box[0] = unescapeJsString(v);
                        latch.countDown();
                    });
                } catch (Exception e) {
                    latch.countDown();
                }
            });
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return box[0];
    }

    private static String unescapeJsString(String v) {
        if (v == null || v.equals("null")) return null;
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            try {
                // Respons evaluateJavascript dibungkus string JSON.
                return new JSONObject("{\"v\":" + v + "}").optString("v", null);
            } catch (Exception e) {
                return null;
            }
        }
        return v;
    }

    private synchronized void writePart(int idx, JSONArray arr) {
        try {
            File f = partFile(idx);
            File tmp = new File(backupDir, "part.tmp");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (Exception e) {
            trace("backup part gagal: " + e);
        }
    }

    private synchronized void cleanupParts(int keep) {
        try {
            for (int i = keep; i < keep + 50; i++) {
                File f = partFile(i);
                if (f.exists() && !f.delete()) break;
                if (!f.exists()) {
                    // 3 lubang beruntun = habis.
                    int miss = 0;
                    for (int j = i; j < i + 3; j++) if (!partFile(j).exists()) miss++;
                    if (miss == 3) break;
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void writeManifest(int count, int rev, String sig, int parts) {
        try {
            File cur = manifestFile();
            File bak = manifestBakFile();
            if (cur.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cur.renameTo(bak); // manifest lama = fallback, WAJIB ada
            }
            JSONObject m = new JSONObject();
            m.put("count", count);
            m.put("rev", rev);
            m.put("sig", sig);
            m.put("parts", parts);
            File tmp = new File(backupDir, "manifest.tmp");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(m.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(cur);
        } catch (Exception e) {
            trace("manifest tulis gagal: " + e);
        }
    }

    /** Baca manifest; rusak/hilang -> fallback .bak (dipakai, bukan hiasan). */
    private synchronized void readManifest() {
        try {
            String raw = readWhole(manifestFile());
            if (raw == null) raw = readWhole(manifestBakFile());
            if (raw == null) { manifestSig = null; manifestCount = 0; manifestRev = 0; return; }
            JSONObject m = new JSONObject(raw);
            manifestCount = m.optInt("count", 0);
            manifestRev = m.optInt("rev", 0);
            manifestSig = m.optString("sig", null);
            if (manifestSig != null && manifestSig.isEmpty()) manifestSig = null;
        } catch (Exception e) {
            try {
                String raw = readWhole(manifestBakFile());
                if (raw != null) {
                    JSONObject m = new JSONObject(raw);
                    manifestCount = m.optInt("count", 0);
                    manifestRev = m.optInt("rev", 0);
                    manifestSig = m.optString("sig", null);
                    trace("manifest utama rusak, pakai .bak");
                    return;
                }
            } catch (Exception ignored) {}
            manifestSig = null;
            manifestCount = 0;
            manifestRev = 0;
        }
    }

    private static String readWhole(File f) {
        InputStream in = null;
        try {
            if (f == null || !f.exists()) return null;
            long len = f.length();
            if (len <= 0 || len > 64 * 1024 * 1024) return null;
            in = new FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.min(len, 1 << 20));
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    /** Dorong 10 pertama arsip saat arsip lebih baru dari web (jarang). */
    private void pushFirstItems() {
        io.execute(() -> {
            try {
                JSONArray first = new JSONArray();
                for (int p = 0; first.length() < 10; p++) {
                    String raw = readWhole(partFile(p));
                    if (raw == null) break;
                    try {
                        JSONArray a = new JSONArray(raw);
                        for (int i = 0; i < a.length() && first.length() < 10; i++) first.put(a.get(i));
                        if (a.length() == 0) break;
                    } catch (Exception e) { break; }
                }
                if (first.length() == 0) return;
                final String json = first.toString();
                main.post(() -> {
                    try {
                        web.evaluateJavascript(
                                "(function(){try{"
                                        + "var a=" + json + ";"
                                        + "if(window.__imageStudio&&window.__imageStudio.restoreItems)"
                                        + "window.__imageStudio.restoreItems(a,false);}catch(e){}})();",
                                null);
                    } catch (Exception ignored) {}
                });
            } catch (Exception e) {
                trace("push awal gagal: " + e);
            }
        });
    }

    /** Umpan balik pill web (bukan notifikasi shade): hasil simpan file. */
    private void onNativeSaved(final String name, final boolean ok) {
        try {
            final String safe = JSONObject.quote(name == null ? "" : name);
            main.post(() -> {
                try {
                    web.evaluateJavascript(
                            "(function(){try{"
                                    + "if(window.__imageStudio&&window.__imageStudio.onNativeSaved)"
                                    + "window.__imageStudio.onNativeSaved(" + safe + "," + (ok ? "true" : "false") + ");"
                                    + "}catch(e){}})();",
                            null);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        trace(ok ? ("file ok: " + name) : ("file gagal: " + name));
    }

    // ---------------- dialog retry + crash log (murah, wajib) ----------------

    private void showRetryDialog() {
        try {
            if (isFinishing()) return;
            try { if (retryDlg != null) retryDlg.dismiss(); } catch (Exception ignored) {}
            retryDlg = new AlertDialog.Builder(this)
                    .setTitle("Gagal memuat")
                    .setMessage("Periksa koneksi lalu coba lagi.")
                    .setPositiveButton("Coba lagi", (di, w) -> {
                        try {
                            pageDone = false;
                            pageFailed = false;
                            web.reload();
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("Tutup", (di, w) -> finish())
                    .setCancelable(true)
                    .create();
            retryDlg.show();
        } catch (Exception ignored) {}
    }

    private void hookCrashLogger() {
        try {
            final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                try {
                    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                    File dir = new File(rootDir != null ? rootDir : getFilesDir(), "error");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, "crash-" + df.format(new java.util.Date()) + ".txt");
                    try (FileOutputStream o = new FileOutputStream(f, true)) {
                        java.io.PrintWriter pw = new java.io.PrintWriter(o);
                        e.printStackTrace(pw);
                        pw.println("---");
                        pw.flush();
                    }
                } catch (Exception ignored) {}
                try { if (def != null) def.uncaughtException(t, e); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- download

    private void handleDownload(String url, String mimeType) {
        if (url == null) return;
        io.execute(() -> {
            try {
                // Dua tombol, dua nasib: save -> galeri (web+backup), unduh -> file.
                // Background diam: hasil lapor ke pill web, bukan notif shade.
                if (url.startsWith("data:")) {
                    String n = saveDataUrl(url);
                    onNativeSaved(n, true);
                } else downloadHttp(url);
            } catch (Exception e) {
                trace("unduh gagal: " + e.getMessage());
                onNativeSaved("", false);
            }
        });
    }

    /** Nama: Generete_<timestamp>_<n>.<ext>, anti-tabrakan. */
    private File nextGenerationFile(String ext) {
        if (ext == null || ext.isEmpty()) ext = "jpg";
        for (int i = 0; i < 1000; i++) {
            int n = fileCounter.incrementAndGet();
            File f = new File(generationDir,
                    "Generete_" + System.currentTimeMillis() + "_" + n + "." + ext);
            if (!f.exists()) return f;
        }
        return new File(generationDir,
                "Generete_" + System.currentTimeMillis() + "_" + System.nanoTime() + "." + ext);
    }

    private static String extForMime(String mime) {
        if (mime == null) return "jpg";
        mime = mime.toLowerCase();
        if (mime.contains("png")) return "png";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("gif")) return "gif";
        return "jpg";
    }

    private String saveDataUrl(String dataUrl) throws Exception {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new Exception("data URL rusak");
        String head = dataUrl.substring(0, comma);
        String ext = "jpg";
        if (head.contains("image/")) {
            int s = head.indexOf("image/") + 6;
            int e = head.indexOf(';', s);
            ext = extForMime(e > s ? head.substring(s, e) : "");
        }
        byte[] raw = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        File out = nextGenerationFile(ext);
        try (InputStream in = new ByteArrayInputStream(raw);
             OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            try { ((FileOutputStream) o).getFD().sync(); } catch (Exception ignored) {}
        }
        return out.getName();
    }

    private void downloadHttp(String url) {
        Context c = this;
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            // VISIBLE saja (progres jalan, hilang sendiri): tanpa sisa di shade.
            r.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE);
            String name = "Generete_" + System.currentTimeMillis()
                    + "_" + fileCounter.incrementAndGet() + ".jpg";
            if (usingFallback || Build.VERSION.SDK_INT >= 29) {
                File out = new File(generationDir, name);
                r.setDestinationUri(Uri.fromFile(out));
            } else {
                // Pola NorVex-R: tipe tanpa garis miring, subpath yang bersarang.
                // (Tipe berisi "/" ditolak di sebagian HP = gagal tulis legacy.)
                r.setDestinationInExternalPublicDir(
                        "perchance_app", "Generete_Image/Generation/" + name);
            }
            long did = dm.enqueue(r);
            synchronized (httpNames) { httpNames.put(did, name); }
            pollDmProgress();
            trace("mengunduh: " + name);
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- util

    private void toast(String msg) {
        main.post(() -> {
            try {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }

    private void trace(String msg) {
        try {
            String ts = "";
            try {
                ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " ";
            } catch (Exception ignored) {}
            final String line = ts + msg;
            File dir = new File(rootDir, "error");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "trace.txt");
            try {
                // Rotasi murah: >512KB -> trace.old, agar tak membengkak.
                if (f.exists() && f.length() > 512 * 1024) {
                    File old = new File(dir, "trace.old");
                    //noinspection ResultOfMethodCallIgnored
                    if (old.exists()) old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
            } catch (Exception ignored) {}
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /** Interface opsional buat web masa depan (saat ini web pakai pull model). */
    // ---------------------------------------------------------------- update otomatis

    private void checkUpdate() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastUpdateCheckAt < 10 * 60 * 1000) return;
            lastUpdateCheckAt = now;
            new Thread(() -> {
                HttpURLConnection c = null;
                InputStream in = null;
                try {
                    URL u = new URL("https://api.github.com/repos/" + UPDATE_REPO + "/releases/latest");
                    c = (HttpURLConnection) u.openConnection();
                    c.setRequestProperty("User-Agent", "NorVex-I");
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
                    if (c.getResponseCode() != 200) return;
                    in = c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                    JSONObject rel = new JSONObject(bos.toString("UTF-8"));
                    String tag = rel.optString("tag_name", "");
                    int remote = parseTagCode(tag);
                    if (remote <= 0) return;
                    int mine = myVersionCode();
                    if (remote <= mine) { trace("cek update: sudah terbaru"); return; }
                    String apkUrl = null;
                    try { apkUrl = rel.getJSONArray("assets").getJSONObject(0).getString("browser_download_url"); } catch (Exception ignored) {}
                    if (apkUrl == null) return;
                    final String fUrl = apkUrl, fTag = tag;
                    trace("update tersedia: " + fTag);
                    main.post(() -> onUpdateFound(fUrl, fTag));
                } catch (Exception e) { trace("update-check gagal: " + e); }
                finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    try { if (c != null) c.disconnect(); } catch (Exception ignored) {}
                }
            }).start();
        } catch (Throwable t) { trace("update-check gagal: " + t); }
    }

    private int parseTagCode(String tag) {
        try {
            tag = tag.trim();
            if (tag.startsWith("b") || tag.startsWith("v")) tag = tag.substring(1);
            return Integer.parseInt(tag.replaceAll("[^0-9]", ""));
        } catch (Exception e) { return -1; }
    }

    private int myVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    // Update OTOMATIS: ketemu versi baru -> langsung unduh di dalam aplikasi
    // (tanpa buka GitHub, tanpa ditanya, tanpa dialog). Kalau file tag ini
    // sudah ada -> langsung pasang. Kalau sedang mengunduh -> biarkan jalan.
    private void onUpdateFound(String apkUrl, String tag) {
        try {
            File apk = new File(new File(getExternalFilesDir(null), "update"), "norvex-i.apk");
            SharedPreferences sp = getSharedPreferences("nv-i", MODE_PRIVATE);
            String have = sp.getString(UPD_PREF_TAG, "");
            if (tag.equals(have) && apk.exists() && apk.length() > 0) {
                final File fApk = apk;
                main.post(() -> promptInstall(fApk));
                return;
            }
            if (updateDownloadId != -1) { trace("update masih mengunduh: " + tag); return; }
            // Hapus APK lama dulu: kalau unduhan BARU gagal, file basi bertag
            // lama JANGAN dikira "siap dipasang".
            try {
                File stale = new File(new File(getExternalFilesDir(null), "update"), "norvex-i.apk");
                if (stale.exists()) stale.delete();
            } catch (Exception ignored) {}
            sp.edit().putString(UPD_PREF_TAG, tag).apply();
            trace("update mengunduh: " + tag);
            startUpdateDownload(apkUrl);
        } catch (Throwable t) { trace("update-found gagal: " + t); }
    }

    private void startUpdateDownload(String apkUrl) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apkUrl));
            r.setTitle("NorVex-I update");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            File dir = new File(getExternalFilesDir(null), "update");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            r.setDestinationInExternalFilesDir(this, "update", "norvex-i.apk");
            updateDownloadId = dm.enqueue(r);
            pollDmProgress();
        } catch (Throwable t) {
            trace("update-dl gagal: " + t);
        }
    }

    // Intip progres DownloadManager tiap detik -> persen ke pill web
    // (bukan notif shade). Berhenti sendiri saat tak ada kerjaan / activity mati.
    private volatile boolean destroyed = false;
    private boolean dmPolling = false;
    private final java.util.Map<Long, Integer> dmLastPct = new java.util.HashMap<Long, Integer>();

    private void pollDmProgress() {
        try {
            if (dmPolling) return;
            dmPolling = true;
            new Thread(() -> {
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    while (true) {
                        if (destroyed) break;
                        java.util.Map<Long, String> jobs = new java.util.HashMap<Long, String>();
                        synchronized (httpNames) { jobs.putAll(httpNames); }
                        long upd = updateDownloadId;
                        if (upd != -1) jobs.put(upd, "update");
                        if (jobs.isEmpty() || dm == null) break;
                        for (java.util.Map.Entry<Long, String> en : jobs.entrySet()) {
                            try {
                                android.database.Cursor c = null;
                                try {
                                    c = dm.query(new DownloadManager.Query().setFilterById(en.getKey()));
                                    if (c != null && c.moveToFirst()) {
                                        long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                                        long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                                        if (total > 0) {
                                            int pct = (int) (done * 100 / total);
                                            Integer last;
                                            synchronized (dmLastPct) { last = dmLastPct.get(en.getKey()); }
                                            if (last == null || pct - last >= 2 || pct >= 100) {
                                                synchronized (dmLastPct) { dmLastPct.put(en.getKey(), pct); }
                                                onNativeProgress(en.getValue(), pct);
                                            }
                                        }
                                    }
                                } finally { try { if (c != null) c.close(); } catch (Exception ignored) {} }
                            } catch (Exception ignored) {}
                        }
                        try { Thread.sleep(1000); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
                dmPolling = false;
            }).start();
        } catch (Exception ignored) {}
    }

    private void onNativeProgress(final String name, final int pct) {
        try {
            final String safe = JSONObject.quote(name == null ? "" : name);
            main.post(() -> {
                try {
                    web.evaluateJavascript(
                            "(function(){try{"
                                    + "if(window.__imageStudio&&window.__imageStudio.onNativeProgress)"
                                    + "window.__imageStudio.onNativeProgress(" + safe + "," + pct + ");"
                                    + "}catch(e){}})();",
                            null);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            try {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == updateDownloadId && id != -1) {
                    updateDownloadId = -1;
                    synchronized (dmLastPct) { dmLastPct.remove(id); }
                    File apk = new File(ctx.getExternalFilesDir(null), "update/norvex-i.apk");
                    // SUKSES beneran (bukan gagal/batal): status DownloadManager +
                    // file ada + berisi. Tanpa ini, APK setengah/basi dikira siap.
                    if (dmSuccess(ctx, id) && apk.exists() && apk.length() > 0) {
                        trace("update siap, pasang langsung");
                        final File fApk = apk;
                        main.post(() -> promptInstall(fApk));
                    } else {
                        trace("update-dl: file hilang/gagal");
                    }
                    return;
                }
                // Unduhan gambar: lapor ke pill web, diam di shade.
                String nm;
                synchronized (httpNames) { nm = httpNames.remove(id); }
                synchronized (dmLastPct) { dmLastPct.remove(id); }
                if (nm != null) {
                    final boolean ok = dmSuccess(ctx, id);
                    final String fNm = nm;
                    main.post(() -> onNativeSaved(fNm, ok));
                }
            } catch (Throwable t) { trace("receiver gagal: " + t); }
        }
    };

    // Status beneran unduhan DownloadManager (jangan percaya event selesai saja).
    private boolean dmSuccess(Context ctx, long id) {
        android.database.Cursor c = null;
        try {
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return false;
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                return st == DownloadManager.STATUS_SUCCESSFUL;
            }
        } catch (Exception ignored) {}
        finally { try { if (c != null) c.close(); } catch (Exception ignored) {} }
        return false;
    }

    private void promptInstall(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    if (!getPackageManager().canRequestPackageInstalls()) {
                        Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        try { startActivity(s); } catch (Exception ignored) {}
                        toast("Izinkan aplikasi ini memasang update, lalu buka lagi aplikasinya");
                        return;
                    }
                } catch (Exception ignored) {}
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.norvex.image.fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(i); }
            catch (android.content.ActivityNotFoundException e) { trace("install: " + e); }
        } catch (Throwable t) { trace("install gagal: " + t); }
    }

    public class NativeBridge {
        /** Ringkasan arsip buat scroll lazy web: {"count":N,"rev":R}. */
        @JavascriptInterface
        public String getBackupInfo() {
            try {
                readManifest();
                JSONObject m = new JSONObject();
                m.put("count", manifestCount);
                m.put("rev", manifestRev);
                return m.toString();
            } catch (Exception e) {
                return "{\"count\":0,\"rev\":0}";
            }
        }

        /** Jendela arsip buat scroll lazy: maks 5 item per panggil (batas binder). */
        @JavascriptInterface
        public String getBackupRangeJSON(int offset, int limit) {
            try {
                int off = Math.max(0, offset);
                int lim = Math.max(1, Math.min(5, limit));
                readManifest();
                JSONArray out = new JSONArray();
                int idx = off;
                int end = off + lim;
                while (idx < end) {
                    int part = idx / BACKUP_PART_SIZE;
                    int at = idx % BACKUP_PART_SIZE;
                    String raw = readWhole(partFile(part));
                    if (raw == null) break;
                    JSONArray a;
                    try { a = new JSONArray(raw); } catch (Exception e) { break; }
                    if (at >= a.length()) break;
                    for (int i = at; i < a.length() && idx < end; i++, idx++) {
                        try { out.put(a.get(i)); } catch (Exception ignored) { idx++; }
                    }
                    if (a.length() < BACKUP_PART_SIZE) break;
                }
                return out.toString();
            } catch (Exception e) {
                return "[]";
            }
        }
    }
}
