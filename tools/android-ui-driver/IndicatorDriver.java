package dev.codex.go.testdriver;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Disposable emulator instrumentation; uses the production window, no test entry in the app. */
public final class IndicatorDriver extends Instrumentation {
    private Bundle arguments;
    private ClassLoader loader;
    private Class<?> indicatorType;
    private Object indicator;
    private Context target;
    private File directory;
    private final JSONObject facts = new JSONObject();
    private Object access;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!Build.HARDWARE.contains("ranchu") && !Build.HARDWARE.contains("goldfish")) throw new SecurityException("Disposable emulators only");
            getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            target = getTargetContext(); loader = target.getClassLoader();
            directory = new File(target.getFilesDir(), "indicator-validation"); directory.mkdirs();
            boolean trusted = "accessibility".equals(arguments.getString("backend"));
            if (trusted) {
                write("phase", "waiting-accessibility");
                Class<?> type = type("dev.codex.go.phone.PhoneAccessService");
                Object companion = type.getField("Companion").get(null);
                long end = SystemClock.uptimeMillis() + 15000;
                while (access == null && SystemClock.uptimeMillis() < end) { access = companion.getClass().getMethod("getInstance").invoke(companion); Thread.sleep(50); }
                require(access != null, "Accessibility service did not bind");
            }
            indicatorType = type("dev.codex.go.phone.PhoneActivityIndicator");
            main(() -> indicator = indicatorType.getConstructor(Context.class, boolean.class).newInstance(trusted ? access : target, trusted));
            long first = begin("phone_observe"); Thread.sleep(200); require(attached(), "Window was not attached");
            require("Viewing".equals(kind()), "Observation did not use the viewing label");
            long reading=begin("phone_pre_read"); require("Reading".equals(kind()), "Pre-read did not use the reading label");
            long using=begin("phone_tap"); require("Using".equals(kind()), "Action did not take priority"); end(using); require("Reading".equals(kind()), "Read label was not restored"); end(reading);
            require("Viewing".equals(kind()), "View label was not restored"); facts.put("viewingReadingAndControlLabels", true);
            write("phase", "visible");
            gate();
            android.view.accessibility.AccessibilityNodeInfo screen = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
            require(screen != null && "dev.codex.go.sharefixture".contentEquals(screen.getPackageName()), "Indicator took application focus");
            require(!screen.findAccessibilityNodeInfosByText("Touch through badge: 1").isEmpty(), "Touch was intercepted by badge");
            android.view.accessibility.AccessibilityNodeInfo editor = screen.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            require(editor != null && "indicator_focus_kept".contentEquals(editor.getText()), "Indicator changed keyboard focus");
            if (editor != null) editor.recycle(); screen.recycle();
            facts.put("badgeTouchPassesThrough", true); facts.put("keyboardFocusRetained", true);
            write("phase", "resize-visible"); gate();
            long second = begin("phone_tap"); end(first); require(attached(), "First exit dismissed overlapping action");
            end(first); require(attached(), "Duplicate exit dismissed overlapping action");
            end(second); require(!attached(), "Last exit did not remove window immediately");
            facts.put("overlappingAndDuplicateExit", true);
            long obsolete = begin("phone_swipe"); close(); long fresh = begin("phone_type"); end(obsolete);
            require(attached(), "Old finally dismissed a new operation"); end(fresh); facts.put("pauseInvalidatesOldLeases", true);
            require(begin("phone_read_image") == 0 && begin("phone_handoff") == 0 && begin("android_shell") == 0 && !attached(), "Non-live work displayed an indicator");
            facts.put("cachedImagesHandoffsAndShellStayDark", true);
            Invocation failed = during(true); failed.await(); require(!attached(), "Failed work left window"); facts.put("exceptionCleanup", true);
            Invocation cancelled = during(false); require(cancelled.started.await(3, TimeUnit.SECONDS), "Work did not begin after first draw");
            require(attached(), "Suspended operation did not keep window");
            long start = SystemClock.uptimeMillis(); cancelled.cancel(); cancelled.await(); require(!attached(), "Cancellation left window");
            facts.put("cancellationCleanup", true); facts.put("cancelToRemovalMs", SystemClock.uptimeMillis() - start);
            if (access != null) {
                Invocation action = execute("phone_swipe", new JSONObject().put("x", 40).put("y", 600).put("endX", 40).put("endY", 620).put("durationMs", 1500));
                Thread.sleep(200); require(accessAttached(), "Actual accessibility gesture did not show indicator");
                action.cancel(); action.await(); require(!accessAttached(), "Actual cancelled gesture left indicator");
                facts.put("actualAccessibilityGestureCancellation", true);
                Invocation capture = execute("phone_observe", new JSONObject().put("mode", "image")); capture.await();
                require(capture.value instanceof JSONObject && ((JSONObject)capture.value).optBoolean("success"), "Actual observation failed: " + capture.value);
                require(!accessAttached(), "Observation result left indicator"); facts.put("actualObservationCleanup", true);
                Invocation invalid = execute("phone_tap", new JSONObject().put("x", -1).put("y", 10)); invalid.await();
                require(invalid.value instanceof JSONObject && !((JSONObject)invalid.value).optBoolean("success") && !accessAttached(), "Action error left indicator");
                facts.put("actualBackendFailureCleanup", true);
                Invocation interrupt = execute("phone_swipe", new JSONObject().put("x",40).put("y",600).put("endX",40).put("endY",620).put("durationMs",1500));
                Thread.sleep(200); require(accessAttached(), "Interrupt fixture did not start");
                main(() -> access.getClass().getMethod("onInterrupt").invoke(access));
                require(!accessAttached(), "Accessibility interruption left indicator"); interrupt.cancel(); interrupt.await(); facts.put("serviceInterruptCleanup", true);
                // Hold an actual backend gesture while the host revokes accessibility.
                write("phase", "disconnect-ready"); gate();
                Invocation disconnect = execute("phone_swipe", new JSONObject().put("x",40).put("y",600).put("endX",40).put("endY",620).put("durationMs",1500));
                Thread.sleep(150); require(accessAttached(), "Disconnect fixture did not start"); write("phase", "disconnect-active"); gate();
                require(!accessAttached(), "Service unbind left indicator"); disconnect.cancel(); disconnect.await(); facts.put("serviceDisconnectCleanup", true);
            }
            write("result.json", facts.toString()); write("phase", "completed"); gate();
            begin("phone_observe"); Thread.sleep(200); require(attached(), "Process-death fixture did not attach");
            write("phase", "kill-visible"); gate();
            throw new AssertionError("Host should terminate this process while the indicator is visible");
        } catch (Throwable error) {
            try { write("error", error.toString()); } catch (Exception ignored) {}
            result.putString("error", error.toString()); finish(Activity.RESULT_CANCELED, result);
        } finally { if (indicator != null) try { close(); } catch (Exception ignored) {} }
    }
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private interface Work { void run() throws Exception; }
    private void main(Work work) throws Exception {
        Throwable[] failure = {null}; runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new Exception(failure[0]);
    }
    private long begin(String tool) throws Exception { long[] token = {0}; main(() -> token[0] = (Long)indicatorType.getMethod("begin", String.class).invoke(indicator, tool)); return token[0]; }
    private void end(long token) throws Exception { main(() -> indicatorType.getMethod("end", long.class).invoke(indicator, token)); }
    private void close() throws Exception { main(() -> indicatorType.getMethod("close").invoke(indicator)); }
    private boolean window(Object controller) throws Exception {
        Field field = indicatorType.getDeclaredField("view"); field.setAccessible(true);
        boolean[] visible = {false}; main(() -> { android.view.View view = (android.view.View)field.get(controller); visible[0] = view != null && view.isAttachedToWindow(); }); return visible[0];
    }
    private Object readKind(Object view) throws Exception { Method method=view.getClass().getMethod("getActivity"); method.setAccessible(true); return method.invoke(view); }
    private String kind() throws Exception {
        Field field=indicatorType.getDeclaredField("view"); field.setAccessible(true);
        Object[] kind={null}; main(() -> { Object view=field.get(indicator); kind[0]=readKind(view); }); return kind[0].toString();
    }
    private boolean attached() throws Exception { return window(indicator); }
    private boolean accessAttached() throws Exception {
        Field field = access.getClass().getDeclaredField("activity$delegate"); field.setAccessible(true);
        Object controller = type("kotlin.Lazy").getMethod("getValue").invoke(field.get(access));
        return window(controller);
    }
    private void write(String file, String value) throws Exception { try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new File(directory,file))) { stream.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }
    private void gate() throws Exception { File gate = new File(directory,"continue"); long end = SystemClock.uptimeMillis()+45000; while (!gate.isFile() && SystemClock.uptimeMillis()<end) Thread.sleep(20); require(gate.isFile(),"Host gate timed out"); gate.delete(); }
    private final class Invocation {
        final CountDownLatch done = new CountDownLatch(1), started = new CountDownLatch(1);
        final Object job, continuation; Object value;
        Invocation() throws Exception {
            Class<?> jobs = type("kotlinx.coroutines.Job"); job = type("kotlinx.coroutines.JobKt").getMethod("Job", jobs).invoke(null,new Object[]{null});
            continuation = Proxy.newProxyInstance(loader,new Class<?>[]{type("kotlin.coroutines.Continuation")},(proxy,method,args) -> {
                if (method.getName().equals("getContext")) return job;
                if (method.getName().equals("resumeWith")) { value=args[0]; done.countDown(); return null; }
                return null;
            });
        }
        void complete(Object result) throws Exception { Object suspended = type("kotlin.coroutines.intrinsics.IntrinsicsKt").getMethod("getCOROUTINE_SUSPENDED").invoke(null); if (result != suspended) { value=result; done.countDown(); } }
        void cancel() throws Exception { main(() -> type("kotlinx.coroutines.Job").getMethod("cancel",java.util.concurrent.CancellationException.class).invoke(job,new java.util.concurrent.CancellationException("Owned emulator cancellation"))); }
        void await() throws Exception { require(done.await(15,TimeUnit.SECONDS),"Suspended operation did not finish"); }
    }
    private Invocation during(boolean fail) throws Exception {
        Invocation call = new Invocation(); Class<?> function = type("kotlin.jvm.functions.Function1");
        Object block = Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,method,args) -> {
            if (method.getName().equals("invoke")) {
                call.started.countDown();
                if (fail) throw new IllegalStateException("Owned emulator failure");
                return type("kotlinx.coroutines.DelayKt").getMethod("delay",long.class,type("kotlin.coroutines.Continuation")).invoke(null,60000L,args[0]);
            }
            return null;
        });
        main(() -> call.complete(indicatorType.getMethod("during",String.class,function,type("kotlin.coroutines.Continuation")).invoke(indicator,"phone_observe",block,call.continuation)));
        return call;
    }
    private Invocation execute(String tool, JSONObject arguments) throws Exception {
        Invocation call = new Invocation();
        main(() -> call.complete(access.getClass().getMethod("execute",String.class,JSONObject.class,boolean.class,type("kotlin.coroutines.Continuation")).invoke(access,tool,arguments,false,call.continuation)));
        return call;
    }
}
