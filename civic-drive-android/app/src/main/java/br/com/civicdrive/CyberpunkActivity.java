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
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Owner-requested CYBERPUNK theme. Mirrors the approved 1648x928 concept's
 * geometry: Honda/CIVIC DRIVE header, electric gauges, large central plasma,
 * left Civic/gear card, right Spotify transport, five instrument tiles,
 * RPM color legend and six bottom shortcuts.
 *
 * Every vehicle reading comes from the existing read-only telemetry cache.
 * Missing pressure/PRND/track metadata are NEVER manufactured from artwork.
 * Does not make an OBD connection or add a Bluetooth worker.
 */
public final class CyberpunkActivity extends Activity {
    private static final Locale PT=new Locale("pt","BR");
    private static final float W=1648f,H=928f;
    private static final int BG=0xff02060d,WHITE=0xfff3f8ff,
        CYAN=0xff20eaff,BLUE=0xff2a8bff,VIOLET=0xff974eff,
        PINK=0xffed48fa,MUTED=0xff94a9c3,GREEN=0xff09ed81;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final MusicReactive music=new MusicReactive();
    private CivicMediaControls player;
    private UpdateManager updates;
    private Screen screen;
    private Live live;
    private long lastReadAt;
    private boolean musicPlaying;
    private boolean resumed;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(!resumed)return;
            live=new Live();
            lastReadAt=SystemClock.uptimeMillis();
            musicPlaying=player!=null&&player.isMusicActive();
            if(screen!=null)screen.invalidate();
            handler.postDelayed(this,1000L);
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        player=new CivicMediaControls(this);
        updates=new UpdateManager(this);
        live=new Live();
        screen=new Screen();
        setContentView(screen);
    }

    @Override protected void onResume(){
        super.onResume();
        resumed=true;music.start(this);
        if(updates!=null)updates.onResume();
        handler.removeCallbacks(refresh);handler.post(refresh);
    }
    @Override protected void onPause(){
        resumed=false;handler.removeCallbacks(refresh);
        if(updates!=null)updates.onPause();
        music.stop();
        super.onPause();
    }

    private final class Live {
        final boolean online,gpsFresh;
        final float rpm,speed,voltage,kmL,fuelLeft;
        final int coolant,gear,gearConfidence;
        final long gearAt;
        Live(){
            SharedPreferences p=getSharedPreferences("civic_telemetry",MODE_PRIVATE);
            long now=System.currentTimeMillis();
            boolean recent=now-p.getLong("lastUpdate",0)<18000L;
            online=recent&&p.getBoolean("connected",false);
            rpm=online?p.getFloat("rpm",-1f):-1f;
            int sp=p.getInt("obdSpeed",-1);
            float gps=p.getFloat("gps",-1f);
            long gpsAt=p.getLong("gpsAt",0);
            gpsFresh=gpsAt>0&&now-gpsAt<9000L&&gps>=0;
            speed=online&&sp>=0?sp:(gpsFresh?gps:-1f);
            coolant=online?p.getInt("temp",-999):-999;
            voltage=online?p.getFloat("volts",-1):-1;
            kmL=p.getFloat("kmL",-1);
            fuelLeft=getSharedPreferences("civic_fuel_manual",MODE_PRIVATE)
                .getFloat("litres",-1f);
            gear=p.getInt("gearEst",0);
            gearConfidence=p.getInt("gearConf",0);
            gearAt=p.getLong("gearAt",0);
        }
        float autonomy(){return fuelLeft>=0&&kmL>0?fuelLeft*kmL:-1f;}
        String gearText(){
            return online&&rpm>=0&&gear>=1&&gear<=5&&gearConfidence>=55&&
                System.currentTimeMillis()-gearAt<13000L?gear+"ª":"—";
        }
    }

    private void musicCommand(int key){
        if(player==null||!player.send(key)){
            Toast.makeText(this,"Player Android indisponível",Toast.LENGTH_SHORT).show();
        }
        if(screen!=null)screen.invalidate();
    }
    private void musicSources(){
        new AlertDialog.Builder(this).setTitle("Cyberpunk · Música")
            .setItems(new String[]{"Spotify instalado na central","YouTube Music",
                "iPhone pelo CPLink / CarPlay","Controles de reprodução"},
                (d,which)->{
                    if(which==0)openApp("com.spotify.music");
                    else if(which==1)openApp("com.google.android.apps.youtube.music");
                    else if(which==2)CivicNavigation.openCarPlay(this);
                    else Toast.makeText(this,
                        "Use anterior, pausa e próxima no painel Spotify",
                        Toast.LENGTH_LONG).show();
                }).setNegativeButton("Fechar",null).show();
    }
    private void openApp(String pkg){
        Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
        if(i==null){Toast.makeText(this,"Aplicativo não instalado",Toast.LENGTH_LONG).show();return;}
        try{startActivity(i);}
        catch(Exception e){Toast.makeText(this,"Não foi possível abrir o aplicativo",Toast.LENGTH_LONG).show();}
    }
    private void openClassicDash(int page){
        Intent i=new Intent(this,DashboardActivity.class);
        i.putExtra("open_page",page);startActivity(i);
    }
    private void themes(){
        new AlertDialog.Builder(this).setTitle("Tema do Civic Drive")
            .setSingleChoiceItems(CivicTheme.NAMES,CivicTheme.index(this),(d,which)->{
                CivicTheme.select(this,which);
                d.dismiss();
                if(which==4){screen.invalidate();return;}
                Toast.makeText(this,"Tema "+CivicTheme.NAMES[which]+" aplicado",
                    Toast.LENGTH_SHORT).show();
                // MainActivity owns the original launcher and OBD2 session.
                finish();
            }).setNegativeButton("Cancelar",null).show();
    }
    private void settings(){
        new AlertDialog.Builder(this).setTitle("Cyberpunk · Ajustes")
            .setItems(new String[]{"Selecionar tema","Atualizar pelo GitHub",
                "GPS / CPLink / CarPlay","Diagnóstico GPS",
                "Dashboards clássicos","Configurações Android"},
                (d,which)->{
                    switch(which){
                        case 0:themes();break;
                        case 1:if(updates!=null)updates.checkForUpdates();break;
                        case 2:CivicNavigation.showChooser(this);break;
                        case 3:CivicNavigation.showGpsDiagnostics(this);break;
                        case 4:openClassicDash(0);break;
                        case 5:try{startActivity(new Intent(Settings.ACTION_SETTINGS));}
                            catch(Exception ignored){}break;
                    }
                }).setNegativeButton("Fechar",null).show();
    }
    private void choose(int slot){
        switch(slot){
            case 0:screen.invalidate();break;
            case 1:musicSources();break;
            case 2:openClassicDash(2);break;
            case 3:openClassicDash(3);break;
            case 4:CivicNavigation.openPreferred(this);break;
            case 5:settings();break;
        }
    }

    private final class Screen extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF tmp=new RectF();
        private final Path path=new Path();
        private final CivicOrbRenderer plasma=new CivicOrbRenderer(CyberpunkActivity.this);
        private final Bitmap honda=BitmapFactory.decodeResource(
            getResources(),R.drawable.civic_dash_honda);
        private final Bitmap civic=BitmapFactory.decodeResource(
            getResources(),R.drawable.civic_cinema);
        private float downX,downY;private long downMs;
        Screen(){super(CyberpunkActivity.this);}

        private int alpha(int color,int a){
            return Color.argb(Math.max(0,Math.min(255,a)),
                Color.red(color),Color.green(color),Color.blue(color));
        }
        private void color(int rgb){
            p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);p.setColor(rgb);
        }
        private void rect(Canvas c,float l,float t,float r,float b,float rad,int rgb){
            color(rgb);c.drawRoundRect(l,t,r,b,rad,rad,p);
        }
        private void text(Canvas c,String str,float x,float y,float size,
                          int rgb,boolean heavy,boolean centered){
            color(rgb);
            p.setTypeface(Typeface.create(heavy?"sans-serif-medium":"sans-serif",
                heavy?Typeface.BOLD:Typeface.NORMAL));
            p.setTextSize(size);
            if(centered)x-=p.measureText(str)*.5f;
            c.drawText(str,x,y,p);
        }
        private void line(Canvas c,float x,float y,float xx,float yy,int rgb,float width){
            color(rgb);p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(width);
            c.drawLine(x,y,xx,yy,p);
            p.setStyle(Paint.Style.FILL);
        }
        private void glass(Canvas c,float x,float y,float w,float h,float radius,int glow){
            color(Color.WHITE);
            p.setShader(new LinearGradient(x,y,x,y+h,
                new int[]{0xff102439,0xff061021,0xff030913},null,Shader.TileMode.CLAMP));
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);p.setShader(null);
            p.setColor(alpha(glow,142));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.9f);
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);
            p.setStyle(Paint.Style.FILL);
        }
        private void gradientPill(Canvas c,float x,float y,float w,float h,
                                   int left,int right){
            color(Color.WHITE);
            p.setShader(new LinearGradient(x,y,x+w,y,
                new int[]{left,right},null,Shader.TileMode.CLAMP));
            c.drawRoundRect(x,y,x+w,y+h,h*.5f,h*.5f,p);
            p.setShader(null);
        }
        private void header(Canvas c){
            color(Color.WHITE);
            p.setShader(new LinearGradient(0,0,0,75,
                0xff06182c,0xff010711,Shader.TileMode.CLAMP));
            c.drawRect(0,0,W,75,p);p.setShader(null);
            line(c,0,76,W,76,alpha(CYAN,165),2.3f);
            if(honda!=null){
                color(WHITE);
                c.drawBitmap(honda,null,new RectF(44,8,123,64),p);
            }
            text(c,"CIVIC",153,48,46,WHITE,true,false);
            text(c,"DRIVE",316,48,46,CYAN,true,false);
            rect(c,476,19,570,60,19,0xff17304e);
            text(c,"v2.0.9",523,47,22,WHITE,true,true);
            bars(c,629,40,CYAN,1f);
            text(c,"Spotify",713,47,31,GREEN,true,false);
            text(c,musicPlaying?"Reproduzindo":"Pausado / sem mídia",
                835,46,24,musicPlaying?WHITE:MUTED,false,false);
            text(c,"BT",1090,44,25,BLUE,true,true);
            text(c,live.online?"OBD2":"OBD2 —",1200,44,25,
                live.online?GREEN:MUTED,true,true);
            text(c,live.gpsFresh?"GPS":"GPS —",1307,44,25,
                live.gpsFresh?GREEN:MUTED,true,true);
            // Ambient temperature is not a supported vehicle sensor.
            text(c,new SimpleDateFormat("HH:mm",PT).format(new Date()),
                1576,47,40,WHITE,true,true);
        }
        private void bars(Canvas c,float x,float y,int rgb,float energy){
            for(int i=0;i<5;i++){
                float ht=(11f+((i*7)%25))*energy;
                rect(c,x+i*11f,y-ht,x+i*11f+6f,y,1,rgb);
            }
        }
        private void gauge(Canvas c,float cx,float cy,float r,float value,
                           int max,String label,String readout,String unit){
            final int colorRing=CYAN;
            color(Color.WHITE);
            p.setShader(new RadialGradient(cx,cy,r+21f,
                new int[]{0xff020713,0xff08182d,0xff020711},
                new float[]{0f,.70f,1f},Shader.TileMode.CLAMP));
            c.drawCircle(cx,cy,r+21f,p);p.setShader(null);
            tmp.set(cx-r,cy-r,cx+r,cy+r);
            color(alpha(BLUE,33));p.setStrokeWidth(30);p.setStyle(Paint.Style.STROKE);
            c.drawArc(tmp,138,264,false,p);
            p.setColor(alpha(CYAN,105));p.setStrokeWidth(13);
            c.drawArc(tmp,138,264,false,p);
            float ratio=value>=0?Math.max(0f,Math.min(1f,value/max)):0f;
            p.setColor(CYAN);p.setStrokeWidth(12);
            c.drawArc(tmp,138,264*ratio,false,p);
            tmp.inset(13,13);
            p.setColor(alpha(BLUE,65));p.setStrokeWidth(3);
            c.drawArc(tmp,138,264,false,p);p.setStyle(Paint.Style.FILL);
            final int tickTotal=max==8?40:44;
            for(int i=0;i<=tickTotal;i++){
                float a=(float)Math.toRadians(138+264f*i/tickTotal);
                boolean major=i%5==0;
                float inner=r-(major?43:34),outer=r-16;
                line(c,cx+(float)Math.cos(a)*inner,cy+(float)Math.sin(a)*inner,
                    cx+(float)Math.cos(a)*outer,cy+(float)Math.sin(a)*outer,
                    alpha(WHITE,major?245:125),major?3:1.6f);
                if(major){
                    int tickValue=max==8?i/5:(i/4)*20;
                    if(tickValue<=max){
                        float rr=r-68f;
                        text(c,String.valueOf(tickValue),
                            cx+(float)Math.cos(a)*rr,
                            cy+(float)Math.sin(a)*rr+10,24,WHITE,true,true);
                    }
                }
            }
            text(c,label,cx,cy-31,29,WHITE,false,true);
            text(c,readout,cx,cy+47,66,WHITE,true,true);
            text(c,unit,cx,cy+84,25,WHITE,false,true);
        }
        private void logoSpotify(Canvas c,float cx,float cy,float radius){
            color(GREEN);c.drawCircle(cx,cy,radius,p);
            for(int i=0;i<3;i++){
                float inset=4f+i*4f;
                color(0xff042b1d);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f+i*.2f);
                p.setStrokeCap(Paint.Cap.ROUND);
                tmp.set(cx-radius+inset,cy-radius*.42f+i*6f,
                    cx+radius-inset,cy+radius*.42f+i*4f);
                c.drawArc(tmp,208,127,false,p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        private void plasma(Canvas c){
            long now=SystemClock.uptimeMillis();
            float strength=music.frame(CyberpunkActivity.this,now);
            c.save();
            c.scale(1.20f,1.20f,814,324);
            plasma.draw(c,814,324,strength,live.rpm,live.speed,live.online);
            c.restore();
        }
        private void carPanel(Canvas c){
            glass(c,32,399,515,204,23,BLUE);
            text(c,"MARCHA EST.",70,455,27,CYAN,true,false);
            text(c,live.gearText(),105,522,64,WHITE,true,true);
            text(c,"ESTIMATIVA",105,560,18,MUTED,false,true);
            // The Civic is decorative; P/R/N/D cannot be read from this ELM.
            if(civic!=null){
                color(WHITE);p.setAlpha(210);
                c.drawBitmap(civic,null,new RectF(230,426,525,586),p);
                p.setAlpha(255);
            }
        }
        private void musicPanel(Canvas c){
            glass(c,1093,377,526,226,24,CYAN);
            // Unspecified album artwork: decorative neon tile, no fake track.
            color(Color.WHITE);
            p.setShader(new LinearGradient(1124,401,1227,498,
                new int[]{0xff23205c,0xff7823b7,0xff0a1744},
                null,Shader.TileMode.CLAMP));
            c.drawRoundRect(1124,400,1231,496,13,13,p);p.setShader(null);
            color(alpha(PINK,130));c.drawCircle(1177,444,27,p);
            text(c,"♪",1177,458,44,WHITE,true,true);
            logoSpotify(c,1251,414,15);
            text(c,"Spotify",1275,423,24,GREEN,true,false);
            text(c,"Spotify Android",1254,461,31,WHITE,true,false);
            text(c,musicPlaying?"Música em reprodução":"Aguardando reprodução",
                1254,489,22,MUTED,false,false);
            line(c,1132,511,1584,511,alpha(BLUE,130),3);
            text(c,"◀◀",1249,573,35,WHITE,true,true);
            color(0xff0a4160);c.drawCircle(1366,556,43,p);
            color(CYAN);p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);c.drawCircle(1366,556,43,p);
            p.setStyle(Paint.Style.FILL);
            if(musicPlaying){
                rect(c,1351,535,1363,577,2,WHITE);
                rect(c,1370,535,1382,577,2,WHITE);
            }else{
                color(WHITE);
                path.rewind();
                path.moveTo(1354,533);path.lineTo(1386,556);
                path.lineTo(1354,579);path.close();c.drawPath(path,p);
            }
            text(c,"▶▶",1481,573,35,WHITE,true,true);
        }
        private String fmt(float val,String suffix,int decimals){
            return val<0||Float.isNaN(val)||Float.isInfinite(val)?"—":
                String.format(PT,"%."+decimals+"f",val)+suffix;
        }
        private void tile(Canvas c,float x,String name,String value,
                          String detail,int color){
            glass(c,x,627,207,123,19,color);
            text(c,name,x+103,658,20,WHITE,false,true);
            text(c,value,x+103,700,35,WHITE,true,true);
            text(c,detail,x+103,728,16,MUTED,false,true);
            rect(c,x+20,739,x+187,743,2,alpha(BLUE,60));
        }
        private void bottomInfo(Canvas c){
            tile(c,32,"Temp. motor",
                live.coolant>-100?live.coolant+"°C":"—","Leitura ELM327",CYAN);
            tile(c,256,"Pressão óleo","—","Sem sensor OBD2",BLUE);
            tile(c,480,"Tensão",
                fmt(live.voltage," V",1),"Bateria / ELM327",BLUE);
            tile(c,704,"Consumo méd.",
                fmt(live.kmL," km/L",1),"Estimativa",CYAN);
            tile(c,928,"Autonomia",
                fmt(live.autonomy()," km",0),"Depende de tanque informado",BLUE);
            glass(c,1159,627,460,123,20,CYAN);
            text(c,"Bolota — Cores por RPM",1389,658,23,WHITE,true,true);
            color(Color.WHITE);
            p.setShader(new LinearGradient(1182,0,1595,0,
                new int[]{CYAN,BLUE,VIOLET,PINK,0xffffbc27},
                new float[]{0f,.23f,.48f,.75f,1f},Shader.TileMode.CLAMP));
            c.drawRoundRect(1182,675,1596,691,8,8,p);p.setShader(null);
            text(c,"Baixo",1200,728,19,WHITE,true,false);
            text(c,"Normal",1308,728,19,WHITE,true,false);
            text(c,"Alto",1430,728,19,WHITE,true,false);
            text(c,"Máximo",1552,728,19,WHITE,true,false);
        }
        private void nav(Canvas c){
            glass(c,20,791,1607,86,37,BLUE);
            final String[] titles={"Dash","Música","Sensores","Autonomia","GPS","Ajustes"};
            final String[] marks={"⌂","♫","◴","▣","◎","⚙"};
            float w=1607f/6f;
            for(int i=0;i<6;i++){
                float left=20+i*w;
                if(i==0){
                    rect(c,left+2,793,left+w-3,875,36,0xff10365f);
                    color(CYAN);p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.2f);
                    c.drawRoundRect(left+2,793,left+w-3,875,36,36,p);
                    p.setStyle(Paint.Style.FILL);
                }
                if(i>0)line(c,left,806,left,865,alpha(BLUE,70),1.5f);
                text(c,marks[i],left+w*.5f,835,35,i==0?CYAN:WHITE,true,true);
                text(c,titles[i],left+w*.5f,863,25,i==0?CYAN:WHITE,i==0,true);
            }
            text(c,"Civic Drive v2.0.9  |  Honda Civic G8",
                824,910,20,MUTED,false,true);
        }
        @Override protected void onDraw(Canvas actual){
            super.onDraw(actual);
            if(live==null||SystemClock.uptimeMillis()-lastReadAt>1500L){
                live=new Live();lastReadAt=SystemClock.uptimeMillis();
            }
            actual.save();actual.scale(getWidth()/W,getHeight()/H);
            actual.drawColor(BG);
            color(Color.WHITE);
            p.setShader(new LinearGradient(0,0,W,H,
                new int[]{0xff050b17,0xff01050d,0xff08132b},
                null,Shader.TileMode.CLAMP));
            actual.drawRect(0,0,W,H,p);p.setShader(null);
            header(actual);
            gauge(actual,278,267,186,live.rpm<0?-1:live.rpm/1000f,8,
                "RPM",live.rpm>=0?String.format(PT,"%.0f",live.rpm):"—","rpm");
            gauge(actual,1366,267,186,live.speed,220,
                "Velocidade",live.speed>=0?String.format(PT,"%.0f",live.speed):"—","km/h");
            plasma(actual);
            carPanel(actual);
            musicPanel(actual);
            bottomInfo(actual);
            nav(actual);
            actual.restore();
            if(getWindowVisibility()==View.VISIBLE)postInvalidateDelayed(125L);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX()*W/getWidth(),y=e.getY()*H/getHeight();
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                downX=x;downY=y;downMs=SystemClock.uptimeMillis();return true;
            }
            if(e.getAction()!=MotionEvent.ACTION_UP)return true;
            if(Math.abs(x-downX)>36f||Math.abs(y-downY)>36f)return true;
            performClick();
            if(y>=790&&y<=878){
                choose(Math.max(0,Math.min(5,(int)((x-20f)/(1607f/6f)))));
            }else if(y>=510&&y<=597&&x>=1180&&x<=1550){
                if(x<1308)musicCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                else if(x<1418)musicCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                else musicCommand(KeyEvent.KEYCODE_MEDIA_NEXT);
            }else if(y>=375&&y<610&&x>=1093){musicSources();}
            else if(y<82&&x>1490){settings();}
            else if(y>=626&&y<=752&&x>1125){themes();}
            else if(y>=398&&y<=608&&x<546){openClassicDash(1);}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
