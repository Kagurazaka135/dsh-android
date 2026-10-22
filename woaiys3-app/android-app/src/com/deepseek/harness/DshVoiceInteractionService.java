package com.deepseek.harness;

import android.service.voice.VoiceInteractionService;
import android.util.Log;

/** System assistant registration only; the existing wake word and voice bridge stay independent. */
public final class DshVoiceInteractionService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        Log.i("dsh-voice", "system voice interaction service ready");
    }
}
