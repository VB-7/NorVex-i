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
    private static final String PRIMARY_ROOT = "/storage/emulated/0/perchance_app/Generete_Image";
    private static final String BACKUP_NAME = "backup_gallery.json";
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
    private final Runnable backupTick = this::pushBackupToWeb;
    private android.widget.FrameLayout webRoot = null; // wadah WebView (buat ulang saat renderer mati)
    private boolean pageDone = false;

    // ---- update otomatis (pola NorVex-R) ----
    private long updateDownloadId = -1;
    private AlertDialog updateDialog = null;

    private File rootDir;       // Generete_Image/ (primer atau fallback)
    private File generationDir; // Generation/
    private File backupDir;     // Backup_Gallery/
    private boolean usingFallback;

    // ---------------------------------------------------------------- storage

    private void resolveStorage() {
        File primary = new File(PRIMARY_ROOT);
        File gen = new File(primary, "Generation");
        File bak = new File(primary, "Backup_Gallery");
        boolean ok = false;
        try {
            ok = (gen.isDirectory() || gen.mkdirs()) && (bak.isDirectory() || bak.mkdirs());
            if (ok) {
                File probe = new File(gen, ".probe");
                try (OutputStream o = new FileOutputStream(probe)) {
                    o.write(1);
                }
                ok = probe.delete();
            }
        } catch (Exception ignored) {
            ok = false;
        }
        if (ok) {
            rootDir = primary;
            usingFallback = false;
        } else {
            // Fallback: ASD, pola yang sama seperti NorVex-r.
            File asd = new File(getExternalFilesDir(null), "NorVex-I");
            gen = new File(asd, "Generation");
            bak = new File(asd, "Backup_Gallery");
            //noinspection ResultOfMethodCallIgnored
            gen.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            bak.mkdirs();
            rootDir = asd;
            usingFallback = true;
        }
        generationDir = new File(rootDir, "Generation");
        backupDir = new File(rootDir, "Backup_Gallery");
    }

    private File backupFile() {
        return new File(backupDir, BACKUP_NAME);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        resolveStorage();
        ensureStoragePerm(); // minta izin tulis SEKALI (HP Android 9/10)

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
            toast(usingFallback
                    ? "Folder utama gagal ditulis — pakai ASD"
                    : "Storage: perchance_app/Generete_Image");

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
                injectCleanCss();
                pullBackupFromWeb();
                pushBackupFileToWeb(); // restore galeri dari file backup
                main.removeCallbacks(backupTick);
                main.postDelayed(backupTick, 4000);
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
        pushBackupToWeb();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(backupTick);
        io.shutdown();
        try { unregisterReceiver(dlReceiver); } catch (Exception ignored) {}
        try { if (updateDialog != null) updateDialog.dismiss(); } catch (Exception ignored) {}
        try {
            web.destroy();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Dialog update paling atas: tutup dulu (pola NorVex-R).
        try {
            if (updateDialog != null && updateDialog.isShowing()) {
                try { updateDialog.dismiss(); } catch (Exception ignored) {}
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
                    toast(usingFallback
                            ? "Izin OK, tapi folder utama tetap gagal — pakai ASD"
                            : "Izin penyimpanan OK — file di perchance_app/Generete_Image");
                    trace("izin tulis diberikan");
                } else {
                    toast("Tanpa izin, file tersimpan di folder aplikasi (bukan perchance_app)");
                    trace("izin tulis ditolak");
                }
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

    /** Tarik galeri dari web → tulis backup (dipakai berkala + saat jeda). */
    private void pushBackupToWeb() {
        pullBackupFromWeb();
    }

    private void pullBackupFromWeb() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                            + "if(!window.__imageStudio||!window.__imageStudio.getGallery)return 'null';"
                            + "return JSON.stringify(window.__imageStudio.getGallery());"
                            + "}catch(e){return 'null';}})();",
                    value -> io.execute(() -> writeBackupJson(unescapeJsString(value))));
        } catch (Exception ignored) {
        }
    }

    /** Dorong backup ke web setelah halaman siap. */
    private void pushBackupFileToWeb() {
        io.execute(() -> {
            String json = readBackupJson();
            if (json == null || json.equals("[]")) return;
            main.post(() -> {
                try {
                    web.evaluateJavascript(
                            "(function(){try{"
                                    + "var a=" + json + ";"
                                    + "if(window.__imageStudio&&window.__imageStudio.restoreItems)"
                                    + "window.__imageStudio.restoreItems(a);}catch(e){}})();",
                            null);
                } catch (Exception ignored) {
                }
            });
        });
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

    // ---------------------------------------------------------------- backup file

    private synchronized void writeBackupJson(String galleryJson) {
        if (galleryJson == null) return;
        try {
            // Validasi: harus array JSON.
            JSONArray arr = new JSONArray(galleryJson);
            if (arr.length() == 0) return;
            File f = backupFile();
            File bak = new File(backupDir, BACKUP_NAME + ".bak");
            if (f.exists()) copyFile(f, bak);
            try (OutputStream o = new FileOutputStream(f)) {
                o.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            trace("backup gagal: " + e);
        }
    }

    private synchronized String readBackupJson() {
        try {
            File f = backupFile();
            if (!f.exists()) return null;
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) Math.min(f.length(), 32 * 1024 * 1024)];
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void copyFile(File a, File b) {
        try (InputStream in = new FileInputStream(a);
             OutputStream out = new FileOutputStream(b)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- download

    private void handleDownload(String url, String mimeType) {
        if (url == null) return;
        io.execute(() -> {
            try {
                if (url.startsWith("data:")) saveDataUrl(url);
                else downloadHttp(url);
            } catch (Exception e) {
                toast("Unduh gagal: " + e.getMessage());
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

    private void saveDataUrl(String dataUrl) throws Exception {
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
        }
        toast("Tersimpan: " + out.getName());
    }

    private void downloadHttp(String url) {
        Context c = this;
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String name = "Generete_" + System.currentTimeMillis()
                    + "_" + fileCounter.incrementAndGet() + ".jpg";
            if (usingFallback || Build.VERSION.SDK_INT >= 29) {
                File out = new File(generationDir, name);
                r.setDestinationUri(Uri.fromFile(out));
            } else {
                r.setDestinationInExternalPublicDir(
                        "perchance_app/Generete_Image", "Generation/" + name);
            }
            dm.enqueue(r);
            toast("Mengunduh: " + name);
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
            File dir = new File(rootDir, "error");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "trace.txt");
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
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
    // (tanpa buka GitHub, tanpa ditanya). Kalau file tag ini sudah ada -> ajak
    // pasang. Kalau sedang mengunduh -> biarkan jalan.
    private void onUpdateFound(String apkUrl, String tag) {
        try {
            File apk = new File(new File(getExternalFilesDir(null), "update"), "norvex-i.apk");
            SharedPreferences sp = getSharedPreferences("nv-i", MODE_PRIVATE);
            String have = sp.getString(UPD_PREF_TAG, "");
            if (tag.equals(have) && apk.exists() && apk.length() > 0) {
                showUpdateReadyDialog(apk, tag);
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

    private void showUpdateReadyDialog(final File apk, String tag) {
        try {
            try { if (updateDialog != null) updateDialog.dismiss(); } catch (Exception ignored) {}
            updateDialog = new AlertDialog.Builder(this)
                    .setTitle("Update " + tag + " siap dipasang")
                    .setMessage("Versi " + tag + " sudah terunduh. Pasang sekarang?")
                    .setPositiveButton("Pasang", (di, w) -> promptInstall(apk))
                    .setNegativeButton("Nanti", null)
                    .setCancelable(true)
                    .create();
            updateDialog.show();
            trace("update siap: " + tag);
        } catch (Throwable t) { trace("update-dlg gagal: " + t); }
    }

    private void startUpdateDownload(String apkUrl) {
        try {
            toast("Mengunduh update…");
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apkUrl));
            r.setTitle("NorVex-I update");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            File dir = new File(getExternalFilesDir(null), "update");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            r.setDestinationInExternalFilesDir(this, "update", "norvex-i.apk");
            updateDownloadId = dm.enqueue(r);
        } catch (Throwable t) {
            trace("update-dl gagal: " + t);
        }
    }

    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            try {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != updateDownloadId || id == -1) return; // unduhan gambar: biarkan notif sistem
                updateDownloadId = -1;
                File apk = new File(ctx.getExternalFilesDir(null), "update/norvex-i.apk");
                String tag = "";
                try { tag = ctx.getSharedPreferences("nv-i", MODE_PRIVATE).getString(UPD_PREF_TAG, ""); } catch (Exception ignored) {}
                // SUKSES beneran (bukan gagal/batal): status DownloadManager +
                // file ada + berisi. Tanpa ini, APK setengah/basi dikira siap.
                if (dmSuccess(ctx, id) && apk.exists() && apk.length() > 0) {
                    final String fTag = tag;
                    main.post(() -> showUpdateReadyDialog(apk, fTag));
                } else {
                    toast("Unduhan update gagal, coba lagi nanti");
                    trace("update-dl: file hilang/gagal");
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
            catch (android.content.ActivityNotFoundException e) { toast("Tak ada pemasang paket"); trace("install: " + e); }
        } catch (Throwable t) { trace("install gagal: " + t); }
    }

    public class NativeBridge {
        @JavascriptInterface
        public String getBackup() {
            String j = readBackupJson();
            return j == null ? "[]" : j;
        }
    }
}
