package dev.codex.go.testdriver;
import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
/** Fake sign-in UI only. The URL and code are deliberately not usable credentials. */
public final class AgentLoginUiDriver extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();ServiceConnection connection=null;int status=Activity.RESULT_CANCELED;
        try{
            if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish"))throw new SecurityException("Owned emulator only");
            Context context=getTargetContext();ClassLoader loader=context.getClassLoader();Object[] engine={null};CountDownLatch ready=new CountDownLatch(1);
            context.getSharedPreferences("codex_go",0).edit().putBoolean("autoPrepare",false).putString("agentEngine","antigravity").commit();
            connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){try{engine[0]=b.getClass().getMethod("getEngine").invoke(b);}catch(Exception e){throw new RuntimeException(e);}ready.countDown();}public void onServiceDisconnected(ComponentName n){}};
            context.bindService(new Intent().setClassName("dev.codex.go","dev.codex.go.runtime.EngineService"),connection,Context.BIND_AUTO_CREATE);if(!ready.await(8,TimeUnit.SECONDS))throw new AssertionError("No engine");
            startActivitySync(new Intent().setClassName("dev.codex.go","dev.codex.go.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Throwable[] failure={null};runOnMainSync(()->{try{
                Field flowField=engine[0].getClass().getDeclaredField("updates");flowField.setAccessible(true);Object flow=flowField.get(engine[0]);Class<?> stateFlow=Class.forName("kotlinx.coroutines.flow.StateFlow",true,loader);Object old=stateFlow.getMethod("getValue").invoke(flow);Class<?> cls=old.getClass();
                Field namesField=PluginDriver.class.getDeclaredField("STATE_FIELDS");namesField.setAccessible(true);String[] names=((String)namesField.get(null)).split(",");Constructor<?> constructor=null;for(Constructor<?> c:cls.getConstructors())if(c.getParameterCount()==names.length)constructor=c;
                if(constructor==null)throw new AssertionError("State fixture changed");Object[] values=new Object[names.length];
                Object login=Class.forName("dev.codex.go.runtime.DeviceLogin",true,loader).getConstructor(String.class,String.class,String.class,String.class,boolean.class).newInstance("fixture","","https://accounts.google.com/o/oauth2/auth?state=TEST_ONLY","Google",true);
                for(int i=0;i<names.length;i++){values[i]=cls.getMethod("component"+(i+1)).invoke(old);if(names[i].equals("login"))values[i]=login;if(names[i].equals("loginPending"))values[i]=true;}
                Class.forName("kotlinx.coroutines.flow.MutableStateFlow",true,loader).getMethod("setValue",Object.class).invoke(flow,constructor.newInstance(values));
            }catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new Exception(failure[0]);
            java.io.File signal=new java.io.File(context.getFilesDir(),"login-ui-stop");signal.delete();new java.io.File(context.getFilesDir(),"login-ui-ready").createNewFile();long end=SystemClock.uptimeMillis()+45000;while(!signal.exists()&&SystemClock.uptimeMillis()<end)Thread.sleep(50);
            status=Activity.RESULT_OK;
        }catch(Throwable e){result.putString("error",e.toString());}
        finally{if(connection!=null)getTargetContext().unbindService(connection);}
        finish(status,result);
    }
}
