package com.sandbox.box;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.system.Os;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 41;
    private static final int REQ_IMPORT = 42;
    private static final int HTTP_PORT = 8022;

    private static final String BOOTSTRAP_URL =
            "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip";
    private static final String BOOTSTRAP_SHA256 =
            "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3";
    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    /** پشتیبان اروپایی بوت‌استرپ — اگر گیت‌هاب (آمریکایی) در دسترس نبود */
    private static final String FDROID_APK_URL = "https://f-droid.org/repo/com.termux_1002.apk";
    private static final String FDROID_BOOTSTRAP_ENTRY = "lib/arm64-v8a/libtermux-bootstrap.so";

    private WebView web;
    private File prefix, home, docsDir;
    private ExecutorService cpu;

    private ServerSocket serverSocket;
    private volatile boolean serverOn = false;
    private volatile Process tunnelProc = null;
    private volatile String tunnelUrl = "";
    private volatile boolean installing = false;
    private volatile boolean tunnelAuto = false;
    private volatile int tunnelTries = 0;
    private PowerManager.WakeLock wakeLock;
    private String pendingExport;

    /* ================================================== */

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File files = getFilesDir();
        prefix = new File(files, "usr");
        home = new File(files, "home");
        docsDir = new File(files, "documents");
        if (!home.exists()) home.mkdirs();
        if (!docsDir.exists()) docsDir.mkdirs();
        cpu = Executors.newFixedThreadPool(2);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1220);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.d("SandBox", cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        stopTunnel();
        stopServer();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (cpu != null) cpu.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ================= ابزارهای عمومی ================= */

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void js(final String call) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web != null) web.evaluateJavascript("try{" + call + "}catch(e){}", null);
            }
        });
    }

    private File docs() { return docsDir; }

    private File safe(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.length() > 200) return null;
        if (n.contains("/") || n.contains("\\") || n.contains("..") || n.contains("\u0000")) return null;
        return new File(docsDir, n);
    }

    private String token() {
        try {
            File f = new File(getFilesDir(), "token");
            if (f.isFile()) {
                String t = readText(f).trim();
                if (!t.isEmpty()) return t;
            }
            String t = java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            writeText(f, t);
            return t;
        } catch (Exception e) {
            return "no-token";
        }
    }

    private static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean arm64() {
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) return true;
        return false;
    }

    public boolean bootstrapReady() {
        return new File(prefix, "bin/bash").canExecute();
    }

    public boolean pythonReady() {
        return new File(prefix, "bin/python3").canExecute() || findPython() != null;
    }

    private File findPython() {
        File bin = new File(prefix, "bin");
        File[] fs = bin.listFiles();
        if (fs == null) return null;
        for (File f : fs)
            if (f.getName().startsWith("python3.") && f.getName().endsWith(".0")) return f;
        for (File f : fs)
            if (f.getName().startsWith("python3")) return f;
        return null;
    }

    /* ================= موتور اجرای شل ================= */

    private static class ExecOut {
        int exit = -1;
        String out = "";
        boolean timedOut = false;
    }

    /** تنظیم پروکسی اختیاری کاربر (فایل proxy.txt) */
    private String proxySetting() {
        try {
            File f = new File(getFilesDir(), "proxy.txt");
            return f.isFile() ? readText(f).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void buildEnv(ProcessBuilder pb) {
        java.util.Map<String, String> env = pb.environment();
        env.put("PATH", prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin:/vendor/bin");
        env.put("LD_LIBRARY_PATH", prefix.getAbsolutePath() + "/lib");
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", prefix.getAbsolutePath() + "/tmp");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("TERM", "dumb");
        env.put("PYTHONHOME", prefix.getAbsolutePath());
        env.put("TMPDIR", prefix.getAbsolutePath() + "/tmp");
        env.put("ANDROID_ROOT", "/system");
        env.put("SHELL", prefix.getAbsolutePath() + "/bin/bash");
        // پروکسی اختیاری: همه‌ی دستورها (curl، pip، git، …) از این مسیر می‌روند
        String px = proxySetting();
        if (px != null && !px.isEmpty()) {
            env.put("http_proxy", px);
            env.put("https_proxy", px);
            env.put("all_proxy", px);
            env.put("HTTP_PROXY", px);
            env.put("HTTPS_PROXY", px);
            env.put("ALL_PROXY", px);
            env.put("no_proxy", "127.0.0.1,localhost");
            env.put("NO_PROXY", "127.0.0.1,localhost");
        }
    }

    /** اجرای یک دستور با bash بوت‌استرپ؛ خروجی ادغام‌شده stdout+stderr */
    public ExecOut runShell(String cmd, String cwd, String stdinData, int timeoutSec) {
        ExecOut r = new ExecOut();
        if (!bootstrapReady()) {
            r.out = "محیط لینوکس نصب نیست — اول از داشبورد نصبش کن.";
            return r;
        }
        Process p = null;
        try {
            File dir = home;
            if (cwd != null) {
                File c = new File(cwd);
                if (c.isDirectory()) dir = c;
            }
            ProcessBuilder pb = new ProcessBuilder(prefix.getAbsolutePath() + "/bin/bash", "-c", cmd);
            buildEnv(pb);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            p = pb.start();

            if (stdinData != null) {
                OutputStream pi = p.getOutputStream();
                pi.write(stdinData.getBytes(StandardCharsets.UTF_8));
                pi.close();
            }

            final Process proc = p;
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try (InputStream in = proc.getInputStream()) {
                        byte[] b = new byte[8192];
                        int n;
                        long total = 0;
                        while ((n = in.read(b)) > 0) {
                            if (total < 400000) { buf.write(b, 0, n); total += n; }
                            else { /* فقط ۴۰۰ کیلوبایت آخر نگه می‌داریم */ }
                        }
                    } catch (IOException ignored) { }
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = proc.waitFor(Math.max(5, timeoutSec), TimeUnit.SECONDS);
            if (!finished) {
                r.timedOut = true;
                proc.destroyForcibly();
                reader.join(1000);
                r.out = new String(buf.toByteArray(), StandardCharsets.UTF_8)
                        + "\n⏱ مهلت اجرا تمام شد و دستور متوقف شد.";
                r.exit = -1;
                return r;
            }
            reader.join(1500);
            r.exit = proc.exitValue();
            r.out = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            r.exit = -2;
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            r.out = "خطای اجرا: " + e + "\n" + sw;
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return r;
    }

    /* ================= نصب بوت‌استرپ و بسته‌ها ================= */

    private interface ProgressCb { void call(int pct, String msg); }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private File download(String urlStr, ProgressCb cb, int pctFrom, int pctTo) throws Exception {
        File out = new File(getCacheDir(), "dl_" + System.currentTimeMillis());
        URL url = new URL(urlStr);
        URLConnection c = url.openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "SandBox/2.0");
        int total = c.getContentLength();
        long totalBytes = 1; // دفع تقسیم بر صفر
        long got = 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             FileOutputStream fo = new FileOutputStream(out)) {
            byte[] b = new byte[65536];
            int n; int lastPct = -1;
            while ((n = in.read(b)) > 0) {
                fo.write(b, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (pctFrom + (pctTo - pctFrom) * ((double) got / total));
                    if (pct != lastPct && pct % 2 == 0) { lastPct = pct; cb.call(pct, ""); }
                }
            }
            totalBytes = got;
        }
        if (totalBytes < 1000) throw new Exception("فایل ناقص دریافت شد");
        return out;
    }

    public void installBootstrapAsync() {
        if (installing) return;
        if (!arm64()) {
            js("_installDone(false,'این بیلد فقط برای گوشی‌های arm64 (اغلب گوشی‌های امروزی) است — پردازنده‌ی گوشی: "
                    + Arrays.toString(Build.SUPPORTED_ABIS) + "')");
            return;
        }
        if (bootstrapReady()) {
            js("_installDone(true,'از قبل نصب بوده ✔')");
            return;
        }
        installing = true;
        new Thread(new Runnable() {
            @Override public void run() {
                File bootstrapSrc = null;
                String firstError = "";
                ProgressCb cb = new ProgressCb() {
                    @Override public void call(int pct, String msg) {
                        js("_installProgress(" + pct + "," + JSONObject.quote(msg) + ")");
                    }
                };
                try {
                    // ---- مسیر ۱: گیت‌هاب (رسمی، جدیدترین) ----
                    js("_installProgress(1,'دانلود محیط لینوکس از گیت‌هاب (~۳۰MB)…')");
                    File zip = download(BOOTSTRAP_URL, cb, 2, 45);
                    js("_installProgress(46,'بررسی صحت فایل…')");
                    if (!sha256Hex(zip).equals(BOOTSTRAP_SHA256)) {
                        //noinspection ResultOfMethodCallIgnored
                        zip.delete();
                        throw new Exception("sha256 mismatch (github)");
                    }
                    bootstrapSrc = zip;
                } catch (Exception e1) {
                    firstError = String.valueOf(e1);
                    try {
                        // ---- مسیر ۲: F-Droid (اروپا) — اگر گیت‌هاب بسته بود ----
                        js("_installProgress(2,'گیت‌هاب پاسخ نداد — تلاش از F-Droid اروپا (~۱۰۹MB)…')");
                        File apk = download(FDROID_APK_URL, cb, 2, 42);
                        js("_installProgress(44,'استخراج بوت‌استرپ از بسته‌ی F-Droid…')");
                        bootstrapSrc = extractEntry(apk, FDROID_BOOTSTRAP_ENTRY);
                        //noinspection ResultOfMethodCallIgnored
                        apk.delete();
                    } catch (Exception e2) {
                        installing = false;
                        js("_installDone(false,'هیچ‌کدام از منابع در دسترس نبود: "
                                + JSONObject.quote(firstError) + " | " + JSONObject.quote(String.valueOf(e2)) + "')");
                        return;
                    }
                }
                try {
                    js("_installProgress(48,'در حال باز کردن بسته…')");
                    prefix.mkdirs();
                    home.mkdirs();
                    extractBootstrapZip(bootstrapSrc, cb);
                    //noinspection ResultOfMethodCallIgnored
                    bootstrapSrc.delete();

                    js("_installProgress(97,'راه‌اندازی لینک‌ها…')");
                    makeSymlinks();

                    new File(prefix, "tmp").mkdirs();

                    js("_installProgress(99,'آزمون سیستم…')");
                    ExecOut t = runShell("echo LINUX-OK", null, null, 20);
                    boolean ok = t.out.contains("LINUX-OK");
                    installing = false;
                    if (ok) js("_installDone(true,'محیط لینوکس آماده شد ✔')");
                    else js("_installDone(false,'نصب شد ولی آزمون اجرا جواب نداد: " +
                            JSONObject.quote(t.out.substring(0, Math.min(300, t.out.length()))) + "')");
                } catch (Exception e) {
                    installing = false;
                    js("_installDone(false,'خطا در باز کردن بسته: " + JSONObject.quote(String.valueOf(e)) + "')");
                }
            }
        }).start();
    }

    /** استخراج یک فایل از داخل zip/apk دیگر */
    private File extractEntry(File container, String entryName) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(container);
        try {
            java.util.zip.ZipEntry e = zf.getEntry(entryName);
            if (e == null) throw new Exception("entry not found: " + entryName);
            File out = new File(getCacheDir(), "inner_" + System.currentTimeMillis());
            try (InputStream in = zf.getInputStream(e);
                 FileOutputStream fo = new FileOutputStream(out)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) fo.write(b, 0, n);
            }
            return out;
        } finally {
            zf.close();
        }
    }

    /** باز کردن بوت‌استرپ با ZipFile (با فایل‌های zip معمولی و بوت‌استرپِ داخل apk کار می‌کند) */
    private void extractBootstrapZip(File zip, ProgressCb cb) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            int count = 0;
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                count++;
                String name = e.getName();
                if (name.equals("SYMLINKS.txt")) {
                    File f = new File(getCacheDir(), "symlinks.txt");
                    try (InputStream in = zf.getInputStream(e);
                         FileOutputStream o = new FileOutputStream(f)) {
                        byte[] b = new byte[8192]; int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    }
                    continue;
                }
                File dst = new File(prefix, name);
                String cpath = dst.getCanonicalPath();
                if (!cpath.startsWith(prefix.getCanonicalPath())) continue; // ضد مسیر خطرناک
                if (name.endsWith("/")) {
                    dst.mkdirs();
                    continue;
                }
                dst.getParentFile().mkdirs();
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream o = new FileOutputStream(dst)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                if (name.startsWith("bin/") || name.startsWith("libexec/")) {
                    dst.setExecutable(true, false);
                }
                if (count % 60 == 0) {
                    int pct = 48 + (int) (Math.min(1.0, count / 3000.0) * 48);
                    js("_installProgress(" + pct + ",'باز کردن… " + count + " فایل')");
                }
            }
        } finally {
            zf.close();
        }
    }

    private void makeSymlinks() {
        File sl = new File(getCacheDir(), "symlinks.txt");
        if (!sl.isFile()) return;
        try {
            for (String line : readText(sl).split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("←");
                if (parts.length != 2) continue;
                String target = parts[0].trim()
                        .replace(TERMUX_PREFIX, prefix.getAbsolutePath());
                File link = new File(prefix, parts[1].trim());
                link.getParentFile().mkdirs();
                try {
                    if (!link.exists()) Os.symlink(target, link.getAbsolutePath());
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    public void installPkgsAsync() {
        if (installing || !bootstrapReady()) {
            if (!bootstrapReady()) js("_pkgsDone(false,'اول محیط لینوکس را نصب کن')");
            return;
        }
        installing = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONArray list = new JSONArray(readAsset("pkglist.json"));
                    long total = 0;
                    for (int i = 0; i < list.length(); i++)
                        total += list.getJSONObject(i).optLong("size", 0);
                    long done = 0;
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject p = list.getJSONObject(i);
                        String name = p.getString("name");
                        int base = (int) ((double) done / Math.max(1, total) * 90);
                        js("_installProgress(" + Math.max(2, base) + ",'نصب " + name + "…')");
                        String rel = "/" + p.getString("url").split("termux-main/")[1];
                        File deb = downloadMirrored(rel, null, base, base + 4);
                        ExecOut r = runShell("HOME=" + home.getAbsolutePath() + " "
                                + prefix.getAbsolutePath() + "/bin/dpkg-deb -x "
                                + deb.getAbsolutePath() + " " + prefix.getAbsolutePath(),
                                null, null, 60);
                        //noinspection ResultOfMethodCallIgnored
                        deb.delete();
                        if (r.exit != 0) {
                            installing = false;
                            js("_pkgsDone(false,'نصب " + name + " خطا داد: " +
                                    JSONObject.quote(r.out.substring(0, Math.min(300, r.out.length()))) + "')");
                            return;
                        }
                        markInstalled(name, p.optString("version", ""));
                        done += p.optLong("size", 0);
                    }
                    installing = false;
                    boolean py = pythonReady();
                    js("_pkgsDone(" + py + "," + JSONObject.quote(
                            py ? "پایتون و ابزار تونل نصب شدند ✔"
                               : "بسته‌ها باز شدند ولی پایتون پیدا نشد") + ")");
                } catch (Exception e) {
                    installing = false;
                    js("_pkgsDone(false,'خطا: " + JSONObject.quote(String.valueOf(e)) + "')");
                }
            }
        }).start();
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /* ================= موتور بسته‌ها (mini-apt) ================= */

    /** میرورهای مخزن — برای شبکه‌های فیلترشده به‌صورت خودکار عوض می‌شوند */
    private static final String[] PKG_BASES = {
            "https://packages.termux.dev/apt/termux-main",
            "https://grimler.se/termux/termux-main",
            "https://ftp.fau.de/termux/termux-main",
    };
    private static final String PKG_INDEX_PATH = "/dists/stable/main/binary-aarch64/Packages.gz";
    private static final String PKG_PRIMARY = "https://packages.termux.dev/apt/termux-main/";

    private HashMap<String, JSONObject> pkgMap;
    private JSONObject pkgJobObj = new JSONObject();
    private final Object pkgLock = new Object();
    private int activeBase = 0;

    private File pkgBaseFile() { return new File(getFilesDir(), "pkgbase.txt"); }

    private void loadActiveBase() {
        try {
            if (pkgBaseFile().isFile())
                activeBase = Integer.parseInt(readText(pkgBaseFile()).trim());
        } catch (Exception ignored) { }
    }

    /** دانلود با چرخش خودکار بین میرورها (ضد فیلتر/قطعی) */
    private File downloadMirrored(String relPath, ProgressCb cb, int a, int b) throws Exception {
        loadActiveBase();
        Exception last = null;
        for (int t = 0; t < PKG_BASES.length; t++) {
            int idx = (activeBase + t) % PKG_BASES.length;
            try {
                File f = download(PKG_BASES[idx] + relPath, cb, a, b);
                if (idx != activeBase) {
                    activeBase = idx;
                    try { writeText(pkgBaseFile(), String.valueOf(idx)); } catch (Exception ignored) { }
                }
                return f;
            } catch (Exception e) {
                last = e;
                pkgJobUpdate("mirror", a, "میرور بعدی امتحان می‌شود… (" + e.getClass().getSimpleName() + ")");
            }
        }
        throw last != null ? last : new Exception("همه‌ی میرورها بی‌پاسخ بودند");
    }

    private void pkgJobUpdate(String state, int pct, String msg) {
        try {
            pkgJobObj = new JSONObject().put("state", state).put("pct", pct).put("msg", msg);
        } catch (Exception ignored) { }
        js("_pkgEvent(" + JSONObject.quote(state) + "," + pct + "," + JSONObject.quote(msg) + ")");
    }

    public JSONObject pkgJob() { return pkgJobObj; }

    private File pkgIndexFile() { return new File(getFilesDir(), "pkgindex.txt"); }
    private File pkgManifestFile() { return new File(getFilesDir(), "installed.json"); }

    private JSONObject readManifest() {
        try {
            if (pkgManifestFile().isFile())
                return new JSONObject(readText(pkgManifestFile()));
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    private void markInstalled(String name, String version) {
        try {
            JSONObject m = readManifest();
            m.put(name, version);
            writeText(pkgManifestFile(), m.toString());
        } catch (Exception ignored) { }
    }

    /** فهرست بسته‌ها را آماده می‌کند (کش هفتگی) */
    private boolean ensureIndex(boolean refresh, boolean quiet) {
        File f = pkgIndexFile();
        if (!refresh && pkgMap != null) return true;
        if (!refresh && f.isFile()
                && System.currentTimeMillis() - f.lastModified() < 7L * 86400000L) {
            pkgMap = parseIndex(f);
            return pkgMap != null;
        }
        if (!bootstrapReady()) return false;
        try {
            if (!quiet) pkgJobUpdate("index", 1, "دریافت فهرست بسته‌ها (~۵MB)…");
            File gz = downloadMirrored(PKG_INDEX_PATH, null, 1, 60);
            try (GZIPInputStream gi = new GZIPInputStream(new FileInputStream(gz));
                 FileOutputStream fo = new FileOutputStream(f)) {
                pump(gi, fo, 200000000L, 200000000L);
            }
            //noinspection ResultOfMethodCallIgnored
            gz.delete();
            pkgMap = parseIndex(f);
            return pkgMap != null;
        } catch (Exception e) {
            return false;
        }
    }

    private HashMap<String, JSONObject> parseIndex(File f) {
        HashMap<String, JSONObject> map = new HashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder cur = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) { addStanza(map, cur.toString()); cur.setLength(0); }
                else cur.append(line).append('\n');
            }
            if (cur.length() > 0) addStanza(map, cur.toString());
        } catch (Exception e) {
            return null;
        }
        return map;
    }

    private void addStanza(HashMap<String, JSONObject> map, String stanza) {
        try {
            String pkg = null, ver = "0", deps = "", fn = "", desc = "", provides = "";
            long size = 0;
            for (String line : stanza.split("\n")) {
                int ci = line.indexOf(':');
                if (ci <= 0) continue;
                String k = line.substring(0, ci).trim();
                String v = line.substring(ci + 1).trim();
                if (k.equals("Package")) pkg = v;
                else if (k.equals("Version")) ver = v;
                else if (k.equals("Depends")) deps = v;
                else if (k.equals("Filename")) fn = v;
                else if (k.equals("Size")) { try { size = Long.parseLong(v); } catch (Exception ignored) { } }
                else if (k.equals("Description")) desc = v;
                else if (k.equals("Provides")) provides = v;
            }
            if (pkg == null || fn.isEmpty()) return;
            JSONObject o = new JSONObject()
                    .put("name", pkg).put("version", ver).put("deps", deps)
                    .put("rel", fn).put("url", PKG_PRIMARY + fn).put("size", size)
                    .put("desc", desc).put("provides", provides);
            JSONObject old = map.get(pkg);
            if (old == null || ver.compareTo(old.optString("version", "0")) > 0) map.put(pkg, o);
        } catch (Exception ignored) { }
    }

    private JSONObject findPkg(String name) {
        JSONObject st = pkgMap.get(name);
        if (st != null) return st;
        for (JSONObject s : pkgMap.values())
            if (s.optString("provides", "").contains(name)) return s;
        return null;
    }

    /** حل وابستگی‌ها به روش BFS */
    private ArrayList<JSONObject> closure(String root) throws Exception {
        ArrayList<JSONObject> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            String p = q.poll();
            if (seen.contains(p)) continue;
            JSONObject st = findPkg(p);
            if (st == null) {
                if (p.equals(root)) throw new Exception("چنین بسته‌ای پیدا نشد: " + p);
                continue;
            }
            seen.add(p);
            out.add(st);
            for (String alt : st.optString("deps", "").split(",")) {
                String n = alt.trim();
                if (n.isEmpty()) continue;
                int sp = n.indexOf(' ');
                String base = (sp > 0 ? n.substring(0, sp) : n).split("\\|")[0].trim();
                if (!base.isEmpty() && !seen.contains(base)) q.add(base);
            }
        }
        return out;
    }

    public String pkgSearch(String query) {
        try {
            if (!ensureIndex(false, false)) return "[]";
            String q = query.trim().toLowerCase(Locale.ROOT);
            JSONArray res = new JSONArray();
            ArrayList<JSONObject> hits = new ArrayList<>();
            for (JSONObject s : pkgMap.values()) {
                String n = s.getString("name").toLowerCase(Locale.ROOT);
                String d = s.optString("desc", "").toLowerCase(Locale.ROOT);
                if (n.contains(q) || d.contains(q)) hits.add(s);
            }
            // نام‌های دقیق‌تر اول
            hits.sort(new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    int la = a.optString("name", "").length(), lb = b.optString("name", "").length();
                    return Integer.compare(la, lb);
                }
            });
            for (int i = 0; i < Math.min(40, hits.size()); i++) {
                JSONObject s = hits.get(i);
                res.put(new JSONObject().put("name", s.getString("name"))
                        .put("version", s.optString("version"))
                        .put("size", s.optLong("size", 0))
                        .put("desc", s.optString("desc", "")));
            }
            return res.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    public void pkgInstallAsync(final String[] names) {
        new Thread(new Runnable() {
            @Override public void run() {
                synchronized (pkgLock) {
                    if (!"idle".equals(pkgJobObj.optString("state", "idle"))
                            && !"done".equals(pkgJobObj.optString("state"))
                            && !"error".equals(pkgJobObj.optString("state"))) {
                        pkgJobUpdate("busy", 0, "یک نصب دیگر در جریان است…");
                        return;
                    }
                    try {
                        pkgJobUpdate("index", 1, "آماده‌سازی…");
                        if (!ensureIndex(false, false)) {
                            pkgJobUpdate("error", 0, "دریافت فهرست بسته‌ها نشد — لینک را چک کن");
                            return;
                        }
                        // حل وابستگی همه‌ی بسته‌های درخواستی
                        LinkedHashSet<String> namesSet = new LinkedHashSet<>();
                        ArrayList<JSONObject> all = new ArrayList<>();
                        for (String n : names) {
                            ArrayList<JSONObject> c = closure(n.trim());
                            all.addAll(c);
                            namesSet.add(n.trim());
                        }
                        JSONObject manifest = readManifest();
                        ArrayList<JSONObject> todo = new ArrayList<>();
                        long total = 0;
                        for (JSONObject p : all) {
                            if (manifest.has(p.getString("name"))) continue;
                            todo.add(p);
                            total += p.optLong("size", 0);
                        }
                        if (todo.isEmpty()) {
                            pkgJobUpdate("done", 100, "همه از قبل نصب بوده ✔");
                            js("if(window.refresh)refresh()");
                            return;
                        }
                        long done = 0;
                        for (JSONObject p : todo) {
                            String pname = p.getString("name");
                            int base = (int) ((double) done / Math.max(1, total) * 95);
                            pkgJobUpdate("download", Math.max(2, base),
                                    "دریافت " + pname + " (" + (p.optLong("size", 0) / 1000000) + "MB)…");
                            File deb = downloadMirrored("/" + p.optString("rel", ""), null, base, base + 5);
                            pkgJobUpdate("extract", base + 5, "باز کردن " + pname + "…");
                            ExecOut r = runShell(prefix.getAbsolutePath() + "/bin/dpkg-deb -x "
                                            + deb.getAbsolutePath() + " " + prefix.getAbsolutePath(),
                                    null, null, 120);
                            //noinspection ResultOfMethodCallIgnored
                            deb.delete();
                            if (r.exit != 0) {
                                pkgJobUpdate("error", 0, "خطا در نصب " + pname + ": "
                                        + r.out.substring(0, Math.min(200, r.out.length())));
                                return;
                            }
                            markInstalled(pname, p.optString("version"));
                            done += p.optLong("size", 0);
                        }
                        StringBuilder sb = new StringBuilder();
                        for (String n : namesSet) sb.append(n).append("، ");
                        pkgJobUpdate("done", 100, "نصب شد ✔ " + sb
                                + "(" + (total / 1000000) + "MB) — در ترمینال در دسترسه");
                        js("if(window.refresh)refresh()");
                    } catch (Exception e) {
                        pkgJobUpdate("error", 0, "خطا: " + e);
                    }
                }
            }
        }).start();
    }


    /* ================= سرور HTTP داخلی ================= */

    public boolean startServer() {
        if (serverOn) return true;
        try {
            serverSocket = new ServerSocket(HTTP_PORT, 64, InetAddress.getByName("127.0.0.1"));
            serverOn = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { serveLoop(); }
            });
            t.setDaemon(true);
            t.start();
            return true;
        } catch (Exception e) {
            serverOn = false;
            return false;
        }
    }

    public void stopServer() {
        serverOn = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
    }

    private void serveLoop() {
        while (serverOn) {
            try {
                final Socket s = serverSocket.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handleConn(s); }
                });
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (!serverOn) return;
            }
        }
    }

    private static void writeResponse(Socket s, int code, String ct, byte[] body) throws IOException {
        OutputStream o = s.getOutputStream();
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 403 ? " Forbidden" : " Not Found") + "\r\n"
                + "Content-Type: " + ct + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        o.write(head.getBytes(StandardCharsets.ISO_8859_1));
        o.write(body);
        o.flush();
        s.close();
    }

    private static byte[] json(Object o) {
        return String.valueOf(o).getBytes(StandardCharsets.UTF_8);
    }

    private void handleConn(Socket s) {
        try {
            s.setSoTimeout(300000);
            InputStream in = s.getInputStream();
            // خواندن سربرگ‌ها
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int state = 0; // تعداد \r\n پشت‌هم
            while (true) {
                int c = in.read();
                if (c < 0) { s.close(); return; }
                if (c == '\n') state++; else if (c != '\r') state = 0;
                hb.write(c);
                if (state == 2) break;
                if (hb.size() > 65536) break;
            }
            String head = new String(hb.toByteArray(), StandardCharsets.ISO_8859_1);
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) { s.close(); return; }
            String method = req[0];
            String path = req[1].split("\\?")[0];

            int len = 0;
            for (int i = 1; i < lines.length; i++) {
                int ci = lines[i].indexOf(':');
                if (ci > 0 && lines[i].substring(0, ci).trim().equalsIgnoreCase("content-length"))
                    len = Integer.parseInt(lines[i].substring(ci + 1).trim());
            }
            String auth = "";
            for (int i = 1; i < lines.length; i++) {
                int ci = lines[i].indexOf(':');
                if (ci > 0 && lines[i].substring(0, ci).trim().equalsIgnoreCase("authorization"))
                    auth = lines[i].substring(ci + 1).trim();
            }

            String tok = token();
            String got = auth.replace("Bearer", "").trim();
            if (!java.security.MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8),
                    tok.getBytes(StandardCharsets.UTF_8))) {
                drain(in, len);
                writeResponse(s, 403, "application/json",
                        json("{\"ok\":false,\"error\":\"bad token\"}"));
                return;
            }

            path = java.net.URLDecoder.decode(path, "UTF-8");

            /* ---------- مسیرها ---------- */
            if (method.equals("POST") && path.equals("/pkg/install")) {
                byte[] body = readBody(in, len, 100000);
                String names = "";
                try {
                    JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    names = o.optString("name", "");
                    if (names.isEmpty() && o.has("names"))
                        names = o.getJSONArray("names").join(" ");
                } catch (Exception ignored) { }
                if (names.trim().isEmpty()) {
                    writeResponse(s, 400, "application/json", json("{\"error\":\"no name\"}"));
                    return;
                }
                pkgInstallAsync(names.split("[\\s,،]+"));
                writeResponse(s, 200, "application/json",
                        json("{\"ok\":true,\"job\":\"started\",\"poll\":\"/pkg/job\"}"));

            } else if (method.equals("GET") && path.equals("/pkg/job")) {
                writeResponse(s, 200, "application/json", pkgJob().toString().getBytes(StandardCharsets.UTF_8));

            } else if (method.equals("GET") && path.equals("/pkg/search")) {
                String q = "";
                if (req[1].contains("?")) q = req[1].substring(req[1].indexOf('?') + 1);
                q = java.net.URLDecoder.decode(q, "UTF-8");
                writeResponse(s, 200, "application/json",
                        pkgSearch(q).getBytes(StandardCharsets.UTF_8));

            } else if (method.equals("GET") && path.equals("/pkg/installed")) {
                writeResponse(s, 200, "application/json",
                        readManifest().toString().getBytes(StandardCharsets.UTF_8));

            } else if (method.equals("GET") && path.equals("/ping")) {
                writeResponse(s, 200, "application/json",
                        json("{\"ok\":true,\"app\":\"sandbox2\",\"time\":" + System.currentTimeMillis() + "}"));

            } else if (method.equals("GET") && path.equals("/info")) {
                JSONObject o = deviceInfo();
                writeResponse(s, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));

            } else if (method.equals("GET") && path.equals("/storage")) {
                File sd = new File("/storage/emulated/0");
                if (sd.isDirectory()) {
                    StatFs fs = new StatFs(sd.getAbsolutePath());
                    JSONObject o = new JSONObject()
                            .put("ok", true)
                            .put("path", "/storage/emulated/0")
                            .put("free", fs.getAvailableBytes())
                            .put("total", fs.getTotalBytes());
                    writeResponse(s, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
                } else {
                    writeResponse(s, 404, "application/json", json("{\"error\":\"sdcard not visible\"}"));
                }

            } else if (method.equals("GET") && path.equals("/docs")) {
                JSONArray a = new JSONArray();
                File[] ls = docsDir.listFiles();
                if (ls != null) {
                    Arrays.sort(ls, new Comparator<File>() {
                        @Override public int compare(File x, File y) {
                            return Long.compare(y.lastModified(), x.lastModified());
                        }
                    });
                    for (File f : ls) {
                        if (!f.isFile()) continue;
                        a.put(new JSONObject().put("name", f.getName())
                                .put("size", f.length()).put("mtime", f.lastModified() / 1000));
                    }
                }
                writeResponse(s, 200, "application/json", a.toString().getBytes(StandardCharsets.UTF_8));

            } else if (method.equals("GET") && path.startsWith("/docs/")) {
                String name = lastSegment(path);
                File f = safe(name);
                if (f == null || !f.isFile()) {
                    writeResponse(s, 404, "application/json", json("{\"error\":\"not found\"}"));
                } else {
                    byte[] b = new byte[(int) f.length()];
                    try (FileInputStream fi = new FileInputStream(f)) {
                        int off = 0, r;
                        while (off < b.length && (r = fi.read(b, off, b.length - off)) > 0) off += r;
                    }
                    writeResponse(s, 200, "application/octet-stream", b);
                }

            } else if ((method.equals("POST") || method.equals("PUT")) && path.startsWith("/docs/")) {
                String name = lastSegment(path);
                File f = safe(name);
                if (f == null) {
                    drain(in, len);
                    writeResponse(s, 400, "application/json", json("{\"error\":\"bad name\"}"));
                } else {
                    f.getParentFile().mkdirs();
                    long wrote = 0;
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        wrote = pump(in, fo, len, 2L * 1024 * 1024 * 1024);
                    }
                    writeResponse(s, 200, "application/json",
                            json("{\"ok\":true,\"name\":\"" + name + "\",\"size\":" + wrote + "}"));
                    js("if(window.refresh)refresh()");
                }

            } else if (method.equals("DELETE") && path.startsWith("/docs/")) {
                String name = lastSegment(path);
                File f = safe(name);
                boolean ok = f != null && f.isFile() && f.delete();
                writeResponse(s, ok ? 200 : 404, "application/json",
                        json(ok ? "{\"ok\":true}" : "{\"error\":\"not found\"}"));

            } else if (method.equals("POST") && path.equals("/exec")) {
                byte[] body = readBody(in, len, 1000000);
                String cmd = "";
                try { cmd = new JSONObject(new String(body, StandardCharsets.UTF_8)).getString("cmd"); }
                catch (Exception ignored) { }
                if (cmd.isEmpty()) {
                    writeResponse(s, 400, "application/json", json("{\"error\":\"empty cmd\"}"));
                    return;
                }
                ExecOut r = runShell(cmd, null, null, 280);
                JSONObject o = new JSONObject().put("exit", r.exit).put("stdout", r.out)
                        .put("stderr", r.timedOut ? "timeout" : "");
                writeResponse(s, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));

            } else if (method.equals("POST") && path.equals("/py")) {
                byte[] body = readBody(in, len, 4000000);
                String code = new String(body, StandardCharsets.UTF_8);
                if (!pythonReady()) {
                    writeResponse(s, 200, "application/json",
                            json("{\"exit\":-1,\"stdout\":\"\",\"stderr\":\"python not installed on phone\"}"));
                    return;
                }
                ExecOut r = runShell(findPython().getAbsolutePath() + " -", null, code, 280);
                JSONObject o = new JSONObject().put("exit", r.exit).put("stdout", r.out)
                        .put("stderr", r.timedOut ? "timeout" : "");
                writeResponse(s, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));

            } else {
                drain(in, Math.min(len, 1000000));
                writeResponse(s, 404, "application/json", json("{\"error\":\"unknown\"}"));
            }
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static void drain(InputStream in, long n) {
        try { pump(in, null, n, 4000000); } catch (Exception ignored) { }
    }

    private static long pump(InputStream in, OutputStream out, long n, long cap) throws IOException {
        long remaining = Math.min(n, cap);
        byte[] b = new byte[16384];
        long done = 0;
        while (remaining > 0) {
            int want = (int) Math.min(b.length, remaining);
            int r = in.read(b, 0, want);
            if (r < 0) break;
            if (out != null) out.write(b, 0, r);
            done += r;
            remaining -= r;
        }
        return done;
    }

    private static byte[] readBody(InputStream in, int len, int cap) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        pump(in, bo, len, cap);
        return bo.toByteArray();
    }

    private static String lastSegment(String path) {
        String p = path.substring(path.lastIndexOf('/') + 1);
        return p.length() > 200 ? p.substring(0, 200) : p;
    }

    private JSONObject deviceInfo() {
        JSONObject o = new JSONObject();
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) am.getMemoryInfo(mi);
            StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
            long used = 0; int count = 0;
            File[] ls = docsDir.listFiles();
            if (ls != null) for (File f : ls) { used += f.length(); count++; }
            o.put("ok", true)
                    .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("ram_total", mi.totalMem)
                    .put("ram_available", mi.availMem)
                    .put("storage_total", fs.getTotalBytes())
                    .put("storage_free", fs.getAvailableBytes())
                    .put("cpu_cores", Runtime.getRuntime().availableProcessors())
                    .put("linux", bootstrapReady())
                    .put("python", pythonReady())
                    .put("tunnel", tunnelUrl)
                    .put("box_used", used)
                    .put("box_files", count);
        } catch (Exception ignored) { }
        return o;
    }

    /* ================= تونل ================= */

    public void startTunnel() {
        if (tunnelProc != null) return; // در حال اجراست
        File cf = new File(prefix, "bin/cloudflared");
        if (!cf.canExecute()) {
            js("_tunnelMsg('اول «پایتون + ابزار تونل» را از داشبورد نصب کن (cloudflared لازم است)')");
            return;
        }
        if (!serverOn && !startServer()) {
            js("_tunnelMsg('سرور داخلی روشن نشد!')");
            return;
        }
        tunnelAuto = true;
        js("_tunnelMsg('در حال ساخت تونل رایگان کلادفلر…')");
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SandBox:tunnel");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception ignored) { }

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            prefix.getAbsolutePath() + "/bin/cloudflared",
                            "tunnel", "--url", "http://127.0.0.1:" + HTTP_PORT,
                            "--no-autoupdate", "--protocol", "http2",
                            "--edge-ip-version", "4", "--retries", "8");
                    buildEnv(pb);
                    // تونل نباید از پروکسی محلی عبور کند
                    pb.environment().remove("http_proxy");
                    pb.environment().remove("https_proxy");
                    pb.environment().remove("all_proxy");
                    pb.environment().remove("HTTP_PROXY");
                    pb.environment().remove("HTTPS_PROXY");
                    pb.environment().remove("ALL_PROXY");
                    pb.directory(home);
                    pb.redirectErrorStream(true);
                    final Process proc = pb.start();
                    tunnelProc = proc;
                    Pattern urlRe = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
                    try (BufferedReaderWrap br = new BufferedReaderWrap(proc.getInputStream())) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            Log.d("SandBox-tunnel", line);
                            Matcher m = urlRe.matcher(line);
                            if (m.find()) {
                                tunnelUrl = m.group();
                                tunnelTries = 0; // وصل شد — شمارنده‌ی قطعی صفر
                                js("_tunnelUrl(" + JSONObject.quote(tunnelUrl) + ")");
                            }
                        }
                    }
                    int rc = proc.waitFor();
                    tunnelUrl = "";
                    tunnelProc = null;
                    if (tunnelAuto && tunnelTries < 12) {
                        tunnelTries++;
                        js("_tunnelMsg('اتصال قطع شد (exit " + rc + ") — تلاش مجدد " + tunnelTries + "/12…')");
                        Thread.sleep(5000);
                        if (tunnelAuto && tunnelProc == null) {
                            startTunnel();
                            return;
                        }
                    } else {
                        js("_tunnelMsg('تونل بسته شد (exit " + rc + ")')");
                    }
                } catch (Exception e) {
                    tunnelProc = null;
                    js("_tunnelMsg('خطای تونل: " + JSONObject.quote(String.valueOf(e)) + "')");
                }
            }
        }).start();
    }

    /** پوششِ سبک برای خواندن خط‌به‌خط */
    private static class BufferedReaderWrap implements java.io.Closeable {
        private final InputStream in;
        BufferedReaderWrap(InputStream in) { this.in = in; }
        String readLine() throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int c = -1;
            while ((c = in.read()) >= 0) {
                if (c == '\n') break;
                bo.write(c);
                if (bo.size() > 65536) break;
            }
            if (bo.size() == 0 && c < 0) return null;
            String s = bo.toString("UTF-8");
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            return s;
        }
        @Override public void close() throws IOException { in.close(); }
    }

    public void stopTunnel() {
        tunnelUrl = "";
        Process p = tunnelProc;
        tunnelProc = null;
        if (p != null) p.destroyForcibly();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    /* ================= پل JS ================= */

    private class Bridge {

        @JavascriptInterface
        public String info() { return deviceInfo().toString(); }

        @JavascriptInterface
        public boolean bootstrapReady() { return MainActivity.this.bootstrapReady(); }

        @JavascriptInterface
        public boolean pythonReady() { return MainActivity.this.pythonReady(); }

        @JavascriptInterface
        public void installLinux() { installBootstrapAsync(); }

        @JavascriptInterface
        public void installPkgs() { installPkgsAsync(); }

        @JavascriptInterface
        public String pkgSearch(String q) { return MainActivity.this.pkgSearch(q); }

        @JavascriptInterface
        public void pkgInstall(String name) {
            final String[] arr = name.split("[\\s,،]+");
            pkgInstallAsync(arr);
        }

        @JavascriptInterface
        public String pkgJob() { return pkgJob().toString(); }

        @JavascriptInterface
        public String pkgInstalled() {
            JSONObject m = readManifest();
            JSONArray a = new JSONArray();
            for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                String k = it.next();
                try { a.put(new JSONObject().put("name", k).put("version", m.optString(k, ""))); }
                catch (Exception ignored) { }
            }
            return a.toString();
        }

        @JavascriptInterface
        public String execStart(final int id, final String cmd, final String cwd,
                                final int timeoutSec, final String stdin) {
            cpu.submit(new Runnable() {
                @Override public void run() {
                    ExecOut r = runShell(cmd, cwd, stdin, timeoutSec);
                    js("_execDone(" + id + "," + r.exit + "," + JSONObject.quote(r.out) + ")");
                }
            });
            return "ok";
        }

        @JavascriptInterface
        public String getToken() { return token(); }

        @JavascriptInterface
        public boolean serverStart() { return startServer(); }

        @JavascriptInterface
        public String tunnelStart() {
            tunnelTries = 0; // شروع دستی — شمارنده صفر
            startTunnel();
            return "started";
        }

        @JavascriptInterface
        public void tunnelStop() {
            tunnelAuto = false;
            stopTunnel();
            js("_tunnelMsg('تونل متوقف شد.')");
        }

        @JavascriptInterface
        public String getProxy() { return proxySetting(); }

        @JavascriptInterface
        public void setProxy(String p) {
            try {
                writeText(new File(getFilesDir(), "proxy.txt"),
                        p == null ? "" : p.trim());
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String getTunnelUrl() { return tunnelUrl; }

        /* ---- اسناد (مثل نسخه ۱) ---- */

        @JavascriptInterface
        public String listDocs() {
            try {
                JSONArray a = new JSONArray();
                File[] ls = docs().listFiles();
                if (ls != null) {
                    Arrays.sort(ls, new Comparator<File>() {
                        @Override public int compare(File x, File y) {
                            return Long.compare(y.lastModified(), x.lastModified());
                        }
                    });
                    for (File f : ls) {
                        if (!f.isFile()) continue;
                        a.put(new JSONObject().put("name", f.getName())
                                .put("size", f.length()).put("date", f.lastModified()));
                    }
                }
                return a.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String readDoc(String name) {
            File f = safe(name);
            if (f == null || !f.isFile()) return null;
            try { return readText(f); } catch (Exception e) { return null; }
        }

        @JavascriptInterface
        public boolean writeDoc(String name, String content) {
            File f = safe(name);
            if (f == null) return false;
            try { writeText(f, content); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean deleteDoc(String name) {
            File f = safe(name);
            return f != null && f.isFile() && f.delete();
        }

        @JavascriptInterface
        public void exportDoc(final String name) {
            final File f = safe(name);
            if (f == null || !f.isFile()) { toast("سند پیدا نشد"); return; }
            pendingExport = name;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TITLE, name);
                        startActivityForResult(i, REQ_EXPORT);
                    } catch (Exception e) { toast("امکان خروجی گرفتن نیست"); }
                }
            });
        }

        @JavascriptInterface
        public void importDoc() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, REQ_IMPORT);
                    } catch (Exception e) { toast("امکان انتخاب فایل نیست"); }
                }
            });
        }
    }

    /* ================= نتیجه‌ی خروجی/ورودی فایل ================= */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (requestCode == REQ_EXPORT && pendingExport != null) {
            final File src = new File(docs(), pendingExport);
            new Thread(new Runnable() {
                @Override public void run() {
                    try (InputStream in = new FileInputStream(src);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) { toast("خطا در ذخیره فایل"); return; }
                        byte[] buf = new byte[8192]; int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        toast("خروجی گرفته شد ✔");
                    } catch (Exception e) { toast("خطا در خروجی"); }
                }
            }).start();

        } else if (requestCode == REQ_IMPORT) {
            final String name = pickName(uri);
            new Thread(new Runnable() {
                @Override public void run() {
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(new File(docs(), name))) {
                        byte[] buf = new byte[8192]; int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        toast("سند وارد شد ✔");
                        js("if(window.refresh)refresh()");
                    } catch (Exception e) { toast("خطا در وارد کردن"); }
                }
            }).start();
        }
    }

    private String pickName(Uri uri) {
        String n = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) n = c.getString(i);
            }
        } catch (Exception ignored) { }
        if (n == null) n = uri.getLastPathSegment();
        if (n == null || n.trim().isEmpty()) n = "doc_" + System.currentTimeMillis() + ".txt";
        n = n.trim();
        if (n.contains("/") || n.contains("\\") || n.contains("..") || n.length() > 200)
            n = "imported_" + System.currentTimeMillis() + ".txt";
        return n;
    }
}
