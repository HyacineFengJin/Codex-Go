package dev.codex.go.example;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.*;

/** Dependency-free example: platform Messenger + JSON, no host implementation classes. */
public final class TextToolsService extends Service {
    private Messenger messenger;
    @Override public void onCreate() {
        super.onCreate();
        messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            // Messenger preserves the actual Binder sender UID. Do not trust a package name in JSON.
            try { if (message.sendingUid != getPackageManager().getApplicationInfo("dev.codex.go", 0).uid) return true; }
            catch (Exception ignored) { return true; }
            if (message.what == 3) return true; // Short synchronous work has no pending job to cancel.
            if (message.what != 1 || message.replyTo == null) return true;
            JSONObject request = null;
            try {
                String raw = message.getData().getString("json", "");
                if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) return true;
                request = new JSONObject(raw);
                if (request.getInt("apiVersion") != 1) throw new IllegalArgumentException("Unsupported API");
                JSONObject result;
                if ("ping".equals(request.getString("method"))) result = new JSONObject().put("apiVersion", 1);
                else if ("tools/call".equals(request.getString("method"))) {
                    JSONObject params = request.getJSONObject("params");
                    if (!"text_stats".equals(params.getString("name"))) throw new IllegalArgumentException("Unknown tool");
                    JSONObject arguments = params.getJSONObject("arguments");
                    Object value = arguments.get("text");
                    if (!(value instanceof String) || arguments.length() != 1) throw new IllegalArgumentException("Expected only text:string");
                    String text = (String)value;
                    if (text.length() > 16000) throw new IllegalArgumentException("Text too long");
                    JSONObject stats = new JSONObject().put("characters", text.codePointCount(0,text.length()))
                        .put("words", text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length)
                        .put("lines", text.isEmpty() ? 0 : text.split("\\R",-1).length);
                    result = new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type","text").put("text",stats.toString()))).put("isError",false);
                } else throw new IllegalArgumentException("Unknown method");
                reply(message.replyTo, new JSONObject().put("apiVersion",1).put("id",request.getString("id")).put("result",result));
            } catch (Exception error) {
                if (request != null) try { reply(message.replyTo,new JSONObject().put("apiVersion",1).put("id",request.optString("id")).put("error",new JSONObject().put("code","INVALID_REQUEST").put("message",error.getMessage()))); } catch (Exception ignored) { }
            }
            return true;
        }));
    }
    private void reply(Messenger target, JSONObject value) throws RemoteException {
        Message message = Message.obtain(null,2); Bundle data=new Bundle();data.putString("json",value.toString());message.setData(data);target.send(message);
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
}
