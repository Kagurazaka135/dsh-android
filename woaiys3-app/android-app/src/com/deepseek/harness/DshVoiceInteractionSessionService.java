package com.deepseek.harness;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import android.util.Log;

/** Empty system session until the assistant UI and voice bridge are integrated in a later batch. */
public final class DshVoiceInteractionSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        Log.i("dsh-voice", "system voice interaction session created (registration shell)");
        return new VoiceInteractionSession(this);
    }
}
