package com.deepseek.harness;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Downloads and verifies the optional offline voice models into the app-private filesDir. */
final class VoiceModelStore {
    private static final String LOG_TAG = "dsh-voice";
    private static final String SENSE_REV = "2365baeacb507f821a0c8120fcee3d484dba7a07";
    private static final String SENSE_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/" + SENSE_REV + "/";
    private static final String SENSE_MODEL_SHA = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51";
    private static final long SENSE_MODEL_BYTES = 239233841L;
    private static final String SENSE_TOKENS_SHA = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc";
    private static final long SENSE_TOKENS_BYTES = 315894L;

    private static final String KWS_ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2";
    private static final String KWS_DIR = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile";

    // 国内直连 huggingface.co / github.com 大概率不可达（2026-10-15 真机实证：模型从未下载成功）。
    // 下载走「镜像优先、原源兜底」的候选链；镜像写成模板，{url}=完整原 URL，{path}=主机名后的路径。
    // 镜像列表可不重编追加：/sdcard/<pkgRoot>/voice/mirrors.txt，每行 `hf|gh <模板>`，# 开头为注释。
    private static final String ORIG_HF = "https://huggingface.co/";
    private static final String ORIG_GH = "https://github.com/";
    private static final String[] HF_MIRROR_TEMPLATES = { "https://hf-mirror.com/{path}" };
    private static final String[] GH_MIRROR_TEMPLATES = { "https://ghfast.top/{url}", "https://gh-proxy.com/{url}" };

    private static final Object ASR_LOCK = new Object();
    private static final Object KWS_LOCK = new Object();
    private static volatile String asrState = "pending";
    private static volatile String kwsState = "pending";
    private static volatile String asrError = "";
    private static volatile String kwsError = "";
    private static volatile boolean asrVerified = false;

    private VoiceModelStore() {}

    static File senseDir(Context context) {
        return new File(new File(context.getFilesDir(), "voice/models"), "sensevoice");
    }

    static File kwsDir(Context context) {
        return new File(new File(context.getFilesDir(), "voice/models"), KWS_DIR);
    }

    static String asrState() { return asrState; }
    static String kwsState() { return kwsState; }
    static String asrError() { return asrError; }
    static String kwsError() { return kwsError; }

    static boolean senseVoiceReady(Context context) {
        if (asrVerified) return true;
        File dir = senseDir(context);
        boolean ready = verifiedFile(new File(dir, "model.int8.onnx"), SENSE_MODEL_BYTES, SENSE_MODEL_SHA)
                && verifiedFile(new File(dir, "tokens.txt"), SENSE_TOKENS_BYTES, SENSE_TOKENS_SHA);
        if (ready) asrVerified = true;
        return ready;
    }

    static boolean kwsReady(Context context) {
        File dir = kwsDir(context);
        return new File(dir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").length() > 1000000L
                && new File(dir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").length() > 100000L
                && new File(dir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").length() > 50000L
                && new File(dir, "tokens.txt").length() > 1000L
                && new File(dir, "keywords.txt").length() > 0L;
    }

    static void prefetch(final Context context) {
        final Context app = context.getApplicationContext();
        if (!senseVoiceReady(app) && "downloading".equals(asrState) == false) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { ensureSenseVoice(app); }
                    catch (Throwable t) { asrError = safeMessage(t); asrState = "error"; }
                }
            }, "voice-model-asr-download").start();
        } else if (senseVoiceReady(app)) {
            asrState = "ready";
        }
        if (!kwsReady(app) && "downloading".equals(kwsState) == false) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { ensureKws(app); }
                    catch (Throwable t) { kwsError = safeMessage(t); kwsState = "error"; }
                }
            }, "voice-model-kws-download").start();
        } else if (kwsReady(app)) {
            kwsState = "ready";
        }
    }

    static boolean ensureSenseVoice(Context context) throws IOException {
        synchronized (ASR_LOCK) {
            if (senseVoiceReady(context)) { asrState = "ready"; return true; }
            asrState = "downloading";
            asrError = "";
            File dir = senseDir(context);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 SenseVoice 模型目录");
            List<String> modelSources = candidateUrls(context, ORIG_HF, SENSE_BASE + "model.int8.onnx");
            List<String> tokenSources = candidateUrls(context, ORIG_HF, SENSE_BASE + "tokens.txt");
            IOException last = null;
            for (int i = 0; i < modelSources.size(); i++) {
                try {
                    download(new URL(modelSources.get(i)), new File(dir, "model.int8.onnx"), SENSE_MODEL_BYTES, SENSE_MODEL_SHA);
                    download(new URL(tokenSources.get(i)), new File(dir, "tokens.txt"), SENSE_TOKENS_BYTES, SENSE_TOKENS_SHA);
                    asrVerified = true;
                    asrState = "ready";
                    return true;
                } catch (IOException e) {
                    last = e;
                    Log.w(LOG_TAG, "SenseVoice 下载源失败（第 " + (i + 1) + "/" + modelSources.size() + " 个候选）：" + e.getMessage());
                }
            }
            throw last != null ? last : new IOException("无可用下载源");
        }
    }

    static boolean ensureKws(Context context) throws IOException {
        synchronized (KWS_LOCK) {
            if (kwsReady(context)) { kwsState = "ready"; return true; }
            kwsState = "downloading";
            kwsError = "";
            File dir = kwsDir(context);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 KWS 模型目录");
            File archive = new File(dir.getParentFile(), "kws-model.tar.bz2.part");
            List<String> sources = candidateUrls(context, ORIG_GH, KWS_ARCHIVE_URL);
            IOException last = null;
            for (int i = 0; i < sources.size(); i++) {
                try {
                    download(new URL(sources.get(i)), archive, -1L, null);
                    extractKwsArchive(archive, dir);
                    writeWakeKeywords(new File(dir, "keywords.txt"));
                    archive.delete();
                    if (!kwsReady(context)) throw new IOException("KWS 模型文件不完整");
                    kwsState = "ready";
                    return true;
                } catch (IOException e) {
                    last = e;
                    Log.w(LOG_TAG, "KWS 下载源失败（第 " + (i + 1) + "/" + sources.size() + " 个候选）：" + e.getMessage());
                    // KWS 包无 SHA 兜底，跨源续传可能拼出"能解压但内容烂"的包 → 换源前清残件重下
                    new File(archive.getAbsolutePath() + ".part").delete();
                    archive.delete();
                }
            }
            throw last != null ? last : new IOException("无可用下载源");
        }
    }

    /**
     * 构建一个文件的候选下载 URL 列表：用户配置镜像 → 内建镜像 → 原源。
     * kind = ORIG_HF（huggingface）或 ORIG_GH（github releases），决定模板按 {path}/{url} 哪种展开。
     */
    private static List<String> candidateUrls(Context context, String kind, String originalUrl) {
        List<String> out = new ArrayList<String>();
        String path = originalUrl.startsWith(kind) ? originalUrl.substring(kind.length()) : originalUrl;
        for (String tpl : userMirrors(context, kind)) {
            String u = tpl.replace("{url}", originalUrl).replace("{path}", path);
            if (u.startsWith("http")) out.add(u);
        }
        for (String tpl : ORIG_HF.equals(kind) ? HF_MIRROR_TEMPLATES : GH_MIRROR_TEMPLATES) {
            out.add(tpl.replace("{url}", originalUrl).replace("{path}", path));
        }
        out.add(originalUrl);
        return out;
    }

    /** 读 /sdcard/<pkgRoot>/voice/mirrors.txt 的自定义镜像（每行 `hf|gh <模板>`），失败/不存在返回空。 */
    private static List<String> userMirrors(Context context, String kind) {
        List<String> out = new ArrayList<String>();
        try {
            File base = new File(Environment.getExternalStorageDirectory(), pkgRoot(context) + "/voice");
            File file = new File(base, "mirrors.txt");
            if (!file.isFile()) return out;
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int space = line.indexOf(' ');
                String tag = space > 0 ? line.substring(0, space).trim().toLowerCase() : "";
                String tpl = space > 0 ? line.substring(space + 1).trim() : "";
                boolean match = ORIG_HF.equals(kind) ? "hf".equals(tag) : "gh".equals(tag);
                if (match && tpl.startsWith("http")) out.add(tpl);
            }
            in.close();
        } catch (Throwable ignored) {}
        return out;
    }

    private static String pkgRoot(Context context) {
        String p = context.getPackageName();
        return p.contains("beta") ? "DeepSeekHarnessLite"
                : p.contains("compat") ? "DeepSeekHarnessCompat" : "DeepSeekHarness";
    }

    /** 可重试的下载失败（429 / 5xx / 连接超时）—— 换源之前先退避重试同一源。 */
    private static final class RetryableDownloadException extends IOException {
        RetryableDownloadException(String message) { super(message); }
    }

    /**
     * 退避重试包装：429 / 5xx / 连接超时 → 重试**同一个 URL**（2s / 5s / 15s），
     * 三次都失败才抛出去、交给上层换下一个候选源。
     * —— 之前是一次 429 就换源（把最好的镜像废掉、换到被墙的原源），实测踩过。
     */
    private static void download(URL url, File target, long expectedBytes, String expectedSha) throws IOException {
        final long[] backoff = { 2000L, 5000L, 15000L };
        IOException last = null;
        for (int attempt = 0; attempt <= backoff.length; attempt++) {
            try {
                downloadAttempt(url, target, expectedBytes, expectedSha);
                return;
            } catch (RetryableDownloadException e) {
                last = e;
                if (attempt < backoff.length) {
                    try { Thread.sleep(backoff[attempt]); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("模型下载被中断", ie); }
                }
            }
        }
        throw last;
    }

    private static void downloadAttempt(URL url, File target, long expectedBytes, String expectedSha) throws IOException {
        File partial = new File(target.getAbsolutePath() + ".part");
        HttpURLConnection connection = null;
        long offset = partial.isFile() ? partial.length() : 0L;
        if (expectedBytes > 0 && offset > expectedBytes) { partial.delete(); offset = 0L; }
        if (expectedBytes > 0 && offset == expectedBytes) {
            MessageDigest check = sha256();
            updateDigestFromFile(check, partial);
            if (expectedSha == null || expectedSha.equals(hex(check.digest()))) {
                if (target.exists()) target.delete();
                if (!partial.renameTo(target)) throw new IOException("无法保存模型文件：" + target.getName());
                return;
            }
            partial.delete();
            offset = 0L;
        }
        MessageDigest digest = sha256();
        long total = 0L;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "DeepSeekHarness-Voice/1.0");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = connection.getResponseCode();
            if (code == 416 && offset > 0) {
                partial.delete();
                throw new RetryableDownloadException("模型服务器拒绝续传（将从头重试）");
            }
            // 429 限流 / 5xx 服务端错 —— 都是"瞬时可恢复"，退避重试同一源，别立刻换源。
            if (code == 429 || (code >= 500 && code <= 599))
                throw new RetryableDownloadException("模型下载 HTTP " + code + "（可重试）：" + url.getHost());
            if (code < 200 || code >= 300) throw new IOException("模型下载 HTTP " + code + "：" + url.getHost());
            boolean append = offset > 0 && code == HttpURLConnection.HTTP_PARTIAL;
            if (append) {
                updateDigestFromFile(digest, partial);
                total = offset;
            } else {
                offset = 0L;
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(partial, append), 65536)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    total += count;
                    if (total > 600000000L) throw new IOException("模型下载超过 600 MB 上限");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
            }
            if (expectedBytes > 0 && total != expectedBytes) throw new IOException("模型下载未完成，当前 " + total + " / " + expectedBytes + " 字节");
            if (expectedSha != null && !expectedSha.equals(hex(digest.digest()))) {
                partial.delete();
                throw new IOException("模型 SHA-256 校验失败");
            }
            if (target.exists() && !target.delete()) throw new IOException("无法替换模型文件：" + target.getName());
            if (!partial.renameTo(target)) throw new IOException("无法保存模型文件：" + target.getName());
        } catch (java.net.SocketTimeoutException e) {
            throw new RetryableDownloadException("模型下载超时（可重试）：" + url.getHost());
        } catch (java.net.ConnectException e) {
            throw new RetryableDownloadException("模型下载连不上（可重试）：" + url.getHost());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void updateDigestFromFile(MessageDigest digest, File file) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        in.close();
    }

    private static void extractKwsArchive(File archive, File dest) throws IOException {
        Set<String> wanted = new HashSet<String>();
        wanted.add("encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
        wanted.add("decoder-epoch-12-avg-2-chunk-16-left-64.onnx");
        wanted.add("joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
        wanted.add("tokens.txt");
        InputStream file = new BufferedInputStream(new FileInputStream(archive));
        TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(file, true));
        byte[] buffer = new byte[32768];
        TarArchiveEntry entry;
        while ((entry = tar.getNextTarEntry()) != null) {
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            int slash = name.lastIndexOf('/');
            String base = slash >= 0 ? name.substring(slash + 1) : name;
            if (!wanted.contains(base) || name.contains("..") || name.startsWith("/")) continue;
            File outFile = new File(dest, base);
            FileOutputStream out = new FileOutputStream(outFile);
            int n;
            while ((n = tar.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
            out.close();
            wanted.remove(base);
        }
        tar.close();
        if (!wanted.isEmpty()) throw new IOException("KWS 压缩包缺少文件：" + wanted);
    }

    private static void writeWakeKeywords(File file) throws IOException {
        // sherpa-onnx Chinese phone+ppinyin tokens for “小鲸鱼” (xiao3 jing1 yu2).
        byte[] data = "x iǎo j īng y ú @小鲸鱼\n".getBytes("UTF-8");
        FileOutputStream out = new FileOutputStream(file, false);
        out.write(data);
        out.close();
    }

    private static boolean verifiedFile(File file, long bytes, String sha) {
        if (!file.isFile() || file.length() != bytes) return false;
        try {
            MessageDigest md = sha256();
            InputStream in = new BufferedInputStream(new FileInputStream(file));
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) md.update(buffer, 0, n);
            in.close();
            return sha.equals(hex(md.digest()));
        } catch (Throwable ignored) { return false; }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }
}
