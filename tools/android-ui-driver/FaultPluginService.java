package dev.codex.go.testdriver;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.*;
/** Fault injection exists only in the disposable emulator driver APK. */
public final class FaultPluginService extends Service {
    private Messenger messenger;
    @Override public void onCreate() {
        super.onCreate();
        messenger=new Messenger(new Handler(Looper.getMainLooper(),message -> {
            if (!(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")) || message.what!=1 || message.replyTo==null) return true;
            try {
                JSONObject request=new JSONObject(message.getData().getString("json"));
                String tool=request.getJSONObject("params").optString("name");
                if(tool.equals("hang"))return true;
                if(tool.equals("crash")){android.os.Process.killProcess(android.os.Process.myPid());return true;}
                String raw=tool.equals("oversize")?"x".repeat(140000):"invalid json";
                Message reply=Message.obtain(null,2);reply.setData(new Bundle());reply.getData().putString("json",raw);message.replyTo.send(reply);
            }catch(Exception ignored){}
            return true;
        }));
    }
    @Override public IBinder onBind(Intent intent){return messenger.getBinder();}
}
