package com.deepseek.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Application-scoped engine state and process operations.
 *
 * This class deliberately has no Activity dependency: EngineService uses the same health
 * probe and spawn path after the UI has gone away or START_STICKY recreates the service.
 */
public final class EngineCore {
    private static final String TAG = "DeepSeekHarness";
    private static final String STATE_PREFS = "dsh_engine_state";
    private static final String DSH_PREFS = "dsh_prefs";
    private static final String SETUP_PREFS = "dsh_setup";
    private static final String KEY_PORT = "engine_port";
    private static final String KEY_STOPPED = "engine_stopped_by_user";
    private static final String KEY_DSHROOT = "engine_dshroot";
    private static final String REL_BINJS = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js";

    private static volatile int enginePort = -1;
    private static volatile Process nodeProcess;

    public interface NodeListener {
        void onTokenUrl(String tokenUrl);
    }

    private EngineCore() {}

    public static int enginePort(Context context) {
        int cached = enginePort;
        if (cached > 0) return cached;
        synchronized (EngineCore.class) {
            if (enginePort <= 0) {
                enginePort = defaultEnginePort(context);
            }
            return enginePort;
        }
    }

    public static void setEnginePort(Context context, int port) {
        if (port <= 0) return;
        enginePort = port;
        try {
            context.getSharedPreferences(DSH_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_PORT, port).apply();
        } catch (Throwable ignored) {}
    }

    public static void rememberDshroot(Context context, File dshroot) {
        if (dshroot == null) return;
        try {
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_DSHROOT, dshroot.getAbsolutePath()).apply();
        } catch (Throwable ignored) {}
    }

    public static void setUserStopped(Context context, boolean stopped) {
        try {
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_STOPPED, stopped).apply();
        } catch (Throwable t) {
            Log.w(TAG, "persist engine stop state", t);
        }
    }

    public static boolean isUserStopped(Context context) {
        try {
            return context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_STOPPED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean healthOk(Context context) {
        return isDshEngine(enginePort(context));
    }

    /** A listening port is not necessarily a ready DSH page, but it is enough to block a duplicate spawn. */
    public static boolean portListening(int port) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        }
    }

    public static boolean hasLiveNodeProcess() {
        Process process = nodeProcess;
        return process != null && process.isAlive();
    }

    public static void setNodeProcess(Process process) {
        nodeProcess = process;
    }

    public static boolean isDshEngine(int port) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1500);
            connection.setRequestProperty("User-Agent", "dsh-probe");
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code == 303 || code == 302) return true;
            if (code == 401) {
                String body = readBody(connection, 4096);
                return body != null && body.contains("dsh web authentication required");
            }
            if (code < 200 || code >= 500) return false;
            InputStream in = connection.getInputStream();
            try {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(chunk)) > 0 && total < 262144) {
                    body.write(chunk, 0, read);
                    total += read;
                }
                return body.toString("UTF-8").contains("<title>DeepSeek Harness</title>");
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Spawn exactly one node process. The process-handle and port checks are intentionally both
     * retained: the handle protects a live in-process launch, and the TCP probe protects a node
     * inherited after an Activity or app-process restart.
     */
    public static synchronized Process spawnNode(Context context, File payload, NodeListener listener) throws IOException {
        Context app = context.getApplicationContext();
        int port = enginePort(app);
        if (hasLiveNodeProcess()) {
            Log.w(TAG, "engine process already alive, skip duplicate spawn");
            return nodeProcess;
        }
        if (portListening(port)) {
            Log.w(TAG, "engine port " + port + " already listening, skip duplicate spawn");
            return null;
        }

        File dshroot = resolveDshroot(app, payload);
        File node = new File(payload, "runtime/bin/node");
        File binjs = new File(dshroot, REL_BINJS);
        File lib = new File(payload, "runtime/lib");
        File home = new File(payload, "dshhome");
        File bin = new File(payload, "bin");
        File tmp = new File(app.getCacheDir(), "tmp");
        if (!tmp.exists()) tmp.mkdirs();

        if (!node.exists()) throw new IOException("node binary missing");
        if (!binjs.exists()) throw new IOException("dsh bin.js missing");
        if (!node.canExecute()) node.setExecutable(true, false);

        ProcessBuilder builder = new ProcessBuilder(
                node.getAbsolutePath(), "--expose-internals", binjs.getAbsolutePath(),
                "web", "--host", "127.0.0.1", "--port", String.valueOf(port));
        Map<String, String> env = builder.environment();
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        File openssl = new File(payload, "runtime/etc/openssl.cnf");
        if (openssl.exists()) env.put("OPENSSL_CONF", openssl.getAbsolutePath());

        File gitDir = new File(payload, "git");
        env.put("GIT_EXEC_PATH", new File(gitDir, "libexec/git-core").getAbsolutePath());
        env.put("GIT_TEMPLATE_DIR", new File(gitDir, "templates").getAbsolutePath());
        env.put("GIT_PAGER", "cat");
        env.put("GIT_TERMINAL_PROMPT", "0");
        File caBundle = ensureSystemCaBundle(app, payload);
        if (caBundle != null) {
            env.put("SSL_CERT_FILE", caBundle.getAbsolutePath());
            env.put("CURL_CA_BUNDLE", caBundle.getAbsolutePath());
            env.put("GIT_SSL_CAINFO", caBundle.getAbsolutePath());
        }
        File gitConfig = ensureGitConfig(app, payload, caBundle);
        if (gitConfig != null) env.put("GIT_CONFIG_GLOBAL", gitConfig.getAbsolutePath());

        env.put("PATH", bin.getAbsolutePath() + ":"
                + new File(payload, "runtime/bin").getAbsolutePath() + ":"
                + new File(app.getFilesDir(), ".npm-global/bin").getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("HOME", app.getFilesDir().getAbsolutePath());
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("TERM", "xterm");
        File rishDex = new File(app.getFilesDir(), "rish/rish_shizuku.dex");
        env.put("SHIZUKU_DEX", rishDex.isFile() ? rishDex.getAbsolutePath() : "");
        File vscreenDex = new File(new File(Environment.getExternalStorageDirectory(), packageRoot(app)), "vscreen/vscreen_shizuku.jar");
        env.put("VS_DEX", vscreenDex.isFile() ? vscreenDex.getAbsolutePath() : "");

        String packageName = app.getPackageName();
        env.put("SHIZUKU_APP_ID", packageName);
        SharedPreferences dshPrefs = app.getSharedPreferences(DSH_PREFS, Context.MODE_PRIVATE);
        dshPrefs.edit()
                .putString("shizuku_app_id", packageName)
                .putInt(KEY_PORT, port)
                .putInt("a11y_port", port + 101)
                .putInt("voice_port", port + 200)
                .apply();
        env.put("SHIZUKU_AVAILABLE", shizukuAvailable() ? "1" : "0");
        env.put("ROOT_AVAILABLE", rootAvailable() ? "1" : "0");
        env.put("APP_LOCAL_TOKEN", localToken(app));
        env.put("APP_NOTIFY_PORT", String.valueOf(port + 1));
        env.put("APP_A11Y_PORT", String.valueOf(port + 101));
        env.put("APP_VOICE_PORT", String.valueOf(port + 200));
        String workspace = app.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
                .getString("workspace_path", null);
        if (workspace != null && !workspace.isEmpty()) env.put("DSH_WORKSPACE", workspace);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        nodeProcess = process;
        startLogReader(app, process, listener);
        return process;
    }

    private static File resolveDshroot(Context context, File payload) {
        try {
            String remembered = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DSHROOT, "");
            if (remembered != null && !remembered.isEmpty()) {
                File root = new File(remembered);
                if (new File(root, REL_BINJS).isFile()) return root;
            }
        } catch (Throwable ignored) {}
        File internal = new File(payload, "dshroot");
        if (new File(internal, REL_BINJS).isFile()) return internal;
        File external = new File(new File(Environment.getExternalStorageDirectory(), packageRoot(context)), "dshroot");
        if (new File(external, REL_BINJS).isFile()) return external;
        return internal;
    }

    private static void startLogReader(final Context context, final Process process, final NodeListener listener) {
        final File logFile = new File(context.getFilesDir(), "dsh-web.log");
        final File externalLog = new File(new File(Environment.getExternalStorageDirectory(), packageRoot(context)), "dsh-web.log");
        new Thread(new Runnable() {
            @Override public void run() {
                FileOutputStream log = null;
                FileOutputStream mirror = null;
                try {
                    log = new FileOutputStream(logFile, true);
                    try {
                        File parent = externalLog.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        mirror = new FileOutputStream(externalLog, true);
                    } catch (Throwable ignored) {}
                    InputStream input = process.getInputStream();
                    byte[] bytes = new byte[4096];
                    String carry = "";
                    int count;
                    while ((count = input.read(bytes)) > 0) {
                        log.write(bytes, 0, count);
                        log.flush();
                        if (mirror != null) {
                            try { mirror.write(bytes, 0, count); mirror.flush(); } catch (Throwable ignored) {}
                        }
                        String all = carry + new String(bytes, 0, count, "UTF-8");
                        int cut = all.lastIndexOf('\n');
                        carry = cut >= 0 ? all.substring(cut + 1) : all;
                        String complete = cut >= 0 ? all.substring(0, cut) : "";
                        for (String line : complete.split("\\n")) {
                            String text = line.trim();
                            if (!text.isEmpty()) Log.i(TAG, "node: " + text);
                            int start = text.indexOf("http://127.0.0.1");
                            int token = text.indexOf("?token=");
                            if (start >= 0 && token > start && listener != null) {
                                String url = text.substring(start);
                                int space = url.indexOf(' ');
                                listener.onTokenUrl(space > 0 ? url.substring(0, space) : url);
                            }
                        }
                    }
                    try { input.close(); } catch (Throwable ignored) {}
                } catch (IOException error) {
                    Log.w(TAG, "node log reader error", error);
                } finally {
                    try { if (log != null) log.close(); } catch (IOException ignored) {}
                    try { if (mirror != null) mirror.close(); } catch (IOException ignored) {}
                }
            }
        }, "node-log").start();
    }

    /**
     * v1.15.7：生成 git 的配置文件（ssh→https 改写 + git 用的 CA + 沿用用户自己的全局配置）。
     *
     * 为什么用文件而不是环境变量：内核给子进程的是「洗过的」父环境（dsh-subprocess 的
     * scrubbedParentEnv，SENSITIVE_ENV_PATTERN = /KEY|PASSWORD|SECRET|TOKEN/i），而 git 用
     * GIT_CONFIG_KEY_&lt;n&gt; 传配置 —— 含 "KEY" 会被洗掉、GIT_CONFIG_COUNT 却活下来，
     * 真机报 "error: missing config key GIT_CONFIG_KEY_0"。配置文件不受这套清洗影响。
     */
    private static File ensureGitConfig(Context context, File payload, File caBundle) {
        File output = new File(new File(payload, "runtime/etc"), "gitconfig");
        try {
            File parent = output.getParentFile();
            if (parent != null) parent.mkdirs();
            StringBuilder text = new StringBuilder();
            text.append("# DSH 手机版自动生成。每次启动引擎都会重写，改这里会被覆盖。\n");
            File userConfig = new File(context.getFilesDir(), ".gitconfig");
            if (userConfig.isFile() && !userConfig.getAbsolutePath().equals(output.getAbsolutePath())) {
                text.append("[include]\n\tpath = ").append(userConfig.getAbsolutePath()).append("\n");
            }
            text.append("[url \"https://github.com/\"]\n")
                    .append("\tinsteadOf = git+ssh://git@github.com/\n")
                    .append("\tinsteadOf = ssh://git@github.com/\n")
                    .append("\tinsteadOf = git@github.com:\n")
                    .append("[url \"https://gitee.com/\"]\n")
                    .append("\tinsteadOf = git+ssh://git@gitee.com/\n")
                    .append("\tinsteadOf = ssh://git@gitee.com/\n")
                    .append("\tinsteadOf = git@gitee.com:\n");
            if (caBundle != null) text.append("[http]\n\tsslCAInfo = ").append(caBundle.getAbsolutePath()).append("\n");
            FileOutputStream outputStream = new FileOutputStream(output);
            try {
                outputStream.write(text.toString().getBytes("UTF-8"));
            } finally {
                outputStream.close();
            }
            return output;
        } catch (Throwable error) {
            Log.w(TAG, "git config generation failed", error);
            return null;
        }
    }

    /**
     * v1.15.5：把 Android 系统 CA（/system/etc/security/cacerts/*.0，PEM）拼成一个 bundle 文件。
     *
     * 为什么必须自己做：内置 curl/git 依赖的 libcrypto 把 OPENSSLDIR 编译死指向 Termux 的
     * /data/data/com.termux/files/usr/etc/tls/cert.pem，本机没装 Termux → 一个 CA 都拿不到，
     * HTTPS 一律失败（实测 curl 报 (77) error adding trust anchors，git 报同一句）。
     * 为什么不指系统目录：对 CAfile，OpenSSL 是「文件不存在即硬报错」，不会回退到目录。
     * 缓存：证书数 + 最新 mtime 未变时复用，避免每次启动重写 ~750KB。
     */
    private static File ensureSystemCaBundle(Context context, File payload) {
        File output = new File(new File(payload, "runtime/etc"), "cacert.pem");
        try {
            File[] certificates = new File("/system/etc/security/cacerts").listFiles();
            if (certificates == null || certificates.length == 0) return output.isFile() ? output : null;
            int count = 0;
            long newest = 0L;
            for (File certificate : certificates) {
                if (certificate.isFile() && certificate.length() > 0) {
                    count++;
                    newest = Math.max(newest, certificate.lastModified());
                }
            }
            if (count == 0) return output.isFile() ? output : null;
            String stamp = count + ":" + newest;
            SharedPreferences prefs = context.getSharedPreferences(DSH_PREFS, Context.MODE_PRIVATE);
            if (output.isFile() && output.length() > 1024L && stamp.equals(prefs.getString("ca_bundle_stamp", ""))) return output;
            File parent = output.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream outputStream = new FileOutputStream(output);
            try {
                byte[] buffer = new byte[8192];
                for (File certificate : certificates) {
                    if (!certificate.isFile()) continue;
                    FileInputStream input = new FileInputStream(certificate);
                    try {
                        int read;
                        while ((read = input.read(buffer)) > 0) outputStream.write(buffer, 0, read);
                    } finally {
                        input.close();
                    }
                    outputStream.write('\n');
                }
            } finally {
                outputStream.close();
            }
            output.setReadable(true, false);
            prefs.edit().putString("ca_bundle_stamp", stamp).apply();
            return output;
        } catch (Throwable error) {
            Log.w(TAG, "system CA bundle failed", error);
            return output.isFile() ? output : null;
        }
    }

    private static String localToken(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(DSH_PREFS, Context.MODE_PRIVATE);
            String token = prefs.getString("local_token", "");
            if (token == null || token.length() < 16) {
                byte[] bytes = new byte[16];
                new SecureRandom().nextBytes(bytes);
                StringBuilder text = new StringBuilder();
                for (byte b : bytes) text.append(String.format("%02x", b));
                token = text.toString();
                prefs.edit().putString("local_token", token).apply();
            }
            return token;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean shizukuAvailable() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean rootAvailable() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy();
                } else {
                    process.waitFor();
                }
            } catch (Throwable ignored) {}
            return line != null && line.contains("uid=0");
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { if (process != null) process.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static String readBody(HttpURLConnection connection, int maxBytes) {
        try {
            InputStream input;
            try { input = connection.getInputStream(); } catch (Throwable ignored) { input = connection.getErrorStream(); }
            if (input == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) > 0 && total < maxBytes) {
                output.write(buffer, 0, read);
                total += read;
            }
            try { input.close(); } catch (Throwable ignored) {}
            return output.toString("UTF-8");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int defaultEnginePort(Context context) {
        String packageName = context == null ? "" : context.getPackageName();
        if (packageName.contains("beta")) return 3082;
        if (packageName.contains("compat")) return 3084;
        return 3080;
    }

    private static String packageRoot(Context context) {
        String packageName = context.getPackageName();
        return packageName.contains("beta") ? "DeepSeekHarnessLite"
                : packageName.contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness";
    }
}
