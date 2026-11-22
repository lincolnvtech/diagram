package br.com.civicdrive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.text.InputType;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Civic Drive 1.9.4: four owner-approved immersive instruments:
 * Velocímetro, Contagiros, Sensores, Autonomia.
 * Artwork is decorative only; EVERY numerical reading comes from SharedPreferences
 * populated by MainActivity's GPS/ELM327, or from user-entered tank litres.
 */
public final class DashboardActivity extends Activity {
    private static final Locale PT = new Locale("pt","BR");
    private static final float W=1648f,H=928f;
    private static int BG=Color.rgb(2,9,17), CYAN=Color.rgb(24,211,244),
            BLUE=Color.rgb(22,128,207), WHITE=Color.rgb(238,245,252),
            MUTED=Color.rgb(151,177,198), OUTLINE=Color.rgb(26,89,127);
    private static final int WARN=Color.rgb(246,178,69);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private DashView surface;
    private final MusicReactive musicReactive=new MusicReactive();
    private CivicMediaControls player;
    private boolean androidMusicActive=false;
    private int page=0;
    private final Runnable update=new Runnable(){
        @Override public void run(){
            if(page==4){
                androidMusicActive=player!=null&&player.isMusicActive();
                if(musicReactive.permitted(DashboardActivity.this))
                    musicReactive.start(DashboardActivity.this);
            }
            if(surface!=null)surface.invalidate();
            handler.postDelayed(this,page==4?850L:1100L);
        }
    };

    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        page=Math.min(4,Math.max(0,getIntent().getIntExtra("open_page",0)));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        player=new CivicMediaControls(this);
        surface=new DashView();setContentView(surface);
    }
    @Override protected void onResume(){
        super.onResume();applyTheme();
        if(page==4 && musicReactive.permitted(this))musicReactive.start(this);
        if(surface!=null)surface.invalidate();
        handler.removeCallbacks(update);handler.post(update);
    }
    @Override protected void onPause(){
        musicReactive.stop();handler.removeCallbacks(update);super.onPause();
    }

    private void selectPage(int selected){
        page=Math.max(0,Math.min(4,selected));
        if(surface!=null){
            surface.setLayerType(page==4?View.LAYER_TYPE_NONE:
                View.LAYER_TYPE_SOFTWARE,null);
            surface.invalidate();
        }
    }

    private void playerCommand(int code){
        boolean sent=player!=null&&player.send(code);
        Toast.makeText(this,sent?"Comando enviado ao player Android":
            "Player Android indisponível",Toast.LENGTH_SHORT).show();
        if(surface!=null)surface.invalidate();
    }

    private void musicSources(){
        new AlertDialog.Builder(this).setTitle("Civic Drive · Fonte da música")
            .setItems(new String[]{"Spotify Android","YouTube Music Android",
                "Rádio da central","iPhone pelo CPLink / CarPlay"},
                (dialog,which)->{
                    if(which==0)openNativePlayer("com.spotify.music");
                    else if(which==1)openNativePlayer("com.google.android.apps.youtube.music");
                    else if(which==2)openNativeRadio();
                    else CivicNavigation.openCarPlay(this);
                }).setNegativeButton("Voltar",null).show();
    }
    private void openNativePlayer(String packageName){
        try{
            Intent i=getPackageManager().getLaunchIntentForPackage(packageName);
            if(i!=null)startActivity(i);
            else Toast.makeText(this,"Player não instalado no Android da central",
                    Toast.LENGTH_LONG).show();
        }catch(Exception e){
            Toast.makeText(this,"Não foi possível abrir o player",Toast.LENGTH_LONG).show();
        }
    }
    private void openNativeRadio(){
        for(String pkg:new String[]{"com.android.fmradio","com.microntek.radio",
                "com.syu.radio","com.android.radio"}){
            if(getPackageManager().getLaunchIntentForPackage(pkg)!=null){
                openNativePlayer(pkg);return;
            }
        }
        startActivity(new Intent(this,AppCatalogActivity.class));
    }

    /** Applying a theme never changes the data sources of the instrument cluster. */
    private void applyTheme(){
        int theme=CivicTheme.index(this);
        BG=CivicTheme.color(theme,0);
        CYAN=CivicTheme.color(theme,1);
        BLUE=CivicTheme.color(theme,2);
        WHITE=CivicTheme.color(theme,3);
        MUTED=CivicTheme.color(theme,4);
        OUTLINE=CivicTheme.color(theme,5);
    }

    private void showThemePicker(){
        new AlertDialog.Builder(this).setTitle("Civic Drive · Temas")
            .setSingleChoiceItems(CivicTheme.NAMES,CivicTheme.index(this),(dialog,choice)->{
                CivicTheme.select(this,choice);
                applyTheme();
                if(surface!=null)surface.invalidate();
                dialog.dismiss();
                if(choice==4){
                    startActivity(new Intent(this,CyberpunkActivity.class));
                    finish();
                }
            })
            .setNegativeButton("Fechar",null).show();
    }

    private final class Data {
        final boolean online, fresh, gpsValid;
        final int gearEst,gearConf;
        final float rpm,ect,load,map,iat,voltage,stft,speed,trip,avg,rate,fuelLitres;
        Data(){
            SharedPreferences p=getSharedPreferences("civic_telemetry",MODE_PRIVATE);
            fresh=System.currentTimeMillis()-p.getLong("lastUpdate",0L)<18000;
            online=fresh && p.getBoolean("connected",false);
            rpm=online?p.getFloat("rpm",-1f):-1f;
            int coolant=p.getInt("temp",-999);
            ect=online && coolant>-100?coolant:-999;
            load=online?p.getFloat("engineLoad",-1f):-1f;
            map=online?p.getFloat("mapKpa",-1f):-1f;
            iat=online?p.getFloat("intakeTemp",-999f):-999f;
            voltage=online?p.getFloat("volts",-1f):-1f;
            stft=online?p.getFloat("stft",-999f):-999f;
            float gps=p.getFloat("gps",-1f);
            int obdSpeed=p.getInt("obdSpeed",-1);
            long gpsAt=p.getLong("gpsAt",0L);
            gpsValid=gpsAt>0L&&System.currentTimeMillis()-gpsAt<9000L&&gps>=0;
            speed=online&&obdSpeed>=0?obdSpeed:(gpsValid?gps:-1f);
            trip=p.getFloat("tripKm",-1f);
            avg=p.getFloat("kmL",-1f);
            rate=online?p.getFloat("fuelRate",-1f):-1f;
            fuelLitres=getSharedPreferences("civic_fuel_manual",MODE_PRIVATE).getFloat("litres",-1f);
            boolean gearFresh=System.currentTimeMillis()-p.getLong("gearAt",0L)<13000L;
            gearEst=online&&gearFresh?p.getInt("gearEst",0):0;
            gearConf=gearEst>0?p.getInt("gearConf",0):0;
        }
        float range(){return fuelLitres>=0&&avg>0?fuelLitres*avg:-1f;}
    }

    private final class DashView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        // Large gauge art is only needed on the car instrument pages,
        // not on the frequently refreshed music display.
        private Bitmap engine, frontTech;
        private final Bitmap honda=BitmapFactory.decodeResource(getResources(),R.drawable.civic_dash_honda);
        private final CivicOrbRenderer musicOrb=new CivicOrbRenderer(DashboardActivity.this);
        private final float[] tabs={163f,490f,817f,1145f,1479f};
        private float downX,downY;private long downMs;
        private Data d;
        private long dataLoadedAt;
        DashView(){
            super(DashboardActivity.this);
            setLayerType(page==4?View.LAYER_TYPE_NONE:View.LAYER_TYPE_SOFTWARE,null);
        }
        private void ensureGaugeArt(){
            if(engine==null)engine=BitmapFactory.decodeResource(
                getResources(),R.drawable.civic_dash_engine);
            if(frontTech==null)frontTech=BitmapFactory.decodeResource(
                getResources(),R.drawable.civic_dash_front_tech);
        }
        private int a(int rgb,int alpha){return Color.argb(alpha,Color.red(rgb),Color.green(rgb),Color.blue(rgb));}
        private void set(int color){p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);p.setColor(color);}
        private void fill(Canvas c,float x,float y,float w,float h,float radius,int col){set(col);c.drawRoundRect(new RectF(x,y,x+w,y+h),radius,radius,p);}
        private void stroke(Canvas c,float x,float y,float w,float h,float radius,int col,float sw){set(col);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);c.drawRoundRect(new RectF(x,y,x+w,y+h),radius,radius,p);p.setStyle(Paint.Style.FILL);}
        private void line(Canvas c,float x,float y,float xx,float yy,int color,float width){set(color);p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x,y,xx,yy,p);}
        private void label(Canvas c,String value,float x,float y,float size,int col,boolean bold,boolean center){
            set(col);p.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
            p.setTextSize(size);
            if(center)x-=p.measureText(value)/2f;
            c.drawText(value,x,y,p);
        }
        private String reading(float v,int decimals,String suffix){
            if(v<=-900f || Float.isNaN(v)||Float.isInfinite(v)||v<0f && !suffix.equals("%") && !suffix.equals("°C"))return "—";
            return String.format(PT,"%."+decimals+"f",v).replace('.',',')+(suffix.isEmpty()?"":" "+suffix);
        }
        private String rpmTxt(float v){return v<0?"—":String.format(PT,"%.1f",v/1000f).replace('.',',');}
        private void background(Canvas c){
            set(BG);c.drawColor(BG);
            // Unlike older builds, the 4 color themes also control the entire dash.
            p.setShader(new LinearGradient(0,0,0,929,
                new int[]{CivicTheme.blend(BG,BLUE,.23f),BG,CivicTheme.blend(BG,Color.BLACK,.45f)},
                null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,W,H,p);p.setShader(null);
            set(Color.WHITE);p.setShader(new RadialGradient(824,535,760,
                new int[]{a(BLUE,34),a(BG,6),Color.TRANSPARENT},null,Shader.TileMode.CLAMP));
            c.drawCircle(824,535,760,p);p.setShader(null);
            line(c,0,98,1648,98,a(CYAN,105),1.5f);
            line(c,0,760,1648,760,a(BLUE,120),1.5f);
            line(c,102,902,1536,902,a(BLUE,95),1.3f);
        }
        private void header(Canvas c){
            // Honda identity is rendered as text, not a duplicated system status bar.
            if(honda!=null){set(Color.WHITE);c.drawBitmap(honda,null,new RectF(35,7,115,92),p);}
            line(c,102,22,102,76,a(CYAN,90),2);
            label(c,"CIVIC",127,51,38,WHITE,true,false);
            label(c,"DRIVE",258,51,38,CYAN,true,false);
            label(c,"G8  •  2011",128,77,20,MUTED,false,false);
            label(c,new SimpleDateFormat("HH:mm",PT).format(new Date()),1493,46,46,WHITE,true,true);
            label(c,new SimpleDateFormat("EEE, dd/MM/yyyy",PT).format(new Date()),1493,77,20,MUTED,false,true);
            line(c,1590,28,1617,28,MUTED,3);line(c,1590,42,1617,42,MUTED,3);line(c,1590,56,1617,56,MUTED,3);
        }
        private void tabs(Canvas c){
            final String[] names={"Velocímetro","Contagiros","Sensores","Autonomia","Música"};
            for(int i=0;i<names.length;i++){
                float x=tabs[i];
                label(c,names[i],x,149,27,i==page?CYAN:WHITE,i==page,true);
                if(i<names.length-1)line(c,(tabs[i]+tabs[i+1])/2f,113,
                        (tabs[i]+tabs[i+1])/2f,157,a(OUTLINE,150),2);
                if(i==page)line(c,x-110,170,x+110,170,CYAN,4);
            }
            line(c,50,176,1598,176,a(BLUE,105),1);
        }
        private void backdropCard(Canvas c,float x,float y,float w,float h){
            set(Color.WHITE);
            p.setShader(new LinearGradient(x,y,x+w,y+h,
                new int[]{a(CivicTheme.blend(BG,BLUE,.20f),193),a(BG,245)},
                null,Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(x,y,x+w,y+h),23,23,p);p.setShader(null);
            stroke(c,x,y,w,h,23,a(CYAN,125),1.3f);
            line(c,x+20,y+5,x+w*.37f,y+5,a(CYAN,155),2);
        }
        private void bottom(Canvas c){
            final String[] dock={"Início","Dash","Waze","Música","Apps","Fundo","Nova"};
            final String[] icons={"⌂","◴","◉","♫","▦","▧","∿"};
            for(int i=0;i<7;i++){
                float x=134+i*228;
                label(c,icons[i],x,840,58,i==1?CYAN:WHITE,false,true);
                label(c,dock[i],x,875,26,i==1?CYAN:MUTED,i==1,true);
            }
            line(c,270,901,455,901,CYAN,4);
        }
        private void musicControl(Canvas c,float cx,float cy,float radius,boolean primary){
            set(primary?a(BLUE,145):a(BG,230));
            c.drawCircle(cx,cy,radius,p);
            set(a(CYAN,primary?211:128));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(primary?3.8f:2f);
            c.drawCircle(cx,cy,radius-2f,p);
            p.setStyle(Paint.Style.FILL);
        }
        private void musicPage(Canvas c){
            backdropCard(c,54f,192f,1540f,556f);
            int theme=CivicTheme.index(DashboardActivity.this);
            float strength=musicReactive.frame(DashboardActivity.this,
                android.os.SystemClock.uptimeMillis());
            float rpm=d!=null?d.rpm:-1f;
            float vehicleSpeed=d!=null?d.speed:-1f;
            musicOrb.draw(c,824f,437f,strength,rpm,vehicleSpeed,rpm>=0f);

            label(c,"CIVIC AUDIO",146f,262f,29,CYAN,true,false);
            label(c,"CENTRAL DE MÚSICA",146f,312f,34,WHITE,true,false);
            line(c,146f,334f,433f,334f,a(CYAN,160),2);
            label(c,androidMusicActive?"ÁUDIO ANDROID EM REPRODUÇÃO":
                    "AGUARDANDO PLAYER ANDROID",146f,392f,22,
                    androidMusicActive?CYAN:MUTED,true,false);
            label(c,"Controles compatíveis com players",146f,435f,20,MUTED,false,false);
            label(c,"do Android: Spotify, YouTube Music.",146f,464f,20,MUTED,false,false);
            label(c,"Som do iPhone via CarPlay:",146f,534f,20,WHITE,false,false);
            label(c,"controle pelo CPLink.",146f,563f,20,MUTED,false,false);
            label(c,"RPM: "+(d!=null&&d.rpm>=0f?Math.round(d.rpm)+" rpm":"—"),
                146f,614f,21,CYAN,true,false);
            label(c,"VELOCIDADE: "+(d!=null&&d.speed>=0f?
                Math.round(d.speed)+" km/h":"—"),
                146f,651f,21,CYAN,true,false);

            // Native Android control keys: send to the active player,
            // without falsely claiming the source accepted the command.
            musicControl(c,638f,665f,52f,false);
            musicControl(c,824f,665f,68f,true);
            musicControl(c,1010f,665f,52f,false);
            set(WHITE);p.setStrokeWidth(7f);p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(619,649,619,681,p);
            android.graphics.Path previous=new android.graphics.Path();
            previous.moveTo(654,648);previous.lineTo(627,665);
            previous.lineTo(654,682);previous.close();c.drawPath(previous,p);
            if(androidMusicActive){
                c.drawRoundRect(new RectF(800,643,813,688),3,3,p);
                c.drawRoundRect(new RectF(835,643,848,688),3,3,p);
            }else{
                android.graphics.Path play=new android.graphics.Path();
                play.moveTo(805,640);play.lineTo(854,665);
                play.lineTo(805,690);play.close();c.drawPath(play,p);
            }
            android.graphics.Path next=new android.graphics.Path();
            next.moveTo(990,648);next.lineTo(1017,665);
            next.lineTo(990,682);next.close();c.drawPath(next,p);
            c.drawLine(1029,649,1029,681,p);
            label(c,"ANTERIOR",638f,738f,19,MUTED,false,true);
            label(c,androidMusicActive?"PAUSAR":"TOCAR",824f,753f,21,CYAN,true,true);
            label(c,"PRÓXIMA",1010f,738f,19,MUTED,false,true);

            // Side actions remain separate from the playback buttons.
            backdropCard(c,1190,317,352,100);
            label(c,"ABRIR REPRODUTOR",1366f,358f,25,WHITE,true,true);
            label(c,"Spotify / Rádio / YouTube",1366f,394f,19,MUTED,false,true);
            backdropCard(c,1190,440,352,94);
            label(c,"iPHONE / CARPLAY",1366f,480f,24,CYAN,true,true);
            label(c,"Abrir CPLink",1366f,511f,19,MUTED,false,true);
            backdropCard(c,1190,557,352,85);
            label(c,"GPS / WAZE",1366f,609f,24,WHITE,true,true);
        }
        /** Instrument with premium dark glass, crisp ticks, restrained cyan progress arc. */
        private void gauge(Canvas c,float cx,float cy,float radius,float min,float max,float value,
                           String name,String unit,int decimals,boolean extraLarge,boolean warning){
            final float start=150f,sweep=240f;
            int color=warning?WARN:CYAN;
            set(BG);c.drawCircle(cx,cy,radius,p);
            set(Color.WHITE);p.setShader(new RadialGradient(cx,cy,radius,
                new int[]{CivicTheme.blend(BG,BLUE,.25f),BG,CivicTheme.blend(BG,Color.BLACK,.50f)},
                null,Shader.TileMode.CLAMP));
            c.drawCircle(cx,cy,radius,p);p.setShader(null);
            set(a(BLUE,90));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawCircle(cx,cy,radius-2,p);
            p.setStrokeWidth(6);p.setColor(a(CYAN,115));c.drawArc(new RectF(cx-radius+11,cy-radius+11,cx+radius-11,cy+radius-11),120,70,false,p);
            p.setStrokeWidth(6);p.setColor(a(CYAN,28));c.drawArc(new RectF(cx-radius+20,cy-radius+20,cx+radius-20,cy+radius-20),start,sweep,false,p);
            for(int i=0;i<=32;i++){
                float t=i/32f;
                double angle=Math.toRadians(start+sweep*t);
                float inner=radius-(i%4==0?41:30),outer=radius-16;
                float sn=(float)Math.sin(angle),cs=(float)Math.cos(angle);
                line(c,cx+inner*cs,cy+inner*sn,cx+outer*cs,cy+outer*sn,a(WHITE,i%4==0?210:95),i%4==0?2.9f:1.3f);
            }
            boolean available=value>-900 && (value>=0 || name.equals("IAT") || name.equals("STFT")) && !Float.isNaN(value);
            // Guia visual: marcações de referência. MAP/IAT/carga dependem do uso do motor;
            // ausência de limite confiável = somente medidor, sem faixa de defeito.
            float band1=-1,band2=-1;
            if(name.equals("ARREFECIMENTO")){band1=102;band2=110;}
            else if(name.equals("RPM")){band1=3500;band2=6000;}
            else if(name.equals("TENSÃO")||name.equals("BATERIA")){band1=14.9f;band2=15.4f;}
            if(band1>=0){
                RectF ring=new RectF(cx-radius+12,cy-radius+12,cx+radius-12,cy+radius-12);
                float t1=Math.max(0,Math.min(1,(band1-min)/(max-min)));
                float t2=Math.max(t1,Math.min(1,(band2-min)/(max-min)));
                set(CYAN);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3.2f);
                p.setColor(a(CYAN,140));c.drawArc(ring,start,sweep*t1,false,p);
                p.setColor(a(WARN,155));c.drawArc(ring,start+sweep*t1,sweep*(t2-t1),false,p);
                p.setColor(a(WARN,220));c.drawArc(ring,start+sweep*t2,sweep*(1-t2),false,p);
                p.setStyle(Paint.Style.FILL);
            }
            if(available){
                float f=Math.max(0,Math.min(1,(value-min)/(max-min)));
                set(color);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(extraLarge?12:7.5f);
                c.drawArc(new RectF(cx-radius+20,cy-radius+20,cx+radius-20,cy+radius-20),start,sweep*f,false,p);
                p.setStyle(Paint.Style.FILL);
            }
            label(c,name,cx,cy-(extraLarge?60:36),extraLarge?30:24,MUTED,false,true);
            String number=!available?"—":name.equals("ROTAÇÃO")?
                    String.format(PT,"%.1f",value/1000f).replace('.',','):
                    String.format(PT,"%."+decimals+"f",value).replace('.',',');
            label(c,number,cx,cy+(extraLarge?52:38),extraLarge?126:64,WHITE,true,true);
            label(c,unit,cx,cy+(extraLarge?101:72),extraLarge?27:22,!available?MUTED:CYAN,false,true);
        }
        /** Non-interactive concept artwork obeys the selected palette. */
        private void drawThemedArt(Canvas c,Bitmap art,RectF bounds,int opacity){
            if(art==null)return;
            set(Color.WHITE);
            p.setAlpha(opacity);
            p.setColorFilter(CivicTheme.artFilter(CivicTheme.index(DashboardActivity.this)));
            c.drawBitmap(art,null,bounds,p);
            p.setColorFilter(null);
            p.setAlpha(255);
        }
        private void smallCard(Canvas c,float x,float y,float w,float h,String title,String value,String info){
            backdropCard(c,x,y,w,h);
            label(c,title,x+31,y+44,23,MUTED,false,false);
            label(c,value,x+31,y+95,45,WHITE,true,false);
            if(info!=null&&!info.isEmpty())label(c,info,x+31,y+h-19,17,a(CYAN,190),false,false);
        }
        /** Premium HUD visual language, modeled on the two owner-approved full-tech previews.
         * Only pixels of a decorative vehicle are sampled from the concept art; the d.*
         * values, gauge needles, status labels, warning colors and time are all live.
         */
        private void glowArc(Canvas c,RectF bounds,float start,float sweep,int col,float width){
            if(sweep<=0)return;
            set(col);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(width*2.7f);p.setColor(a(col,38));
            c.drawArc(bounds,start,sweep,false,p);
            p.setStrokeWidth(width*1.55f);p.setColor(a(col,88));
            c.drawArc(bounds,start,sweep,false,p);
            p.setStrokeWidth(width);p.setColor(a(col,236));
            c.drawArc(bounds,start,sweep,false,p);
            p.setStyle(Paint.Style.FILL);p.clearShadowLayer();
        }
        private void hudField(Canvas c,float cx,float cy,float radius){
            set(Color.WHITE);p.setShader(new RadialGradient(cx,cy,radius*1.5f,
                 new int[]{a(CYAN,28),a(BLUE,14),Color.TRANSPARENT},
                 null,Shader.TileMode.CLAMP));
            c.drawCircle(cx,cy,radius*1.5f,p);p.setShader(null);
            // Minimal floor grid, the visual reference without its example readings.
            for(int i=-5;i<=5;i++)line(c,cx+i*50,687,cx+i*14,565,a(CYAN,20),1f);
            for(int y=580;y<=692;y+=28)line(c,cx-260+(y-580)*.25f,y,cx+260-(y-580)*.25f,y,a(CYAN,20),1f);
        }
        /** Concentric brushed-metal rims and a small reflective bevel, not a bitmap. */
        private void machinedBezel(Canvas c,float cx,float cy,float r){
            set(Color.WHITE);p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.6f);p.setColor(a(WHITE,115));
            c.drawCircle(cx,cy,r-4,p);
            p.setStrokeWidth(1.5f);p.setColor(a(CYAN,215));
            c.drawCircle(cx,cy,r-11,p);
            p.setStrokeWidth(8f);p.setColor(a(BLUE,62));
            c.drawCircle(cx,cy,r-20,p);
            p.setStyle(Paint.Style.FILL);
            line(c,cx-r*.39f,cy-r*.91f,cx+r*.32f,cy-r*.95f,a(WHITE,65),1.5f);
        }
        private void heroDial(Canvas c,float cx,float cy,float r,float raw,float maximum,
                              String title,String units,boolean tach){
            final boolean valid=raw>=0f && !Float.isNaN(raw) && !Float.isInfinite(raw);
            final float value=valid?Math.max(0,Math.min(maximum,raw)):0;
            final int highlight=valid && (tach?raw>=5000:raw>=120)?WARN:CYAN;
            // Transparent graphite glass dial, delicate mechanical graduations.
            set(Color.WHITE);p.setShader(new RadialGradient(cx,cy,r,
                 new int[]{a(BLUE,36),BG,CivicTheme.blend(BG,Color.BLACK,.45f)},
                 null,Shader.TileMode.CLAMP));
            c.drawCircle(cx,cy,r,p);p.setShader(null);
            machinedBezel(c,cx,cy,r);
            set(a(BLUE,80));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);
            c.drawCircle(cx,cy,r-3,p);c.drawCircle(cx,cy,r-15,p);c.drawCircle(cx,cy,r-40,p);
            p.setStyle(Paint.Style.FILL);
            RectF arc=new RectF(cx-r+20,cy-r+20,cx+r-20,cy+r-20);
            float start=150f,sweep=240f;
            set(a(CYAN,35));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(11);
            c.drawArc(arc,start,sweep,false,p);p.setStyle(Paint.Style.FILL);
            if(valid)glowArc(c,arc,start,sweep*value/maximum,highlight,7f);
            // Conta-giros: zonas indicativas sem confundir giro alto com falha.
            if(tach){
                RectF bands=new RectF(cx-r+11,cy-r+11,cx+r-11,cy+r-11);
                float normal=3500/maximum, advisory=6000/maximum;
                set(CYAN);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4f);
                p.setColor(a(CYAN,145));c.drawArc(bands,start,sweep*normal,false,p);
                p.setColor(a(BLUE,210));c.drawArc(bands,start+sweep*normal,sweep*(advisory-normal),false,p);
                p.setColor(a(WARN,220));c.drawArc(bands,start+sweep*advisory,sweep*(1-advisory),false,p);
                p.setStyle(Paint.Style.FILL);
            }
            for(int i=0;i<=44;i++){
                float theta=(float)Math.toRadians(start+240f*i/44f);
                float cA=(float)Math.cos(theta),sA=(float)Math.sin(theta);
                float ro=r-26,ri=r-(i%4==0?67: i%2==0?52:41);
                line(c,cx+ri*cA,cy+ri*sA,cx+ro*cA,cy+ro*sA,
                     a(i%4==0?WHITE:CYAN,i%4==0?220:102),i%4==0?2.8f:1.25f);
            }
            // Fixed scale marks are instrument graduations, not vehicle measurements.
            final int count=tach?8:11;
            for(int j=0;j<=count;j++){
                float theta=(float)Math.toRadians(start+240f*j/count);
                float rad=r-94;
                float n=tach?j:j*20;
                label(c,String.format(PT,"%.0f",n),cx+rad*(float)Math.cos(theta),
                    cy+rad*(float)Math.sin(theta)+10f,tach?29:24,WHITE,j%2==0,true);
            }
            // Angular sweep synchronized to *real* GPS/OBD data.
            // Use a short perimeter marker to avoid obscuring the large digital number.
            if(valid){
                float theta=(float)Math.toRadians(start+sweep*value/maximum);
                float cc=(float)Math.cos(theta),ss=(float)Math.sin(theta);
                line(c,cx+(r-127)*cc,cy+(r-127)*ss,
                     cx+(r-38)*cc,cy+(r-38)*ss,a(highlight,80),13f);
                line(c,cx+(r-127)*cc,cy+(r-127)*ss,
                     cx+(r-38)*cc,cy+(r-38)*ss,highlight,4.8f);
                set(WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2f);
                c.drawCircle(cx+(r-39)*cc,cy+(r-39)*ss,5f,p);
                p.setStyle(Paint.Style.FILL);
            }
            label(c,title,cx,cy-43,23,MUTED,false,true);
            String number=valid?(tach?String.format(PT,"%.1f",raw/1000f):
                                      String.format(PT,"%.0f",raw)):"—";
            label(c,number,cx,cy+62,tach?118:132,WHITE,true,true);
            label(c,units,cx,cy+105,28,valid?CYAN:MUTED,false,true);
        }
        private void sideDial(Canvas c,float cx,float cy,float r,float v,float min,float max,
                              String title,String unit,int decimals,boolean warning){
            gauge(c,cx,cy,r,min,max,v,title,unit,decimals,false,warning);
        }
        private void statusBarFullTech(Canvas c,String caption,String left,String right){
            float x=413,y=690,w=819,h=65;
            backdropCard(c,x,y,w,h);
            label(c,caption,x+32,y+37,21,MUTED,false,false);
            label(c,left,x+295,y+43,28,d.online?CYAN:MUTED,true,false);
            line(c,x+493,y+12,x+493,y+53,a(CYAN,90),1.3f);
            label(c,right,x+518,y+43,21,MUTED,false,false);
        }
        private void gearChip(Canvas c,float x,float y){
            backdropCard(c,x,y,225,109);
            label(c,"MARCHA EST.",x+21,y+31,21,MUTED,false,false);
            label(c,d.gearEst>0?d.gearEst+"ª":"—",x+21,y+85,52,d.gearEst>0?CYAN:WHITE,true,false);
            label(c,"PRND: não lido",x+94,y+83,15,MUTED,false,false);
        }
        private void speedPage(Canvas c){
            // Main 220km/h HUD dial, GPS card, two ancillary instruments.
            gearChip(c,1170,184);
            hudField(c,822,436,285);
            heroDial(c,820,454,282,d.speed,220,"VELOCIDADE", "km/h",false);
            drawThemedArt(c,frontTech,new RectF(631,575,1011,711),186);
            backdropCard(c,60,235,371,128);
            label(c,"VELOCIDADE GPS",92,279,25,MUTED,false,false);
            label(c,d.gpsValid?reading(d.speed,0,"km/h"):"— km/h",92,330,49,WHITE,true,false);
            label(c,d.gpsValid?"SINAL DISPONÍVEL":"AGUARDANDO GPS",92,349,16,CYAN,false,false);
            sideDial(c,244,549,145,d.ect,40,120,"ARREFECIMENTO","°C",0,d.ect>=105);
            sideDial(c,1409,369,143,d.rpm,0,8000,"RPM","rpm",0,d.rpm>=5000);
            sideDial(c,1409,626,123,d.voltage,10,16,"BATERIA","V",1,d.online&&d.voltage>0&&(d.voltage<12||d.voltage>15.2));
            statusBarFullTech(c,"OBD2",d.online?"Conectado":"Desconectado", "DADOS REAIS · ELM327");
        }
        private void rpmPage(Canvas c){
            // Performance tachometer with large dial and 4 true ECU sensor readings.
            gearChip(c,1170,184);
            hudField(c,820,436,290);
            heroDial(c,820,444,284,d.rpm,8000,"ROTAÇÃO", "×1000 RPM",true);
            drawThemedArt(c,engine,new RectF(650,562,990,695),172);
            sideDial(c,242,348,141,d.ect,40,120,"ARREFECIMENTO","°C",0,d.ect>=105);
            sideDial(c,242,623,125,d.map,0,120,"MAP","kPa",0,false);
            sideDial(c,1410,347,141,d.iat,-20,90,"IAT","°C",0,false);
            sideDial(c,1410,624,125,d.load,0,100,"CARGA MOTOR","%",0,false);
            statusBarFullTech(c,"TENSÃO",reading(d.voltage,1,"V"),d.online?"MOTOR AO VIVO":"AGUARDANDO ECU");
        }
        private void sensorsPage(Canvas c){
            // Decorative engine art from the approved concept, never a source of data.
            drawThemedArt(c,engine,new RectF(548,210,1099,466),255);
            gauge(c,269,305,146,40,120,d.ect,"ARREFECIMENTO","°C",0,false,d.ect>=105);
            gauge(c,1376,305,146,0,100,d.load,"CARGA MOTOR","%",0,false,false);
            gauge(c,199,605,114,0,110,d.map,"MAP","kPa",0,false,false);
            gauge(c,606,605,114,-20,90,d.iat,"IAT","°C",0,false,false);
            gauge(c,1031,605,114,10,16,d.voltage,"TENSÃO","V",1,false,d.rpm>600&&(d.voltage<12||d.voltage>15.2));
            gauge(c,1439,605,114,-35,35,d.stft,"STFT","%",1,false,d.stft>-900&&Math.abs(d.stft)>25);
        }
        private void autonomyPage(Canvas c){
            float range=d.range();
            gauge(c,824,423,245,0,600,range,"AUTONOMIA ESTIMADA","km",0,true,false);
            // No bitmap over the dial: some head units render the artwork as a white rectangle.
            // Show explicit status rather than a blank white box.
            String autonomyStatus=d.fuelLitres<0f?"INFORME LITROS PARA AUTONOMIA":
                d.avg<=0f?"AGUARDANDO CONSUMO DO MOTOR":"AUTONOMIA APROXIMADA";
            label(c,autonomyStatus,824,626,21,MUTED,false,true);
            smallCard(c,54,305,380,144,"CONSUMO MÉDIO",reading(d.avg,1,"km/L"),
                d.avg>=0f?"Média estimada do percurso":"Aguardando dados do motor");
            // At speed km/h divided by L/h gives an instantaneous km/L approximation.
            // At idle show L/h instead; do not divide by zero or invent ECU values.
            String instant=d.rate>0.05f && d.speed>3f
                ?reading(d.speed/d.rate,1,"km/L"):reading(d.rate,2,"L/h");
            smallCard(c,54,493,380,144,"CONSUMO INSTANTÂNEO",instant,
                d.rate>=0f?"Estimativa pela ECU":"Sem vazão 015E / MAF");
            smallCard(c,1220,305,372,144,"TRIP LOCAL",reading(d.trip,1,"km"),"Não é hodômetro");
            smallCard(c,1220,493,372,144,"COMBUSTÍVEL INFORMADO",reading(d.fuelLitres,1,"L"),"Toque para alterar");
            label(c,"Autonomia depende do consumo estimado e do combustível inserido manualmente.",824,736,20,MUTED,false,true);
        }
        private void help(String subject){
            String desc;
            switch(subject){
                case "RPM":desc="Rotação do motor em voltas por minuto. Azul até 3.500 RPM, faixa de giro elevado acima disso e âmbar a partir de 6.000. São referências visuais, não limite oficial de falha.";break;
                case "ARREFECIMENTO":desc="Temperatura do líquido de arrefecimento (ECT). Motor frio abaixo de ~70 °C; normalmente aquecido em torno de 80–102 °C. Atenção acima de 103 °C, alerta elevado a partir de 110 °C. Referência orientativa, não especificação de alarme Honda. Se houver superaquecimento, pare em segurança.";break;
                case "TENSÃO":case "BATERIA":desc="Tensão elétrica medida pelo adaptador ELM327. Com o motor funcionando, aproximadamente 13–15 V costuma indicar carga, mas o alternador varia a tensão. Valores fora da faixa repetidamente precisam de verificação.";break;
                case "MAP":desc="Pressão absoluta do coletor de admissão em kPa. Varia com altitude e aceleração: alto não significa defeito sozinho. Compare com marcha lenta e carga.";break;
                case "IAT":desc="Temperatura do ar admitido. Muda com clima, trânsito e calor do motor; não há uma faixa verde universal.";break;
                case "CARGA MOTOR":desc="Carga calculada pela ECU em %. Aumenta ao acelerar ou subir ladeiras; 100% não significa falha por si só.";break;
                case "STFT":desc="Correção rápida de combustível. Perto de zero em regime estável é comum; valores persistentes acima de ±20% merecem diagnóstico, não indicação de falha isolada.";break;
                case "VELOCIDADE":desc="Velocidade fornecida por GPS ou ELM327. As faixas legais mudam conforme a via. Use o painel original como referência.";break;
                case "MARCHA":desc="A marcha é apenas uma estimativa local feita com RPM, velocidade e relações oficiais da caixa automática Honda 2011. O conversor de torque pode patinar e as borboletas mudam o giro. P/R/N/D não estão sendo medidos; exibimos — se não for confiável.";break;
                case "AUTONOMIA":desc="Autonomia aproximada = consumo médio estimado × litros restantes informados manualmente. O Civic Drive não mede o nível real do tanque.";break;
                default:desc="Os números dependem da leitura do ELM327. Marcadores azuis e âmbar são orientativos, não equivalem ao diagnóstico de uma oficina.";
            }
            new AlertDialog.Builder(DashboardActivity.this).setTitle(subject+" · Como interpretar")
                .setMessage(desc+"\n\nNão consulte enquanto dirige.")
                .setPositiveButton("Entendi",null).show();
        }
        private void showFuelDialog(){
            EditText input=new EditText(DashboardActivity.this);
            input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setHint("Litros disponíveis no tanque");
            float existing=getSharedPreferences("civic_fuel_manual",MODE_PRIVATE).getFloat("litres",-1);
            if(existing>=0)input.setText(String.format(PT,"%.1f",existing));
            new AlertDialog.Builder(DashboardActivity.this).setTitle("Combustível informado manualmente")
                .setMessage("Informe os litros estimados que restam no tanque. A ECU pode não informar o nível, e a autonomia continuará aproximada.")
                .setView(input).setNegativeButton("Cancelar",null)
                .setNeutralButton("Limpar",(dialog,which)->{getSharedPreferences("civic_fuel_manual",MODE_PRIVATE).edit().remove("litres").apply();invalidate();})
                .setPositiveButton("Salvar",(dialog,which)->{
                    try {float v=Float.parseFloat(input.getText().toString().replace(',','.'));
                        if(v<0||v>100){Toast.makeText(DashboardActivity.this,"Informe um valor entre 0 e 100 litros",Toast.LENGTH_LONG).show();return;}
                        getSharedPreferences("civic_fuel_manual",MODE_PRIVATE).edit().putFloat("litres",v).apply();invalidate();
                    }catch(Exception ex){Toast.makeText(DashboardActivity.this,"Quantidade inválida",Toast.LENGTH_LONG).show();}
                }).show();
        }
        private void launch(String pkg){try{Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i!=null)startActivity(i);else Toast.makeText(DashboardActivity.this,"Não disponível nesta central",Toast.LENGTH_LONG).show();}catch(Exception ex){Toast.makeText(DashboardActivity.this,"Aplicativo indisponível",Toast.LENGTH_LONG).show();}}
        private void dockClick(float x){
            int which=Math.max(0,Math.min(6,Math.round((x-134f)/228f)));
            switch(which){
                case 0:finish();break;
                case 1:break;
                case 2:CivicNavigation.openPreferred(DashboardActivity.this);break;
                case 3:selectPage(4);break;
                case 4:startActivity(new Intent(DashboardActivity.this,AppCatalogActivity.class));break;
                case 5:finish();break; // background selector remains in the main launcher
                case 6:startActivity(new Intent(DashboardActivity.this,NovaActivity.class));break;
            }
        }
        @Override protected void onDraw(Canvas real){
            super.onDraw(real);
            long uptime=android.os.SystemClock.uptimeMillis();
            if(d==null||uptime-dataLoadedAt>1150L){
                d=new Data();dataLoadedAt=uptime;
            }
            if(page!=4)ensureGaugeArt();
            real.save();real.scale(getWidth()/W,getHeight()/H);
            background(real);header(real);tabs(real);
            if(page==0)speedPage(real);
            else if(page==1)rpmPage(real);
            else if(page==2)sensorsPage(real);
            else if(page==3)autonomyPage(real);
            else musicPage(real);
            bottom(real);real.restore();
            if(page==4 && getWindowVisibility()==View.VISIBLE)
                postInvalidateDelayed(125L); // ~8 fps, reduce load on ELM327 head units
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX()*W/getWidth(),y=e.getY()*H/getHeight();
            if(e.getAction()==MotionEvent.ACTION_DOWN){downX=x;downY=y;downMs=android.os.SystemClock.uptimeMillis();return true;}
            if(e.getAction()!=MotionEvent.ACTION_UP)return true;
            if(Math.abs(x-downX)>48||Math.abs(y-downY)>48)return true;
            performClick();
            if(y>=103&&y<185){selectPage((int)(x/(W/5f)));return true;}
            if(y>780){dockClick(x);return true;}
            if(y<101&&x>1550){new AlertDialog.Builder(DashboardActivity.this).setTitle("Civic G8")
                .setItems(new String[]{"Ler falhas","Histórico","Informar combustível",
                    "Temas","Música","GPS / CarPlay","Voltar à home"},(dialog,idx)->{
                    if(idx==0)startActivity(new Intent(DashboardActivity.this,FaultCodesActivity.class));
                    else if(idx==1)startActivity(new Intent(DashboardActivity.this,CarHistoryActivity.class));
                    else if(idx==2)showFuelDialog();
                    else if(idx==3)showThemePicker();
                    else if(idx==4)selectPage(4);
                    else if(idx==5)CivicNavigation.showChooser(DashboardActivity.this);
                    else finish();
                }).show();return true;}
            if(page==4){
                if(x>1180f&&y>=555f&&y<=646f){
                    CivicNavigation.showChooser(DashboardActivity.this);return true;
                }
                if(y>=595&&y<=760){
                    if(Math.abs(x-638)<87)playerCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    else if(Math.abs(x-824)<95)playerCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    else if(Math.abs(x-1010)<87)playerCommand(KeyEvent.KEYCODE_MEDIA_NEXT);
                    else if(x>1180)CivicNavigation.showChooser(DashboardActivity.this);
                    return true;
                }
                if(x>1180&&y>=310&&y<425){musicSources();return true;}
                if(x>1180&&y>=432&&y<550){CivicNavigation.openCarPlay(DashboardActivity.this);return true;}
                return true;
            }
            if((page==0||page==1) && x>1160&&x<1410&&y>=184&&y<293){help("MARCHA");return true;}
            if(page==0){
                if(x>530&&x<1140){help("VELOCIDADE");return true;}
                if(x<470){help("ARREFECIMENTO");return true;}
                if(x>1245){help(y<490?"RPM":"BATERIA");return true;}
            }
            if(page==1){
                if(x>530&&x<1140){help("RPM");return true;}
                if(x<480){help(y<490?"ARREFECIMENTO":"MAP");return true;}
                if(x>1220){help(y<490?"IAT":"CARGA MOTOR");return true;}
            }
            if(page==3 && x>1160&&y>455&&y<650){showFuelDialog();return true;}
            if(page==3 && x>520&&x<1140&&y>185&&y<680){showFuelDialog();return true;}
            if(page==2){
                if(y<455)help(x<490?"ARREFECIMENTO":x>1180?"CARGA MOTOR":"SENSORES");
                else help(x<405?"MAP":x<810?"IAT":x<1230?"TENSÃO":"STFT");
                return true;
            }
            if(page==3){help("AUTONOMIA");return true;}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
