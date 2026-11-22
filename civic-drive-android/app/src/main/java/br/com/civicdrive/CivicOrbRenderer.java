package br.com.civicdrive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * Single plasma renderer for both launcher and Music dashboard.
 * A shared sprite cache avoids decoding five large pictures twice.
 * Clear internal movement and hue variation, without rotating the whole sphere.
 */
final class CivicOrbRenderer {
    private static final int[] RPM_COLORS={
        Color.rgb(55,195,222),Color.rgb(18,155,239),
        Color.rgb(50,94,251),Color.rgb(175,103,241),
        Color.rgb(248,165,58)
    };
    private static Bitmap[] sharedSprites;

    private final Context context;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path coreClip=new Path();
    private final Bitmap[] sprites;
    private int activeBand=0,previousBand=0;
    private long bandChangedAt;
    private PorterDuffColorFilter currentFilter;
    private int filterColor;
    private long filterUpdatedAt;

    CivicOrbRenderer(Context activity){
        context=activity.getApplicationContext();
        synchronized(CivicOrbRenderer.class){
            if(sharedSprites==null){
                sharedSprites=new Bitmap[]{
                    BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_cyan),
                    BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_neon),
                    BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_blue),
                    BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_purple),
                    BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_amber)
                };
            }
            sprites=sharedSprites;
        }
    }
    private static int rgba(int c,int opacity){
        return Color.argb(Math.max(0,Math.min(255,opacity)),
                Color.red(c),Color.green(c),Color.blue(c));
    }
    private void prepare(int color){
        paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
        paint.setColor(color);
    }
    private void sprite(Canvas c,Bitmap bitmap,float cx,float cy,int alpha,int theme,
                        boolean tinted){
        if(bitmap==null||alpha<=0)return;
        prepare(Color.WHITE);
        paint.setAlpha(Math.max(0,Math.min(255,alpha)));
        if(tinted)paint.setColorFilter(currentFilter);
        else paint.setColorFilter(CivicTheme.artFilter(theme));
        int h=(int)(bitmap.getHeight()*.80f);
        c.drawBitmap(bitmap,new Rect(0,0,bitmap.getWidth(),h),
                new RectF(cx-318f,cy-249f,cx+316f,cy+164f),paint);
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }

    void draw(Canvas c,float cx,float cy,float energy,float high,
              float rpm,boolean obdConnected){
        long ms=SystemClock.uptimeMillis();
        float seconds=ms/1000f;
        float wave=.5f+.5f*(float)Math.sin(seconds*1.25f);
        float orbit=.5f+.5f*(float)Math.sin(seconds*.62f);
        float intensity=Math.max(0f,Math.min(1f,energy));
        int theme=CivicTheme.index(context);

        int band=(!obdConnected||rpm<0f)?0:
            rpm<900?0:rpm<2000?1:rpm<3500?2:rpm<5000?3:4;
        if(band!=activeBand){
            previousBand=activeBand;
            activeBand=band;
            bandChangedAt=ms;
        }
        float transition=Math.min(1f,Math.max(0f,(ms-bandChangedAt)/480f));
        int base=CivicTheme.orbAccent(
                CivicTheme.blend(RPM_COLORS[previousBand],RPM_COLORS[activeBand],transition),
                theme);
        int cyan=CivicTheme.color(theme,1),blue=CivicTheme.color(theme,2);
        boolean highRpm=band==4;
        int violet=theme==0?Color.rgb(147,85,246):blue;
        float colorWave=.5f+.5f*(float)Math.sin(seconds*.84f+intensity*1.6f);
        // Active music changes hue and core size; ambient mode still changes visibly.
        int shifting=CivicTheme.blend(cyan,violet,Math.min(1f,
            .12f+.67f*colorWave+.16f*intensity));
        int glow=highRpm?base:CivicTheme.blend(base,shifting,.45f+.26f*intensity);
        int energyColor=highRpm?base:shifting;

        // Quantized filter update (max 5/s) keeps audio visualization lightweight.
        int shade=CivicTheme.blend(glow,energyColor,.32f+.24f*orbit);
        if(currentFilter==null||
                (ms-filterUpdatedAt>=200L && shade!=filterColor)){
            filterColor=shade;
            currentFilter=new PorterDuffColorFilter(shade,PorterDuff.Mode.SRC_ATOP);
            filterUpdatedAt=ms;
        }

        prepare(Color.WHITE);
        paint.setShader(new RadialGradient(cx,cy,250f,
            new int[]{rgba(energyColor,120+(int)(75f*intensity)),
                rgba(glow,48+(int)(42f*intensity)),Color.TRANSPARENT},
            new float[]{0f,.50f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,250f,paint);
        paint.setShader(null);

        float scale=1.020f+.035f*wave+.046f*intensity;
        c.save();c.scale(scale,scale,cx,cy);
        sprite(c,sprites[previousBand],cx,cy,Math.round(230f*(1f-transition)),
                theme,false);
        sprite(c,sprites[activeBand],cx,cy,Math.round(230f*transition),theme,false);
        // Colored highlights tint the *plasma itself*, not just the outside ring.
        // Low alpha retains original organic texture and the RPM color beneath it.
        sprite(c,sprites[activeBand],cx,cy,
            Math.round((highRpm?28f:72f+33f*intensity)*Math.max(.35f,transition)),
            theme,true);
        c.restore();

        // 3 electrically charged fluid bands confined inside the bright nucleus.
        // The nucleus itself stays centered: no spinning image or flashing.
        coreClip.reset();
        coreClip.addCircle(cx,cy,154f,Path.Direction.CW);
        c.save();
        c.clipPath(coreClip);
        prepare(Color.WHITE);
        float ox=cx+22f*(float)Math.sin(seconds*.74f);
        float oy=cy+19f*(float)Math.cos(seconds*.57f);
        paint.setShader(new RadialGradient(ox,oy,153f,
            new int[]{rgba(energyColor,105+(int)(80f*intensity)),
                rgba(glow,40+(int)(55f*intensity)),Color.TRANSPARENT},
            new float[]{0f,.44f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(ox,oy,153f,paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for(int i=0;i<3;i++){
            float shift=seconds*(i==1?12f:-9f)+i*110f;
            float radius=77f+i*27f+8f*(float)Math.sin(seconds*(.75f+i*.21f));
            paint.setStrokeWidth(7f+3.5f*intensity+i);
            paint.setColor(rgba(i==1?glow:energyColor,
                Math.min(240,110+(int)(110f*intensity))));
            c.drawArc(new RectF(cx-radius,cy-radius,cx+radius,cy+radius),
                    shift,68f+i*14f,false,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        c.restore();

        prepare(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3.1f+4.6f*intensity);
        paint.setColor(rgba(energyColor,170+(int)(70f*intensity)));
        c.drawArc(new RectF(cx-212f,cy-215f,cx+212f,cy+215f),
                207f+13f*(float)Math.sin(seconds*.54f),83f,false,paint);
        paint.setStrokeWidth(2.2f+2.6f*intensity);
        paint.setColor(rgba(glow,115+(int)(90f*intensity)));
        c.drawArc(new RectF(cx-190f,cy-193f,cx+190f,cy+193f),
                18f-15f*(float)Math.sin(seconds*.46f),65f,false,paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
