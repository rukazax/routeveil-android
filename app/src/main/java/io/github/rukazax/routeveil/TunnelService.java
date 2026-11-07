package io.github.rukazax.routeveil;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.lifecycle.Observer;
import org.torproject.onionmasq.ConnectivityHandler;
import org.torproject.onionmasq.ISocketProtect;
import org.torproject.onionmasq.OnionMasq;
import org.torproject.onionmasq.RouteveilProtectionCheck;
import org.torproject.onionmasq.events.BootstrapEvent;
import org.torproject.onionmasq.events.ConnectivityEvent;
import org.torproject.onionmasq.events.DNSConnectivityEvent;
import org.torproject.onionmasq.events.OnionmasqEvent;
import org.torproject.onionmasq.events.VPNetworkLostEvent;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Experimental single-engine lifecycle. A retained TUN duplicate blocks selected apps during handover. */
public final class TunnelService extends VpnService {
    public static final String ACTION_START="io.github.rukazax.routeveil.START";
    public static final String ACTION_STOP="io.github.rukazax.routeveil.STOP";
    public static final String ACTION_ROTATE="io.github.rukazax.routeveil.ROTATE";
    public static final String ACTION_OPTIMIZE="io.github.rukazax.routeveil.OPTIMIZE";
    private static final int NOTICE=10, CHALLENGE=11;
    private final ExecutorService commands=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    private final Handler main=new Handler(android.os.Looper.getMainLooper());
    private volatile CountDownLatch bootstrap;
    private volatile Thread engine;
    private volatile java.util.concurrent.atomic.AtomicBoolean runCancellation;
    private volatile Throwable engineFailure;
    private volatile boolean initialized, destroyed, connected, busy;
    private volatile int engineTicket;
    private volatile int latestStartId;
    private volatile long lastRotation;
    private ParcelFileDescriptor guard;
    private ConnectivityHandler connectivity;
    private Observer<OnionmasqEvent> observer;
    private TransportPlan.Profile current;
    private boolean registered, bound;
    private final ProtectBinder binder=new ProtectBinder();

    public final class ProtectBinder extends Binder implements ISocketProtect {
        @Override public boolean protect(int fd) { return TunnelService.this.protect(fd); }
    }
    @Override public IBinder onBind(Intent intent) {
        if (intent!=null && SERVICE_INTERFACE.equals(intent.getAction())) return super.onBind(intent);
        return binder;
    }
    @Override public void onCreate() {
        super.onCreate();
        Settings.initDefaults(this);
        log("service","created");
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("tunnel","Routeveil",NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("action",tr("Нужно ваше действие","Action required"),NotificationManager.IMPORTANCE_DEFAULT));
        try {
            OnionMasq.init(this);
            initialized=true;log("service","core_ready");
            OnionMasq.bindVPNService(TunnelService.class);bound=true;
            connectivity=new ConnectivityHandler(this);connectivity.register();registered=true;
            observer=this::onEngineEvent;OnionMasq.getEventObservable().observeForever(observer);
            clock.scheduleWithFixedDelay(()->{
                int seconds=RouteScore.rotationSeconds(Settings.prefs(this).getString("rotation_seconds","0"));
                if(seconds>0 && connected && !busy && SystemClock.elapsedRealtime()-lastRotation>=seconds*1000L) {
                    int ticket=generation.get();
                    commands.execute(()->{if(ticket==generation.get() && connected && !busy) rotate();});
                }
            },5,5,TimeUnit.SECONDS);
        } catch(Exception | LinkageError error) {
            engineFailure=error;log("service","core_init_failed");
            state(tr("Ошибка ядра","Core error"),tr("Не удалось загрузить VPN-ядро. Соединение не защищено.","Could not load VPN core. Connection is not protected."),false);
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        latestStartId=startId;
        startForeground(NOTICE,notification(tr("Подготовка","Preparing"),"tunnel"));
        String action=intent==null?ACTION_START:intent.getAction();
        if(ACTION_STOP.equals(action)) {
            int ticket=generation.incrementAndGet();log("stop","requested");
            commands.execute(()->disconnect(ticket,startId));
        } else if(ACTION_ROTATE.equals(action)) {
            commands.execute(()->{if(!busy && connected)rotate();});
        } else if(ACTION_START.equals(action)||ACTION_OPTIMIZE.equals(action)||SERVICE_INTERFACE.equals(action)) {
            int ticket=generation.incrementAndGet();
            boolean optimize=ACTION_OPTIMIZE.equals(action);
            commands.execute(()->connect(ticket,optimize));
        }
        return START_NOT_STICKY;
    }
    private void log(String event,String code) { DiagnosticLog.event(this,event,code); }
    private void logNetworkType() {
        android.net.ConnectivityManager cm=getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
        String code=caps==null?"offline":caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)?"wifi":caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)?"cellular":"other";
        log("network",code);
    }
    private boolean active(int ticket) {return !destroyed && ticket==generation.get();}
    private void checkActive(int ticket) throws InterruptedException {if(!active(ticket))throw new InterruptedException("cancelled");}
    private String tr(String ru,String en) {return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("ru")?ru:en;}
    private void state(String status,String detail,boolean isConnected) {
        if(destroyed)return;
        connected=isConnected;
        Settings.prefs(this).edit().putString("status",status).putString("detail",detail).putBoolean("connected",isConnected).apply();
        getSystemService(NotificationManager.class).notify(NOTICE,notification(status+" · "+detail,"tunnel"));
    }
    private Notification notification(String text,String channel) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,TunnelService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,channel).setSmallIcon(R.drawable.ic_routeveil).setContentTitle("Routeveil · Experimental")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(true);
        if(channel.equals("tunnel"))b.setOngoing(true).addAction(new Notification.Action.Builder(null,tr("Отключить","Disconnect"),stop).build());
        return b.build();
    }
    private void onEngineEvent(OnionmasqEvent event) {
        if(event==null || destroyed)return;
        if(event instanceof BootstrapEvent b) {
            CountDownLatch latch=bootstrap;
            if(latch!=null && active(engineTicket) && b.isReadyForTraffic && b.bootstrapPercent==100)latch.countDown();
        } else if(event instanceof DNSConnectivityEvent d && d.hasEnforcedPrivateDNS) {
            log("network","private_dns");
            int ticket=generation.incrementAndGet();
            commands.execute(()->{try {stopEngine();}catch(Exception ignored){} if(active(ticket))state(tr("Трафик приостановлен","Traffic paused"),tr("Строгий Private DNS несовместим с этим ядром. Выберите автоматический DNS и подключитесь снова. Исключения DIRECT остаются прямыми.","Strict Private DNS is incompatible with this core. Select automatic DNS and reconnect. DIRECT exceptions remain direct."),false);});
        } else if(event instanceof VPNetworkLostEvent || (event instanceof ConnectivityEvent c && !c.hasInternetConnectivity)) {
            log("network","lost");
            if(connected)state(tr("Сеть потеряна","Network lost"),tr("Туннель удерживается. Для восстановления нажмите Подключить.","Tunnel retained. Tap Connect to recover."),false);
        }
    }
    private String asset(String name) throws Exception {
        try(InputStream input=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[2048];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private List<TransportPlan.Profile> profiles() throws Exception {
        return TransportPlan.create(Settings.transport(this),Settings.bridgeLines(this),asset("snowflake.txt"),asset("obfs4.txt"));
    }
    private void connect(int ticket,boolean optimize) {
        if(!active(ticket))return;
        busy=true;
        log("connect","requested");logNetworkType();
        if(optimize)log("probe","requested");
        try {
            if(!initialized)throw new IllegalStateException("core_init");
            if(optimize && !ProbeClient.available(this)) {
                log("probe","helper_missing");
                state(tr("Нужен помощник измерений","Probe helper required"),tr("Установите Routeveil Probe с той же подписью. VPN работает без него, но скорость через Tor не будет измерена.","Install Routeveil Probe with the same signature. VPN works without it, but Tor speed cannot be measured."),connected);
                return;
            }
            if(connectivity.isPrivateDnsEnforced())throw new IllegalStateException("private_dns");
            List<TransportPlan.Profile> plan=profiles();
            String last=Settings.prefs(this).getString("best_profile","");
            if(!optimize && "auto".equals(Settings.transport(this))) {
                for(int i=0;i<plan.size();i++)if(plan.get(i).id.equals(last)){Collections.swap(plan,0,i);break;}
            }
            if(Build.VERSION.SDK_INT>=29 && isLockdownEnabled() && !Settings.directPackages(this).isEmpty())throw new IllegalStateException("lockdown");
            TransportPlan.Profile best=null,lastReady=null;
            double bestCost=Double.POSITIVE_INFINITY;
            List<String> results=new ArrayList<>();
            for(TransportPlan.Profile profile:plan) {
                checkActive(ticket);
                state(tr("Подключение","Connecting"),profile.type+" · "+(plan.indexOf(profile)+1)+"/"+plan.size(),false);
                if(!startProfile(profile,ticket)) {results.add(profile.id+": timeout/error");continue;}
                lastReady=profile;
                if(!optimize) {
                    state(tr("Подключено","Connected"),profile.type+tr(" · Tor готов; скорость и доступность сайтов ещё не проверены."," · Tor ready; speed and website availability are not yet verified."),true);
                    return;
                }
                state(tr("Измерение через Tor","Measuring through Tor"),profile.type+tr(" · HTTPS + 64 КиБ; DIRECT не используется для оценки."," · HTTPS + 64 KiB; DIRECT is not used for scoring."),true);
                try {
                    Bundle measured=ProbeClient.measure(this,()->active(ticket));checkActive(ticket);
                    String status=measured.getString("status","error");
                    log("probe",status);
                    if("challenge".equals(status)){challenge(measured.getString("challenge_url",""));results.add(profile.id+": challenge");continue;}
                    double score=RouteScore.cost("ok".equals(status),measured.getLong("bytes",0),measured.getLong("download_ms",0),measured.getLong("elapsed_ms",-1));
                    results.add(profile.id+": "+status+" / "+measured.getLong("download_ms",0)+" ms");
                    if(RouteScore.shouldReplace(bestCost,score)){best=profile;bestCost=score;}
                } catch(InterruptedException e){throw e;} catch(Exception e){results.add(profile.id+": probe_error");}
            }
            checkActive(ticket);
            Settings.prefs(this).edit().putString("last_measurements",String.join("\n",results)).apply();
            if(best!=null) {
                log("probe","winner_selected");
                if(current!=best || !connected){if(!startProfile(best,ticket))throw new IllegalStateException("winner_unavailable");}
                Settings.prefs(this).edit().putString("best_profile",best.id).apply();
                state(tr("Выбран по измерениям","Selected by measurements"),best.type+" · "+Math.round(bestCost)+tr(" мс (оценка). Лучший из проверенных сейчас; не гарантия максимума."," ms (score). Best observed now, not a global speed guarantee."),true);
            } else if(lastReady!=null && optimize) {
                log("probe","no_verified_result");
                if(current!=lastReady || !connected)startProfile(lastReady,ticket);
                state(tr("Скорость не подтверждена","Speed unverified"),tr("Tor запускался, но тесты не прошли. Выбор самого быстрого соединения не выполнен.","Tor bootstrapped, but probes failed. No fastest-route selection was made."),connected);
            } else {
                stopEngine();
                state(tr("Подключение недоступно","Connection unavailable"),tr("Мосты недоступны. Выбранные приложения заблокированы живым туннелем; DIRECT-исключения идут напрямую. Добавьте свежий мост или отключите VPN.","Bridges unavailable. Selected apps are blocked by the live tunnel; DIRECT exceptions remain direct. Add a fresh bridge or disconnect."),false);
            }
        } catch(InterruptedException ignored) {
            log("connect","cancelled");
            // A newer command owns cleanup/status; no automatic direct fallback.
        } catch(Exception | LinkageError error) {
            log("error",error instanceof IllegalArgumentException?"configuration":"lifecycle");
            try{if(initialized)stopEngine();}catch(Exception ignored){}
            if(active(ticket))state(tr("Требуется действие","Action required"),errorMessage(error),false);
        } finally {busy=false;}
    }
    private String errorMessage(Throwable error) {
        String code=error.getMessage();
        if("private_dns".equals(code))return tr("Выключите строгий Private DNS или выберите Автоматически, затем подключитесь снова.","Disable strict Private DNS or select Automatic, then reconnect.");
        if("lockdown".equals(code))return tr("Системный «Блокировать без VPN» несовместим с DIRECT. Отключите блокировку или уберите исключения. С мостами системная блокировка также требует отдельной проверки.","System Block connections without VPN conflicts with DIRECT. Disable lockdown or remove exceptions. Bridge mode also requires separate lockdown testing.");
        if(error instanceof IllegalArgumentException)return tr("Некорректные строки мостов или режим. WebTunnel требует HTTPS-мост. Неподдерживаемые параметры не заменяются прямым Tor.","Invalid bridge lines or mode. WebTunnel needs an HTTPS bridge. Unsupported parameters are never replaced with direct Tor.");
        return tr("Не удалось запустить туннель. Если значок VPN остался, выбранный трафик удерживается; отключение вернёт прямой доступ. Подробности ядра не публикуются.","Could not start the tunnel. If the VPN icon remains, selected traffic is held; disconnecting restores direct access. Core details are not published.");
    }
    private boolean startProfile(TransportPlan.Profile profile,int ticket) throws Exception {
        checkActive(ticket);stopEngine();checkActive(ticket);
        log("connect","profile_"+profile.type);
        if(connectivity.isPrivateDnsEnforced())throw new IllegalStateException("private_dns");
        if(profile.bridge() && Build.VERSION.SDK_INT>=29 && isLockdownEnabled())throw new IllegalStateException("lockdown");
        long deadline=SystemClock.elapsedRealtime()+5000;
        while(!RouteveilProtectionCheck.ready()) {
            checkActive(ticket);
            if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("socket_protection_not_ready");
            Thread.sleep(50);
        }
        // Drain previously queued callbacks before assigning the new bootstrap latch.
        CountDownLatch drained=new CountDownLatch(1);
        main.post(()->{OnionMasq.getEventObservable().setValue(null);drained.countDown();});
        if(!drained.await(5,TimeUnit.SECONDS))throw new IllegalStateException("main_unavailable");
        checkActive(ticket);
        Builder builder=new Builder().setSession("Routeveil · "+profile.type)
            .addAddress("169.254.42.1",16).addAddress("fc00::",7)
            .addRoute("0.0.0.0",0).addRoute("::",0)
            .addDnsServer("169.254.42.53").addDnsServer("fe80::53").setMtu(1500);
        if(Build.VERSION.SDK_INT>=29)builder.setMetered(true);
        for(String pkg:Settings.directPackages(this)) {
            if(pkg.equals(getPackageName())||pkg.equals(ProbeClient.PACKAGE))continue;
            try{builder.addDisallowedApplication(pkg);}catch(PackageManager.NameNotFoundException ignored){}
        }
        // Stock IPtProxy does not protect sockets; upstream Tor VPN uses the same bridge-only exclusion.
        if(profile.bridge())builder.addDisallowedApplication(getPackageName());
        builder.setConfigureIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        ParcelFileDescriptor tun=builder.establish();
        if(tun==null)throw new IllegalStateException("vpn_permission");
        ParcelFileDescriptor newGuard;
        try {newGuard=ParcelFileDescriptor.dup(tun.getFileDescriptor());}
        catch(Exception failure){tun.close();throw failure;}
        ParcelFileDescriptor previousGuard=guard;guard=newGuard;
        if(previousGuard!=null)try{previousGuard.close();}catch(Exception ignored){}
        current=profile;bootstrap=new CountDownLatch(1);engineFailure=null;engineTicket=ticket;
        java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean(false);
        runCancellation=cancelled;
        engine=new Thread(()->{
            try{OnionMasq.start(tun,profile.lines,()->!cancelled.get() && active(ticket));}catch(Exception | LinkageError e){engineFailure=e;}
            finally {
                CountDownLatch latch=bootstrap;if(latch!=null)latch.countDown();
                Thread finished=Thread.currentThread();
                try { commands.execute(()->{
                    if(active(ticket) && engine==finished) {
                        log("bootstrap","engine_error");
                        try{stopEngine();}catch(Exception ignored){}
                        state(tr("Трафик приостановлен","Traffic paused"),tr("Ядро остановилось. Туннель удерживается; подключитесь заново или отключите VPN.","Core stopped. Tunnel retained; reconnect or disconnect VPN."),false);
                    }
                }); } catch(java.util.concurrent.RejectedExecutionException ignored) { }
            }
        },"routeveil-native");
        try {engine.start();} catch(RuntimeException | Error failure) {tun.close();engine=null;throw failure;}
        long bootDeadline=SystemClock.elapsedRealtime()+90000;
        while(bootstrap.getCount()!=0) {
            checkActive(ticket);
            if(SystemClock.elapsedRealtime()>bootDeadline){log("bootstrap","timeout");stopEngine();return false;}
            bootstrap.await(250,TimeUnit.MILLISECONDS);
        }
        checkActive(ticket);
        if(engineFailure!=null || engine==null || !engine.isAlive() || !OnionMasq.isRunning()){stopEngine();return false;}
        connected=true;lastRotation=SystemClock.elapsedRealtime();
        log("bootstrap","ready");log("connect","connected");
        return true;
    }
    private void stopEngine() throws Exception {
        connected=false;bootstrap=null;
        java.util.concurrent.atomic.AtomicBoolean cancelled=runCancellation;
        if(cancelled!=null)cancelled.set(true);
        Thread running=engine;
        if(initialized && running!=null) {
            long deadline=SystemClock.elapsedRealtime()+15000;
            // The first stop may precede JNI installing its command sender. Retry until the
            // owner thread really exits; never mistake a sent command for terminated native code.
            while(running.isAlive()) {
                OnionMasq.requestNativeStop();
                running.join(100);
                if(SystemClock.elapsedRealtime()>=deadline && running.isAlive()) {
                    log("stop","engine_timeout");
                    throw new IllegalStateException("native_stop_timeout");
                }
            }
            OnionMasq.stop(); // PT preparation lock is released after the native owner exits.
        }
        engine=null;runCancellation=null;
        // Do not close guard: holding it prevents selected apps falling back to DIRECT during handover.
    }
    private void rotate() {
        if(!connected||busy)return;
        if(SystemClock.elapsedRealtime()-lastRotation<60000){state(tr("Подождите","Please wait"),tr("Не чаще одного запроса новых цепочек в минуту.","Request new circuits at most once a minute."),true);return;}
        try {
            OnionMasq.refreshCircuits();lastRotation=SystemClock.elapsedRealtime();
            state(tr("Новые цепочки запрошены","New circuits requested"),tr("Для новых соединений. IP может остаться прежним; старые соединения, cookies и входы в аккаунты не меняются.","For new connections. IP may stay the same; existing connections, cookies and logins are unchanged."),true);
        } catch(Exception e){state(tr("Ротация не выполнена","Rotation failed"),tr("Проверьте соединение. Новый IP не подтверждён.","Check connectivity. A new IP is not confirmed."),connected);}
    }
    private void challenge(String url) {
        if(!"https://check.torproject.org/".equals(url) && !"https://bridges.torproject.org/".equals(url))return;
        Settings.prefs(this).edit().putString("challenge_url",url).apply();
        String text=tr("Проверочный сайт запросил подтверждение человека. Откройте его и пройдите CAPTCHA вручную, затем повторите тест нового IP. Это требование сайта, не обязательный этап смены IP в Tor.","The check website requested human verification. Open it and solve CAPTCHA manually, then retry the new-IP test. This is a website requirement, not a required step for Tor IP rotation.");
        getSystemService(NotificationManager.class).notify(CHALLENGE,notification(text,"action"));
    }
    private void disconnect(int ticket,int startId) {
        if(!active(ticket))return;
        try {if(initialized)stopEngine();}catch(Exception error){
            if(active(ticket))state(tr("Остановка не завершена","Stop not completed"),tr("Ядро ещё останавливается. Туннель сохранён; повторите Отключить. Прямой доступ не заявляется.","Core is still stopping. Tunnel retained; retry Disconnect. Direct access is not claimed."),false);
            return;
        }
        if(!active(ticket))return;
        try{if(guard!=null)guard.close();}catch(Exception ignored){}guard=null;
        log("stop","complete");
        state(tr("Отключено","Disconnected"),tr("VPN отключён. Приложения используют обычную сеть.","VPN off. Apps use the ordinary network."),false);
        main.post(()->{
            if(!active(ticket))return;
            if(bound){OnionMasq.unbindVPNService();bound=false;}
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);
        });
    }
    @Override public void onRevoke() {
        int ticket=generation.incrementAndGet();int startId=latestStartId;log("stop","revoked");
        commands.execute(()->disconnect(ticket,startId));
    }
    @Override public void onDestroy() {
        destroyed=true;generation.incrementAndGet();clock.shutdownNow();
        Settings.prefs(this).edit().putBoolean("connected",false).apply();
        if(initialized && observer!=null)OnionMasq.getEventObservable().removeObserver(observer);
        if(registered && connectivity!=null)connectivity.unregister();
        if(bound){OnionMasq.unbindVPNService();bound=false;}
        commands.execute(()->{try{if(initialized)stopEngine();}catch(Exception ignored){}try{if(guard!=null)guard.close();}catch(Exception ignored){}guard=null;});
        commands.shutdown();
        super.onDestroy();
    }
}
