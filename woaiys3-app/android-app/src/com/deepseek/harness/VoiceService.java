package com.deepseek.harness;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.WaveData;
import com.k2fsa.sherpa.onnx.WaveReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 语音服务（B 路线起点）：把「手机上的语音能力」做成桥的原子接口，插件层（纯 Node）负责编排。
 *
 * 与 AccessibilityService 同构：前台服务 + 手搓 ServerSocket + LocalAuth 鉴权 + JSON 响应。
 * 端口 = enginePort + 200（3080 → 3280；与 notify=+1、a11y=+101 同一派生体系）。
 *
 * 能力包括：
 *   ① 系统 TTS 播报（android.speech.tts.TextToSpeech）—— 开箱即用 /tts /tts-stop
 *   ② 系统语音识别（android.speech.SpeechRecognizer）—— /listen 一次性听写（依赖设备语音服务）
 *   ③ 麦克风采集（AudioRecord → 16k 单声道 PCM16 WAV）—— /record /stop
 *   ④ 离线 ASR / 唤醒词（sherpa-onnx SenseVoice + zipformer KWS）—— /recognize /wake；
 *      模型首启从固定版本下载到 filesDir 并校验。
 *
 * 半双工闸（抄 Luwi 的结构性解法）：
 *   系统 TTS 的 onDone 常早报 → 用「按字数估的尾巴预算」把闸多关一会儿（见 isSpeaking()）；
 *   TTS 播报和尾音期间 KWS 与 VAD 都不消费音频，并丢弃 VAD 半句；
 *   一路 AudioRecord 同时喂给唤醒识别与指令 VAD。
 *
 * 静态状态里不含任何密钥；引擎侧插件一律带 X-DSH-Token 访问（LocalAuth）。
 */
public class VoiceService extends Service {

    private static final String TAG = "dsh-voice";
    private static final String PREFS = "dsh_prefs";
    private static final String KEY_VOICE_PORT = "voice_port";
    private static final String CHANNEL_ID = "dsh_voice";
    private static final int NOTIF_ID = 3;

    /** 单连接读头超时（长轮询 /event 会占住连接，要大于 poll 上限 60s）。 */
    private static final int SOCKET_TIMEOUT_MS = 90000;
    /** 录音硬上限（插件给的 maxMs 再大也不超过它）。 */
    private static final int MAX_RECORD_MS = 120000;
    private static final int DEFAULT_RECORD_MS = 30000;
    private static final int WAKE_SAMPLE_RATE = 16000;
    private static final int WAKE_FRAME_SAMPLES = 1600; // 100 ms mono PCM16

    private ServerSocket serverSocket;
    private volatile boolean serverRunning = false;

    // ---- TTS ----
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean ttsActive = false;      // onStart 与 onDone 之间
    private volatile long speakGateUntil = 0;        // 估算的可闻结束时刻（尾巴预算）
    private volatile String lastSpokenText = "";
    private int uttSeq = 0;

    // ---- 录音 ----
    private AudioRecord recorder;
    private Thread recordThread;
    private volatile boolean recording = false;
    private volatile boolean recordStopFlag = false;
    private volatile long recordStartedAt = 0;
    private volatile File lastWav = null;
    private volatile long lastWavDurationMs = 0;
    private volatile long lastWavBytes = 0;

    // ---- 离线 sherpa 引擎 ----
    private final Object asrLock = new Object();
    private volatile OfflineRecognizer offlineRecognizer;

    // ---- 唤醒与 VAD 共用的一路 AudioRecord ----
    private volatile boolean wakeStop = false;
    private volatile boolean wakeRunning = false;
    private volatile AudioRecord wakeRecorder;
    private volatile Thread wakeThread;
    private final Object wakeLock = new Object();

    // ---- 系统听写 ----
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean listening = false;
    private SpeechRecognizer recognizer;

    // ---- 事件流（/event 长轮询）----
    private final Object eventsLock = new Object();
    private final ArrayDeque<JSONObject> events = new ArrayDeque<JSONObject>();
    private long eventSeq = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification("语音服务就绪", "离线语音模型准备中"));
        initTts();
        startServer(voicePort());
        VoiceModelStore.prefetch(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification("语音服务就绪", "离线语音模型：" + VoiceModelStore.asrState()));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        serverRunning = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        stopWakeMonitor();
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        synchronized (asrLock) {
            try { if (offlineRecognizer != null) offlineRecognizer.release(); } catch (Throwable ignored) {}
            offlineRecognizer = null;
        }
        stopRecordingInternal();
        mainHandler.post(new Runnable() { @Override public void run() {
            try { if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; } } catch (Throwable ignored) {}
        }});
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
        super.onDestroy();
    }

    /** 语音端口：与 AccessibilityService 同一口径（按包名派生引擎端口 + 200）。 */
    private int voicePort() {
        int port = 3080 + 200;
        try {
            String pkg = getPackageName();
            int defaultEngine = pkg.contains("beta") ? 3082 : pkg.contains("compat") ? 3084 : 3080;
            port = defaultEngine + 200;
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (prefs.getInt(KEY_VOICE_PORT, port) != port) {
                prefs.edit().putInt(KEY_VOICE_PORT, port).apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "derive voice port failed", t);
        }
        return port;
    }

    // ======================= TTS =======================

    private void initTts() {
        try {
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) {
                        Log.w(TAG, "TTS init failed: " + status);
                        ttsReady = false;
                        return;
                    }
                    // 语言选择：默认 → 中文 → 英文，全失败也不崩（ttsReady 保持 false）
                    int r = tts.setLanguage(Locale.getDefault());
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        r = tts.setLanguage(Locale.CHINA);
                    }
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        r = tts.setLanguage(Locale.US);
                    }
                    ttsReady = (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {
                            ttsActive = true;
                        }
                        @Override public void onDone(String utteranceId) {
                            // 系统 TTS 的 onDone 常早报：不直接放开，交给 speakGateUntil 的尾巴预算
                            ttsActive = false;
                            pushEvent("speak.done", null);
                        }
                        @Override public void onError(String utteranceId) {
                            ttsActive = false;
                            speakGateUntil = System.currentTimeMillis();
                            pushEvent("speak.error", null);
                        }
                    });
                    Log.i(TAG, "TTS ready=" + ttsReady);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "TTS create failed", t);
            ttsReady = false;
        }
    }

    /** 按字数估播报时长（Luwi 尾巴预算思路）：~180ms/字 + 600ms 起播余量。 */
    private static long estimateSpeechMs(String text) {
        int len = text == null ? 0 : text.length();
        long ms = 180L * len + 600L;
        if (ms < 800) ms = 800;
        if (ms > 60000) ms = 60000;
        return ms;
    }

    /** 是否仍在「说话」：onDone 提前到 → 用预算把闸关满；onDone 迟到 → 超时 10s 视为 stuck 放行。 */
    private boolean isSpeaking() {
        long now = System.currentTimeMillis();
        if (ttsActive) {
            if (now > speakGateUntil + 10000) { ttsActive = false; return false; }
            return true;
        }
        if (now < speakGateUntil) return true;
        // 有些 TTS 引擎 onDone 早于扬声器尾音；短暂尾巴期内再用系统音乐状态兜底。
        if (now < speakGateUntil + 2500) {
            try {
                AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                return audio != null && audio.isMusicActive();
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private String handleTts(String body, String path) {
        try {
            String text = "";
            boolean interrupt = true;
            if (body != null && !body.trim().isEmpty()) {
                JSONObject b = new JSONObject(body);
                text = b.optString("text", "");
                if (b.has("interrupt")) interrupt = b.optBoolean("interrupt", true);
            } else {
                text = queryParam(path, "text");
            }
            if (text == null) text = "";
            text = text.trim();
            if (text.isEmpty()) return jsonError("缺少参数 text");
            if (!ttsReady || tts == null) return jsonError("系统 TTS 未就绪（设备无可用语音引擎？请在系统设置安装/启用 TTS）");
            lastSpokenText = text;
            speakGateUntil = System.currentTimeMillis() + estimateSpeechMs(text);
            ttsActive = true;
            Bundle params = new Bundle();
            String uttId = "dsh-voice-" + (++uttSeq);
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uttId);
            int mode = interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int r = tts.speak(text, mode, params, uttId);
            if (r != TextToSpeech.SUCCESS) {
                ttsActive = false;
                speakGateUntil = System.currentTimeMillis();
                return jsonError("TTS speak 调用失败（code=" + r + "）");
            }
            pushEvent("speak.start", null);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("speaking", true);
            o.put("estimateMs", estimateSpeechMs(text));
            return o.toString();
        } catch (Throwable t) {
            return jsonError("tts error: " + t.getMessage());
        }
    }

    private String handleTtsStop() {
        try {
            if (tts != null) tts.stop();
            ttsActive = false;
            speakGateUntil = System.currentTimeMillis();
            pushEvent("speak.stopped", null);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return o.toString();
        } catch (Throwable t) {
            return jsonError("tts-stop error: " + t.getMessage());
        }
    }

    // ======================= 麦克风采集（AudioRecord → WAV） =======================

    private boolean micGranted() {
        try {
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private String handleRecord(String body) {
        try {
            if (!micGranted()) return jsonError("麦克风权限未授予：请在系统设置 → 应用 → DeepSeek Harness → 权限 → 麦克风 中开启");
            if (isSpeaking()) return jsonError("正在播报（半双工闸）：请等播报结束后再录音");
            if (wakeRunning) return jsonError("唤醒监听正在占用麦克风：请先 voice_wake stop，再录音");
            if (recording) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("recording", true);
                o.put("note", "已在录音");
                return o.toString();
            }
            int maxMs = DEFAULT_RECORD_MS;
            if (body != null && !body.trim().isEmpty()) {
                try { maxMs = new JSONObject(body).optInt("maxMs", DEFAULT_RECORD_MS); } catch (Throwable ignored) {}
            }
            if (maxMs <= 0) maxMs = DEFAULT_RECORD_MS;
            if (maxMs > MAX_RECORD_MS) maxMs = MAX_RECORD_MS;

            File dir = new File(getFilesDir(), "voice");
            if (!dir.exists() && !dir.mkdirs()) return jsonError("无法创建录音目录");
            final File out = new File(dir, "rec_" + System.currentTimeMillis() + ".wav");

            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf > 0 ? minBuf : 8192, 16384);
            AudioRecord rec;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            } catch (Throwable t) {
                return jsonError("AudioRecord 创建失败: " + t.getMessage());
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                try { rec.release(); } catch (Throwable ignored) {}
                return jsonError("麦克风被占用或不可用（AudioRecord 未初始化）");
            }
            recorder = rec;
            recording = true;
            recordStopFlag = false;
            recordStartedAt = System.currentTimeMillis();
            final int maxMsF = maxMs;
            rec.startRecording();
            pushEvent("record.start", null);

            recordThread = new Thread(new Runnable() {
                @Override public void run() {
                    FileOutputStream fos = null;
                    long total = 0;
                    try {
                        fos = new FileOutputStream(out);
                        byte[] header = wavHeader(0);
                        fos.write(header);
                        byte[] buf = new byte[3200];
                        while (!recordStopFlag) {
                            if (System.currentTimeMillis() - recordStartedAt > maxMsF) break;
                            int n = recorder.read(buf, 0, buf.length);
                            if (n > 0) { fos.write(buf, 0, n); total += n; }
                            else if (n < 0) break;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "record loop error", t);
                    } finally {
                        try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                        // 回填 WAV 头（RIFF/data 两个尺寸字段）
                        try {
                            RandomAccessFile raf = new RandomAccessFile(out, "rw");
                            long[] offs = {4, 40};
                            long[] vals = {36 + total, total};
                            for (int i = 0; i < offs.length; i++) {
                                raf.seek(offs[i]);
                                long v = vals[i];
                                raf.write((byte) (v & 0xff));
                                raf.write((byte) ((v >> 8) & 0xff));
                                raf.write((byte) ((v >> 16) & 0xff));
                                raf.write((byte) ((v >> 24) & 0xff));
                            }
                            raf.close();
                        } catch (Throwable t) { Log.w(TAG, "patch wav header failed", t); }
                        try { recorder.stop(); } catch (Throwable ignored) {}
                        try { recorder.release(); } catch (Throwable ignored) {}
                        recorder = null;
                        recording = false;
                        lastWav = out;
                        lastWavBytes = total;
                        long dur = (long) (total / 2.0 / 16000.0 * 1000.0); // PCM16 mono 16k
                        lastWavDurationMs = dur;
                        try {
                            JSONObject d = new JSONObject();
                            d.put("wav", out.getAbsolutePath());
                            d.put("durationMs", dur);
                            pushEvent("record.done", d);
                        } catch (Throwable ignored) {}
                    }
                }
            }, "voice-record");
            recordThread.start();

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("recording", true);
            o.put("maxMs", maxMs);
            return o.toString();
        } catch (Throwable t) {
            return jsonError("record error: " + t.getMessage());
        }
    }

    private void stopRecordingInternal() {
        recordStopFlag = true;
        Thread t = recordThread;
        if (t != null) {
            try { t.join(3000); } catch (Throwable ignored) {}
        }
    }

    private String handleStop() {
        try {
            if (!recording) {
                // 录音可能已因 maxMs 自动结束：把这条会话的产物如实交回（而非报错让插件抓瞎）
                if (lastWav != null && System.currentTimeMillis() - recordStartedAt < 10 * 60 * 1000L) {
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("wav", lastWav.getAbsolutePath());
                    o.put("durationMs", lastWavDurationMs);
                    o.put("bytes", lastWavBytes);
                    o.put("note", "录音已自动结束（达到 maxMs）");
                    return o.toString();
                }
                return jsonError("当前没有在录音");
            }
            stopRecordingInternal();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("wav", lastWav != null ? lastWav.getAbsolutePath() : "");
            o.put("durationMs", lastWavDurationMs);
            o.put("bytes", lastWavBytes);
            return o.toString();
        } catch (Throwable t) {
            return jsonError("stop error: " + t.getMessage());
        }
    }

    /** 44 字节标准 WAV 头（PCM16 / 单声道 / 16k），尺寸字段先填 0，停止时回填。 */
    private static byte[] wavHeader(int dataBytes) {
        int byteRate = 16000 * 2;
        byte[] h = new byte[44];
        // "RIFF"
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putLe32(h, 4, 36 + dataBytes);
        // "WAVE"
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        // "fmt "
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putLe32(h, 16, 16);
        putLe16(h, 20, 1);            // PCM
        putLe16(h, 22, 1);            // mono
        putLe32(h, 24, 16000);        // sample rate
        putLe32(h, 28, byteRate);
        putLe16(h, 32, 2);            // block align
        putLe16(h, 34, 16);           // bits
        // "data"
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putLe32(h, 40, dataBytes);
        return h;
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    // ======================= 系统听写（SpeechRecognizer） =======================

    private boolean systemAsrAvailable() {
        try {
            return SpeechRecognizer.isRecognitionAvailable(this);
        } catch (Throwable t) {
            return false;
        }
    }

    private String handleListen(String body, String path) {
        boolean resumeWake = false;
        try {
            if (!micGranted()) return jsonError("麦克风权限未授予：请在系统设置 → 应用 → DeepSeek Harness → 权限 → 麦克风 中开启");
            if (isSpeaking()) return jsonError("正在播报（半双工闸）：请等播报结束后再说");
            if (!systemAsrAvailable()) return jsonError("设备无系统语音识别服务；可用 voice_recognize 对录音做离线识别");
            if (listening) return jsonError("已有一次听写在途，请稍候");
            if (recording) return jsonError("正在录音：请先 /voice/stop");
            resumeWake = wakeRunning;
            if (resumeWake) stopWakeMonitor(); // SpeechRecognizer 与常驻 KWS 轮流使用麦克风

            long timeoutMs = 20000;
            String lang = "zh-CN";
            if (body != null && !body.trim().isEmpty()) {
                try {
                    JSONObject b = new JSONObject(body);
                    timeoutMs = b.optLong("timeoutMs", 20000);
                    // lang 优先取 body（插件就是这么发的）；旧版只认 query，参数被静默忽略
                    String bl = b.optString("lang", "");
                    if (bl != null && !bl.isEmpty()) lang = bl;
                } catch (Throwable ignored) {}
            }
            if (path != null && path.contains("lang=")) {
                String l = queryParam(path, "lang");
                if (l != null && !l.isEmpty()) lang = l;
            }
            if (timeoutMs < 3000) timeoutMs = 3000;
            if (timeoutMs > 60000) timeoutMs = 60000;

            final Object lock = new Object();
            final String[] resultText = {null};
            final String[] resultErr = {null};
            final boolean[] finished = {false};
            final CountDownLatch latch = new CountDownLatch(1);
            listening = true;

            final String langF = lang;
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (recognizer != null) { try { recognizer.destroy(); } catch (Throwable ignored) {} }
                        recognizer = SpeechRecognizer.createSpeechRecognizer(VoiceService.this);
                        recognizer.setRecognitionListener(new RecognitionListener() {
                            @Override public void onReadyForSpeech(Bundle params) {}
                            @Override public void onBeginningOfSpeech() {}
                            @Override public void onRmsChanged(float rmsdB) {}
                            @Override public void onBufferReceived(byte[] buffer) {}
                            @Override public void onEndOfSpeech() {}
                            @Override public void onPartialResults(Bundle partialResults) {}
                            @Override public void onEvent(int eventType, Bundle params) {}
                            @Override public void onResults(Bundle results) {
                                synchronized (lock) {
                                    if (finished[0]) return;
                                    finished[0] = true;
                                    ArrayList<String> list = results != null
                                            ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                                    resultText[0] = (list != null && !list.isEmpty()) ? list.get(0) : "";
                                    latch.countDown();
                                }
                            }
                            @Override public void onError(int error) {
                                synchronized (lock) {
                                    if (finished[0]) return;
                                    finished[0] = true;
                                    resultErr[0] = asrErrorText(error);
                                    latch.countDown();
                                }
                            }
                        });
                        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langF);
                        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                        recognizer.startListening(i);
                    } catch (Throwable t) {
                        synchronized (lock) {
                            if (!finished[0]) {
                                finished[0] = true;
                                resultErr[0] = "启动系统识别失败: " + t.getMessage();
                                latch.countDown();
                            }
                        }
                    }
                }
            });

            boolean done = latch.await(timeoutMs + 3000, TimeUnit.MILLISECONDS);
            listening = false;
            mainHandler.post(new Runnable() { @Override public void run() {
                try { if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; } } catch (Throwable ignored) {}
            }});

            if (!done) {
                pushEvent("listen.error", null);
                return jsonError("听写超时（" + (timeoutMs / 1000) + "s 内无结果；没说话或麦克风被占用？）");
            }
            synchronized (lock) {
                if (resultErr[0] != null) {
                    pushEvent("listen.error", null);
                    return jsonError(resultErr[0]);
                }
                String text = resultText[0] == null ? "" : resultText[0].trim();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("text", text);
                o.put("lang", langF);
                try {
                    JSONObject d = new JSONObject();
                    d.put("text", text);
                    d.put("lang", langF);
                    pushEvent("listen.text", d);
                } catch (Throwable ignored) {}
                return o.toString();
            }
        } catch (Throwable t) {
            listening = false;
            return jsonError("listen error: " + t.getMessage());
        } finally {
            if (resumeWake && !recording && !listening) startWakeMonitor();
        }
    }

    private static String asrErrorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "系统识别错误：录音失败（ERROR_AUDIO）";
            case SpeechRecognizer.ERROR_CLIENT: return "系统识别错误：客户端（ERROR_CLIENT）";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "系统识别错误：无麦克风权限（ERROR_INSUFFICIENT_PERMISSIONS）";
            case SpeechRecognizer.ERROR_NETWORK: return "系统识别错误：网络问题（系统识别多为在线服务）";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "系统识别错误：网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没有识别到内容（再说一次？）";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "系统识别服务忙，请稍后重试";
            case SpeechRecognizer.ERROR_SERVER: return "系统识别服务端错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "没听到说话（超时）";
            default: return "系统识别错误（code=" + error + "）";
        }
    }

    // ======================= 离线 SenseVoice + KWS =======================

    /** sherpa-onnx AAR 与 JNI 库是否可加载。 */
    private boolean sherpaAvailable() {
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String handleRecognize(String body, String path) {
        try {
            if (!sherpaAvailable()) return jsonError("sherpa-onnx AAR/JNI 尚未加载，请检查 APK 内的 sherpa 类和 arm64 原生库");
            if (isSpeaking()) return jsonError("正在播报（半双工闸）：请等播报结束后再识别");
            if (!VoiceModelStore.senseVoiceReady(this)) {
                VoiceModelStore.prefetch(this);
                return jsonError("SenseVoice 离线模型尚未就绪（" + VoiceModelStore.asrState() + ")：" + VoiceModelStore.asrError());
            }
            String wavPath = "";
            if (body != null && !body.trim().isEmpty()) {
                try { wavPath = new JSONObject(body).optString("path", ""); } catch (Throwable ignored) {}
            }
            if (wavPath == null || wavPath.trim().isEmpty()) {
                File fallback = lastWav;
                wavPath = fallback != null ? fallback.getAbsolutePath() : "";
            }
            if (wavPath.trim().isEmpty()) return jsonError("缺少 WAV 路径，且没有最近一次录音");
            File wavFile = new File(wavPath);
            if (!wavFile.isFile() || !wavFile.canRead()) return jsonError("WAV 文件不存在或不可读：" + wavPath);
            if (wavFile.length() < 44) return jsonError("WAV 文件太短或格式无效");

            WaveData wave = WaveReader.Companion.readWave(wavFile.getAbsolutePath());
            float[] samples = wave.getSamples();
            if (samples == null || samples.length == 0) return jsonError("WAV 中没有可识别的音频样本");
            String text = transcribeSamples(samples, wave.getSampleRate());
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("text", text == null ? "" : text.trim());
            result.put("engine", "sherpa-onnx-sensevoice");
            result.put("path", wavFile.getAbsolutePath());
            JSONObject detail = new JSONObject();
            detail.put("text", text == null ? "" : text.trim());
            detail.put("path", wavFile.getAbsolutePath());
            pushEvent("recognize.text", detail);
            return result.toString();
        } catch (Throwable t) {
            Log.w(TAG, "offline recognize failed", t);
            return jsonError("离线识别失败：" + t.getMessage());
        }
    }

    private String handleWake(String body) {
        try {
            String action = "";
            if (body != null && !body.trim().isEmpty()) {
                try { action = new JSONObject(body).optString("action", ""); } catch (Throwable ignored) {}
            }
            JSONObject o = new JSONObject();
            if ("start".equals(action)) {
                if (!micGranted()) {
                    o.put("ok", false);
                    o.put("engine", "none");
                    o.put("running", false);
                    o.put("error", "麦克风权限未授予：请先在系统权限页开启麦克风");
                    return o.toString();
                }
                if (!sherpaAvailable()) {
                    o.put("ok", false); o.put("engine", "none"); o.put("running", false);
                    o.put("error", "sherpa-onnx AAR/JNI 未能加载");
                    return o.toString();
                }
                if (recording || listening) {
                    o.put("ok", false); o.put("engine", "sherpa"); o.put("running", false);
                    o.put("error", "麦克风正用于录音或听写，请结束后再启动唤醒");
                    return o.toString();
                }
                if (isSpeaking()) {
                    o.put("ok", false); o.put("engine", "sherpa"); o.put("running", false);
                    o.put("error", "TTS 正在播报，请播报结束后再启动唤醒");
                    return o.toString();
                }
                if (!VoiceModelStore.kwsReady(this) || !VoiceModelStore.senseVoiceReady(this)) {
                    VoiceModelStore.prefetch(this);
                    o.put("ok", false); o.put("engine", "sherpa"); o.put("running", false);
                    o.put("error", "离线模型仍在准备：ASR=" + VoiceModelStore.asrState() + "，KWS=" + VoiceModelStore.kwsState());
                    return o.toString();
                }
                if (!startWakeMonitor()) {
                    o.put("ok", false); o.put("engine", "sherpa"); o.put("running", false);
                    o.put("error", "无法启动 AudioRecord 唤醒监听");
                    return o.toString();
                }
                o.put("ok", true); o.put("running", true); o.put("engine", "sherpa");
                return o.toString();
            }
            if ("stop".equals(action)) {
                stopWakeMonitor();
                try { JSONObject d = new JSONObject(); d.put("running", false); pushEvent("wake.state", d); } catch (Throwable ignored) {}
                o.put("ok", true); o.put("running", false); o.put("engine", sherpaAvailable() ? "sherpa" : "none");
                return o.toString();
            }
            o.put("ok", true);
            o.put("running", wakeRunning);
            o.put("engine", sherpaAvailable() && VoiceModelStore.kwsReady(this) ? "sherpa" : "none");
            o.put("asrModel", VoiceModelStore.asrState());
            o.put("kwsModel", VoiceModelStore.kwsState());
            return o.toString();
        } catch (Throwable t) {
            return jsonError("wake error: " + t.getMessage());
        }
    }

    private OfflineRecognizer offlineRecognizer() throws Exception {
        OfflineRecognizer current = offlineRecognizer;
        if (current != null) return current;
        synchronized (asrLock) {
            if (offlineRecognizer != null) return offlineRecognizer;
            File dir = VoiceModelStore.senseDir(this);
            OfflineSenseVoiceModelConfig sense = new OfflineSenseVoiceModelConfig();
            sense.setModel(new File(dir, "model.int8.onnx").getAbsolutePath());
            OfflineModelConfig model = new OfflineModelConfig();
            model.setSenseVoice(sense);
            model.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
            model.setNumThreads(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
            model.setDebug(false);
            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setModelConfig(model);
            config.setDecodingMethod("greedy_search");
            offlineRecognizer = new OfflineRecognizer(getAssets(), config);
            return offlineRecognizer;
        }
    }

    private String transcribeSamples(float[] samples, int sampleRate) throws Exception {
        if (samples == null || samples.length == 0) return "";
        synchronized (asrLock) {
            OfflineRecognizer recognizer = offlineRecognizer();
            OfflineStream stream = recognizer.createStream();
            try {
                stream.acceptWaveform(samples, sampleRate > 0 ? sampleRate : WAKE_SAMPLE_RATE);
                recognizer.decode(stream);
                return recognizer.getResult(stream).getText();
            } finally {
                try { stream.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean startWakeMonitor() {
        synchronized (wakeLock) {
            if (wakeRunning) return true;
            if (wakeThread != null && wakeThread.isAlive()) return false;
            if (!micGranted() || !VoiceModelStore.kwsReady(this) || !VoiceModelStore.senseVoiceReady(this)) return false;
            wakeStop = false;
            wakeRunning = true;
            Thread thread = new Thread(new Runnable() {
                @Override public void run() { runWakeMonitor(); }
            }, "voice-wake-audiorecord");
            wakeThread = thread;
            thread.start();
            return true;
        }
    }

    private void stopWakeMonitor() {
        Thread thread;
        AudioRecord active;
        synchronized (wakeLock) {
            wakeStop = true;
            wakeRunning = false;
            thread = wakeThread;
            active = wakeRecorder;
        }
        try { if (active != null && active.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) active.stop(); }
        catch (Throwable ignored) {}
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(5000); } catch (Throwable ignored) {}
        }
        synchronized (wakeLock) {
            if (wakeThread == thread && (thread == null || !thread.isAlive())) wakeThread = null;
        }
    }

    private KeywordSpotter createKeywordSpotter() {
        File dir = VoiceModelStore.kwsDir(this);
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(new File(dir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").getAbsolutePath());
        transducer.setDecoder(new File(dir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").getAbsolutePath());
        transducer.setJoiner(new File(dir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").getAbsolutePath());
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
        model.setNumThreads(1);
        model.setDebug(false);
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setModelConfig(model);
        config.setKeywordsFile(new File(dir, "keywords.txt").getAbsolutePath());
        return new KeywordSpotter(getAssets(), config);
    }

    private void runWakeMonitor() {
        AudioRecord mic = null;
        KeywordSpotter kws = null;
        OnlineStream stream = null;
        ArrayList<Float> speech = new ArrayList<Float>(WAKE_SAMPLE_RATE * 2);
        boolean commandArmed = false;
        boolean inSpeech = false;
        int quietFrames = 0;
        long armedAt = 0;
        try {
            kws = createKeywordSpotter();
            stream = kws.createStream("");
            int min = AudioRecord.getMinBufferSize(WAKE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("AudioRecord 不支持 16kHz 单声道 PCM16");
            int bufferSize = Math.max(min * 2, WAKE_FRAME_SAMPLES * 4);
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WAKE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord 初始化失败");
            wakeRecorder = mic;
            mic.startRecording();
            pushEvent("wake.state", new JSONObject().put("running", true).put("keyword", "小鲸鱼"));
            short[] pcm = new short[WAKE_FRAME_SAMPLES];
            float[] chunk = new float[WAKE_FRAME_SAMPLES];
            while (!wakeStop && wakeRunning) {
                int n = mic.read(pcm, 0, pcm.length);
                if (n == 0) continue;
                if (n < 0) throw new IllegalStateException("AudioRecord.read 失败：" + n);
                float[] samples = n == chunk.length ? chunk : new float[n];
                double energy = 0d;
                for (int i = 0; i < n; i++) {
                    float v = pcm[i] / 32768.0f;
                    samples[i] = v;
                    energy += v * v;
                }
                float rms = (float) Math.sqrt(energy / Math.max(1, n));

                if (isSpeaking()) {
                    // TTS 半双工：KWS 不收 TTS 音频，VAD 当前半句整段作废。
                    kws.reset(stream);
                    commandArmed = false;
                    inSpeech = false;
                    quietFrames = 0;
                    speech.clear();
                    continue;
                }

                stream.acceptWaveform(samples, WAKE_SAMPLE_RATE);
                boolean detected = false;
                while (kws.isReady(stream)) {
                    kws.decode(stream);
                    String keyword = kws.getResult(stream).getKeyword();
                    if (keyword != null && !keyword.isEmpty()) {
                        kws.reset(stream);
                        commandArmed = true;
                        inSpeech = false;
                        quietFrames = 0;
                        speech.clear();
                        armedAt = System.currentTimeMillis();
                        JSONObject data = new JSONObject();
                        data.put("keyword", keyword);
                        data.put("wakeWord", "小鲸鱼");
                        pushEvent("wake.hit", data);
                        notifyWake("唤醒词已识别", "小鲸鱼正在听");
                        detected = true;
                        break;
                    }
                }
                // 唤醒词命中的这一帧包含唤醒词尾音，不能混进后续指令。
                if (detected) continue;

                if (commandArmed && !inSpeech && System.currentTimeMillis() - armedAt > 10000L) {
                    commandArmed = false;
                    speech.clear();
                    pushEvent("wake.timeout", null);
                }
                if (!commandArmed) continue;

                boolean voiced = rms >= 0.015f;
                if (voiced) {
                    inSpeech = true;
                    quietFrames = 0;
                    for (int i = 0; i < samples.length; i++) speech.add(samples[i]);
                } else if (inSpeech) {
                    quietFrames++;
                    for (int i = 0; i < samples.length; i++) speech.add(samples[i]);
                }
                if (inSpeech && (quietFrames >= 7 || speech.size() >= WAKE_SAMPLE_RATE * 12)) {
                    final float[] utterance = new float[speech.size()];
                    for (int i = 0; i < speech.size(); i++) utterance[i] = speech.get(i);
                    commandArmed = false;
                    inSpeech = false;
                    quietFrames = 0;
                    speech.clear();
                    transcribeWakeCommand(utterance);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "wake monitor failed", t);
            try { JSONObject d = new JSONObject(); d.put("error", safeMessage(t)); pushEvent("wake.error", d); } catch (Throwable ignored) {}
            notifyWake("唤醒监听停止", safeMessage(t));
        } finally {
            try { if (mic != null && mic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) mic.stop(); } catch (Throwable ignored) {}
            try { if (mic != null) mic.release(); } catch (Throwable ignored) {}
            if (wakeRecorder == mic) wakeRecorder = null;
            try { if (stream != null) stream.release(); } catch (Throwable ignored) {}
            try { if (kws != null) kws.release(); } catch (Throwable ignored) {}
            synchronized (wakeLock) {
                wakeRunning = false;
                if (wakeThread == Thread.currentThread()) wakeThread = null;
            }
        }
    }

    private void transcribeWakeCommand(final float[] samples) {
        if (samples == null || samples.length < WAKE_SAMPLE_RATE / 4) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String text = transcribeSamples(samples, WAKE_SAMPLE_RATE);
                    JSONObject data = new JSONObject();
                    data.put("text", text == null ? "" : text.trim());
                    data.put("wakeWord", "小鲸鱼");
                    pushEvent("wake.command", data);
                    notifyWake("唤醒语音已识别", text == null || text.trim().isEmpty() ? "未识别到内容" : text.trim());
                } catch (Throwable t) {
                    Log.w(TAG, "wake command ASR failed", t);
                    try { JSONObject data = new JSONObject(); data.put("error", safeMessage(t)); pushEvent("wake.error", data); } catch (Throwable ignored) {}
                }
            }
        }, "voice-wake-command-asr").start();
    }

    private void notifyWake(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
        } catch (Throwable ignored) {}
    }

    private static String safeMessage(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.isEmpty() ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : message;
    }

    // ======================= 事件流 =======================

    private void pushEvent(String type, JSONObject data) {
        try {
            JSONObject e = new JSONObject();
            synchronized (eventsLock) {
                eventSeq++;
                e.put("seq", eventSeq);
                e.put("ts", System.currentTimeMillis());
                e.put("type", type);
                if (data != null) e.put("data", data);
                events.addLast(e);
                while (events.size() > 200) events.removeFirst();
                eventsLock.notifyAll();
            }
        } catch (Throwable ignored) {}
    }

    private String handleEvent(String path) {
        try {
            long since = 0;
            long timeoutMs = 20000;
            try { since = Long.parseLong(queryParam(path, "since")); } catch (Throwable ignored) {}
            try { timeoutMs = Long.parseLong(queryParam(path, "timeoutMs")); } catch (Throwable ignored) {}
            if (timeoutMs < 0) timeoutMs = 0;
            if (timeoutMs > 60000) timeoutMs = 60000;
            long deadline = System.currentTimeMillis() + timeoutMs;

            synchronized (eventsLock) {
                while (eventSeq <= since) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) break;
                    eventsLock.wait(Math.min(remain, 1000));
                }
                JSONArray arr = new JSONArray();
                for (JSONObject e : events) {
                    if (e.optLong("seq", 0) > since) arr.put(e);
                }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("seq", eventSeq);
                o.put("events", arr);
                return o.toString();
            }
        } catch (Throwable t) {
            return jsonError("event error: " + t.getMessage());
        }
    }

    // ======================= /status =======================

    private String handleStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("running", true);
            o.put("apiLevel", Build.VERSION.SDK_INT);
            o.put("micGranted", micGranted());
            // TTS
            o.put("ttsReady", ttsReady);
            o.put("speaking", isSpeaking());
            o.put("lastSpokenText", lastSpokenText == null ? "" : lastSpokenText);
            // 录音
            o.put("recording", recording);
            if (recording) o.put("recDurationMs", System.currentTimeMillis() - recordStartedAt);
            o.put("lastWav", lastWav != null ? lastWav.getAbsolutePath() : "");
            o.put("lastWavDurationMs", lastWavDurationMs);
            // 识别 / 唤醒
            o.put("asrEngine", systemAsrAvailable() ? "system" : "none");
            o.put("offlineAsr", sherpaAvailable() && VoiceModelStore.senseVoiceReady(this) ? "sherpa" : VoiceModelStore.asrState());
            o.put("wakeEngine", sherpaAvailable() && VoiceModelStore.kwsReady(this) ? "sherpa" : VoiceModelStore.kwsState());
            o.put("offlineAsrModel", VoiceModelStore.asrState());
            o.put("wakeModel", VoiceModelStore.kwsState());
            o.put("wakeRunning", wakeRunning);
            o.put("listening", listening);
            return o.toString();
        } catch (Throwable t) {
            return jsonError("status error: " + t.getMessage());
        }
    }

    // ======================= HTTP 服务端（与 AccessibilityService 同构） =======================

    private void startServer(final int port) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", port));
                    serverSocket = ss;
                    serverRunning = true;
                    Log.i(TAG, "voice server listening on " + port);
                    while (serverRunning) {
                        try {
                            final Socket s = ss.accept();
                            handleConnection(s);
                        } catch (Throwable t) {
                            try { Thread.sleep(100); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "voice server stopped", t);
                } finally {
                    try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                }
            }
        }, "voice-server").start();
    }

    private String parsePath(String head) {
        int sp1 = head.indexOf(' ');
        int sp2 = sp1 >= 0 ? head.indexOf(' ', sp1 + 1) : -1;
        if (sp1 >= 0 && sp2 > sp1) return head.substring(sp1 + 1, sp2);
        return "/";
    }

    private String queryParam(String path, String key) {
        if (path == null) return "";
        int q = path.indexOf('?');
        if (q < 0) return "";
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq);
                if (k.equals(key)) {
                    try {
                        return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    } catch (Exception ignored) {
                        return pair.substring(eq + 1);
                    }
                }
            }
        }
        return "";
    }

    private void handleConnection(final Socket s) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    s.setSoTimeout(SOCKET_TIMEOUT_MS);
                    InputStream in = s.getInputStream();
                    StringBuilder head = new StringBuilder();
                    int c;
                    while ((c = in.read()) != -1) {
                        head.append((char) c);
                        if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
                        if (head.length() > 8192) break;
                    }
                    String headStr = head.toString();
                    String path = parsePath(headStr);
                    // 读取 POST body
                    String body = "";
                    int ci = headStr.toLowerCase().indexOf("content-length:");
                    if (ci >= 0) {
                        int eol = headStr.indexOf("\r\n", ci);
                        if (eol > ci) {
                            String v = headStr.substring(ci + 15, eol).trim();
                            try {
                                int len = Integer.parseInt(v);
                                if (len > 0 && len < 262144) {
                                    byte[] buf = new byte[len];
                                    int off = 0;
                                    while (off < len) {
                                        int n = in.read(buf, off, len - off);
                                        if (n < 0) break;
                                        off += n;
                                    }
                                    body = new String(buf, 0, off, "UTF-8");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    String respBody;
                    if (!LocalAuth.ok(LocalAuth.token(VoiceService.this), headStr, path)) {
                        Log.w(TAG, "voice 请求被拒（令牌缺失或错误）：" + path);
                        respBody = LocalAuth.denied();
                    } else {
                        try {
                            respBody = route(path, body);
                        } catch (Throwable t) {
                            respBody = jsonError("内部错误: " + t.getMessage());
                        }
                    }
                    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
                    w.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + respBody.getBytes("UTF-8").length + "\r\nConnection: close\r\n\r\n" + respBody);
                    w.flush();
                    s.close();
                } catch (Throwable t) {
                    Log.w(TAG, "voice connection error", t);
                    try { s.close(); } catch (Throwable ignored) {}
                }
            }
        }, "voice-conn").start();
    }

    private String jsonError(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg == null ? "未知错误" : msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"json error\"}";
        }
    }

    private String route(String path, String body) throws Exception {
        String base = path;
        int q = path.indexOf('?');
        if (q >= 0) base = path.substring(0, q);

        if (base.equals("/status")) return handleStatus();
        if (base.equals("/tts")) return handleTts(body, path);
        if (base.equals("/tts-stop")) return handleTtsStop();
        if (base.equals("/listen")) return handleListen(body, path);
        if (base.equals("/record")) return handleRecord(body);
        if (base.equals("/stop")) return handleStop();
        if (base.equals("/recognize")) return handleRecognize(body, path);
        if (base.equals("/wake")) return handleWake(body);
        if (base.equals("/event")) return handleEvent(path);
        return jsonError("未知路由: " + base);
    }

    // ======================= 通知 =======================

    private Notification buildNotification(String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "语音服务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("语音助手（TTS / 听写 / 唤醒）服务状态");
            nm.createNotificationChannel(ch);
        }
    }
}
