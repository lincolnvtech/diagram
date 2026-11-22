package br.com.civicdrive;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Civic G8 1.9.4: owner-approved HUD, fixed Nova core, four new dashboards.
 * Art contains NO live telemetry. RPM, ECT, updates and shortcuts stay native.
 * Valores não disponíveis aparecem como travessão.
 */
public final class MainActivity extends Activity implements LocationListener {
    private static final int REQUEST_GPS = 41, REQUEST_BLUETOOTH = 42;
    private static final int LIGHT = Color.WHITE;
    private static final int BLUE = Color.rgb(31, 148, 252);
    private static final Locale PT = new Locale("pt", "BR");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final String OBD_PREFS = "civic_obd";
    private long nextObdRetryMs = 0L;
    private boolean autoObdEnabled = true;
    private boolean isShuttingDown = false;
    private Ui surface;
    private LocationManager gps;
    private boolean dashboardOpen = false;
    private ObdManager obd;
    private UpdateManager updates;
    private String gpsStatus = "GPS aguardando sinal";
    private String obdStatus = "Desconectado";
    private volatile boolean obdConnected = false;
    private float gpsKmh = -1f;
    private long lastGpsTime = 0L;
    private double rpm = -1d;
    private int coolant = -999, obdSpeed = -1;
    private float volts = -1f;
    private double mapKpa = -1, engineLoadPct = -1, intakeTempC = -999, shortFuelTrimPct = -999;
    private double avgKmL = -1d, fuelLitresPerHour = -1d, tripDistanceKm = 0d, tripFuelLitres = 0d;
    private final GearEstimator gearEstimator=new GearEstimator();
    private int estimatedGear=0,estimatedGearConfidence=0;
    private long estimatedGearAt=0L;
    private String estimatedGearReason="SEM OBD2", estimatedGearSource="—";
    private float estimatedGearSpeed=-1f;
    private long lastFuelMs = 0L;
    private long lastHistorySample = 0L;
    // Throttle flash-backed SharedPreferences, preserving the latest ECU state.
    private static final long TELEMETRY_MIN_GAP_MS=1000L;
    private long lastTelemetryWrite=0L;
    private boolean telemetryFlushPending=false;
    private final Runnable telemetryFlush=new Runnable(){
        @Override public void run(){
            telemetryFlushPending=false;
            writeTelemetry();
        }
    };
    private static final String HOME_PREFS = "civic_shortcuts";
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (gpsKmh >= 0 && System.currentTimeMillis() - lastGpsTime > 30000) {
                gpsKmh = -1; gpsStatus = "GPS aguardando sinal";
            }
            // A BT socket can remain blocked in connect() on older head units.
            // Abort only if no ECU data ever arrived for 42 seconds.
            if(obd!=null && obd.abortStalledConnection(42000L))
                nextObdRetryMs=System.currentTimeMillis()+12000L;
            // Reconnect only with a paired ELM327 or a previously selected adapter.
            tryAutoObd();
            if (surface != null) surface.invalidate();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        autoObdEnabled = getSharedPreferences(OBD_PREFS, MODE_PRIVATE).getBoolean("auto", true);
        gps = (LocationManager) getSystemService(LOCATION_SERVICE);
        updates = new UpdateManager(this);
        obd = new ObdManager(new ObdManager.Listener() {
            @Override public void onStatus(String status, boolean connected) {
                runOnUiThread(() -> {
                    obdStatus = connected ? "Conectado" :
                        status != null && status.startsWith("Conectando") ? "Conectando…" :
                        status != null && status.contains("ECU") ? "ECU sem dados" :
                        status != null && status.contains("permissão") ? "Sem permissão BT" :
                        status != null && status.contains("expirou") ? "Tempo esgotado" :
                        "Desconectado";
                    obdConnected = connected;
                    if (!connected) { rpm = -1; coolant = -999; volts = -1; obdSpeed = -1;
                        fuelLitresPerHour = -1; lastFuelMs = 0;
                        estimatedGear=0;estimatedGearConfidence=0;estimatedGearAt=0;
                        estimatedGearReason="SEM OBD2";estimatedGearSource="—";estimatedGearSpeed=-1f;
                        mapKpa=-1; engineLoadPct=-1; intakeTempC=-999; shortFuelTrimPct=-999;
                        nextObdRetryMs = System.currentTimeMillis() + 12000L;
                    } else nextObdRetryMs = 0L;
                    saveTelemetry();
                    surface.invalidate();
                });
            }
            @Override public void onValues(double valueRpm, int temperature, float voltage, int speed) {
                runOnUiThread(() -> {
                    rpm = valueRpm; coolant = temperature; volts = voltage; obdSpeed = speed;
                    // A v1.9.7 descartava GPS. Sem resposta 010D o campo ficava sempre vazio.
                    // OBD2 tem prioridade; GPS recente e valido e usado como alternativa.
                    long now=System.currentTimeMillis();
                    boolean gpsFresh=lastGpsTime>0 && now-lastGpsTime<7000L && gpsKmh>=0;
                    double speedForGear=obdSpeed;
                    String speedSource=obdSpeed>=0?"OBD2":"—";
                    if(gpsFresh && (obdSpeed<0 || (obdSpeed<5 && gpsKmh>=12) ||
                            (gpsKmh>=15 && obdSpeed>=0 &&
                             Math.abs(obdSpeed-gpsKmh)>Math.max(13d,gpsKmh*0.40d)))) {
                        speedForGear=gpsKmh;
                        speedSource="GPS";
                    }
                    GearEstimator.Result gear=gearEstimator.update(rpm,speedForGear,now);
                    estimatedGear=gear.gear; estimatedGearConfidence=gear.confidence;
                    estimatedGearAt=now; estimatedGearReason=gear.reason;
                    estimatedGearSource=speedSource; estimatedGearSpeed=(float)speedForGear;
                    saveTelemetry();
                    surface.invalidate();
                    saveHistorySample();
                });
            }
            @Override public void onFuelRate(double litresPerHour) {
                runOnUiThread(() -> {
                    fuelLitresPerHour = litresPerHour;
                    updateTrip(litresPerHour);
                    saveTelemetry();
                    surface.invalidate();
                });
            }
            @Override public void onExtended(double map, double load, double intake, double trim) {
                runOnUiThread(() -> {
                    mapKpa=map; engineLoadPct=load; intakeTempC=intake; shortFuelTrimPct=trim;
                    saveTelemetry();
                    saveHistorySample();
                });
            }
        });
        surface = new Ui();
        setContentView(surface);
        handler.post(ticker);
        requestGps();
        handler.postDelayed(this::tryAutoObd, 1100L);
        // The Cyberpunk cockpit is a presentation Activity. This MainActivity
        // keeps owning the existing Bluetooth/ELM327 worker and telemetry.
        if(CivicTheme.index(this)==4&&state==null){
            handler.postDelayed(()->{
                if(!isShuttingDown&&!isFinishing()){
                    dashboardOpen=true;
                    safeStart(new Intent(this,CyberpunkActivity.class));
                }
            },620L);
        }
    }

    private void updateTrip(double litresPerHour) {
        long now = System.currentTimeMillis();
        if (litresPerHour < 0 || Double.isNaN(litresPerHour)) { lastFuelMs = 0; return; }
        if (lastFuelMs > 0) {
            double hours = (now-lastFuelMs)/3600000d;
            if (hours > 0 && hours < (30d / 3600d)) {
                double speed = obdSpeed >= 0 ? obdSpeed : gpsKmh;
                if (speed >= 0) {
                    tripDistanceKm += speed * hours;
                    tripFuelLitres += litresPerHour * hours;
                    if (tripDistanceKm > .10 && tripFuelLitres > .02)
                        avgKmL = Math.min(99.9, tripDistanceKm / tripFuelLitres);
                }
            }
        }
        lastFuelMs = now;
    }
    private void resetTrip() {
        tripDistanceKm=0;tripFuelLitres=0;avgKmL=-1;lastFuelMs=0;
        saveTelemetry();surface.invalidate();
    }
    private void saveTelemetry() {
        long elapsed=lastTelemetryWrite==0L?TELEMETRY_MIN_GAP_MS:
            android.os.SystemClock.elapsedRealtime()-lastTelemetryWrite;
        long remaining=TELEMETRY_MIN_GAP_MS-elapsed;
        if(remaining<=0L){
            handler.removeCallbacks(telemetryFlush);
            telemetryFlushPending=false;
            writeTelemetry();
        }else if(!telemetryFlushPending){
            telemetryFlushPending=true;
            handler.postDelayed(telemetryFlush,remaining);
        }
    }
    private void writeTelemetry(){
        lastTelemetryWrite=android.os.SystemClock.elapsedRealtime();
        getSharedPreferences("civic_telemetry",MODE_PRIVATE).edit()
                .putBoolean("connected", obdConnected).putFloat("rpm", (float)rpm)
                .putInt("temp",coolant).putFloat("volts",volts)
                .putFloat("gps",gpsKmh).putLong("gpsAt",lastGpsTime)
                .putInt("obdSpeed",obdSpeed)
                .putFloat("fuelRate",(float)fuelLitresPerHour).putFloat("kmL",(float)avgKmL)
                .putFloat("tripKm",(float)tripDistanceKm).putFloat("tripL",(float)tripFuelLitres)
                .putFloat("mapKpa",(float)mapKpa).putFloat("engineLoad",(float)engineLoadPct)
                .putFloat("intakeTemp",(float)intakeTempC).putFloat("stft",(float)shortFuelTrimPct)
                .putInt("gearEst",estimatedGear).putInt("gearConf",estimatedGearConfidence)
                .putString("gearReason",estimatedGearReason).putString("gearSource",estimatedGearSource)
                .putFloat("gearSpeed",estimatedGearSpeed).putLong("gearAt",estimatedGearAt)
                .putLong("lastUpdate",System.currentTimeMillis()).apply();
    }


    /** Coleta local, sem GPS exato, VIN nem dados pessoais; nenhum envio automático. */
    private void saveHistorySample() {
        long now = System.currentTimeMillis();
        // Ten-minute snapshots: 1008 points cover seven days without oversized exports.
        if (!obdConnected || now - lastHistorySample < 600000L) return;
        lastHistorySample = now;
        TelemetryArchive.record(this, now, (float)rpm, coolant, volts,
                gpsKmh, (float)avgKmL, (float)mapKpa, (float)engineLoadPct,
                (float)intakeTempC, (float)shortFuelTrimPct);
    }

    private void openApplicationCatalog(boolean selecting, int slot) {
        Intent intent = new Intent(this, AppCatalogActivity.class);
        intent.putExtra("select_slot", selecting ? slot : -1);
        safeStart(intent);
    }
    private String slotPackage(int slot) {
        return getSharedPreferences(HOME_PREFS, MODE_PRIVATE).getString("pkg" + slot, "");
    }
    private String slotActivity(int slot) {
        return getSharedPreferences(HOME_PREFS, MODE_PRIVATE).getString("activity" + slot, "");
    }
    private void openShortcut(int slot) {
        String pkg=slotPackage(slot), activity=slotActivity(slot);
        if(pkg.isEmpty()) { openApplicationCatalog(true,slot);return; }
        if(!activity.isEmpty()) {
            Intent i=new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg,activity));
            safeStart(i);return;
        }
        openPackage(pkg);
    }

    /**
     * Native responsive HUD, without any baked-in telemetry or speed-color legend.
     * Draws only live GPS / OBD values. Its center is a small animated energy orb.
     */
    private final class Ui extends View {
        private static final float CW=1648f, CH=928f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Bitmap civic = BitmapFactory.decodeResource(getResources(), R.drawable.civic_cinema);
        private final Bitmap approvedBackdrop = BitmapFactory.decodeResource(getResources(), R.drawable.civic_v191_base);
        // Shared with the music dashboard; images are decoded only once.
        private final CivicOrbRenderer orbRenderer=
            new CivicOrbRenderer(MainActivity.this);
        private final Drawable[] shortcuts = new Drawable[3];
        private final String[] shortcutsNames = new String[3];
        private int wallpaper = 0; // 0 = Nova (animated), 1 = Civic photo, 2 = quiet energy
        // Plasma color and movement are driven by valid engine RPM only.
        private float downX, downY;
        private long downAt;
        private boolean animationRunning = true;
        private final float[] dockX = {174, 358, 542, 726, 910, 1094, 1278, 1462};
        private final String[] dockNames = {"Início","Dash","Waze","Música","YouTube","Apps","Fundo","Nova"};
        Ui(){
            super(MainActivity.this);
            wallpaper=getSharedPreferences("civic_wallpaper",MODE_PRIVATE).getInt("mode",0);
            setContentDescription("Civic Drive. Nova em energia animada, temperatura, RPM e menu de aplicativos.");
        }
        void resumeAnimation() { animationRunning=true;invalidate(); }
        void pauseAnimation() { animationRunning=false; }
        void useWallpaper(int mode){ wallpaper=mode;getSharedPreferences("civic_wallpaper",MODE_PRIVATE).edit().putInt("mode",mode).apply();invalidate(); }
        private void color(int c){
            p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);
            p.setColor(CivicTheme.homeAccent(c,CivicTheme.index(MainActivity.this)));
        }
        private void txt(Canvas c,String s,float x,float baseline,float size,int tint,boolean bold){
            color(tint);p.setTextSize(size);p.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
            c.drawText(s,x,baseline,p);
        }
        private void center(Canvas c,String s,float x,float baseline,float size,int tint,boolean bold){
            color(tint);p.setTextSize(size);p.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
            c.drawText(s,x-p.measureText(s)/2,baseline,p);
        }
        private void line(Canvas c,float x1,float y1,float x2,float y2,int tint,float stroke){
            color(tint);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x1,y1,x2,y2,p);
        }
        private void outline(Canvas c,RectF rect,float radius,int tint,float stroke){
            color(tint);p.setStrokeWidth(stroke);p.setStyle(Paint.Style.STROKE);c.drawRoundRect(rect,radius,radius,p);p.setStyle(Paint.Style.FILL);
        }
        private void glowBackground(Canvas c){
            // Exact reference approved by the owner, with text/data removed in the authored asset.
            // This preserves its glassy instrument rings and natural electric illumination.
            if(approvedBackdrop!=null){
                color(Color.WHITE);
                p.setColorFilter(CivicTheme.artFilter(CivicTheme.index(MainActivity.this)));
                c.drawBitmap(approvedBackdrop,null,new RectF(0,0,CW,CH),p);
                p.setColorFilter(null);
            } else {
                color(Color.rgb(2,10,20));c.drawRect(0,0,CW,CH,p);
            }
            // Preserve screen boundaries on lower-resolution central units.
            line(c,42,694,1606,694,Color.argb(60,26,110,190),1);
            line(c,150,885,1500,885,Color.argb(55,21,111,179),2);
        }
        private int velocity(){
            float live = gpsKmh >= 0 && System.currentTimeMillis()-lastGpsTime <= 30000 ? gpsKmh :
                (obdConnected && obdSpeed >= 0 ? obdSpeed : -1f);
            return live < 0 ? -1 : Math.round(live);
        }
        private int alpha(int color,int a){
            return Color.argb(a,Color.red(color),Color.green(color),Color.blue(color));
        }
        private float orbVehicleSpeed(){
            // Real OBD2 speed first; recent GPS only when an OBD2 reading
            // is missing. Never infer km/h from RPM or music.
            if(obdConnected && obdSpeed>=0)return obdSpeed;
            if(gpsKmh>=0 && System.currentTimeMillis()-lastGpsTime<9000L)
                return gpsKmh;
            return -1f;
        }
        private void orb(Canvas c){
            float level=musicReactive.frame(MainActivity.this,
                android.os.SystemClock.uptimeMillis());
            orbRenderer.draw(c,811f,263f,level,(float)rpm,
                orbVehicleSpeed(),obdConnected);
        }
        private void civicWallpaper(Canvas c){
            if(civic != null){
                color(Color.WHITE);p.setAlpha(175);
                c.drawBitmap(civic,null,new RectF(425,128,1223,520),p);
                p.setAlpha(255);
            }
            center(c,"CIVIC",820,515,41,Color.rgb(205,222,243),true);
        }
        private void quietWallpaper(Canvas c){
            color(Color.WHITE);
            int palette=CivicTheme.index(MainActivity.this);
            int accent=CivicTheme.color(palette,1);
            p.setShader(new android.graphics.RadialGradient(820,300,210,
                new int[]{alpha(accent,110),alpha(CivicTheme.color(palette,2),32),Color.TRANSPARENT},
                null,android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(820,300,210,p);p.setShader(null);
            color(Color.rgb(19,105,184));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);
            c.drawCircle(820,300,154,p);p.setStyle(Paint.Style.FILL);
            // Papel de parede minimalista sem título na esfera.
        }
        private void header(Canvas c){
            // Honda identity is part of the approved decoration; don't redraw it.
            // Date/time must be live, never pre-printed in wallpaper.
            java.util.Date date = new java.util.Date();
            center(c,new java.text.SimpleDateFormat("HH:mm",PT).format(date),1423,53,46,
                Color.rgb(232,245,255),true);
            center(c,new java.text.SimpleDateFormat("EEE, dd/MM/yyyy",PT).format(date),1423,88,22,
                Color.rgb(169,190,211),false);
            line(c,1567,37,1593,37,Color.rgb(158,195,224),3);
            line(c,1567,50,1593,50,Color.rgb(158,195,224),3);
            line(c,1567,63,1593,63,Color.rgb(158,195,224),3);
        }
        private void gauge(Canvas c,float cx,float cy,boolean temp){
            // Dial contours, ticks and blue reflections are in the approved art.
            // Needle/progress and center numbers are live, not painted-in demo data.
            final boolean available=obdConnected && (temp?coolant>-100:rpm>=0);
            final float radius=177f;
            final float f=available?Math.max(0f,Math.min(1f,temp?(coolant-40)/75f:(float)(rpm/8000d))):0f;
            if(available){
                final boolean hot=temp && coolant>=105;
                final int accent=hot?Color.rgb(248,170,56):Color.rgb(15,167,234);
                color(accent);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(8f);
                c.drawArc(new RectF(cx-radius,cy-radius,cx+radius,cy+radius),143,253*f,false,p);
                p.setStyle(Paint.Style.FILL);
            }
            if(temp){
                center(c,"ARREFECIMENTO",cx,cy-21,24,Color.rgb(199,217,231),false);
                center(c,available?coolant+" °C":"— °C",cx,cy+52,61,Color.WHITE,true);
            }else{
                center(c,available?String.format(PT,"%.1f",rpm/1000d):"—",cx,cy+20,67,Color.WHITE,true);
                center(c,"×1000 RPM",cx,cy+65,23,Color.rgb(206,223,234),false);
            }
        }
        private void status(Canvas c){
            // The approved layout uses a slim, almost-black translucent information ribbon.
            RectF bar=new RectF(75,559,1573,680);
            color(Color.argb(112,2,12,25));c.drawRoundRect(bar,13,13,p);
            outline(c,bar,13,Color.argb(84,33,109,172),1.2f);
            line(c,531,577,531,660,Color.rgb(15,73,106),1.6f);
            line(c,1045,577,1045,660,Color.rgb(15,73,106),1.6f);
            txt(c,"OBD2",171,602,24,Color.rgb(164,193,213),false);
            txt(c,obdStatus,171,646,34,obdConnected?Color.rgb(42,204,232):Color.rgb(201,208,223),true);
            // A temperatura já aparece no mostrador esquerdo: usar o espaço
            // para a marcha estimada, nunca fingir que é leitura PRND da ECU.
            txt(c,"MARCHA ESTIMADA",614,602,24,Color.rgb(164,193,213),false);
            boolean gearFresh=obdConnected && estimatedGear>=1 && estimatedGear<=5
                    && estimatedGearConfidence>=55 && estimatedGearAt>0
                    && System.currentTimeMillis()-estimatedGearAt<10000L;
            String gearDisplay=gearFresh?(estimatedGear+"ª  EST."):
                !obdConnected?"— · SEM OBD2":
                estimatedGearAt<=0||System.currentTimeMillis()-estimatedGearAt>10000L?
                "— · SEM DADOS":"— · "+estimatedGearReason;
            txt(c,gearDisplay,614,646,gearFresh?35:28,
                    gearFresh?Color.rgb(58,199,239):Color.rgb(176,190,205),true);
            txt(c,"CONSUMO ESTIMADO",1136,602,24,Color.rgb(164,193,213),false);
            txt(c,avgKmL>=0?String.format(PT,"%.1f km/L",avgKmL):"— km/L",1136,646,34,Color.WHITE,true);
        }
        private void icon(Canvas c,int kind,float x,float y,int tint){
            color(tint);p.setStrokeWidth(4f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
            android.graphics.Path shape=new android.graphics.Path();
            switch(kind){
                case 0: // Home
                    shape.moveTo(x-23,y);shape.lineTo(x,y-24);shape.lineTo(x+23,y);shape.lineTo(x+19,y+24);
                    shape.lineTo(x+6,y+24);shape.lineTo(x+6,y+6);shape.lineTo(x-6,y+6);shape.lineTo(x-6,y+24);
                    shape.lineTo(x-19,y+24);shape.close();c.drawPath(shape,p);break;
                case 1: // Gauge
                    c.drawArc(new RectF(x-24,y-20,x+24,y+28),190,160,false,p);
                    c.drawLine(x,y+6,x+17,y-12,p);break;
                case 2: // Waze / speech
                    c.drawRoundRect(new RectF(x-23,y-21,x+22,y+15),14,14,p);
                    c.drawLine(x-6,y+15,x-10,y+23,p);
                    c.drawCircle(x+10,y-4,2,p);break;
                case 3: // Música
                    c.drawCircle(x-12,y+17,8,p);
                    c.drawCircle(x+18,y+9,8,p);
                    c.drawLine(x-4,y+17,x-4,y-22,p);
                    c.drawLine(x+26,y+9,x+26,y-27,p);
                    c.drawLine(x-4,y-22,x+26,y-27,p);break;
                case 4: // YouTube - ícone play; não confundir com o botão de adicionar
                    c.drawRoundRect(new RectF(x-27,y-19,x+27,y+19),8,8,p);
                    shape.moveTo(x-7,y-11);shape.lineTo(x+12,y);
                    shape.lineTo(x-7,y+11);shape.close();c.drawPath(shape,p);break;
                case 5: // Apps
                    for(int col=-1;col<=1;col++)for(int row=-1;row<=1;row++){
                        c.drawRoundRect(new RectF(x+col*14-4,y+row*14-4,x+col*14+4,y+row*14+4),2,2,p);
                    }break;
                case 6: // Wallpaper
                    c.drawRoundRect(new RectF(x-25,y-21,x+25,y+22),5,5,p);
                    shape.moveTo(x-18,y+14);shape.lineTo(x-4,y);shape.lineTo(x+6,y+10);
                    shape.lineTo(x+14,y+1);shape.lineTo(x+22,y+14);c.drawPath(shape,p);
                    c.drawCircle(x+14,y-11,4,p);break;
                case 7: // Nova waveform
                    shape.moveTo(x-27,y);shape.lineTo(x-15,y);shape.lineTo(x-8,y-15);shape.lineTo(x+1,y+18);
                    shape.lineTo(x+8,y-16);shape.lineTo(x+16,y);shape.lineTo(x+27,y);c.drawPath(shape,p);break;
            }
            p.setStyle(Paint.Style.FILL);
        }
        private void dock(Canvas c){
            for(int i=0;i<dockX.length;i++){
                float x=dockX[i];int tint=i==0?Color.rgb(45,205,244):Color.rgb(200,219,235);
                boolean custom=i>=2&&i<=4;
                String label=dockNames[i];
                if(custom){
                    int slot=i-2;
                    String pkg=slotPackage(slot),activity=slotActivity(slot);
                    String customLabel=getSharedPreferences(HOME_PREFS,MODE_PRIVATE).getString("label"+slot,"");
                    if(!pkg.isEmpty()){
                        label=customLabel.isEmpty()?"Atalho":customLabel;
                        try{
                            String key=pkg+"/"+activity;
                            if(!key.equals(shortcutsNames[slot])||shortcuts[slot]==null){
                                shortcutsNames[slot]=key;
                                shortcuts[slot]=activity.isEmpty()?getPackageManager().getApplicationIcon(pkg):getPackageManager().getActivityIcon(new ComponentName(pkg,activity));
                            }
                            Drawable icon=shortcuts[slot];icon.setBounds((int)x-25,749,(int)x+25,799);icon.draw(c);
                        }catch(Exception e){icon(c,i,x,774,tint);}
                    }else icon(c,i,x,774,tint);
                }else icon(c,i,x,774,tint);
                if(label.length()>10)label=label.substring(0,9)+"…";
                center(c,label,x,851,23,i==0?Color.rgb(62,212,244):Color.rgb(185,204,224),i==0);
            }
            line(c,125,875,223,875,Color.rgb(36,196,242),3);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);c.save();c.scale(getWidth()/CW,getHeight()/CH);
            glowBackground(c);header(c);
            if(wallpaper==1)civicWallpaper(c);else if(wallpaper==2)quietWallpaper(c);else orb(c);
            gauge(c,303,334,true);gauge(c,1324,334,false);
            center(c,"TEMA  "+CivicTheme.NAMES[CivicTheme.index(MainActivity.this)].toUpperCase(PT),
                   810,543,23,CivicTheme.color(CivicTheme.index(MainActivity.this),1),true);
            status(c);dock(c);
            c.restore();
            // Deliberately limit CPU usage on older Android head units.
            if(animationRunning && wallpaper==0 && getWindowVisibility()==View.VISIBLE)
                postInvalidateDelayed(110); // ~9 fps: preserva CPU para Bluetooth e ELM327
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX()*CW/getWidth(),y=e.getY()*CH/getHeight();
            if(e.getAction()==MotionEvent.ACTION_DOWN){downX=x;downY=y;downAt=android.os.SystemClock.uptimeMillis();return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){
                if(Math.abs(x-downX)>42||Math.abs(y-downY)>42)return true;
                performClick();
                if(android.os.SystemClock.uptimeMillis()-downAt>650){
                    if(y<720){pickWallpaper();return true;}
                    if(y>730 && x>=400 && x<=1035){
                        int slot=Math.round((x-542)/184);if(slot>=0&&slot<3)openApplicationCatalog(true,slot);
                    }
                    return true;
                }
                if(y>731){
                    int idx=Math.round((x-174)/184);if(idx<0||idx>=dockX.length||Math.abs(x-dockX[idx])>83)return true;
                    switch(idx){
                        case 0:break;
                        case 1:openDashboards();break;
                        case 2:if(slotPackage(0).isEmpty())openWaze();else openShortcut(0);break;
                        case 3:if(slotPackage(1).isEmpty())openMusic();else openShortcut(1);break;
                        case 4:if(slotPackage(2).isEmpty())openYouTube();else openShortcut(2);break;
                        case 5:showInstalledApps();break;
                        case 6:pickWallpaper();break;
                        case 7:safeStart(new Intent(MainActivity.this,NovaActivity.class));break;
                    }
                }else if(x>1525 && y<110)showMenu();
                else if(y>=573 && y<=690 && x<560)chooseObdDevice();
                else if(y>=573 && y<=690 && x<1045)showGearDiagnostics();
                else if(y>=573 && y<=690)openDashboards();
                else if(x>495&&x<1140&&y>110&&y<555)safeStart(new Intent(MainActivity.this,NovaActivity.class));
                else if(y>130&&y<546)openDashboards();
                return true;
            }
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }

    private void pickWallpaper(){
        new AlertDialog.Builder(this).setTitle("Plano de fundo")
            .setSingleChoiceItems(new String[]{"Nova · esfera animada", "Civic prata", "Nova · azul minimalista"},
                getSharedPreferences("civic_wallpaper", MODE_PRIVATE).getInt("mode",0),
                (d,which)->{ if(surface!=null)surface.useWallpaper(which);d.dismiss(); })
            .setNeutralButton("5 TEMAS DE CORES",(d,w)->showThemes())
            .setNegativeButton("Cancelar",null).show();
    }
    private void openRadio(){
        for(String pkg:new String[]{"com.android.fmradio","com.microntek.radio","com.ts.MainUI","com.syu.radio","com.android.radio"}){
            if(getPackageManager().getLaunchIntentForPackage(pkg)!=null){openPackage(pkg);return;}
        }
        // OEM radio activities are often not exported. Let the user choose an available app.
        openApplicationCatalog(true,1);
    }
    private void openDashboards() {
        dashboardOpen=true;
        safeStart(new Intent(this,CivicTheme.index(this)==4?
            CyberpunkActivity.class:DashboardActivity.class));
    }
    private void openMusicDashboard(){
        dashboardOpen=true;
        if(CivicTheme.index(this)==4){
            safeStart(new Intent(this,CyberpunkActivity.class));return;
        }
        Intent music=new Intent(this,DashboardActivity.class);
        music.putExtra("open_page",4);
        safeStart(music);
    }
    private void showGearDiagnostics(){
        long now=System.currentTimeMillis();
        boolean gpsFresh=lastGpsTime>0 && now-lastGpsTime<7000L && gpsKmh>=0;
        boolean gearFresh=estimatedGearAt>0 && now-estimatedGearAt<10000L &&
                obdConnected && estimatedGear>0 && estimatedGearConfidence>=55;
        String message="Marcha: "+(gearFresh?estimatedGear+"ª EST.":"indisponível")+"\n"
            +"Estado: "+estimatedGearReason+"\n"
            +"RPM: "+(rpm>=0?String.format(PT,"%.0f rpm",rpm):"sem leitura")+"\n"
            +"Velocidade OBD2: "+(obdSpeed>=0?obdSpeed+" km/h":"sem leitura")+"\n"
            +"Velocidade GPS: "+(gpsFresh?String.format(PT,"%.0f km/h",gpsKmh):"sem sinal recente")+"\n"
            +"Fonte usada: "+estimatedGearSource+"\n"
            +(gearFresh?"Confiança indicativa: "+estimatedGearConfidence+"%\n":"")
            +"\nA estimativa exige movimento (12 km/h ou mais) e RPM válido."
            +" Conversor de torque e troca de marcha podem impedir a identificação."
            +" Não representa P/R/N/D nem leitura direta do câmbio.";
        new AlertDialog.Builder(this).setTitle("Civic Drive · Marcha estimada")
            .setMessage(message).setPositiveButton("Entendi",null).show();
    }
    private final MusicReactive musicReactive=new MusicReactive();
    private volatile boolean foreground = false;
    private void showOrbDiagnostics(){
        String status="Cores dinâmicas: SEMPRE LIGADAS\n"
            +"RPM: "+(rpm>=0?String.format(PT,"%.0f rpm",rpm):"sem leitura")+"\n"
            +"Áudio: "+musicReactive.diagnostics(this)+"\n\n"
            +"Funcionamento automático. Se a central não compartilhar as "
            +"batidas, a esfera mantém seu movimento elétrico suave.";
        new AlertDialog.Builder(this)
            .setTitle("Civic Drive · Diagnóstico da esfera")
            .setMessage(status)
            .setPositiveButton("Fechar",null).show();
    }
    private void showThemes(){
        new AlertDialog.Builder(this).setTitle("Civic Drive · 5 temas")
            .setSingleChoiceItems(CivicTheme.NAMES,CivicTheme.index(this),(dialog,index)->{
                CivicTheme.select(this,index);
                if(surface!=null)surface.invalidate();
                Toast.makeText(this,"Tema "+CivicTheme.NAMES[index]+" aplicado",Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                if(index==4) {
                    dashboardOpen=true;
                    safeStart(new Intent(this,CyberpunkActivity.class));
                }
            }).setNegativeButton("Fechar",null).show();
    }

    /** Safe local diagnostic: no Bluetooth address, private token or ECU commands. */
    private void showObdDiagnostics(){
        String mode=obdConnected?"Conectado":obd!=null && obd.isActive()?"Conectando":"Desconectado";
        String last=obd==null?"Adaptador não iniciado":obd.diagnostics();
        String message="Estado: "+mode+"\n"
            +"Último evento: "+last+"\n"
            +"Reconexão automática: "+(autoObdEnabled?"ativada":"desativada")+"\n"
            +"RPM: "+(rpm>=0?String.format(PT,"%.0f",rpm):"sem leitura")+"\n"
            +"Temperatura: "+(coolant>-100?coolant+" °C":"sem leitura")+"\n\n"
            +"Com a ignição ligada, confira se o ELM327 está pareado no Bluetooth. "
            +"Se o carro estiver desligado, a ECU pode não responder.";
        new AlertDialog.Builder(this).setTitle("Civic Drive · Diagnóstico OBD2")
            .setMessage(message)
            .setNeutralButton("SELECIONAR ADAPTADOR",(d,w)->chooseObdDevice())
            .setPositiveButton("Fechar",null).show();
    }

    private void showMenu() {
        final String[] entries={"Aplicativos da central (ícones)","Adicionar atalho na home","Dashboards","Ler falhas do carro","Histórico local do Civic","Nova Total","Trocar adaptador OBD2","Mapas","Waze","Música","YouTube e som","Bluetooth","Atualizar pelo GitHub","Ajustes","Temas","GPS / CarPlay","Diagnóstico GPS"};
        new AlertDialog.Builder(this).setTitle("Civic G8 · Menu")
                .setItems(entries,(d,w)->{
                    switch(w){
                        case 0:openApplicationCatalog(false,-1);break;
                        case 1:chooseShortcutSlot();break;
                        case 2:openDashboards();break;
                        case 3:safeStart(new Intent(this,FaultCodesActivity.class));break;
                        case 4:safeStart(new Intent(this,CarHistoryActivity.class));break;
                        case 5:safeStart(new Intent(this,NovaActivity.class));break;
                        case 6:chooseObdDevice();break;
                        case 7:openPackage("com.google.android.apps.maps");break;
                        case 8:openWaze();break;
                        case 9:openMusic();break;
                        case 10:showYoutubeSettings();break;
                        case 11:safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));break;
                        case 12:updates.checkForUpdates();break;
                        case 14:showThemes();break;
                        case 15:CivicNavigation.showChooser(this);break;
                        case 16:CivicNavigation.showGpsDiagnostics(this);break;
                        default:showSettings();
                    }
                }).setNegativeButton("Fechar",null).show();
    }
    private void chooseShortcutSlot(){
        new AlertDialog.Builder(this).setTitle("Escolha o atalho da tela inicial")
                .setItems(new String[]{"Atalho 1","Atalho 2","Atalho 3"},
                        (d,i)->openApplicationCatalog(true,i))
                .setNeutralButton("Limpar atalhos",(d,w)->new AlertDialog.Builder(this)
                        .setTitle("Remover os atalhos da home?")
                        .setNegativeButton("Cancelar",null)
                        .setPositiveButton("Remover",(a,b)->{getSharedPreferences(HOME_PREFS,MODE_PRIVATE).edit().clear().apply();surface.invalidate();})
                        .show())
                .setNegativeButton("Cancelar",null).show();
    }
    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("Civic G8 · Ajustes")
            .setItems(new String[]{"TEMAS · 5 CORES", "DIAGNÓSTICO OBD2", "Trocar ELM327", "Atualizar GitHub", "Configurar Nova", "Reiniciar média de consumo", "Conexão OBD2 automática", "Atalhos da home", "Plano de fundo", "Definir tela inicial", "Configurações Android", "Permitir GPS", "Sobre", "Diagnóstico da esfera", "GPS / CarPlay", "Diagnóstico GPS"},
                (d,which)->{
                    if(which==0){showThemes();return;}
                    if(which==1){showObdDiagnostics();return;}
                    if(which==13){showOrbDiagnostics();return;}
                    if(which==14){CivicNavigation.showChooser(this);return;}
                    if(which==15){CivicNavigation.showGpsDiagnostics(this);return;}
                    switch(which-2){
                        case 0:chooseObdDevice();break;
                        case 1:updates.checkForUpdates();break;
                        case 2:safeStart(new Intent(this,NovaActivity.class));break;
                        case 3:new AlertDialog.Builder(this).setTitle("Zerar consumo da viagem?")
                            .setMessage("Isso zera só o cálculo estimado, não os dados do carro.")
                            .setNegativeButton("Cancelar",null).setPositiveButton("Zerar",(a,b)->resetTrip()).show();break;
                        case 4:toggleAutomaticObd();break;
                        case 5:chooseShortcutSlot();break;
                        case 6:pickWallpaper();break;
                        case 7:safeStart(new Intent(Settings.ACTION_HOME_SETTINGS));break;
                        case 8:safeStart(new Intent(Settings.ACTION_SETTINGS));break;
                        case 9:requestGps();break;
                        default:new AlertDialog.Builder(this).setTitle("Civic G8 2.0.10")
                            .setMessage("Versão independente para Civic 2011. O GPS mede a velocidade; temperatura e RPM dependem de um ELM327 clássico. Consumo é estimativa com PID 015E ou MAF (0110), quando suportados. Sem leitura, exibimos —. O painel original do carro continua sendo a referência para dirigir.")
                            .setPositiveButton("OK",null).show();
                    }
                }).setNegativeButton("Fechar",null).show();
    }
    private void showInstalledApps() { openApplicationCatalog(false,-1); }
    private void openPackage(String name) {
        Intent i=getPackageManager().getLaunchIntentForPackage(name);
        if(i!=null)safeStart(i);else Toast.makeText(this,"App não instalado: "+name,Toast.LENGTH_LONG).show();
    }
    private void openWaze(){
        CivicNavigation.openPreferred(this);
    }
    private void openYouTube(){
        openAppOrWebsite("com.google.android.youtube","https://www.youtube.com/");
    }
    private void openAppOrWebsite(String packageName,String website){
        Intent launch=getPackageManager().getLaunchIntentForPackage(packageName);
        if(launch!=null){safeStart(launch);return;}
        // Algumas centrais não têm Google Play Services nem YouTube instalado.
        // Não embutir player WebView que perderia áudio ao sair da tela.
        try{
            Intent browser=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(website));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(browser);
        }catch(Exception ex){
            Toast.makeText(this,"Instale ou habilite o app nesta central",Toast.LENGTH_LONG).show();
        }
    }
    private void appBatterySettings(String packageName){
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:"+packageName)));
        }catch(Exception e){safeStart(new Intent(Settings.ACTION_SETTINGS));}
    }
    private void showYoutubeSettings(){
        final String music="com.google.android.apps.youtube.music";
        final String video="com.google.android.youtube";
        new AlertDialog.Builder(this).setTitle("YouTube e áudio da central")
            .setMessage("O iPhone toca pelo canal Bluetooth/CarPlay, mas o YouTube instalado "
                +"na central usa o áudio do Android. São rotas diferentes. "
                +"Se não sair som, teste primeiro a saída do Android com o carro parado. "
                +"O áudio em segundo plano do YouTube depende das regras do serviço e do Premium.")
            .setItems(new String[]{
                "Abrir YouTube",
                "Abrir YouTube Music",
                "Diagnóstico de som do Android",
                "Volume e ajustes de som",
                "Bateria / permissões do YouTube",
                "Bateria / permissões do YouTube Music",
                "Configurações Bluetooth"
            },(d,i)->{
                switch(i){
                    case 0:openYouTube();break;
                    case 1:openAppOrWebsite(music,"https://music.youtube.com/");break;
                    case 2:showAudioDiagnostics();break;
                    case 3:safeStart(new Intent(Settings.ACTION_SOUND_SETTINGS));break;
                    case 4:appBatterySettings(video);break;
                    case 5:appBatterySettings(music);break;
                    case 6:safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));break;
                }
            }).setNegativeButton("Fechar",null).show();
    }
    /** Teste de saída do Android, NÃO é um player de YouTube ou de som do iPhone. */
    private void showAudioDiagnostics(){
        android.media.AudioManager audio=(android.media.AudioManager)getSystemService(Context.AUDIO_SERVICE);
        int current=0,max=0;
        if(audio!=null){
            current=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            max=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        }
        StringBuilder outputs=new StringBuilder();
        if(audio!=null && Build.VERSION.SDK_INT>=23){
            try {
                for(android.media.AudioDeviceInfo device:audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)){
                    if(outputs.length()>0)outputs.append(", ");
                    switch(device.getType()){
                        case android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:outputs.append("alto-falante Android");break;
                        case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:outputs.append("Bluetooth A2DP");break;
                        case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:outputs.append("fones");break;
                        case android.media.AudioDeviceInfo.TYPE_AUX_LINE:outputs.append("AUX");break;
                        case android.media.AudioDeviceInfo.TYPE_USB_DEVICE:outputs.append("áudio USB");break;
                        default:outputs.append("saída ").append(device.getType());break;
                    }
                }
            }catch(Exception ignored){outputs.append("não identificada");}
        }
        new AlertDialog.Builder(this).setTitle("Áudio nativo da multimídia")
            .setMessage("Volume de mídia do Android: "+current+"/"+max+".\n"
                +"Saídas detectadas: "+(outputs.length()==0?"não identificadas":outputs.toString())
                +".\n\nO som do iPhone via Bluetooth/CarPlay não comprova que a fonte Android "
                +"esteja selecionada na central. Ajuste a fonte MEDIA/ANDROID/APP "
                +"e o volume de mídia da central.\n\n"
                +"Toque em TESTAR SOM para tocar um aviso bem curto e baixo. "
                +"Faça o teste com o carro parado.")
            .setPositiveButton("TESTAR SOM",(d,i)->playAndroidAudioTest())
            .setNeutralButton("AJUSTAR SOM",(d,i)->safeStart(new Intent(Settings.ACTION_SOUND_SETTINGS)))
            .setNegativeButton("Fechar",null).show();
    }
    private void playAndroidAudioTest(){
        // Som PCM de 0,35s, 660Hz, volume baixo e fade, somente quando solicitado.
        // Não força volume, não contorna proteções do YouTube e não toma controle do BT.
        android.media.AudioTrack track=null;
        try {
            final int sampleRate=22050,samples=7717;
            short[] sample=new short[samples];
            for(int i=0;i<samples;i++){
                double envelope=Math.min(1d,Math.min(i/(sampleRate*.03d),(samples-i-1)/(sampleRate*.06d)));
                sample[i]=(short)(Math.sin(2d*Math.PI*660d*i/sampleRate)*2200d*Math.max(0d,envelope));
            }
            track=new android.media.AudioTrack.Builder()
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples*2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).build();
            if(track.getState()!=android.media.AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("Áudio Android indisponível");
            if(track.write(sample,0,sample.length)!=sample.length)throw new IllegalStateException("Não foi possível enviar som");
            track.play();
            final android.media.AudioTrack output=track;
            handler.postDelayed(()->{try {output.stop();}catch(Exception ignored){}output.release();},750L);
            Toast.makeText(this,"Teste enviado à saída de mídia do Android",Toast.LENGTH_LONG).show();
        }catch(Exception e){
            if(track!=null){try{track.release();}catch(Exception ignored){}}
            Toast.makeText(this,"Áudio do Android indisponível: confira volume e fonte",Toast.LENGTH_LONG).show();
        }
    }
    private void openMusic() {
        openMusicDashboard();
    }
    private void safeStart(Intent intent) {
        try{startActivity(intent);}catch(ActivityNotFoundException|SecurityException e){Toast.makeText(this,"Não disponível nesta central",Toast.LENGTH_LONG).show();}
    }
    private boolean gpsAllowed() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                ==PackageManager.PERMISSION_GRANTED ||
               checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                ==PackageManager.PERMISSION_GRANTED;
    }
    private boolean fineGpsAllowed(){
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                ==PackageManager.PERMISSION_GRANTED;
    }
    private void requestGps(){
        if(!gpsAllowed() || !fineGpsAllowed()){
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION},REQUEST_GPS);
        }else startGps();
    }
    @SuppressWarnings("MissingPermission") private void startGps(){
        if(!gpsAllowed()||gps==null)return;
        boolean listening=false;
        try {
            if(fineGpsAllowed()&&gps.isProviderEnabled(LocationManager.GPS_PROVIDER)){
                gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this);
                listening=true;
            }
        }catch(Exception ignored){ }
        // Many Android head units have no fix/antenna: a network-based position
        // is informative but must not be mislabeled as satellite GPS.
        try {
            if(gps.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                gps.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2500,0,this);
                listening=true;
            }
        }catch(Exception ignored){ }
        gpsStatus=listening?"Localização aguardando sinal":"Localização indisponível";
        if(surface!=null)surface.invalidate();
    }
    @Override public void onLocationChanged(Location location){
        if(location==null)return;
        final long now=System.currentTimeMillis();
        final boolean satellite=LocationManager.GPS_PROVIDER.equals(location.getProvider());
        if(location.hasSpeed()){
            gpsKmh=Math.max(0f,location.getSpeed()*3.6f);
            lastGpsTime=now;
        } else if(now-lastGpsTime>8500L) {
            gpsKmh=-1f;
        }
        gpsStatus=(satellite?"GPS satélite":"Localização por rede")
            +(location.hasAccuracy()?" · ~"+Math.round(location.getAccuracy())+" m":"")
            +(location.hasSpeed()?"":" · sem velocidade");
        saveTelemetry();if(surface!=null)surface.invalidate();
    }
    @Override public void onProviderDisabled(String provider){
        if(LocationManager.GPS_PROVIDER.equals(provider)){
            gpsStatus="GPS Android desativado";
        }
        if(surface!=null)surface.invalidate();
    }
    @Override public void onProviderEnabled(String provider){
        if(LocationManager.GPS_PROVIDER.equals(provider) ||
            LocationManager.NETWORK_PROVIDER.equals(provider))startGps();
    }
    private boolean btAllowed(){return Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    @SuppressWarnings("MissingPermission") private void chooseObdDevice(){
        if(!btAllowed()) {requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},REQUEST_BLUETOOTH);return;}
        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){Toast.makeText(this,"Sem Bluetooth nesta central",Toast.LENGTH_LONG).show();return;}
        try{
            if(!adapter.isEnabled()) {safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));return;}
            Set<BluetoothDevice> paired=adapter.getBondedDevices();
            List<BluetoothDevice> list=new ArrayList<>(
                paired==null?Collections.<BluetoothDevice>emptySet():paired);
            Collections.sort(list,Comparator.comparing(d->d.getName()==null?"":d.getName()));
            if(list.isEmpty()){safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));Toast.makeText(this,"Pareie o ELM327 antes",Toast.LENGTH_LONG).show();return;}
            String[] labels=new String[list.size()];for(int i=0;i<list.size();i++)labels[i]=(list.get(i).getName()==null?"Dispositivo":list.get(i).getName())+" · "+list.get(i).getAddress();
            new AlertDialog.Builder(this).setTitle("ELM327 · Selecione o adaptador").setItems(labels,(d,i)->{
                BluetoothDevice selected = list.get(i);
                getSharedPreferences(OBD_PREFS,MODE_PRIVATE).edit().putString("device",selected.getAddress())
                        .putBoolean("auto",true).apply();
                autoObdEnabled = true;
                nextObdRetryMs = System.currentTimeMillis() + 15000L;
                obd.connect(selected);
            }).setNegativeButton("Cancelar",null).show();
        }catch(SecurityException e){Toast.makeText(this,"Autorize o Bluetooth",Toast.LENGTH_LONG).show();}
    }
    private void toggleAutomaticObd() {
        autoObdEnabled = !autoObdEnabled;
        getSharedPreferences(OBD_PREFS, MODE_PRIVATE).edit().putBoolean("auto",autoObdEnabled).apply();
        if (!autoObdEnabled) {
            obd.close();
            obdConnected=false;
            obdStatus="Desconectado";
            surface.invalidate();
        } else {
            nextObdRetryMs = 0L;
            tryAutoObd();
        }
        Toast.makeText(this,"OBD2 automático: " + (autoObdEnabled?"ativado":"desativado"),Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("MissingPermission") private void tryAutoObd() {
        if (isShuttingDown || !autoObdEnabled || obd == null || obdConnected || obd.isActive() ||
                System.currentTimeMillis() < nextObdRetryMs || !btAllowed()) return;
        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if (adapter == null) return;
        try {
            if (!adapter.isEnabled()) return;
            SharedPreferences p=getSharedPreferences(OBD_PREFS,MODE_PRIVATE);
            String mac=p.getString("device","");
            BluetoothDevice selected=null;
            Set<BluetoothDevice> devices=adapter.getBondedDevices();
            if (devices == null || devices.isEmpty()) return;
            if (mac != null && !mac.isEmpty()) {
                for (BluetoothDevice d:devices) {
                    if (mac.equalsIgnoreCase(d.getAddress())) { selected=d;break; }
                }
            }
            // On first use, only auto-select if a single OBD-related paired device exists.
            if (selected == null && (mac == null || mac.isEmpty())) {
                for (BluetoothDevice d:devices) {
                    String name=d.getName();
                    if (name == null) continue;
                    String upper=name.toUpperCase(Locale.ROOT);
                    if (!(upper.contains("ELM") || upper.contains("OBD") || upper.contains("V-LINK")
                            || upper.contains("VGATE") || upper.contains("OBDII"))) continue;
                    if (selected != null) { selected=null;break; } // Ambiguous: ask user to choose manually.
                    selected=d;
                }
                if (selected != null) p.edit().putString("device",selected.getAddress()).apply();
            }
            if (selected == null) return;
            nextObdRetryMs = System.currentTimeMillis() + 15000L;
            obd.connect(selected);
        } catch (SecurityException | IllegalArgumentException ex) {
            nextObdRetryMs = System.currentTimeMillis() + 30000L;
        }
    }

    @Override public void onRequestPermissionsResult(
            int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==REQUEST_GPS&&gpsAllowed())startGps();
        if(code==REQUEST_BLUETOOTH&&btAllowed())tryAutoObd();
    }
    @Override protected void onResume(){
        super.onResume();
        foreground=true;
        dashboardOpen=false;
        musicReactive.start(this);
        if(surface!=null)surface.resumeAnimation();
        if(gpsAllowed())startGps();
        if(updates!=null){updates.onResume();updates.checkForUpdatesIfDue();}
        if(Build.VERSION.SDK_INT>=31&&!btAllowed()){
            SharedPreferences settings=getSharedPreferences(OBD_PREFS,MODE_PRIVATE);
            BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
            if(manager!=null&&manager.getAdapter()!=null &&
                !settings.getBoolean("bt_permission_asked",false)){
                settings.edit().putBoolean("bt_permission_asked",true).apply();
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH);
            }
        }else tryAutoObd();

        // No audio permission or system-wide Visualizer is used.
    }

    @Override protected void onPause(){
        foreground=false;
        musicReactive.stop();
        if(surface!=null)surface.pauseAnimation();
        handler.removeCallbacks(telemetryFlush);
        telemetryFlushPending=false;
        writeTelemetry();
        if(gps!=null&&!dashboardOpen)gps.removeUpdates(this);
        if(updates!=null)updates.onPause();
        super.onPause();
    }
    @Override protected void onDestroy(){
        isShuttingDown=true;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(telemetryFlush);
        if(gps!=null)gps.removeUpdates(this);
        if(obd!=null)obd.close();
        super.onDestroy();
    }
}
