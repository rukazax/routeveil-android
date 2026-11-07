package io.github.rukazax.routeveil;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Uses a separately signed, separate-UID APK: the main bridge process is excluded from the VPN. */
public final class ProbeClient {
    public static final String PACKAGE="io.github.rukazax.routeveil.probe";
    private ProbeClient() {}
    public static boolean available(Context context) {
        return context.getPackageManager().checkSignatures(context.getPackageName(),PACKAGE)==PackageManager.SIGNATURE_MATCH;
    }
    public static Bundle measure(Context context, java.util.function.BooleanSupplier stillActive) throws Exception {
        if(!available(context)) throw new IllegalStateException("probe_missing");
        String id=UUID.randomUUID().toString();
        CompletableFuture<Bundle> result=new CompletableFuture<>();
        Messenger reply=new Messenger(new Handler(Looper.getMainLooper(), msg->{
            Bundle data=msg.getData();
            if(msg.what==2 && id.equals(data.getString("request_id"))) result.complete(new Bundle(data));
            return true;
        }));
        ServiceConnection connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    Message request=Message.obtain(null,1);
                    Bundle data=new Bundle();data.putString("request_id",id);request.setData(data);request.replyTo=reply;
                    new Messenger(service).send(request);
                } catch(Exception e){result.completeExceptionally(e);}
            }
            public void onServiceDisconnected(ComponentName name){result.completeExceptionally(new IllegalStateException("probe_disconnected"));}
            public void onNullBinding(ComponentName name){result.completeExceptionally(new IllegalStateException("probe_unavailable"));}
            public void onBindingDied(ComponentName name){result.completeExceptionally(new IllegalStateException("probe_unavailable"));}
        };
        boolean bound=context.bindService(new Intent().setComponent(new ComponentName(PACKAGE,PACKAGE+".ProbeService")),connection,Context.BIND_AUTO_CREATE);
        if(!bound) throw new IllegalStateException("probe_bind_failed");
        try {
            long deadline=android.os.SystemClock.elapsedRealtime()+70000;
            while(stillActive.getAsBoolean()) {
                try {return result.get(1,TimeUnit.SECONDS);}
                catch(java.util.concurrent.TimeoutException wait) {
                    if(android.os.SystemClock.elapsedRealtime()>=deadline)throw wait;
                }
            }
            throw new InterruptedException("cancelled");
        } finally {context.unbindService(connection);}
    }
}
