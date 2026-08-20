package com.sandbox.box;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.StatFs;
import android.app.PendingIntent;
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
import java.net.HttpURLConnection;
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
    private String pendingImportDir;
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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                String src = cm.sourceId() == null ? "" : cm.sourceId();
                Log.d("SandBox", cm.message() + " @" + cm.lineNumber());
                // لاگ‌های صفحه‌ی پیش‌نمایش را به پنل کنسول بفرست
                if (src.contains("127.0.0.1:" + PREVIEW_PORT)) {
                    js("_pvLog(" + JSONObject.quote("[" + cm.message() + "] @" + cm.lineNumber()) + ")");
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        startPreviewServer();

        // بررسی سلامت محیط + ترمیم خودکار اگر بوت‌استرپ رله‌شده موجود باشد
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(2000);
                    File b = new File(prefix, "bin/bash");
                    File z = new File(docsDir, "bootstrap.zip");
                    if (b.canExecute() && z.isFile() && z.length() > 10000000) {
                        ExecOut t = runShell("echo SBXOK", null, null, 6);
                        if (!t.out.contains("SBXOK")) {
                            toast("🔧 خرابی محیط شناسایی شد — در حال ترمیم خودکار…");
                            recoverEnvironmentAsync();
                        }
                    }
                } catch (Exception ignored) { }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        // تونل و سرور عمداً زنده می‌مانند — سرویس Foreground نگه می‌دارد
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
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
                if (web != null && !isDestroyed()) web.evaluateJavascript("try{" + call + "}catch(e){}", null);
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
            if (f.getName().equals("python3")) return f;
        for (File f : fs)
            if (f.getName().startsWith("python3.") && !f.getName().contains("-")) return f;
        return null;
    }

    /* ================= موتور اجرای شل ================= */

    /* ---------- نگهبان ایمنی محیط لینوکس ---------- */

    private static final String[] GUARD_A = {
            "rm ", "mv ", "dd ", "shred ", "truncate ", "chmod ", "chown ",
            "unlink ", "rmdir "};
    private static final String[] GUARD_B = {
            "dpkg -r", "dpkg --remove", "dpkg --purge",
            "apt remove", "apt purge", "apt-get remove", "apt-get purge"};

    /** اگر دستور بخواهد محیط لینوکس (usr) را خراب کند null برمی‌گرداند */
    static String shellGuard(String cmd) {
        if (cmd == null) return null;
        String low = " " + cmd.toLowerCase(Locale.ROOT).replace('\t', ' ') + " ";
        for (String v : GUARD_B) if (low.contains(" " + v)) return null;
        boolean touchesUsr = low.contains("/usr") || low.contains("$prefix")
                || low.contains("files/usr") || low.contains("usr/bin")
                || low.contains("usr/lib") || low.contains("usr/etc");
        if (touchesUsr) {
            for (String v : GUARD_A) if (low.contains(" " + v)) return null;
        }
        return cmd;
    }

    /** بازسازی محیط لینوکس — داده‌های کاربر (home/docs) دست‌نخورده می‌مانند */
    public void rebuildLinuxAsync() {
        if (installing) return;
        installing = true;
        new Thread(new Runnable() {
            @Override public void run() {
                js("_installProgress(1,'حذف محیط لینوکسِ خراب‌شده… (home و docs سالم می‌مانند)')");
                stopTunnel();
                stopServer();
                wsDeleteRecursive(prefix);
                installing = false;
                installBootstrapAsync();
            }
        }).start();
    }

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
        if (shellGuard(cmd) == null) {
            r.exit = -403;
            r.out = "⛔ نگهبان ایمنی: این دستور می‌توانست محیط لینوکس را خراب کند و مسدود شد.\n" +
                    "   usr فقط‌خواندن است. برای محیط تازه: داشبورد ← ♻️ بازسازی لینوکس.\n" +
                    "   (فایل‌های خودت در /home و /docs سالم‌اند)";
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
            final boolean[] truncated = new boolean[1];
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try (InputStream in = proc.getInputStream()) {
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) {
                            buf.write(b, 0, n);
                            if (buf.size() > 480000) { // آخرِ خروجی نگه داشته می‌شود
                                byte[] all = buf.toByteArray();
                                buf.reset();
                                buf.write(all, all.length - 400000, 400000);
                                truncated[0] = true;
                            }
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
            r.out = (truncated[0] ? "…(برای صرفه‌جویی، ابتدای خروجی حذف شد)\n" : "")
                    + new String(buf.toByteArray(), StandardCharsets.UTF_8);
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
                    if (cb != null && pct != lastPct && pct % 2 == 0) { lastPct = pct; cb.call(pct, ""); }
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
                installing = true;
                try {
                    wsDeleteRecursive(new File(prefix, "data")); // خرابی نسخه‌های قبلی
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
                        File stage = new File(getCacheDir(), "stage_" + System.currentTimeMillis());
                        ExecOut r = runShell(prefix.getAbsolutePath() + "/bin/dpkg-deb -x "
                                + deb.getAbsolutePath() + " " + stage.getAbsolutePath()
                                + " && cp -a " + stage.getAbsolutePath()
                                + "/data/data/com.termux/files/usr/. " + prefix.getAbsolutePath() + "/",
                                null, null, 120);
                        wsDeleteRecursive(stage);
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
                            File stage = new File(getCacheDir(), "stage_" + System.currentTimeMillis());
                            ExecOut r = runShell(prefix.getAbsolutePath() + "/bin/dpkg-deb -x "
                                            + deb.getAbsolutePath() + " " + stage.getAbsolutePath()
                                            + " && cp -a " + stage.getAbsolutePath()
                                            + "/data/data/com.termux/files/usr/. " + prefix.getAbsolutePath() + "/",
                                    null, null, 120);
                            wsDeleteRecursive(stage);
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
            if (method.equals("POST") && path.equals("/self/build")) {
                readBody(in, len, 1000);
                buildSelfAsync();
                writeResponse(s, 200, "application/json", json("{\"ok\":true,\"job\":\"self-build started\"}"));
                return;
            } else if (method.equals("POST") && path.equals("/self/install")) {
                byte[] bb = readBody(in, len, 10000);
                String pth = "";
                try { pth = new JSONObject(new String(bb, StandardCharsets.UTF_8)).optString("path", ""); }
                catch (Exception ignored) { }
                File apkF = null;
                if (!pth.isEmpty()) apkF = wsResolve(pth, false);
                else {
                    File bd = new File(home, "build");
                    File[] ls = bd.listFiles();
                    if (ls != null) {
                        for (File x : ls)
                            if (x.getName().endsWith(".apk") &&
                                    (apkF == null || x.lastModified() > apkF.lastModified())) apkF = x;
                    }
                }
                if (apkF != null && apkF.isFile()) {
                    installApkInternal(apkF);
                    writeResponse(s, 200, "application/json", json("{\"ok\":true,\"started\":true}"));
                } else {
                    writeResponse(s, 200, "application/json", json("{\"ok\":false,\"error\":\"apk not found\"}"));
                }
                return;
            } else if (method.equals("POST") && path.equals("/self/recover")) {
                readBody(in, len, 1000);
                recoverEnvironmentAsync();
                writeResponse(s, 200, "application/json", json("{\"ok\":true,\"job\":\"recovery started\"}"));
                return;
            } else if (method.equals("POST") && path.equals("/pkg/install")) {
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

            } else if (path.startsWith("/preview")) {
                servePath(s, path.length() > 8 ? path.substring(8) : "/");
                return;

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


    /* ================= سرور پیش‌نمایش ================= */

    private static final int PREVIEW_PORT = 8090;
    private ServerSocket previewSocket;
    private volatile boolean previewOn = false;

    public boolean startPreviewServer() {
        if (previewOn) return true;
        try {
            previewSocket = new ServerSocket(PREVIEW_PORT, 32, InetAddress.getByName("127.0.0.1"));
            previewOn = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { previewLoop(); }
            });
            t.setDaemon(true);
            t.start();
            ensureDemoPage();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureDemoPage() {
        File idx = new File(home, "index.html");
        if (idx.isFile()) return;
        try { writeText(idx, DEMO_PAGE); } catch (Exception ignored) { }
    }

    private void previewLoop() {
        while (previewOn) {
            try {
                final Socket s = previewSocket.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handlePreview(s); }
                });
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (!previewOn) return;
            }
        }
    }

    private void handlePreview(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int state = 0;
            while (true) {
                int c = in.read();
                if (c < 0) { s.close(); return; }
                if (c == '\n') state++; else if (c != '\r') state = 0;
                hb.write(c);
                if (state == 2 || hb.size() > 65536) break;
            }
            String[] req = new String(hb.toByteArray(), StandardCharsets.ISO_8859_1)
                    .split("\r\n")[0].split(" ");
            if (req.length < 2) { s.close(); return; }
            String path = req[1].split("\\?")[0];
            if (!req[0].equals("GET")) {
                writeResponse(s, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
                return;
            }
            servePath(s, path);
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String htmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain; charset=utf-8";
        if (n.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    /** سرو فایل استاتیک از home (و docs با پیشوند /docs/) */
    private boolean servePath(Socket s, String path) {
        try {
            path = java.net.URLDecoder.decode(path, "UTF-8");
            if (path.contains("..")) {
                writeResponse(s, 403, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
                return true;
            }
            File f;
            if (path.startsWith("/docs/")) f = new File(docsDir, path.substring(6));
            else if (path.equals("/docs")) f = docsDir;
            else f = new File(home, path.equals("/") ? "" : path.substring(1));

            if (f != null && f.isDirectory()) {
                File idx = new File(f, "index.html");
                if (idx.isFile()) f = idx;
                else {
                    File[] ls = f.listFiles();
                    StringBuilder sb = new StringBuilder(
                            "<!doctype html><meta charset='utf-8'><title>index</title>" +
                            "<body style='font-family:monospace;background:#0f1626;color:#cbd5e1;padding:24px;direction:ltr'>" +
                            "<h3 style='color:#22d3ee'>Index of " + htmlEsc(path) + "</h3>");
                    if (ls != null) {
                        Arrays.sort(ls);
                        for (File x : ls)
                            sb.append("<a style='color:#818cf8;display:block;padding:4px' href='")
                              .append(htmlEsc((path.endsWith("/") ? path : path + "/"))
                                      .replace("'", "%27"))
                              .append(htmlEsc(x.getName()).replace("'", "%27"))
                              .append("'>").append(htmlEsc(x.getName()))
                              .append(x.isDirectory() ? "/" : "").append("</a>");
                    }
                    sb.append("</body>");
                    byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
                    writeResponse(s, 200, "text/html; charset=utf-8", b);
                    return true;
                }
            }
            if (f != null && f.isFile()) {
                OutputStream o = s.getOutputStream();
                String head = "HTTP/1.1 200 OK\r\nContent-Type: " + mimeOf(f.getName())
                        + "\r\nContent-Length: " + f.length()
                        + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                o.write(head.getBytes(StandardCharsets.ISO_8859_1));
                try (FileInputStream fi = new FileInputStream(f)) {
                    byte[] b = new byte[16384]; int n;
                    while ((n = fi.read(b)) > 0) o.write(b, 0, n);
                }
                o.flush();
                s.close();
                return true;
            }
            writeResponse(s, 404, "text/plain; charset=utf-8",
                    "404 — فایل پیدا نشد".getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
            return true;
        }
    }

    private void collectPreviewFiles(File dir, String urlPrefix, JSONArray out, int depth) {
        if (depth > 3 || out.length() >= 150) return;
        File[] ls = dir.listFiles();
        if (ls == null) return;
        Arrays.sort(ls);
        for (File f : ls) {
            if (out.length() >= 150) return;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isDirectory()) {
                collectPreviewFiles(f, urlPrefix + f.getName() + "/", out, depth + 1);
            } else if (n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".svg")
                    || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".md")) {
                try {
                    out.put(new JSONObject()
                            .put("name", f.getName())
                            .put("url", urlPrefix + f.getName())
                            .put("root", urlPrefix.startsWith("docs") ? "/docs" : "/home"));
                } catch (Exception ignored) { }
            }
        }
    }

    private static final String DEMO_PAGE =
            "<!doctype html><html lang='fa' dir='rtl'><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>سندباکس — شبیه‌سازی زنده</title><style>" +
            "body{margin:0;background:#0b1220;color:#e2e8f0;font-family:Tahoma,sans-serif;overflow:hidden}" +
            "#c{display:block}h1{position:fixed;top:14px;right:16px;margin:0;font-size:18px;color:#22d3ee}" +
            "p{position:fixed;top:42px;right:16px;margin:0;font-size:12px;color:#8b9cc0}" +
            "</style></head><body><canvas id='c'></canvas>" +
            "<h1>📦 سندباکس — پیش‌نمایش زنده</h1><p>این صفحه را می‌تونی عوض کنی: /home/index.html</p>" +
            "<script>" +
            "var cv=document.getElementById('c'),cx=cv.getContext('2d'),W,H;" +
            "function rs(){W=cv.width=innerWidth;H=cv.height=innerHeight}" +
            "addEventListener('resize',rs);rs();" +
            "var cols=['#22d3ee','#818cf8','#34d399','#fbbf24','#f87171'];" +
            "var balls=[];for(var i=0;i<40;i++)balls.push({x:Math.random()*W,y:Math.random()*H," +
            "vx:(Math.random()-.5)*4,vy:(Math.random()-.5)*4,r:6+Math.random()*18,c:cols[i%5]});" +
            "var fps=0,fr=0,t0=Date.now();" +
            "function tick(){cx.fillStyle='#0b1220';cx.fillRect(0,0,W,H);" +
            "for(var b of balls){b.x+=b.vx;b.y+=b.vy;" +
            "if(b.x<b.r||b.x>W-b.r)b.vx*=-1;if(b.y<b.r||b.y>H-b.r)b.vy*=-1;" +
            "cx.beginPath();cx.arc(b.x,b.y,b.r,0,7);cx.fillStyle=b.c;cx.globalAlpha=.85;cx.fill();}" +
            "cx.globalAlpha=1;fr++;var d=Date.now()-t0;if(d>500){fps=Math.round(fr*1000/d);fr=0;t0=Date.now();}" +
            "cx.fillStyle='#8b9cc0';cx.font='12px monospace';cx.direction='ltr';" +
            "cx.fillText(fps+' FPS • '+balls.length+' balls',12,20);requestAnimationFrame(tick)}tick();" +
            "</script></body></html>";

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
        if (checkSelfPermission("android.permission.FOREGROUND_SERVICE")
                == android.content.pm.PackageManager.PERMISSION_GRANTED)
        try {
            Intent svc = new Intent(this, TunnelService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Exception ignored) { }
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
                    Pattern urlRe = Pattern.compile("https://(?!api\\.)[a-z0-9]+(?:-[a-z0-9]+)+\\.trycloudflare\\.com");
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
                    if (tunnelAuto && tunnelTries < 40) {
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
        try { stopService(new Intent(this, TunnelService.class)); } catch (Exception ignored) { }
        tunnelUrl = "";
        Process p = tunnelProc;
        tunnelProc = null;
        if (p != null) p.destroyForcibly();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }


    /* ================= عامل هوش مصنوعی ================= */

    private static final String AI_SYS =
            "تو «سندباکس‌بات» هستی؛ یک عامل برنامه‌نویس که مستقیم روی گوشی اندرویدیِ کاربر اجرا می‌شوی.\n" +
            "محیط اجرا: لینوکس Termux بدون روت روی گوشی — بش، پایتون۳ و بسته‌های نصب‌شده در /usr/bin.\n" +
            "سیستم فایل: /home (فضای کار و ساخت پروژه)، /docs (اسناد کاربر)، /tmp (موقتی).\n" +
            "قواعد:\n" +
            "- برای هر کاری ابزار را صدا بزن (run_command و…)، خروجی را بخوان و ادامه بده؛ حدس نزن.\n" +
            "- فایل‌ها را در /home بساز مگر کاربر جای دیگر بگوید.\n" +
            "- اگر بسته‌ای نصب نبود، به کاربر بگو از تب «بسته‌ها» نصبش کند.\n" +
            "- جواب نهایی را کوتاه و به فارسی بده.";

    private final Object aiLock = new Object();
    private JSONArray aiHist = new JSONArray();
    private volatile boolean aiBusy = false;

    private JSONObject aiCfg() {
        try {
            File f = new File(getFilesDir(), "aiconfig.json");
            if (f.isFile()) return new JSONObject(readText(f));
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    private void aiSaveCfg(String provider, String key, String model, String base) {
        try {
            JSONObject o = new JSONObject()
                    .put("provider", provider).put("key", key)
                    .put("model", model).put("base", base);
            writeText(new File(getFilesDir(), "aiconfig.json"), o.toString());
            toast("تنظیمات چت ذخیره شد ✔");
        } catch (Exception ignored) { }
    }

    private static JSONArray aiToolDefs() throws Exception {
        return new JSONArray()
                .put(new JSONObject()
                        .put("name", "run_command")
                        .put("description", "اجرای یک دستور شل لینوکس (bash) در پوشه‌ی خانه و برگرداندن خروجی کامل")
                        .put("parameters", new JSONObject().put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("command", new JSONObject().put("type", "string")
                                                .put("description", "دستور شل، مثل: ls -la یا python3 script.py"))
                                        .put("timeout_seconds", new JSONObject().put("type", "integer")
                                                .put("description", "مهلت اجرا بر حسب ثانیه (۱ تا ۲۴۰)، پیش‌فرض ۶۰")))
                                .put("required", new JSONArray().put("command"))))
                .put(new JSONObject()
                        .put("name", "read_file")
                        .put("description", "خواندن محتوای یک فایل متنی")
                        .put("parameters", new JSONObject().put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("path", new JSONObject().put("type", "string")
                                                .put("description", "مسیر مثل /home/app.py یا /docs/note.txt")))
                                .put("required", new JSONArray().put("path"))))
                .put(new JSONObject()
                        .put("name", "write_file")
                        .put("description", "نوشتن/ساخت فایل متنی (بازنویسی کامل)")
                        .put("parameters", new JSONObject().put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("path", new JSONObject().put("type", "string")
                                                .put("description", "مسیر مقصد، مثل /home/main.py"))
                                        .put("content", new JSONObject().put("type", "string")
                                                .put("description", "محتوای کامل فایل")))
                                .put("required", new JSONArray().put("path").put("content"))))
                .put(new JSONObject()
                        .put("name", "list_dir")
                        .put("description", "فهرست فایل‌ها و پوشه‌های یک مسیر")
                        .put("parameters", new JSONObject().put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("path", new JSONObject().put("type", "string")
                                                .put("description", "مسیر مثل /home (پیش‌فرض /home)")))));
    }

    /** مسیرهای مجاز: /home /docs /tmp خواندن+نوشتن — /usr فقط خواندن */
    private File aiResolve(String p, boolean write) {
        if (p == null) return null;
        p = p.trim();
        File base;
        String root = "/home";
        if (p.startsWith("/docs")) { base = docsDir; root = "/docs"; }
        else if (p.startsWith("/usr")) { if (write) return null; base = prefix; root = "/usr"; }
        else if (p.startsWith("/tmp")) { base = new File(prefix, "tmp"); root = "/tmp"; }
        else if (p.startsWith("/home") || !p.startsWith("/")) { base = home; root = "/home"; }
        else return null;
        String rel = p.startsWith(root) ? p.substring(root.length()) : p;
        if (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.contains("..")) return null;
        File f = rel.isEmpty() ? base : new File(base, rel);
        try {
            if (!f.getCanonicalPath().startsWith(base.getCanonicalPath())) return null;
        } catch (Exception e) { return null; }
        return f;
    }

    private String aiExecTool(String name, JSONObject args) {
        try {
            if (name.equals("run_command")) {
                int t = Math.max(5, Math.min(240, args.optInt("timeout_seconds", 60)));
                ExecOut r = runShell(args.getString("command"), home.getAbsolutePath(), null, t);
                String out = r.out.length() > 60000
                        ? r.out.substring(0, 60000) + "\n…(کوتاه شد)" : r.out;
                return new JSONObject().put("exit_code", r.exit).put("output", out).toString();
            }
            if (name.equals("read_file")) {
                File f = aiResolve(args.optString("path"), false);
                if (f == null || !f.isFile()) return "{\"error\":\"file not found\"}";
                String s = readText(f);
                if (s.length() > 80000) s = s.substring(0, 80000) + "…(کوتاه شد)";
                return new JSONObject().put("content", s).toString();
            }
            if (name.equals("write_file")) {
                File f = aiResolve(args.optString("path"), true);
                if (f == null) return "{\"error\":\"bad path\"}";
                f.getParentFile().mkdirs();
                writeText(f, args.optString("content", ""));
                return new JSONObject().put("ok", true).put("path", args.optString("path")).toString();
            }
            if (name.equals("list_dir")) {
                File f = aiResolve(args.optString("path", "/home"), false);
                if (f == null || !f.isDirectory()) return "{\"error\":\"dir not found\"}";
                JSONArray a = new JSONArray();
                File[] ls = f.listFiles();
                if (ls != null) {
                    Arrays.sort(ls);
                    for (int i = 0; i < Math.min(300, ls.length); i++)
                        a.put(new JSONObject().put("name", ls[i].getName())
                                .put("type", ls[i].isDirectory() ? "dir" : "file")
                                .put("size", ls[i].length()));
                }
                return a.toString();
            }
            return "{\"error\":\"unknown tool\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + e + "\"}";
        }
    }

    private String httpPostJson(String url, String json, String authHeader) throws Exception {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(240000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "SandBox/5.0");
        if (authHeader != null) c.setRequestProperty("Authorization", authHeader);
        c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
        String body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400)
            throw new Exception("HTTP " + code + ": " + body.substring(0, Math.min(300, body.length())));
        return body;
    }

    private JSONObject aiCall(JSONArray hist) throws Exception {
        JSONObject cfg = aiCfg();
        if ("gemini".equals(cfg.optString("provider", "gemini")))
            return aiCallGemini(cfg, hist);
        return aiCallOpenai(cfg, hist);
    }

    private JSONObject aiCallGemini(JSONObject cfg, JSONArray hist) throws Exception {
        JSONArray contents = new JSONArray();
        for (int i = 0; i < hist.length(); i++) {
            JSONObject m = hist.getJSONObject(i);
            String role = m.getString("role");
            if (role.equals("user")) {
                contents.put(new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", m.optString("text")))));
            } else if (role.equals("assistant")) {
                JSONArray parts = new JSONArray();
                if (!m.optString("text", "").isEmpty())
                    parts.put(new JSONObject().put("text", m.optString("text")));
                JSONArray calls = m.optJSONArray("calls");
                if (calls != null)
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject c = calls.getJSONObject(j);
                        parts.put(new JSONObject().put("functionCall",
                                new JSONObject().put("name", c.getString("name"))
                                        .put("args", new JSONObject(c.optString("args", "{}")))));
                    }
                contents.put(new JSONObject().put("role", "model").put("parts", parts));
            } else if (role.equals("tool")) {
                JSONArray parts = new JSONArray();
                JSONArray rs = m.getJSONArray("results");
                for (int j = 0; j < rs.length(); j++) {
                    JSONObject r = rs.getJSONObject(j);
                    parts.put(new JSONObject().put("functionResponse",
                            new JSONObject().put("name", r.getString("name"))
                                    .put("response", new JSONObject().put("result", r.opt("content")))));
                }
                contents.put(new JSONObject().put("role", "user").put("parts", parts));
            }
        }
        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", AI_SYS))))
                .put("contents", contents)
                .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", aiToolDefs())));
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + cfg.optString("model", "gemini-2.0-flash") + ":generateContent?key="
                + cfg.optString("key", "");
        String resp = httpPostJson(url, body.toString(), null);
        JSONObject root = new JSONObject(resp);
        JSONArray cands = root.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            String err = resp.length() > 200 ? resp.substring(0, 200) : resp;
            throw new Exception("پاسخ خالی از Gemini: " + err);
        }
        JSONArray parts = cands.getJSONObject(0).optJSONObject("content").optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        JSONArray calls = new JSONArray();
        if (parts != null)
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                if (p.has("text")) text.append(p.getString("text"));
                if (p.has("functionCall")) {
                    JSONObject fc = p.getJSONObject("functionCall");
                    calls.put(new JSONObject().put("id", "call_" + i)
                            .put("name", fc.getString("name"))
                            .put("args", fc.optJSONObject("args") == null
                                    ? "{}" : fc.optJSONObject("args").toString()));
                }
            }
        return new JSONObject().put("text", text.toString()).put("calls", calls);
    }

    private JSONObject aiCallOpenai(JSONObject cfg, JSONArray hist) throws Exception {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", AI_SYS));
        for (int i = 0; i < hist.length(); i++) {
            JSONObject m = hist.getJSONObject(i);
            String role = m.getString("role");
            if (role.equals("user")) {
                msgs.put(new JSONObject().put("role", "user").put("content", m.optString("text")));
            } else if (role.equals("assistant")) {
                JSONObject a = new JSONObject().put("role", "assistant");
                if (!m.optString("text", "").isEmpty()) a.put("content", m.optString("text"));
                JSONArray calls = m.optJSONArray("calls");
                if (calls != null) {
                    JSONArray tcs = new JSONArray();
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject c = calls.getJSONObject(j);
                        tcs.put(new JSONObject().put("id", c.optString("id", "call_" + j))
                                .put("type", "function")
                                .put("function", new JSONObject()
                                        .put("name", c.getString("name"))
                                        .put("arguments", c.optString("args", "{}"))));
                    }
                    a.put("tool_calls", tcs);
                }
                msgs.put(a);
            } else if (role.equals("tool")) {
                JSONArray rs = m.getJSONArray("results");
                for (int j = 0; j < rs.length(); j++) {
                    JSONObject r = rs.getJSONObject(j);
                    msgs.put(new JSONObject().put("role", "tool")
                            .put("tool_call_id", r.optString("id", "call_" + j))
                            .put("content", r.optString("content", "")));
                }
            }
        }
        JSONArray tools = new JSONArray();
        JSONArray defs = aiToolDefs();
        for (int i = 0; i < defs.length(); i++)
            tools.put(new JSONObject().put("type", "function").put("function", defs.getJSONObject(i)));
        JSONObject body = new JSONObject()
                .put("model", cfg.optString("model", ""))
                .put("messages", msgs)
                .put("tools", tools)
                .put("tool_choice", "auto");
        String base = cfg.optString("base", "https://openrouter.ai/api/v1");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String resp = httpPostJson(base + "/chat/completions", body.toString(),
                "Bearer " + cfg.optString("key", ""));
        JSONObject msg = new JSONObject(resp).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message");
        JSONArray calls = new JSONArray();
        JSONArray tcs = msg.optJSONArray("tool_calls");
        if (tcs != null)
            for (int i = 0; i < tcs.length(); i++) {
                JSONObject tc = tcs.getJSONObject(i);
                JSONObject fn = tc.getJSONObject("function");
                calls.put(new JSONObject().put("id", tc.optString("id", "call_" + i))
                        .put("name", fn.getString("name"))
                        .put("args", fn.optString("arguments", "{}")));
            }
        return new JSONObject().put("text", msg.optString("content", "")).put("calls", calls);
    }

    public void aiSendAsync(final String text) {
        if (aiBusy) { js("_aiError('یک گفتگو در جریان است — صبر کن')"); return; }
        final JSONObject cfg = aiCfg();
        if (cfg.optString("key", "").isEmpty()) {
            js("_aiError('اول کلید API را در تنظیمات چت (⚙) بگذار')");
            return;
        }
        aiBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                synchronized (aiLock) {
                    try {
                        aiHist.put(new JSONObject().put("role", "user").put("text", text));
                        for (int step = 0; step < 12; step++) {
                            js("_aiState('think')");
                            JSONArray hist = trimAiHist();
                            JSONObject r = aiCall(hist);
                            String atext = r.optString("text", "");
                            JSONArray calls = r.optJSONArray("calls");
                            if (calls == null || calls.length() == 0) {
                                aiHist.put(new JSONObject().put("role", "assistant").put("text", atext));
                                js("_aiFinal(" + JSONObject.quote(atext) + ")");
                                aiBusy = false;
                                return;
                            }
                            aiHist.put(new JSONObject().put("role", "assistant")
                                    .put("text", atext).put("calls", calls));
                            JSONArray results = new JSONArray();
                            for (int j = 0; j < calls.length(); j++) {
                                JSONObject c = calls.getJSONObject(j);
                                String name = c.getString("name");
                                String args = c.optString("args", "{}");
                                js("_aiTool(" + JSONObject.quote(name) + "," + JSONObject.quote(args) + ")");
                                String out = aiExecTool(name, new JSONObject(args));
                                js("_aiToolDone(" + JSONObject.quote(name) + "," +
                                        JSONObject.quote(out.substring(0, Math.min(3000, out.length()))) + ")");
                                results.put(new JSONObject()
                                        .put("id", c.optString("id", "call_" + j))
                                        .put("name", name).put("content", out));
                            }
                            aiHist.put(new JSONObject().put("role", "tool").put("results", results));
                        }
                        aiHist.put(new JSONObject().put("role", "assistant")
                                .put("text", "(به سقف مراحل اجرا رسیدم — با یک پیام تازه ادامه بده)"));
                        js("_aiFinal('به سقف ۱۲ مرحله رسیدم. بگو «ادامه بده» تا کار را تمام کنم.')");
                        aiBusy = false;
                    } catch (Exception e) {
                        js("_aiError(" + JSONObject.quote(String.valueOf(e)) + ")");
                        aiBusy = false;
                    }
                }
            }
        }).start();
    }

    private JSONArray trimAiHist() throws Exception {
        if (aiHist.length() <= 24) return aiHist;
        JSONArray t = new JSONArray();
        for (int i = aiHist.length() - 24; i < aiHist.length(); i++) t.put(aiHist.get(i));
        return t;
    }


    /* ================= موتور ساخت (کامپایل روی گوشی) ================= */

    private File sdkJar() { return new File(prefix, "android-sdk/android.jar"); }
    private File buildScript() { return new File(home, ".sandb0x/make-apk.sh"); }

    public boolean buildKitReady() {
        return new File(prefix, "bin/javac").canExecute()
                && new File(prefix, "bin/d8").canExecute()
                && new File(prefix, "bin/apksigner").canExecute()
                && sdkJar().isFile();
    }

    /** استخراج android.jar و اسکریپت بیلد از assets */
    public boolean ensureBuildAssets() {
        try {
            if (!sdkJar().isFile()) {
                sdkJar().getParentFile().mkdirs();
                File tmp = new File(getCacheDir(), "android.jar.part");
                try (GZIPInputStream gi = new GZIPInputStream(getAssets().open("android.jar.gz"));
                     FileOutputStream fo = new FileOutputStream(tmp)) {
                    pump(gi, fo, 200000000L, 200000000L);
                }
                if (tmp.length() > 10000000 && !tmp.renameTo(sdkJar())) {
                    try (FileInputStream in = new FileInputStream(tmp);
                         FileOutputStream out = new FileOutputStream(sdkJar())) {
                        pump(in, out, 200000000L, 200000000L);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            if (!buildScript().isFile()) {
                buildScript().getParentFile().mkdirs();
                try (InputStream in = getAssets().open("make-apk.sh");
                     FileOutputStream fo = new FileOutputStream(buildScript())) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) > 0) fo.write(b, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                buildScript().setExecutable(true, false);
            }
            return sdkJar().isFile() && buildScript().isFile();
        } catch (Exception e) {
            return false;
        }
    }

    public void installBuildKit() {
        ensureBuildAssets();
        pkgInstallAsync(new String[]{"openjdk-17", "aapt", "apksigner", "dx", "clang"});
    }

    /** پروژه‌ی نمونه برای آزمون بیلد */
    public void createSampleProject() {
        try {
            File proj = new File(home, "HelloApp");
            File src = new File(proj, "src/com/sandbox/hello");
            File res = new File(proj, "res/values");
            src.mkdirs();
            res.mkdirs();
            writeText(new File(proj, "AndroidManifest.xml"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    package=\"com.sandbox.hello\">\n" +
                    "    <application android:label=\"ساختِ خودم\">\n" +
                    "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n" +
                    "            <intent-filter>\n" +
                    "                <action android:name=\"android.intent.action.MAIN\"/>\n" +
                    "                <category android:name=\"android.intent.category.LAUNCHER\"/>\n" +
                    "            </intent-filter>\n" +
                    "        </activity>\n" +
                    "    </application>\n" +
                    "</manifest>\n");
            writeText(new File(res, "strings.xml"),
                    "<resources>\n  <string name=\"app_name\">HelloApp</string>\n</resources>\n");
            writeText(new File(src, "MainActivity.java"),
                    "package com.sandbox.hello;\n\n" +
                    "import android.app.Activity;\n" +
                    "import android.os.Bundle;\n" +
                    "import android.widget.TextView;\n\n" +
                    "public class MainActivity extends Activity {\n" +
                    "    @Override protected void onCreate(Bundle b) {\n" +
                    "        super.onCreate(b);\n" +
                    "        TextView tv = new TextView(this);\n" +
                    "        tv.setTextSize(22);\n" +
                    "        tv.setPadding(48, 96, 48, 48);\n" +
                    "        tv.setText(\"سلام! این اپ روی همین گوشی کامپایل شد 📦\");\n" +
                    "        setContentView(tv);\n" +
                    "    }\n" +
                    "}\n");
            js("_buildEvent('✅ پروژه‌ی نمونه در /home/HelloApp ساخته شد — دکمه‌ی «ساخت APK» را بزن')");
        } catch (Exception e) {
            js("_buildEvent('خطا در ساخت پروژه‌ی نمونه: " + e + "')");
        }
    }

    private String exportApkToDownloads(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, apk.getName());
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                        "application/vnd.android.package-archive");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/SandBox");
                Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    byte[] b = new byte[16384]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                return "Download/SandBox/" + apk.getName();
            } else {
                File d = new File(android.os.Environment
                        .getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS), "SandBox");
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                File dst = new File(d, apk.getName());
                try (InputStream in = new FileInputStream(apk);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] b = new byte[16384]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                return dst.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void buildApkAsync(final String projectDir) {
        new Thread(new Runnable() {
            @Override public void run() {
                js("_buildEvent('بررسی پیش‌نیازها…')");
                if (!ensureBuildAssets())
                    js("_buildEvent('⚠ فایل‌های بیلد آماده نشدند (android.jar)')");
                if (!buildKitReady()) {
                    js("_buildEvent('✖ بیلدکیت نصب نیست — اول دکمه‌ی «نصب بیلدکیت» را بزن (حدود ۱۰۰MB)')");
                    return;
                }
                File proj = projectDir.startsWith("/") && !projectDir.startsWith("/home")
                        ? aiResolve(projectDir, false)
                        : aiResolve("/home/" + projectDir.replaceFirst("^/home/?", ""), false);
                if (proj == null || !proj.isDirectory()) {
                    js("_buildEvent('✖ پوشه‌ی پروژه پیدا نشد: " + projectDir + " — در /home بگذار')");
                    return;
                }
                js("_buildEvent('🔨 بیلد " + proj.getName() + " شروع شد…')");
                ExecOut r = runShell("bash " + buildScript().getAbsolutePath()
                                + " " + sqPath(proj.getAbsolutePath()),
                        home.getAbsolutePath(), null, 900);
                js("_buildEvent(" + JSONObject.quote(r.out) + ")");
                if (r.out.contains("APK-OK")) {
                    for (String line : r.out.split("\n")) {
                        if (line.startsWith("APK-OK ")) {
                            File apk = new File(line.substring(7).trim());
                            String where = exportApkToDownloads(apk);
                            js("_buildDone(" + JSONObject.quote(
                                    "✅ APK ساخته شد: " + apk.getName() + " (" +
                                            (apk.length() / 1024) + "KB)"+
                                            (where != null ? " — کپی شد در: " + where : "")) + ")");
                            return;
                        }
                    }
                }
                js("_buildDone('✖ بیلد کامل نشد — لاگ بالا را ببین')");
            }
        }).start();
    }

    private static String sqPath(String p) { return "'" + p.replace("'", "'\\''") + "'"; }


    /* ================= ورک‌اسپیس (مدیریت فایل) ================= */

    private File wsResolve(String p, boolean write) {
        if (p == null) return null;
        p = p.trim();
        if (p.isEmpty()) return home;
        File base;
        String root;
        if (p.startsWith("/docs")) { base = docsDir; root = "/docs"; }
        else if (p.startsWith("/usr")) { if (write) return null; base = prefix; root = "/usr"; }
        else if (p.startsWith("/tmp")) { base = new File(prefix, "tmp"); root = "/tmp"; }
        else { base = home; root = "/home"; }
        String rel = p.startsWith(root) ? p.substring(root.length()) : p;
        if (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.contains("\u0000")) return null;
        File f = rel.isEmpty() ? base : new File(base, rel);
        try {
            String c = f.getCanonicalPath();
            String bc = base.getCanonicalPath();
            if (!c.equals(bc) && !c.startsWith(bc + "/")) return null;
        } catch (Exception e) { return null; }
        return f;
    }

    private String wsListInternal(String path) {
        try {
            File dir = wsResolve(path, false);
            JSONArray a = new JSONArray();
            if (dir == null || !dir.isDirectory()) return a.toString();
            File[] ls = dir.listFiles();
            if (ls == null) return a.toString();
            Arrays.sort(ls, new Comparator<File>() {
                @Override public int compare(File x, File y) {
                    if (x.isDirectory() != y.isDirectory())
                        return x.isDirectory() ? -1 : 1;
                    return x.getName().compareToIgnoreCase(y.getName());
                }
            });
            for (File f : ls) {
                try {
                    a.put(new JSONObject()
                            .put("name", f.getName())
                            .put("type", f.isDirectory() ? "dir" : "file")
                            .put("size", f.length())
                            .put("mtime", f.lastModified()));
                } catch (Exception ignored) { }
            }
            return a.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private boolean wsDeleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] ls = f.listFiles();
            if (ls != null) for (File c : ls) wsDeleteRecursive(c);
        }
        return f.delete();
    }

    private void zipWalk(File root, File dir, java.util.zip.ZipOutputStream zo) throws Exception {
        File[] ls = dir.listFiles();
        if (ls == null) return;
        Arrays.sort(ls);
        for (File f : ls) {
            if (f.isDirectory()) {
                zipWalk(root, f, zo);
            } else {
                String rel = f.getCanonicalPath().substring(root.getCanonicalPath().length() + 1);
                zo.putNextEntry(new java.util.zip.ZipEntry(rel));
                try (FileInputStream fi = new FileInputStream(f)) {
                    byte[] b = new byte[16384]; int n;
                    while ((n = fi.read(b)) > 0) zo.write(b, 0, n);
                }
                zo.closeEntry();
            }
        }
    }

    private String wsZipInternal(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return null;
            String name = dir.getName().isEmpty() ? "home" : dir.getName();
            File outDir = new File(home, "build");
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
            File out = new File(outDir, name + "-" + System.currentTimeMillis() + ".zip");
            try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(
                    new FileOutputStream(out))) {
                zipWalk(dir, dir, zo);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void wsShareInternal(final File f) {
        try {
            Uri uri = null;
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeOf(f.getName()));
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/SandBox");
                uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(f);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        byte[] b = new byte[16384]; int n;
                        while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    }
                }
            }
            final Uri shareUri = uri;
            final String mime = mimeOf(f.getName());
            final String label = f.getName();
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(mime);
                        if (shareUri != null) {
                            i.putExtra(Intent.EXTRA_STREAM, shareUri);
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } else {
                            i.putExtra(Intent.EXTRA_TEXT, "SandBox: " + label);
                        }
                        startActivity(Intent.createChooser(i, "اشتراک " + label));
                    } catch (Exception e) {
                        toast("اشتراک ممکن نشد");
                    }
                }
            });
        } catch (Exception e) {
            toast("خطا در اشتراک");
        }
    }

    /* ================= خودتوسعه (سندباکس خودش را می‌سازد) ================= */

    private void copyAssetDir(String path, File dst) {
        try {
            String[] items = getAssets().list(path);
            if (items == null || items.length == 0) {
                dst.getParentFile().mkdirs();
                try (InputStream in = getAssets().open(path);
                     FileOutputStream o = new FileOutputStream(dst)) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            for (String it : items) copyAssetDir(path + "/" + it, new File(dst, it));
        } catch (Exception ignored) { }
    }

    private void ensureSelfKey() {
        File ks = new File(getFilesDir(), "selfbuild.jks");
        if (ks.isFile()) return;
        try (InputStream in = getAssets().open("selfkey/selfbuild.jks");
             FileOutputStream o = new FileOutputStream(ks)) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } catch (Exception ignored) { }
    }

    private void extractSelfSource() {
        File dir = new File(home, "SandBox-src");
        if (dir.isDirectory()) {
            toast("سورس از قبل هست: /home/SandBox-src");
            return;
        }
        copyAssetDir("selfsrc", dir);
        ensureSelfKey();
        toast("سورس Extract شد ✔ /home/SandBox-src");
        js("if(window.refresh)refresh()");
    }

    /** دروازه‌بان آپدیت خودتوسعه: مجوزها + امضا + پکیج */
    private static String shaHex(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private String runningCertSha256() {
        try {
            android.content.pm.Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                sigs = getPackageManager().getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo.getApkContentsSigners();
            } else {
                sigs = getPackageManager().getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNATURES).signatures;
            }
            return sigs != null && sigs.length > 0 ? shaHex(sigs[0].toByteArray()) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String apkCertSha256(File apk) {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) return "";
            return shaHex(pi.signatures[0].toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private static final java.util.HashSet<String> SELF_ALLOWED_PERMS =
            new java.util.HashSet<>(Arrays.asList(
                    "android.permission.INTERNET", "android.permission.WAKE_LOCK",
                    "android.permission.REQUEST_INSTALL_PACKAGES",
                    "android.permission.FOREGROUND_SERVICE"));

    /** بررسی کامل APK ساخته‌شده — فقط اگر کاملاً «خودِ ما» باشد اجازه‌ی نصب می‌دهد */
    private String verifySelfApk(File apk) {
        try {
            JSONObject res = new JSONObject();
            android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(), android.content.pm.PackageManager.GET_PERMISSIONS);
            if (pi == null) return res.put("ok", false).put("reason", "APK قابل خواندن نیست").toString();
            if (!getPackageName().equals(pi.packageName))
                return res.put("ok", false)
                        .put("reason", "پکیج متفاوت است: " + pi.packageName).toString();
            if (pi.requestedPermissions != null) {
                JSONArray bad = new JSONArray();
                for (String p : pi.requestedPermissions)
                    if (!SELF_ALLOWED_PERMS.contains(p)) bad.put(p);
                if (bad.length() > 0)
                    return res.put("ok", false)
                            .put("reason", "مجوز غیرمجاز: " + bad).toString();
            }
            String a = apkCertSha256(apk);
            String b = runningCertSha256();
            if (a.isEmpty() || b.isEmpty() || !a.equals(b))
                return res.put("ok", false)
                        .put("reason", "امضا با نسخه‌ی در حال اجرا فرق دارد — مسدود!").toString();
            return res.put("ok", true).put("reason", "پکیج ✔ مجوزها ✔ امضا ✔").toString();
        } catch (Exception e) {
            try { return new JSONObject().put("ok", false).put("reason", "خطای بررسی: " + e).toString(); }
            catch (Exception ignored) { return "{\"ok\":false}"; }
        }
    }

    private String selfInstallAction = "";
    private BroadcastReceiver selfInstallReceiver = null;

    private boolean canSelfInstall() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void requestSelfInstallGrant() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("اجازه‌ی «نصب اپ ناشناس» را به سندباکس بده، بعد دوباره «نصب آپدیت» را بزن");
        } catch (Exception e) {
            toast("باز کردن تنظیمات ممکن نشد");
        }
    }

    /** نصب اتمیک: بایت‌هایی که هش می‌شوند همان بایت‌هایی هستند که نصب می‌شوند */
    private void installViaSession(final File apk) {
        try {
            if (selfInstallReceiver != null) {
                try { unregisterReceiver(selfInstallReceiver); } catch (Exception ignored) { }
            }
            selfInstallAction = "com.sandbox.box.selfinst." + java.util.UUID.randomUUID();
            selfInstallReceiver = new BroadcastReceiver() {
                @Override public void onReceive(android.content.Context ctx, Intent data) {
                    int st = data.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
                    if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent ui = data.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (ui != null) {
                            ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try { startActivity(ui); } catch (Exception ignored) { }
                            toast("پنجره‌ی تأیید اندروید باز شد — با دقت بخوان و تأیید کن");
                        }
                    } else if (st == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                        toast("آپدیت نصب شد ✔");
                    } else {
                        toast("نصب رد شد");
                    }
                    try { unregisterReceiver(this); } catch (Exception ignored) { }
                }
            };
            registerReceiver(selfInstallReceiver, new android.content.IntentFilter(selfInstallAction));

            android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams sp =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            final int sid = pi.createSession(sp);
            android.content.pm.PackageInstaller.Session session = pi.openSession(sid);

            // استریم + هش هم‌زمان: بررسی و نصب روی یک جریان واحد
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("sandbox-apk", 0, apk.length())) {
                byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    md.update(b, 0, n);
                }
                session.fsync(out);
            }
            String streamedHash = shaHex(md.digest());
            String diskHash = sha256File(apk);
            if (!streamedHash.equals(diskHash)) {
                session.abandon();
                js("_selfVerified({\"ok\":false,\"reason\":\"فایل حین نصب تغییر کرد — مسدود (TOCTOU)!\"})");
                return;
            }
            // هش دیسک ثابت است → نتیجه‌ی verifySelfApk معتبر است

            PendingIntent p = PendingIntent.getBroadcast(this, sid,
                    new Intent(selfInstallAction), PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(p.getIntentSender());
            session.close();
        } catch (Exception e) {
            js("_selfVerified({\"ok\":false,\"reason\":\"خطای نصب: " +
                    String.valueOf(e).replace("\"", "'").replace("{", "(").replace("}", ")") + "\"})");
        }
    }

    private String sha256File(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return shaHex(md.digest());
    }

    private void installApkInternal(File f) {
        String verdict = verifySelfApk(f);
        boolean ok;
        try { ok = new JSONObject(verdict).optBoolean("ok"); }
        catch (Exception e) { ok = false; }
        js("_selfVerified(" + verdict + ")");
        if (!ok) {
            toast("⛔ آپدیت مسدود شد — لاگ ساخت را ببین");
            return;
        }
        if (!canSelfInstall()) {
            requestSelfInstallGrant();
            return;
        }
        installViaSession(f);
    }

    public void buildSelfAsync() {
        ensureSelfKey();
        if (new File(home, "SandBox-src").isDirectory() == false) {
            copyAssetDir("selfsrc", new File(home, "SandBox-src"));
        }
        new Thread(new Runnable() {
            @Override public void run() {
                js("_buildEvent('🪞 ساخت نسخه‌ی جدید خودِ برنامه…')");
                ensureBuildAssets();
                if (!buildKitReady()) {
                    js("_buildDone('✖ بیلدکیت نصب نیست — تب ساخت → نصب بیلدکیت')");
                    return;
                }
                ExecOut r = runShell("bash " + home.getAbsolutePath() + "/SandBox-src/build-self.sh",
                        home.getAbsolutePath(), null, 1200);
                js("_buildEvent(" + JSONObject.quote(r.out) + ")");
                for (String line : r.out.split("\n")) {
                    if (line.startsWith("SELF-APK-OK ")) {
                        String[] parts = line.substring(12).trim().split("\\|");
                        String path = parts[0];
                        String ver = parts.length > 1 ? parts[1] : "?";
                        String size = parts.length > 2 ? parts[2] : "?";
                        js("_selfReady(" + JSONObject.quote(path) + "," + JSONObject.quote(ver)
                                + "," + JSONObject.quote(size) + ")");
                        return;
                    }
                }
                js("_buildDone('✖ ساخت خودم کامل نشد — لاگ را ببین')");
            }
        }).start();
    }

    /** ترمیم محیط لینوکس از bootstrap.zip رله‌شده در اسناد — کاملاً جاوا، بدون شل */
    public void recoverEnvironmentAsync() {
        if (installing) return;
        installing = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File z = new File(docsDir, "bootstrap.zip");
                    if (!z.isFile() || z.length() < 10000000) {
                        toast("فایل bootstrap.zip در اسناد نیست");
                        installing = false;
                        return;
                    }
                    toast("🔧 ترمیم محیط لینوکس (چند دقیقه)…");
                    wsDeleteRecursive(prefix);
                    //noinspection ResultOfMethodCallIgnored
                    prefix.mkdirs();
                    //noinspection ResultOfMethodCallIgnored
                    home.mkdirs();
                    extractBootstrapZip(z, null);
                    makeSymlinks();
                    //noinspection ResultOfMethodCallIgnored
                    new File(prefix, "tmp").mkdirs();
                    ExecOut t = runShell("echo SBXOK", null, null, 10);
                    boolean ok = t.out.contains("SBXOK");
                    installing = false;
                    toast(ok ? "✅ محیط لینوکس ترمیم شد!" : "⚠ ترمیم کامل نشد — دوباره تلاش کن");
                    js("if(window.refresh)refresh()");
                } catch (Exception e) {
                    installing = false;
                    toast("خطای ترمیم: " + e);
                }
            }
        }).start();
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
        public String previewBase() {
            if (!previewOn) startPreviewServer();
            return previewOn ? "http://127.0.0.1:" + PREVIEW_PORT : "";
        }

        @JavascriptInterface
        public String listPreviewFiles() {
            JSONArray a = new JSONArray();
            collectPreviewFiles(home, "", a, 0);
            collectPreviewFiles(docsDir, "docs/", a, 0);
            return a.toString();
        }

        @JavascriptInterface
        public boolean buildKitReady() { return MainActivity.this.buildKitReady(); }

        @JavascriptInterface
        public void installBuildKit() { MainActivity.this.installBuildKit(); }

        @JavascriptInterface
        public void buildSample() { createSampleProject(); }

        @JavascriptInterface
        public void rebuildLinux() { rebuildLinuxAsync(); }

        @JavascriptInterface
        public void selfRecover() { recoverEnvironmentAsync(); }

        @JavascriptInterface
        public void selfExtract() { extractSelfSource(); }

        @JavascriptInterface
        public void selfBuild() { buildSelfAsync(); }

        @JavascriptInterface
        public String selfVerify(String path) {
            File f = wsResolve(path, false);
            return (f != null && f.isFile()) ? verifySelfApk(f)
                    : "{\"ok\":false,\"reason\":\"APK پیدا نشد\"}";
        }

        @JavascriptInterface
        public void selfInstall(String path) {
            File f = wsResolve(path, false);
            if (f != null && f.isFile()) installApkInternal(f);
            else toast("APK پیدا نشد: " + path);
        }

        @JavascriptInterface
        public void buildApk(String dir) { buildApkAsync(dir); }

        /* ---- ورک‌اسپیس ---- */

        @JavascriptInterface
        public String wsList(String path) { return wsListInternal(path); }

        @JavascriptInterface
        public String wsRead(String path) {
            File f = wsResolve(path, false);
            if (f == null || !f.isFile()) return null;
            try {
                String s = readText(f);
                return s.length() > 300000 ? s.substring(0, 300000) : s;
            } catch (Exception e) { return null; }
        }

        @JavascriptInterface
        public boolean wsWrite(String path, String content) {
            File f = wsResolve(path, true);
            if (f == null) return false;
            try {
                f.getParentFile().mkdirs();
                writeText(f, content == null ? "" : content);
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean wsMkdir(String path) {
            File f = wsResolve(path, true);
            return f != null && f.mkdirs();
        }

        @JavascriptInterface
        public boolean wsDelete(String path) {
            File f = wsResolve(path, true);
            return f != null && wsDeleteRecursive(f);
        }

        @JavascriptInterface
        public boolean wsRename(String from, String to) {
            File a = wsResolve(from, true);
            File b = wsResolve(to, true);
            return a != null && b != null && a.renameTo(b);
        }

        @JavascriptInterface
        public String wsZip(String path) {
            File dir = wsResolve(path, false);
            return wsZipInternal(dir);
        }

        @JavascriptInterface
        public void wsShare(String path) {
            File f = wsResolve(path, false);
            if (f != null && f.isFile()) wsShareInternal(f);
            else toast("فایل پیدا نشد");
        }

        @JavascriptInterface
        public void wsImport(String dir) {
            pendingImportDir = dir;
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

        /* ---- چت هوش مصنوعی ---- */

        @JavascriptInterface
        public String aiGetConfig() { return aiCfg().toString(); }

        @JavascriptInterface
        public void aiSetConfig(String provider, String key, String model, String base) {
            aiSaveCfg(provider, key, model, base);
        }

        @JavascriptInterface
        public void aiSend(String text) { aiSendAsync(text); }

        @JavascriptInterface
        public void aiClear() {
            aiHist = new JSONArray();
        }

        @JavascriptInterface
        public boolean aiBusy() { return aiBusy; }

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
            File destDir = pendingImportDir != null ? wsResolve(pendingImportDir, true) : null;
            if (destDir == null) destDir = docsDir;
            pendingImportDir = null;
            final File finalDestDir = destDir;
            new Thread(new Runnable() {
                @Override public void run() {
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(new File(finalDestDir, name))) {
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
