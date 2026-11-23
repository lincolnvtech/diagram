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
    /** Clean, single-theme cockpit. All drawing is procedural, no bitmap backdrops. */
    private final class Ui extends View {
        private static final float W=1648f,H=928f;
        private static final int BLACK=0xff020407;
        private static final int NEON=0xff10d7ff;
        private static final int BLUE_NEON=0xff136ff4;
        private static final int WHITE=0xfff1f8ff;
        private static final int MUTED=0xff90abc2;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        private final CivicOrbRenderer orb=new CivicOrbRenderer(MainActivity.this);
        private final CivicMediaControls media=new CivicMediaControls(MainActivity.this);
        private boolean running=true;
        private float downX,downY;
        private long touchedAt;
        private final String[] dock={"DASH","GARAGEM","GPS","MÚSICA","APPS","NOVA"};
        Ui(){
            super(MainActivity.this);
            setContentDescription("Civic Drive: tema preto e azul neon; OBD2, música e garagem.");
        }
        void resumeAnimation(){running=true;invalidate();}
        void pauseAnimation(){running=false;}
        void useWallpaper(int ignored){invalidate();}

        private int a(int color,int alpha){
            return Color.argb(Math.max(0,Math.min(255,alpha)),
                Color.red(color),Color.green(color),Color.blue(color));
        }
        private void paint(int color){
            p.reset();p.setAntiAlias(true);p.setColor(color);
            p.setStyle(Paint.Style.FILL);
        }
        private void line(Canvas c,float x1,float y1,float x2,float y2,int color,float width){
            paint(color);p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(x1,y1,x2,y2,p);p.setStyle(Paint.Style.FILL);
        }
        private void text(Canvas c,String value,float x,float y,float size,int color,
                          boolean bold,boolean center){
            paint(color);
            p.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",
                bold?Typeface.BOLD:Typeface.NORMAL));
            p.setTextSize(size);
            if(center)x-=p.measureText(value)*.5f;
            c.drawText(value,x,y,p);
        }
        private void round(Canvas c,float x,float y,float w,float h,int radius,int fill,
                           int outline){
            bounds.set(x,y,x+w,y+h);
            paint(fill);c.drawRoundRect(bounds,radius,radius,p);
            if(Color.alpha(outline)>0){
                paint(outline);p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.7f);c.drawRoundRect(bounds,radius,radius,p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        private void backdrop(Canvas c){
            c.drawColor(BLACK);
            paint(WHITE);
            p.setShader(new android.graphics.RadialGradient(824,325,730,
                new int[]{0xff092743,0xff030b17,BLACK},
                new float[]{0f,.51f,1f},
                android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0,0,W,H,p);p.setShader(null);
            line(c,64,102,1584,102,a(NEON,110),1.6f);
            line(c,68,736,1580,736,a(BLUE_NEON,110),1.7f);
        }
        private void header(Canvas c){
            text(c,"CIVIC",90,55,40,WHITE,true,false);
            text(c,"DRIVE",218,55,40,NEON,true,false);
            text(c,"G8  ·  2011    /    PRETO + AZUL NEON",92,83,18,MUTED,false,false);
            String current=new java.text.SimpleDateFormat("HH:mm",PT).format(new java.util.Date());
            text(c,current,1395,58,44,WHITE,true,true);
            text(c,"MENU",1527,84,19,NEON,true,true);
            line(c,1574,27,1606,27,NEON,3);
            line(c,1574,39,1606,39,NEON,3);
            line(c,1574,51,1606,51,NEON,3);
        }
        private void gauge(Canvas c,float cx,float cy,float radius,String label,
                           String value,String suffix,float ratio,boolean available){
            paint(WHITE);
            p.setShader(new android.graphics.RadialGradient(cx,cy,radius,
                new int[]{0xff0c2135,0xff040b15,BLACK},null,
                android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx,cy,radius,p);p.setShader(null);
            bounds.set(cx-radius+12,cy-radius+12,cx+radius-12,cy+radius-12);
            paint(a(BLUE_NEON,110));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.2f);
            c.drawCircle(cx,cy,radius-8,p);
            p.setStrokeWidth(9.5f);p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(a(NEON,44));c.drawArc(bounds,146,248,false,p);
            if(available){
                p.setColor(NEON);c.drawArc(bounds,146,248*Math.min(1f,Math.max(0,ratio)),false,p);
            }
            p.setStyle(Paint.Style.FILL);
            for(int i=0;i<=30;i++){
                double theta=Math.toRadians(146+i*(248d/30d));
                float inner=radius-(i%5==0?35:28),outer=radius-20;
                line(c,cx+inner*(float)Math.cos(theta),cy+inner*(float)Math.sin(theta),
                    cx+outer*(float)Math.cos(theta),cy+outer*(float)Math.sin(theta),
                    a(i%5==0?WHITE:NEON,i%5==0?185:90),i%5==0?2.3f:1.4f);
            }
            text(c,label,cx,cy-35,23,MUTED,true,true);
            text(c,value,cx,cy+38,available?71:63,WHITE,true,true);
            text(c,suffix,cx,cy+86,25,available?NEON:MUTED,false,true);
        }
        private String rpmText(){
            return !obdConnected||rpm<0?"—":String.format(PT,"%.1f",rpm/1000d);
        }
        private String tempText(){return !obdConnected||coolant<-100?"—":String.valueOf(coolant);}
        private void musicButton(Canvas c,float cx,float cy,int kind){
            paint(0xff07182a);c.drawCircle(cx,cy,36,p);
            paint(a(NEON,170));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.6f);
            c.drawCircle(cx,cy,35,p);p.setStyle(Paint.Style.FILL);
            if(kind==0){
                line(c,cx-13,cy-12,cx-13,cy+12,WHITE,3);
                text(c,"◀",cx+7,cy+9,28,WHITE,true,true);
            }else if(kind==2){
                text(c,"▶",cx-7,cy+9,28,WHITE,true,true);
                line(c,cx+13,cy-12,cx+13,cy+12,WHITE,3);
            }else{
                text(c,media.isMusicActive()?"Ⅱ":"▶",cx,cy+11,32,WHITE,true,true);
            }
        }
        private void status(Canvas c){
            round(c,85,570,1478,143,14,a(0xff07182a,220),a(NEON,75));
            line(c,546,586,546,699,a(BLUE_NEON,150),1.6f);
            line(c,900,586,900,699,a(BLUE_NEON,150),1.6f);
            text(c,"CONEXÃO OBD2",117,609,22,MUTED,true,false);
            text(c,obdConnected?"CONECTADO":obdStatus.toUpperCase(PT),
                 117,650,34,obdConnected?NEON:WHITE,true,false);
            text(c,"Toque para diagnóstico",117,686,19,MUTED,false,false);
            text(c,"MARCHA ESTIMADA",580,609,22,MUTED,true,false);
            boolean valid=obdConnected&&estimatedGear>0 &&
                System.currentTimeMillis()-estimatedGearAt<10000L;
            text(c,valid?estimatedGear+"ª  EST.":"—  SEM LEITURA",580,657,32,
                 valid?NEON:WHITE,true,false);
            text(c,"Não é posição PRND",580,690,19,MUTED,false,false);
            text(c,"MÚSICA   ·   ANDROID",938,604,21,MUTED,true,false);
            musicButton(c,1195,654,0);
            musicButton(c,1320,654,1);
            musicButton(c,1445,654,2);
            text(c,"ANTERIOR",1195,706,16,MUTED,false,true);
            text(c,"TOCAR/PAUSAR",1320,706,16,MUTED,false,true);
            text(c,"PRÓXIMA",1445,706,16,MUTED,false,true);
        }
        private void dock(Canvas c){
            for(int i=0;i<dock.length;i++){
                float cx=(i+.5f)*W/6f;
                round(c,cx-122,765,244,122,14,0xff06101c,
                    i==0?a(NEON,155):a(BLUE_NEON,86));
                text(c,new String[]{"◴","▱","◎","♫","▦","✧"}[i],
                     cx,815,39,NEON,false,true);
                text(c,dock[i],cx,861,24,WHITE,true,true);
                if(i==0)line(c,cx-64,887,cx+64,887,NEON,3.4f);
            }
        }
        @Override protected void onDraw(Canvas actual){
            super.onDraw(actual);
            actual.save();actual.scale(getWidth()/W,getHeight()/H);
            backdrop(actual);header(actual);
            gauge(actual,305,327,169,"ARREFECIMENTO",tempText(),"°C",
                (coolant-40f)/80f,obdConnected&&coolant>-100);
            gauge(actual,1342,327,169,"ROTAÇÃO",rpmText(),"×1000 RPM",
                (float)rpm/8000f,obdConnected&&rpm>=0);
            float energy=musicReactive.frame(MainActivity.this,
                android.os.SystemClock.uptimeMillis());
            float speed=obdConnected&&obdSpeed>=0?obdSpeed:
                gpsKmh>=0&&System.currentTimeMillis()-lastGpsTime<9000L?gpsKmh:-1f;
            orb.draw(actual,823,331,energy,(float)rpm,speed,obdConnected&&rpm>=0);
            text(actual,"NOVA",824,545,28,NEON,true,true);
            status(actual);dock(actual);
            actual.restore();
            if(running&&getWindowVisibility()==View.VISIBLE)
                postInvalidateDelayed(145L); // approx. 7 fps, lightweight
        }
        private void mediaCommand(int keyCode){
            boolean active=media.isMusicActive();
            boolean sent=media.send(keyCode);
            Toast.makeText(MainActivity.this,
                sent&&active?"Comando enviado ao player Android":
                    "Sem player Android ativo. CarPlay: use o CPLink.",
                Toast.LENGTH_SHORT).show();
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            float x=event.getX()*W/getWidth(),y=event.getY()*H/getHeight();
            if(event.getAction()==MotionEvent.ACTION_DOWN){
                downX=x;downY=y;touchedAt=android.os.SystemClock.uptimeMillis();return true;
            }
            if(event.getAction()!=MotionEvent.ACTION_UP)return true;
            if(Math.abs(x-downX)>40||Math.abs(y-downY)>40)return true;
            performClick();
            if(y<109&&x>1430){showMenu();return true;}
            if(y>770){
                int slot=Math.max(0,Math.min(5,(int)(x/(W/6f))));
                if(slot==0)openDashboards();
                else if(slot==1)openGarage();
                else if(slot==2)CivicNavigation.showChooser(MainActivity.this);
                else if(slot==3)openMusicDashboard();
                else if(slot==4)showInstalledApps();
                else safeStart(new Intent(MainActivity.this,NovaActivity.class));
                return true;
            }
            if(y>=570&&y<=730){
                if(x<540)showObdDiagnostics();
                else if(x<902)showGearDiagnostics();
                else if(x>=1140&&x<1253)mediaCommand(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                else if(x>=1253&&x<1390)mediaCommand(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                else if(x>=1390&&x<1510)mediaCommand(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
                else openMusicDashboard();
                return true;
            }
            if(y>125&&y<550){
                if(x>610&&x<1030)safeStart(new Intent(MainActivity.this,NovaActivity.class));
                else showObdDiagnostics();
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
