package com.deepseek.harness;

import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/** Registration stub. System requests complete with no matches without opening the microphone. */
public final class DshRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        Bundle results = new Bundle();
        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<String>());
        try {
            listener.results(results);
        } catch (RemoteException error) {
            Log.w("dsh-voice", "system recognition client disconnected", error);
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        // Requests already finish synchronously in onStartListening.
    }

    @Override
    protected void onCancel(Callback listener) {
        // The registration stub owns no microphone or pending work.
    }
}
