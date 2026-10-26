package net.dalerank.caesaria;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

/**
 * Minimal crash telemetry for the Android port.
 *
 * - On every start: dumps the tail of the system logcat ring buffer (which still
 *   contains the previous session, including native tombstones) into a file.
 * - If a crash marker from the previous run exists, the report is uploaded to
 *   the GitHub logs repository via the Issues API (token from
 *   assets/telemetry.properties, injected at build time from CI secrets; the
 *   token is never committed to the public repo).
 * - Java crashes are caught by an uncaught-exception handler that writes a
 *   crash file before rethrowing to the default handler.
 *
 * If no token is configured the reports stay on the device, so the developer
 * can still pull them manually; the game never crashes *because* of telemetry.
 */
public final class CrashReporter {

    private static final String TAG = "CaesarIA";
    private static File telemetryDir;
    private static String githubToken;
    private static String githubRepo; // e.g. "user/caesaria-logs"

    private CrashReporter() {}

    public static void init(Context context) {
        try {
            telemetryDir = new File(context.getFilesDir(), "telemetry");
            telemetryDir.mkdirs();

            loadConfig(context);
            dumpPreviousSessionLog();
            uploadPendingReports();

            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    try {
                        saveCrash(throwable);
                    } catch (Throwable ignored) {
                    }
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable);
                    }
                }
            });
        } catch (Throwable ignored) {
            // telemetry must never break the game
        }
    }

    private static void loadConfig(Context context) {
        githubToken = System.getenv("CAESARIA_TELEMETRY_TOKEN");
        githubRepo = System.getenv("CAESARIA_TELEMETRY_REPO");
        InputStream in = null;
        try {
            in = context.getAssets().open("telemetry.properties");
            Properties p = new Properties();
            p.load(in);
            if (githubToken == null || githubToken.isEmpty()) {
                githubToken = p.getProperty("github.token", "");
            }
            if (githubRepo == null || githubRepo.isEmpty()) {
                githubRepo = p.getProperty("github.repo", "");
            }
        } catch (Throwable ignored) {
            // no telemetry.properties: offline mode
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** Save the last ~3000 logcat lines (previous session included). */
    private static void dumpPreviousSessionLog() {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-t", "3000", "-v", "time")
                    .redirectErrorStream(true).start();
            String log = readAll(p.getInputStream());
            p.waitFor();
            File out = new File(telemetryDir, "previous_session.log");
            if (log.contains("FATAL") || log.contains("Fatal signal")
                    || log.contains("backtrace:") || log.contains("SDL")
                    || log.contains("caesaria")) {
                writeString(out, log);
            } else {
                out.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveCrash(Throwable t) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(deviceInfo().getBytes("UTF-8"));
        bos.write('\n');
        t.printStackTrace(new java.io.PrintWriter(bos));
        File out = new File(telemetryDir, "crash-"
                + System.currentTimeMillis() + ".txt");
        writeString(out, bos.toString("UTF-8"));
    }

    private static void uploadPendingReports() {
        if (githubToken == null || githubToken.isEmpty()
                || githubRepo == null || githubRepo.isEmpty()) {
            return; // offline mode
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                File[] files = telemetryDir.listFiles();
                if (files == null) return;
                for (File f : files) {
                    String name = f.getName();
                    boolean isCrash = name.startsWith("crash-") && name.endsWith(".txt");
                    boolean isPrev = name.equals("previous_session.log");
                    if (!isCrash && !isPrev) continue;
                    try {
                        String body = deviceInfo() + "\n\n"
                                + tail(readString(f), 60 * 1024);
                        String title = "[android] "
                                + (isCrash ? "Java crash" : "session log")
                                + " - " + Build.MODEL + " - "
                                + new SimpleDateFormat("yyyy-MM-dd HH:mm",
                                        Locale.US).format(new Date());
                        boolean ok = createIssue(title, body);
                        if (ok) {
                            // keep session log until a crash replaces it
                            if (isCrash) f.delete();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "caesaria-telemetry").start();
    }

    private static boolean createIssue(String title, String body) throws Exception {
        URL url = new URL("https://api.github.com/repos/" + githubRepo + "/issues");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization", "token " + githubToken);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Content-Type", "application/json");
            String json = "{\"title\": " + jsonQuote(title)
                    + ", \"body\": " + jsonQuote(body) + "}";
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.close();
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } finally {
            conn.disconnect();
        }
    }

    private static String deviceInfo() {
        String apkVersion;
        try {
            apkVersion = "0.5-android.1";
        } catch (Throwable e) {
            apkVersion = "?";
        }
        return "apk: " + apkVersion
                + "\ndevice: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nandroid: " + Build.VERSION.RELEASE + " (api "
                + Build.VERSION.SDK_INT + ")"
                + "\ntime: " + new Date().toString();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return bos.toString("UTF-8");
    }

    private static String readString(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            return readAll(fis);
        } finally {
            fis.close();
        }
    }

    private static void writeString(File f, String s) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(s.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    private static String tail(String s, int max) {
        if (s.length() <= max) return s;
        return "...\n" + s.substring(s.length() - max);
    }

    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
