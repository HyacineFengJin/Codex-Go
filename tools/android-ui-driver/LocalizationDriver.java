package dev.codex.go.testdriver;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.Locale;
import java.util.regex.*;

/** Verify resource formatting and service text through the same bridge used in production. */
public final class LocalizationDriver extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private Configuration configuration(Context context, Locale locale) { Configuration config=new Configuration(context.getResources().getConfiguration()); config.setLocale(locale); return config; }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private Object engine;
    private ServiceConnection connection;
    private void bindEngine(Context context) throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) { try { engine=binder.getClass().getMethod("getEngine").invoke(binder); } catch(Exception e) { throw new RuntimeException(e); } ready.countDown(); }
            public void onServiceDisconnected(ComponentName name) { }
        };
        require(context.bindService(new Intent().setClassName("dev.codex.go","dev.codex.go.runtime.EngineService"),connection,Context.BIND_AUTO_CREATE),"Could not bind service");
        require(ready.await(5,TimeUnit.SECONDS),"Service did not bind");
    }
    private void verifyNotification(Context context, Context expected, boolean refresh) throws Exception {
        Object[] notification={null};Throwable[] failure={null};
        runOnMainSync(() -> { try {
            if (refresh) engine.getClass().getMethod("refreshLanguage").invoke(engine);
            Method create=engine.getClass().getDeclaredMethod("notification");create.setAccessible(true);notification[0]=create.invoke(engine);
        } catch(Throwable e) { failure[0]=e; } });
        if (failure[0]!=null) throw new Exception(failure[0]);
        Notification item=(Notification)notification[0];
        Class<?> strings=Class.forName("dev.codex.go.R$string",true,context.getClassLoader());
        String status=expected.getString(strings.getField("wireless_debugging_not_connected").getInt(null));
        require(status.contentEquals(item.extras.getCharSequence(Notification.EXTRA_TEXT)),"Initial notification was not localized: "+item.extras.getCharSequence(Notification.EXTRA_TEXT));
        String channel=expected.getString(strings.getField("local_codex").getInt(null));
        require(channel.contentEquals(context.getSystemService(NotificationManager.class).getNotificationChannel("engine").getName()),"Channel name did not refresh");
        Object flow=engine.getClass().getMethod("getState").invoke(engine);
        Object state=Class.forName("kotlinx.coroutines.flow.StateFlow",true,context.getClassLoader()).getMethod("getValue").invoke(flow);
        require("Idle".equals(state.getClass().getMethod("getPhase").invoke(state).toString()),"Changing language started the engine");
        Object selected=state.getClass().getMethod("getProject").invoke(state);
        String project="default".equals(selected.getClass().getMethod("getId").invoke(selected)) ? expected.getString(strings.getField("default_project").getInt(null)) : (String)selected.getClass().getMethod("getName").invoke(selected);
        require(item.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString().startsWith(project+" · "),"Notification project label was not localized");
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "Disposable emulators only");
            Context context = getTargetContext(); ClassLoader loader = context.getClassLoader();
            Class<?> strings = Class.forName("dev.codex.go.R$string", true, loader);
            Class<?> bridge = Class.forName("dev.codex.go.i18n.AppText", true, loader);
            Object text = bridge.getField("INSTANCE").get(null);
            Method translate = bridge.getMethod("get", int.class, Object[].class);
            Method metadata = bridge.getMethod("metadata", String.class);
            Context en = context.createConfigurationContext(configuration(context, Locale.ENGLISH));
            Context zh = context.createConfigurationContext(configuration(context, Locale.SIMPLIFIED_CHINESE));
            Context fr = context.createConfigurationContext(configuration(context, Locale.FRENCH));
            Pattern placeholder = Pattern.compile("%(\\d+)\\$s"); int count=0;
            // All application-owned extracted messages; library resources are outside this catalog.
            Class<?> catalog = Class.forName("dev.codex.go.i18n.LegacyMessagesKt", true, loader);
            int[] ids = (int[])catalog.getMethod("getLegacyMessageIds").invoke(null);
            for (int id : ids) {
                String english=en.getString(id), chinese=zh.getString(id); int max=0;
                Matcher matcher=placeholder.matcher(english);
                while (matcher.find()) max=Math.max(max,Integer.parseInt(matcher.group(1)));
                Object[] args=new Object[max]; java.util.Arrays.fill(args,"<user%原文>");
                en.getString(id,args); zh.getString(id,args);
                require(fr.getString(id).equals(english), "Unsupported locale did not fall back to English: "+context.getResources().getResourceEntryName(id));
                count++;
            }
            bindEngine(context);
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            LocaleList previous = manager.getApplicationLocales();
            try {
                manager.setApplicationLocales(LocaleList.forLanguageTags("en"));
                verifyNotification(context,en,false);
                verifyNotification(context,en,true);
                require("Not started yet".equals(metadata.invoke(text,"尚未启动")), "Old Chinese status was not localized");
                require("Export failed: fixture".equals(metadata.invoke(text,"导出失败：fixture")), "Formatted status lost its arguments");
                require("custom % message".equals(metadata.invoke(text,"custom % message")), "Unknown metadata changed");
                require("Default project".equals(translate.invoke(text,strings.getField("default_project").getInt(null),new Object[0])), "English resource lookup failed");
                manager.setApplicationLocales(LocaleList.forLanguageTags("zh-Hans"));
                verifyNotification(context,zh,true);
                require("尚未启动".equals(metadata.invoke(text,"Not started yet")), "Old English status was not localized");
                require("Opaque example text".equals(metadata.invoke(text,"Opaque example text")), "Unknown metadata was caught by a generic template");
                for (String name : new String[]{"indicator_using","indicator_viewing","indicator_reading"}) {
                    int id=strings.getField(name).getInt(null);
                    require(en.getString(id).equals(zh.getString(id)), "Indicator should retain the requested English text");
                }
            } finally { manager.setApplicationLocales(previous); }
            JSONObject facts=new JSONObject().put("notificationAndChannelRefresh",true).put("formattedResources",count).put("englishFallback",true).put("legacyStatusLocalization",true).put("unknownMetadataPreserved",true).put("englishIndicatorsInBothLanguages",true);
            result.putString("facts",facts.toString()); finish(Activity.RESULT_OK,result);
        } catch (Throwable error) { result.putString("error",error.toString()); finish(Activity.RESULT_CANCELED,result); }
        finally { if (connection!=null) getTargetContext().unbindService(connection); }
    }
}
