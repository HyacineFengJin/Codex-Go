package dev.codex.go.testdriver;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;

/** Separate source APK: UI instrumentation cannot revoke its temporary provider grants. */
public final class ShareSourceActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!Build.HARDWARE.contains("ranchu") && !Build.HARDWARE.contains("goldfish")) throw new SecurityException("Disposable emulators only");
        if (saved == null) ShareProvider.send(this, getIntent().getExtras());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); ShareProvider.send(this, intent.getExtras()); }
}
