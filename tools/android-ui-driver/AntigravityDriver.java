package dev.codex.go.testdriver;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
/** Own-emulator PRoot ACP handshake only. No authentication or model request. */
public final class AntigravityDriver extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();java.lang.Process process=null;int status=Activity.RESULT_CANCELED;
        try {
            if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish"))throw new SecurityException("Owned emulator only");
            Context context=getTargetContext();File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir);File binary=new File(nativeDir,"libguest_agy.so");
            if(!binary.isFile())throw new AssertionError("Official runtime missing");
            File home=new File(context.getFilesDir(),"agy-protocol-probe");File root=new File(home,"rootfs");root.mkdirs();
            try(ZipInputStream zip=new ZipInputStream(context.getAssets().open("antigravity/probe-rootfs.zip"))){
                ZipEntry entry;byte[] buffer=new byte[16384];
                while((entry=zip.getNextEntry())!=null){File target=new File(root,entry.getName());if(!target.getCanonicalPath().startsWith(root.getCanonicalPath()+"/"))throw new AssertionError("Invalid probe path");target.getParentFile().mkdirs();if(entry.isDirectory()){target.mkdirs();continue;}try(FileOutputStream output=new FileOutputStream(target)){int n;while((n=zip.read(buffer))>0)output.write(buffer,0,n);}}
            }
            JSONObject links=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(root,".links.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            for(Iterator<String> keys=links.keys();keys.hasNext();){String key=keys.next();File target=new File(root,key);target.getParentFile().mkdirs();if(!java.nio.file.Files.isSymbolicLink(target.toPath()))android.system.Os.symlink(links.getString(key),target.getPath());}
            for(String path:new String[]{"dev","proc","sys","tmp","root","bin"})new File(root,path).mkdirs();
            List<String> command=new ArrayList<>(Arrays.asList(new File(nativeDir,"libproot.so").getPath(),"-0","--link2symlink","-r",root.getPath(),"-w","/root","-b","/dev","-b","/proc","-b","/sys","-b",binary.getPath()+":/bin/agy"));
            JSONObject bindings=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(root,".bindings.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            for(Iterator<String> keys=bindings.keys();keys.hasNext();){String key=keys.next();command.add("-b");command.add(new File(nativeDir,bindings.getString(key)).getPath()+":"+key);}
            command.addAll(Arrays.asList("/bin/agy","acp","--disable-telemetry","--app-data-dir","protocol-probe"));
            ProcessBuilder builder=new ProcessBuilder(command);builder.directory(home);builder.environment().clear();builder.environment().put("HOME","/root");builder.environment().put("PATH","/bin:/usr/bin");builder.environment().put("TMPDIR","/tmp");builder.environment().put("PROOT_TMP_DIR",context.getCacheDir().getPath());builder.environment().put("PROOT_LOADER",new File(nativeDir,"libloader.so").getPath());builder.environment().put("PROOT_LOADER_32",new File(nativeDir,"libloader32.so").getPath());builder.environment().put("LD_LIBRARY_PATH",nativeDir.getPath());builder.redirectError(new File(home,"stderr.log"));process=builder.start();
            BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));
            writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientInfo\":{\"name\":\"codex-go-probe\",\"version\":\"0.20.0\"},\"clientCapabilities\":{}}}\n");writer.flush();
            BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));ExecutorService worker=Executors.newSingleThreadExecutor();String line;
            try{line=worker.submit(reader::readLine).get(20,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
            if(line==null)throw new AssertionError("Native process exited before ACP initialize: "+process.waitFor());
            JSONObject response=new JSONObject(line).getJSONObject("result");if(response.getInt("protocolVersion")!=1)throw new AssertionError("ACP mismatch");
            boolean google=false;JSONArray methods=response.getJSONArray("authMethods");for(int i=0;i<methods.length();i++)if(methods.getJSONObject(i).getString("id").equals("oauth-personal"))google=true;
            if(!google)throw new AssertionError("No Google auth method");
            writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"/root\",\"mcpServers\":[]}}\n");writer.flush();
            ExecutorService authWorker=Executors.newSingleThreadExecutor();String authLine;
            try{authLine=authWorker.submit(reader::readLine).get(30,TimeUnit.SECONDS);}finally{authWorker.shutdownNow();}
            JSONObject unauth=new JSONObject(authLine);
            if(!unauth.has("error")||unauth.getJSONObject("error").getInt("code")!=-32000)throw new AssertionError("Native auth gate failed: "+unauth.optJSONObject("error"));
            result.putString("facts",new JSONObject().put("officialAndroidProotHandshake",true).put("googleMethodAdvertised",true).put("officialAuthGateReached",true).put("agentVersion",response.getJSONObject("agentInfo").getString("version")).toString());status=Activity.RESULT_OK;
        }catch(Throwable error){result.putString("error",error.toString());}
        finally{if(process!=null)process.destroy();}
        finish(status,result);
    }
}
