package dev.codex.go.testdriver;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Actual cross-UID IPC and production EngineService dispatch; no native engine or model calls. */
public final class PluginDriver extends Instrumentation {
    private Context context; private ClassLoader loader; private Object manager, engine; private ServiceConnection connection;
    private final JSONObject facts = new JSONObject();
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private Class<?> type(String name)throws Exception{return Class.forName(name,true,loader);}
    private Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private interface Work{void run()throws Exception;}
    private void main(Work work)throws Exception{Throwable[] failure={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new Exception(failure[0]);}
    private Object property(Object owner,String name)throws Exception{return owner.getClass().getMethod(name).invoke(owner);}
    private List<?> installed()throws Exception{return (List<?>)type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(property(manager,"getInstalled"));}
    private Object plugin(String packageName)throws Exception{
        long end=SystemClock.uptimeMillis()+8000;
        while(SystemClock.uptimeMillis()<end){for(Object p:installed())if(packageName.equals(property(p,"getPackageName")))return p;Thread.sleep(60);}
        throw new AssertionError("Plugin not discovered: "+packageName);
    }
    private void enable(Object plugin,boolean enabled)throws Exception{main(()->manager.getClass().getMethod("setEnabled",String.class,boolean.class).invoke(manager,property(plugin,"getKey"),enabled));}
    private final class Pending {
        final CountDownLatch done=new CountDownLatch(1);Object value;Throwable error;
        void finish(Object result){try{if(result!=null&&result.getClass().getName().equals("kotlin.Result$Failure"))error=(Throwable)field(result,"exception");else value=result;}catch(Exception e){error=e;}done.countDown();}
        Object await()throws Exception{require(done.await(8,TimeUnit.SECONDS),"Suspension did not complete");if(error!=null)throw new Exception(error);return value;}
    }
    private Pending begin(Object owner,String name,Class<?>[] parameters,Object... values)throws Exception{
        Class<?> continuation=type("kotlin.coroutines.Continuation");Class<?>[] signature=Arrays.copyOf(parameters,parameters.length+1);signature[parameters.length]=continuation;
        Pending pending=new Pending();Object callback=Proxy.newProxyInstance(loader,new Class[]{continuation},(proxy,method,args)->{
            if(method.getName().equals("getContext"))return type("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
            if(method.getName().equals("resumeWith")){pending.finish(args[0]);return null;}return null;
        });
        Object[] arguments=Arrays.copyOf(values,values.length+1);arguments[values.length]=callback;
        main(()->{try{Method target=owner.getClass().getDeclaredMethod(name,signature);target.setAccessible(true);Object result=target.invoke(owner,arguments);if(!"COROUTINE_SUSPENDED".equals(String.valueOf(result)))pending.finish(result);}catch(InvocationTargetException e){pending.error=e.getCause();pending.done.countDown();}});
        return pending;
    }
    private Pending invoke(Object p,String local,JSONObject args)throws Exception{
        String name=(String)p.getClass().getMethod("toolName",String.class).invoke(p,local);
        return begin(manager,"invoke",new Class[]{String.class,JSONObject.class,String.class},name,args,property(p,"getFingerprint"));
    }
    private void fails(Pending call,String reason)throws Exception{try{call.await();throw new AssertionError(reason);}catch(Exception expected){require(call.error!=null,reason);}}
    private void patchState()throws Exception{
        Object flow=field(engine,"updates");Object old=type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(flow);Class<?> cls=old.getClass();
        String[] names=STATE_FIELDS.split(",");Constructor<?> constructor=null;for(Constructor<?> c:cls.getConstructors())if(c.getParameterCount()==names.length)constructor=c;
        require(constructor!=null,"EngineState fixture needs updating");Object[] arguments=new Object[names.length];
        for(int i=0;i<names.length;i++)arguments[i]=cls.getMethod("component"+(i+1)).invoke(old);
        for(int i=0;i<names.length;i++){if(names[i].equals("phase"))arguments[i]=type("dev.codex.go.runtime.EnginePhase").getField("Ready").get(null);if(names[i].equals("busy"))arguments[i]=true;if(names[i].equals("threadId"))arguments[i]="plugin-thread";if(names[i].equals("turnId"))arguments[i]="plugin-turn";}
        Object next=constructor.newInstance(arguments);type("kotlinx.coroutines.flow.MutableStateFlow").getMethod("setValue",Object.class).invoke(flow,next);
    }
    private JSONObject dispatch(ByteArrayOutputStream output,int id,String tool,String turn)throws Exception{
        JSONObject message=new JSONObject().put("id",id).put("method","item/tool/call").put("params",new JSONObject().put("threadId","plugin-thread").put("turnId",turn).put("tool",tool).put("arguments",new JSONObject().put("text","Hi 喵\n")));
        Method handle=engine.getClass().getDeclaredMethod("handle",JSONObject.class,type("kotlin.coroutines.Continuation"));handle.setAccessible(true);
        // begin uses the same declared method; make it accessible via the helper's implementation.
        begin(engine,"handle",new Class[]{JSONObject.class},message).await();
        long end=SystemClock.uptimeMillis()+6000;
        while(SystemClock.uptimeMillis()<end){String snapshot=output.toString("UTF-8");int last=snapshot.lastIndexOf('\n');for(String line:(last<0?"":snapshot.substring(0,last)).split("\n")){if(line.isBlank())continue;JSONObject reply=new JSONObject(line);if(reply.optInt("id")==id&&reply.has("result"))return reply.getJSONObject("result");}Thread.sleep(30);}
        throw new AssertionError("No production tool reply");
    }
    @Override public void onStart(){Bundle result=new Bundle();int status=Activity.RESULT_CANCELED;Object client=null;PipedOutputStream keepAlive=null;
        try{
            require(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"),"Owned emulator only");context=getTargetContext();loader=context.getClassLoader();
            context.getSharedPreferences("plugin_approvals",0).edit().clear().commit();
            main(()->manager=type("dev.codex.go.plugins.PluginManager").getConstructor(Context.class).newInstance(context));
            Object sample=plugin("dev.codex.go.example.texttools"),fault=plugin("dev.codex.go.testdriver");
            require(!(Boolean)property(sample,"getEnabled"),"Plugin enabled itself");enable(sample,true);enable(fault,true);
            JSONObject response=(JSONObject)invoke(sample,"text_stats",new JSONObject().put("text","Hi 🐈\n")).await();
            JSONObject stats=new JSONObject(response.getJSONArray("content").getJSONObject(0).getString("text"));require(stats.getInt("characters")==5&&stats.getInt("words")==2&&stats.getInt("lines")==2,"Wrong cross-process output");
            begin(manager,"ping",new Class[]{String.class},property(sample,"getKey")).await();facts.put("discoveryOptInRealBinderAndPing",true);
            fails(invoke(sample,"text_stats",new JSONObject().put("text",true)),"Invalid input accepted");
            for(String local:new String[]{"malformed","oversize","hang","crash"})fails(invoke(fault,local,new JSONObject()),"Fault escaped: "+local);
            facts.put("invalidInputTimeoutCrashAndOutputBounds",true);
            Pending hang=invoke(fault,"hang",new JSONObject());Thread.sleep(150);enable(fault,false);fails(hang,"Disable did not cancel");
            require(((Map<?,?>)field(manager,"calls")).isEmpty(),"Bindings/calls left tracked");facts.put("disableCancelsInFlight",true);
            String sampleName=(String)sample.getClass().getMethod("toolName",String.class).invoke(sample,"text_stats");
            fails(begin(manager,"invoke",new Class[]{String.class,JSONObject.class,String.class},sampleName,new JSONObject(),"changed-fingerprint"),"Changed descriptor accepted");facts.put("staleApprovalRejected",true);
            main(()->manager.getClass().getMethod("close").invoke(manager));
            CountDownLatch connected=new CountDownLatch(1);connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){try{engine=b.getClass().getMethod("getEngine").invoke(b);}catch(Exception e){throw new RuntimeException(e);}connected.countDown();}public void onServiceDisconnected(ComponentName n){}};
            require(context.bindService(new Intent().setClassName("dev.codex.go","dev.codex.go.runtime.EngineService"),connection,Context.BIND_AUTO_CREATE),"No engine service");require(connected.await(8,TimeUnit.SECONDS),"No engine binder");
            manager=property(engine,"getPlugins");sample=plugin("dev.codex.go.example.texttools");require((Boolean)property(sample,"getEnabled"),"Enable did not persist");
            Object catalog=property(manager,"catalog");JSONObject approvals=(JSONObject)property(catalog,"getApprovals");context.getSharedPreferences("codex_go",0).edit().putString("pluginCatalog:plugin-thread",approvals.toString()).apply();
            main(this::patchState);
            PipedInputStream input=new PipedInputStream();keepAlive=new PipedOutputStream(input);ByteArrayOutputStream output=new ByteArrayOutputStream();
            Class<?> function=type("kotlin.jvm.functions.Function1");Object noop=Proxy.newProxyInstance(loader,new Class[]{function},(p,m,v)->type("kotlin.Unit").getField("INSTANCE").get(null));
            client=type("dev.codex.go.protocol.JsonRpcClient").getConstructor(InputStream.class,OutputStream.class,function,function).newInstance(input,output,noop,noop);set(engine,"rpc",client);
            require(dispatch(output,100,sampleName,"plugin-turn").getBoolean("success"),"Production routing failed without phone permission");
            require(!dispatch(output,101,sampleName,"obsolete-turn").getBoolean("success"),"Obsolete turn executed");
            enable(sample,false);require(!dispatch(output,102,sampleName,"plugin-turn").getBoolean("success"),"Revoked tool executed");
            require(field(engine,"process")==null,"Native engine started");facts.put("productionDispatchWithoutPhoneGrant",true);facts.put("oldTurnAndRevokedToolRejected",true);facts.put("enablePersistsAcrossManagerRestart",true);facts.put("noNativeEngineOrInference",true);
            result.putString("facts",facts.toString());status=Activity.RESULT_OK;
        }catch(Throwable e){result.putString("error",e.toString());}
        finally{try{if(client!=null)client.getClass().getMethod("close").invoke(client);if(keepAlive!=null)keepAlive.close();if(engine!=null)set(engine,"rpc",null);else if(manager!=null)main(()->manager.getClass().getMethod("close").invoke(manager));if(connection!=null)context.unbindService(connection);context.getSharedPreferences("plugin_approvals",0).edit().clear().commit();}catch(Exception ignored){}}
        finish(status,result);
    }
    private static final String STATE_FIELDS="phase,autoPrepare,status,account,signedIn,login,loginPending,accountBusy,startupStage,exportingDiagnostics,notice,configurationWarning,configuration,shares,shareRequest,quickRequest,chatOverlayEnabled,quickReplyEnabled,quickMessages,quickFeedback,threadId,title,project,projects,turnId,busy,browsing,runningTask,conversationRequest,loadingHistory,feed,sessions,sessionsCursor,sessionQuery,archivedSessions,sessionsLoading,sessionsScanned,sessionsError,sessionMutation,models,selectedModel,selectedEffort,effectiveModel,effectiveEffort,failure,networkAvailable,refreshingAccount,prompts,phoneEnabled,phonePreRead,phoneModelPreRead,phonePreReadModel,historyCompression,historyImageStatus,historyImageStats,shellEnabled,shellAccess,adbStatus,adbConnected,adbSearching,error,logs,draft,importing,workspaceRevision,queue,queuePaused,steering,failedSubmission,phoneTaskActive,phonePaused,engine,handoffReason,phoneAction";
}
