package dev.codex.go.testdriver;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Owned-emulator UI fixture. The production overlay is exercised without starting Codex. */
public final class OverlayDriver extends Instrumentation {
    private Context context; private ClassLoader loader; private Object engine, flow, overlay;
    private File directory; private ServiceConnection connection;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private Class<?> type(String name) throws Exception { return Class.forName(name,true,loader); }
    private Object field(Object owner,String name) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private interface Work { void run() throws Exception; }
    private void main(Work work) throws Exception { Throwable[] error={null};runOnMainSync(() -> { try { work.run(); } catch(Throwable e) { error[0]=e; } });if(error[0]!=null)throw new Exception(error[0]); }
    private Object state() throws Exception { return type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(flow); }
    private void patch(Map<String,Object> changes) throws Exception {
        main(() -> {
            Object old=state();Class<?> cls=old.getClass();String[] names="phase,autoPrepare,status,account,signedIn,login,loginPending,accountBusy,startupStage,exportingDiagnostics,notice,configurationWarning,configuration,shares,shareRequest,quickRequest,chatOverlayEnabled,quickReplyEnabled,quickMessages,quickFeedback,threadId,title,project,projects,turnId,busy,browsing,runningTask,conversationRequest,loadingHistory,feed,sessions,sessionsCursor,sessionQuery,archivedSessions,sessionsLoading,sessionsScanned,sessionsError,sessionMutation,models,selectedModel,selectedEffort,effectiveModel,effectiveEffort,failure,networkAvailable,refreshingAccount,prompts,phoneEnabled,phonePreRead,phoneModelPreRead,phonePreReadModel,historyCompression,historyImageStatus,historyImageStats,shellEnabled,shellAccess,adbStatus,adbConnected,adbSearching,error,logs,draft,importing,workspaceRevision,queue,queuePaused,steering,failedSubmission,phoneTaskActive,phonePaused,engine,handoffReason,phoneAction".split(",");
            Constructor<?> constructor=null;
            for(Constructor<?> c:cls.getConstructors())if(c.getParameterCount()==names.length)constructor=c;
            require(constructor!=null,"State constructor changed; update fixture field names");Object[] arguments=new Object[names.length];
            for(int i=0;i<names.length;i++)arguments[i]=changes.containsKey(names[i])?changes.get(names[i]):cls.getMethod("component"+(i+1)).invoke(old);
            Object next=constructor.newInstance(arguments);type("kotlinx.coroutines.flow.MutableStateFlow").getMethod("setValue",Object.class).invoke(flow,next);
        });Thread.sleep(120);
    }
    private void write(String name,String value) throws Exception { Files.write(new File(directory,name).toPath(),value.getBytes(StandardCharsets.UTF_8)); }
    private void report(JSONObject command) throws Exception {
        JSONObject facts=new JSONObject();
        main(() -> {
            View root=(View)field(overlay,"root");Object glass=field(overlay,"glass");
            facts.put("attached",root!=null&&root.isAttachedToWindow());facts.put("visible",root!=null&&root.getVisibility()==View.VISIBLE);
            facts.put("expanded",field(overlay,"expanded"));facts.put("draft",context.getSharedPreferences("codex_go",0).getString("chatOverlayDraft",""));
            facts.put("mainDraft",field(field(state(),"draft"),"text"));
            facts.put("nativeEngineStarted",field(engine,"process")!=null);
            if(glass!=null){facts.put("usingBlur",glass.getClass().getMethod("getUsingBlur").invoke(glass));Object surface=glass.getClass().getMethod("getSurface").invoke(glass);facts.put("fillOpacity",surface.getClass().getMethod("getFillOpacity").invoke(surface));}
        });
        facts.put("id",command.optInt("id"));write("state.json",facts.toString());
    }
    @SuppressWarnings({"unchecked","rawtypes"}) @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            require(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"),"Disposable emulators only");
            context=getTargetContext();loader=context.getClassLoader();directory=new File(context.getFilesDir(),"floating-validation");directory.mkdirs();
            context.getSharedPreferences("codex_go",0).edit().putBoolean("autoPrepare",false).putBoolean("engineUsed",false).putString("chatOverlayDraft", "").remove("quickMessages").apply();
            CountDownLatch ready=new CountDownLatch(1);connection=new ServiceConnection(){
                public void onServiceConnected(ComponentName name,IBinder binder){try{engine=binder.getClass().getMethod("getEngine").invoke(binder);}catch(Exception e){throw new RuntimeException(e);}ready.countDown();}
                public void onServiceDisconnected(ComponentName name){}
            };
            require(context.bindService(new Intent().setClassName("dev.codex.go","dev.codex.go.runtime.EngineService"),connection,Context.BIND_AUTO_CREATE),"Could not bind service");require(ready.await(8,TimeUnit.SECONDS),"Service timeout");flow=field(engine,"updates");
            overlay=type("kotlin.Lazy").getMethod("getValue").invoke(field(engine,"chatOverlay$delegate"));
            Constructor<?> chat=type("dev.codex.go.protocol.ChatItem").getConstructor(String.class,String.class,String.class,String.class,boolean.class,boolean.class,List.class,boolean.class);
            List<Object> feed=new ArrayList<>();feed.add(chat.newInstance("user-preview","user","Make the next step easy to see.","",false,false,Collections.emptyList(),false));feed.add(chat.newInstance("assistant-preview","assistant","Your workspace is ready.\n\nWe can review the files, refine an idea, or continue the conversation here.\n\nEverything stays in this chat.","",false,false,Collections.emptyList(),false));
            Map<String,Object> changes=new HashMap<>();changes.put("phase",Enum.valueOf((Class)type("dev.codex.go.runtime.EnginePhase"),"Ready"));changes.put("chatOverlayEnabled",true);changes.put("feed",feed);changes.put("threadId","overlay-preview");changes.put("title","Workspace notes");changes.put("selectedModel","Preview model");patch(changes);
            main(() -> engine.getClass().getMethod("updateDraft",String.class).invoke(engine,"MAIN_DRAFT_KEEP"));write("phase","ready");report(new JSONObject());
            long deadline=SystemClock.uptimeMillis()+600000;
            while(SystemClock.uptimeMillis()<deadline){
                File commandFile=new File(directory,"command.json");if(!commandFile.isFile()){Thread.sleep(40);continue;}
                JSONObject command=new JSONObject(new String(Files.readAllBytes(commandFile.toPath()),StandardCharsets.UTF_8));commandFile.delete();String action=command.getString("action");
                if(action.equals("finish")){main(() -> overlay.getClass().getMethod("close").invoke(overlay));report(command);result.putString("completed","true");finish(Activity.RESULT_OK,result);return;}
                if(action.equals("hide"))main(() -> overlay.getClass().getMethod("hideForTool").invoke(overlay));
                if(action.equals("restore"))main(() -> overlay.getClass().getMethod("restoreAfterTool").invoke(overlay));
                if(action.equals("refresh"))main(() -> engine.getClass().getMethod("refreshLanguage").invoke(engine));
                if(action.equals("enable"))main(() -> engine.getClass().getMethod("setChatOverlayEnabled",boolean.class).invoke(engine,true));
                if(action.equals("busy"))patch(Collections.singletonMap("busy",command.getBoolean("value")));
                if(action.equals("target"))patch(Collections.singletonMap("threadId",command.getString("value")));
                report(command);
            }
            throw new AssertionError("Host commands timed out");
        }catch(Throwable error){try{write("error",error.toString());}catch(Exception ignored){}result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}
        finally{if(overlay!=null)try{main(() -> overlay.getClass().getMethod("close").invoke(overlay));}catch(Exception ignored){}if(connection!=null)context.unbindService(connection);}
    }
}
