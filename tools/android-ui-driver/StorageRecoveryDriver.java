package dev.codex.go.testdriver;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Production storage/controller calls only: no fake engine state or model requests. */
public final class StorageRecoveryDriver extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); ServiceConnection connection = null;
        try {
            if (!Build.HARDWARE.contains("ranchu") && !Build.HARDWARE.contains("goldfish")) throw new SecurityException("Owned emulator only");
            Context context = getTargetContext(); ClassLoader loader = context.getClassLoader(); Object[] engine = {null}; CountDownLatch ready = new CountDownLatch(1);
            context.getSharedPreferences("codex_go", 0).edit().putBoolean("autoPrepare", false).commit();
            connection = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) { try { engine[0] = binder.getClass().getMethod("getEngine").invoke(binder); } catch (Exception e) { throw new RuntimeException(e); } ready.countDown(); }
                public void onServiceDisconnected(ComponentName name) {}
            };
            context.bindService(new Intent().setClassName("dev.codex.go", "dev.codex.go.runtime.EngineService"), connection, Context.BIND_AUTO_CREATE);
            if (!ready.await(8, TimeUnit.SECONDS)) throw new AssertionError("No engine service");
            JSONObject facts = new JSONObject(); Throwable[] error = {null};
            runOnMainSync(() -> { try {
                Object service = engine[0]; Class<?> api = service.getClass(); Class<?> kind = Class.forName("dev.codex.go.runtime.AgentEngine", true, loader);
                Object codex = Enum.valueOf((Class)kind, "Codex"), agy = Enum.valueOf((Class)kind, "Antigravity");
                api.getMethod("selectEngine", kind).invoke(service, codex); api.getMethod("selectProject", String.class).invoke(service, "default");
                String token = UUID.randomUUID().toString(); String codexText = "CODEX_KEEP_" + token, agyText = "AGY_KEEP_" + token;
                api.getMethod("updateDraft", String.class).invoke(service, codexText); api.getMethod("selectEngine", kind).invoke(service, agy); api.getMethod("updateDraft", String.class).invoke(service, agyText);
                Class<?> draft = Class.forName("dev.codex.go.files.Draft", true, loader), shared = Class.forName("dev.codex.go.files.SharedDraft", true, loader), inboxType = Class.forName("dev.codex.go.files.ShareInbox", true, loader);
                Object input = draft.getConstructor(String.class, List.class).newInstance("SHARED_" + token, Collections.emptyList());
                Object entry = shared.getConstructor(String.class, long.class, draft).newInstance(token, System.currentTimeMillis(), input);
                Object inbox = inboxType.getConstructor(Context.class).newInstance(context); inboxType.getMethod("add", shared).invoke(inbox, entry);
                if (!Boolean.TRUE.equals(api.getMethod("applyShare", String.class, boolean.class).invoke(service, token, false))) throw new AssertionError("Share not applied");
                Class<?> quickTarget = Class.forName("dev.codex.go.runtime.QuickTarget", true, loader);
                api.getMethod("submitQuickText", String.class, quickTarget, String.class, String.class, String.class).invoke(service, "QUICK_" + token, null, "fixture", "quick-" + token, "Antigravity");
                if (!Boolean.TRUE.equals(api.getMethod("applyQuickMessage", String.class).invoke(service, "quick-" + token))) throw new AssertionError("Quick message not applied");
                String codexDraft = new JSONObject(context.getSharedPreferences("codex_go", 0).getString("draft:new", "{}")).getString("text");
                String agyDraft = new JSONObject(context.getSharedPreferences("antigravity_conversations", 0).getString("draft:new", "{}")).getString("text");
                String expected = agyText + "\n\nSHARED_" + token + "\n\nQUICK_" + token;
                if (!codexText.equals(codexDraft) || !expected.equals(agyDraft)) throw new AssertionError("Engine drafts crossed");
                facts.put("codexText", codexText).put("antigravityText", expected).put("shareAndQuickUseSelectedEngine", true).put("noCodexDraftOverwrite", true);
                new File(context.getFilesDir(), "storage-recovery-expected.json").createNewFile();
                Files.write(new File(context.getFilesDir(), "storage-recovery-expected.json").toPath(), facts.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable e) { error[0] = e; } });
            if (error[0] != null) throw new Exception(error[0]);
            result.putString("facts", facts.toString()); finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("error", e.toString()); finish(Activity.RESULT_CANCELED, result); }
        finally { if (connection != null) getTargetContext().unbindService(connection); }
    }
}
