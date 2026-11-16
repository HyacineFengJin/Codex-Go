package dev.codex.go.testdriver;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Large local fixture. Never starts an engine, signs in, or touches a physical phone. */
public final class LimitUiDriver extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); ServiceConnection connection = null;
        try {
            if (!Build.HARDWARE.contains("ranchu") && !Build.HARDWARE.contains("goldfish")) throw new SecurityException("Owned emulator only");
            Context context = getTargetContext(); ClassLoader loader = context.getClassLoader(); Object[] engine = {null}; CountDownLatch ready = new CountDownLatch(1);
            context.getSharedPreferences("codex_go", 0).edit().putBoolean("autoPrepare", false).putBoolean("engineUsed", false).putString("agentEngine", "codex").commit();
            connection = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) { try { engine[0] = binder.getClass().getMethod("getEngine").invoke(binder); } catch (Exception e) { throw new RuntimeException(e); } ready.countDown(); }
                public void onServiceDisconnected(ComponentName name) {}
            };
            context.bindService(new Intent().setClassName("dev.codex.go", "dev.codex.go.runtime.EngineService"), connection, Context.BIND_AUTO_CREATE);
            if (!ready.await(8, TimeUnit.SECONDS)) throw new AssertionError("No engine service");
            startActivitySync(new Intent().setClassName("dev.codex.go", "dev.codex.go.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Throwable[] failure = {null};
            runOnMainSync(() -> { try {
                Field updates = engine[0].getClass().getDeclaredField("updates"); updates.setAccessible(true); Object flow = updates.get(engine[0]);
                Object old = Class.forName("kotlinx.coroutines.flow.StateFlow", true, loader).getMethod("getValue").invoke(flow); Class<?> state = old.getClass();
                Field fields = PluginDriver.class.getDeclaredField("STATE_FIELDS"); fields.setAccessible(true); String[] names = ((String) fields.get(null)).split(",");
                Constructor<?> constructor = null; for (Constructor<?> c : state.getConstructors()) if (c.getParameterCount() == names.length) constructor = c;
                if (constructor == null) throw new AssertionError("State fixture changed");
                Constructor<?> chat = Class.forName("dev.codex.go.protocol.ChatItem", true, loader).getConstructor(String.class, String.class, String.class, String.class, boolean.class, boolean.class, List.class, boolean.class);
                String text = "BEGIN_LONG_REPLY\n" + "Regression line: all text must remain readable.\n".repeat(30000) + "END_LONG_REPLY";
                List<Object> feed = Collections.singletonList(chat.newInstance("long-reply", "assistant", text, "", false, false, Collections.emptyList(), false));
                Constructor<?> draft = Class.forName("dev.codex.go.files.Draft", true, loader).getConstructor(String.class, List.class);
                Constructor<?> pending = Class.forName("dev.codex.go.runtime.PendingMessage", true, loader).getConstructor(String.class, Class.forName("dev.codex.go.files.Draft", true, loader));
                List<Object> queue = new ArrayList<>(); for (int i = 0; i < 500; i++) queue.add(pending.newInstance("queue-" + i, draft.newInstance("Queued message " + i, Collections.emptyList())));
                Object[] values = new Object[names.length];
                for (int i = 0; i < names.length; i++) {
                    values[i] = state.getMethod("component" + (i + 1)).invoke(old);
                    if (names[i].equals("feed")) values[i] = feed;
                    if (names[i].equals("queue")) values[i] = queue;
                    if (names[i].equals("threadId")) values[i] = "large-local-fixture";
                    if (names[i].equals("phase")) values[i] = Enum.valueOf((Class)Class.forName("dev.codex.go.runtime.EnginePhase", true, loader), "Ready");
                }
                Class.forName("kotlinx.coroutines.flow.MutableStateFlow", true, loader).getMethod("setValue", Object.class).invoke(flow, constructor.newInstance(values));
            } catch (Throwable e) { failure[0] = e; } });
            if (failure[0] != null) throw new Exception(failure[0]);
            File stop = new File(context.getFilesDir(), "limit-ui-stop"); stop.delete(); new File(context.getFilesDir(), "limit-ui-ready").createNewFile();
            long end = SystemClock.uptimeMillis() + 120000; while (!stop.exists() && SystemClock.uptimeMillis() < end) Thread.sleep(50);
            if (!stop.exists()) throw new AssertionError("UI fixture host timed out");
            result.putString("completed", "true"); finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("error", e.toString()); finish(Activity.RESULT_CANCELED, result); }
        finally { if (connection != null) getTargetContext().unbindService(connection); }
    }
}
