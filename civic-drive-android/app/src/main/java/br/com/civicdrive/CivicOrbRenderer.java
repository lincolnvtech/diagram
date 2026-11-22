package br.com.civicdrive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * Reusable visual-only Orb. Shares the exact HUD plasma artwork and theme
 * palette; inputs are audio strength and the latest read-only RPM.
 */
final class CivicOrbRenderer {
    private final Context context;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Bitmap[] sprites;
    private static final int[] RPM_COLORS={
        Color.rgb(55,195,222),
        Color.rgb(18,155,239),
        Color.rgb(50,94,251),
        Color.rgb(175,103,241),
        Color.rgb(248,165,58)
    };
    private int activeBand=0,previousBand=0;
    private long bandChangedAt=0L;

    CivicOrbRenderer(Context context){
        this.context=context;
        sprites=new Bitmap[]{
            BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_cyan),
            BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_neon),
            BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_blue),
            BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_purple),
            BitmapFactory.decodeResource(context.getResources(),R.drawable.civic_orb_amber)
        };
    }
    private static int argb(int c,int a){
        return Color.argb(Math.max(0,Math.min(255,a)),
            Color.red(c),Color.green(c),Color.blue(c));
    }
    private void prep(int color){
        paint.reset();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setColor(color);
    }
    private void sprite(Canvas c,Bitmap bmp,float cx,float cy,int alpha,int theme){
        if(bmp==null||alpha<=0)return;
        prep(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setColorFilter(CivicTheme.artFilter(theme));
        int height=(int)(bmp.getHeight()*.80f);
        c.drawBitmap(bmp,new Rect(0,0,bmp.getWidth(),height),
            new RectF(cx-318f,cy-249f,cx+316f,cy+164f),paint);
        paint.setColorFilter(null);
    }
    void draw(Canvas c,float cx,float cy,float energy,float high,
              float rpm,boolean obdConnected){
        long ms=SystemClock.uptimeMillis();
        float now=ms/1000f;
        int theme=CivicTheme.index(context);
        int band=0;
        if(obdConnected&&rpm>=0f){
            band=rpm<900?0:rpm<2000?1:rpm<3500?2:rpm<5000?3:4;
        }
        if(band!=activeBand){
            previousBand=activeBand;
            activeBand=band;
            bandChangedAt=ms;
        }
        float fade=Math.max(0f,Math.min(1f,(ms-bandChangedAt)/460f));
        float intensity=Math.max(0f,Math.min(1f,energy));
        int rpmColor=CivicTheme.orbAccent(
            CivicTheme.blend(RPM_COLORS[previousBand],RPM_COLORS[activeBand],fade),theme);
        int cyan=CivicTheme.color(theme,1),blue=CivicTheme.color(theme,2);
        int tint=MusicReactive.livelyColor(rpmColor,cyan,blue,intensity,high);
        float breathing=.5f+.5f*(float)Math.sin(now*1.25f);
        float shimmer=.5f+.5f*(float)Math.sin(now*2.8f);
        int electric=CivicTheme.blend(tint,cyan,.18f+.32f*shimmer);
        int shadow=CivicTheme.blend(tint,blue,.22f+.22f*(1f-shimmer));
        prep(Color.WHITE);
        paint.setShader(new RadialGradient(cx,cy,250f,new int[]{
                argb(electric,Math.min(230,90+(int)(110*intensity))),
                argb(shadow,Math.min(135,38+(int)(66*intensity))),
                Color.TRANSPARENT
            },new float[]{0f,.52f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,250f,paint);
        paint.setShader(null);

        float scale=1.01f+.018f*breathing+.055f*intensity;
        c.save();
        c.scale(scale,scale,cx,cy);
        sprite(c,sprites[previousBand],cx,cy,Math.round(245f*(1f-fade)),theme);
        sprite(c,sprites[activeBand],cx,cy,Math.round(245f*fade),theme);
        c.restore();

        float lightX=cx+16f*(float)Math.sin(now*.77f);
        float lightY=cy+12f*(float)Math.cos(now*.63f);
        prep(Color.WHITE);
        paint.setShader(new RadialGradient(lightX,lightY,160f,new int[]{
                argb(electric,Math.min(190,65+(int)(94*intensity))),
                argb(shadow,Math.min(115,20+(int)(51*intensity))),
                Color.TRANSPARENT
            },new float[]{0f,.48f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,187f,paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.4f+4f*intensity);
        paint.setColor(argb(electric,Math.min(240,94+(int)(126*intensity))));
        c.drawArc(new RectF(cx-217f,cy-220f,cx+217f,cy+220f),
            206f+11f*(float)Math.sin(now*.54f),69f,false,paint);
        paint.setStrokeWidth(1.9f+2.8f*intensity);
        paint.setColor(argb(shadow,Math.min(220,79+(int)(94*intensity))));
        c.drawArc(new RectF(cx-198f,cy-201f,cx+198f,cy+201f),
            18f-12f*(float)Math.sin(now*.46f+1.1f),61f,false,paint);
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<8;i++){
            float angle=(float)(i*Math.PI/4d)+now*(i%2==0?.09f:-.07f);
            float radius=191f+8f*(float)Math.sin(now*.83f+i);
            float x=cx+radius*(float)Math.cos(angle);
            float y=cy+radius*(float)Math.sin(angle);
            paint.setColor(argb(i%2==0?electric:shadow,
                Math.min(230,75+(int)(88*intensity))));
            c.drawCircle(x,y,2.1f+3.2f*intensity,paint);
        }
    }
}
