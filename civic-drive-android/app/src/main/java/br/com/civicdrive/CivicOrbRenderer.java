package br.com.civicdrive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * Nova Plasma V2 — deep-black electric core modelled after the owner's
 * approved reference: thin branched lightning, dense interior, intense
 * cyan nucleus, blue orbiting rings and a restrained outer corona.
 *
 * Not a spinning bitmap or a static screenshot: light paths move slowly.
 * No audio recording, GPU shader, new threads, OBD commands or networking.
 * Paint and Path objects are reused to keep the Android head unit responsive.
 */
final class CivicOrbRenderer {
    private static final float TAU=6.2831853f;
    private static final int BLACK=0xff020611, COBALT=0xff0754f9;
    private static final int AZURE=0xff087eff, BLUE=0xff1bafff;
    private static final int CYAN=0xff55eafa, ICE=0xffe8fbff;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF rim=new RectF();
    private long lastFrame=0L;
    private float speedSmooth=0f,energySmooth=.20f,rotation=0f;
    private int rpmBand=0;
    private final Context context;
    private final Bitmap referenceTexture=CivicPlasmaReference.decode();

    CivicOrbRenderer(Context c){context=c.getApplicationContext();}
    private static float clamp(float x,float lo,float hi){return Math.max(lo,Math.min(hi,x));}
    private static int alpha(int color,float amount){
        int v=(int)clamp(amount,0f,255f);
        return Color.argb(v,Color.red(color),Color.green(color),Color.blue(color));
    }
    private void fill(int color){
        p.reset();p.setAntiAlias(true);p.setColor(color);p.setStyle(Paint.Style.FILL);
    }
    private void stroke(int color,float width){
        fill(color);p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(width);
    }
    private void glowPath(Canvas c,Path shape,int color,float strength,float thickness){
        stroke(alpha(color,19f*strength),thickness*5f);c.drawPath(shape,p);
        p.setColor(alpha(color,58f*strength));p.setStrokeWidth(thickness*2.3f);c.drawPath(shape,p);
        p.setColor(alpha(color,195f*strength));p.setStrokeWidth(thickness);c.drawPath(shape,p);
    }
    private void glowArc(Canvas c,RectF bounds,float start,float sweep,int color,float strength){
        stroke(alpha(color,21f*strength),13f);c.drawArc(bounds,start,sweep,false,p);
        p.setColor(alpha(color,72f*strength));p.setStrokeWidth(5.6f);
        c.drawArc(bounds,start,sweep,false,p);
        p.setColor(alpha(color,220f*strength));p.setStrokeWidth(1.65f);
        c.drawArc(bounds,start,sweep,false,p);
    }
    private static float wave(float t,float offset){
        return (float)Math.sin(t+offset);
    }
    /**
     * A branching spark crosses a curved section of the plasma. Every branch
     * is deterministic and changes gently over time, never flashing entire frames.
     */
    private void lightning(Canvas c,float cx,float cy,float outer,float phase,
                           float time,int series,int color,float intensity){
        final float spin=phase+time*.045f*(series%2==0?1f:-1f);
        final float wave=wave(time*.48f,series*2.1f);
        final float sx=cx+26f*(float)Math.cos(spin+.55f);
        final float sy=cy+25f*(float)Math.sin(spin+.55f);
        final float ex=cx+outer*(float)Math.cos(spin+1.65f);
        final float ey=cy+outer*(float)Math.sin(spin+1.65f);
        path.rewind();path.moveTo(sx,sy);
        for(int step=1;step<=11;step++){
            float u=step/11f;
            float angle=spin+.55f+1.10f*u;
            float distance=26f+(outer-26f)*u;
            float perturb=(7f+11f*u)*wave(time*.75f+step*.37f,
                series*1.97f+step*2.48f);
            float radial=distance+perturb;
            float px=cx+radial*(float)Math.cos(angle);
            float py=cy+radial*(float)Math.sin(angle);
            path.lineTo(px,py);
        }
        // The preview has highly branched, luminous electricity,
        // not solid thick wires or a single flat circular rim.
        glowPath(c,path,color,intensity,1.45f);
        for(int fork=0;fork<3;fork++){
            float u=.30f+fork*.22f;
            float angle=spin+.55f+1.10f*u;
            float rr=26f+(outer-26f)*u;
            float ox=cx+rr*(float)Math.cos(angle);
            float oy=cy+rr*(float)Math.sin(angle);
            path.rewind();path.moveTo(ox,oy);
            for(int n=1;n<=4;n++){
                float v=n/4f;
                float bend=(fork%2==0?1f:-1f)*(.22f+.32f*v);
                float a=angle+bend+time*.035f;
                float r=rr+22f*v+13f*v*wave(time*.9f,series+fork+n);
                path.lineTo(cx+r*(float)Math.cos(a),
                            cy+r*(float)Math.sin(a));
            }
            glowPath(c,path,fork%3==0?ICE:CYAN,intensity*.72f,.78f);
        }
    }
    /** Called by the existing 7–8 fps home and music dashboards. */
    void draw(Canvas c,float cx,float cy,float energy,float rpm,
              float kmh,boolean rpmValid){
        long now=SystemClock.uptimeMillis();
        final float t=now/1000f;
        float dt=lastFrame==0? .14f:clamp((now-lastFrame)/1000f,.02f,.4f);
        lastFrame=now;
        boolean realRpm=rpmValid&&rpm>=0&&rpm<=8500;
        boolean realSpeed=kmh>=0&&kmh<=260;
        speedSmooth+=(realSpeed?kmh-speedSmooth:-speedSmooth)*clamp(dt*2f,.04f,.24f);
        energySmooth+=(clamp(energy,0f,1f)-energySmooth)*.16f;
        rotation+=dt*(.10f+clamp(speedSmooth/180f,0f,1f)*.13f);
        float pulse=.5f+.5f*wave(t*(1.10f+energySmooth*.8f),0);
        float breath=.5f+.5f*wave(t*.61f,.9f);
        float radius=178f+6f*breath;
        rpmBand=realRpm?(rpm<1600?0:rpm<3400?1:rpm<5100?2:3):0;
        // Bright blue at idle and medium RPM. The original plasma remains blue;
        // high RPM uses amber only in the dedicated warning gauges.
        final int[] colors={AZURE,COBALT,BLUE,CYAN};
        int electric=colors[rpmBand];

        // Very dark spherical depth and restrained luminous blue edge.
        fill(Color.WHITE);
        p.setShader(new RadialGradient(cx,cy,radius+31f,
            new int[]{0xff063464,0xff02142d,BLACK,0xff062d6a,Color.TRANSPARENT},
            new float[]{0f,.20f,.68f,.87f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,radius+31f,p);p.setShader(null);
        fill(Color.WHITE);
        p.setShader(new RadialGradient(cx,cy,radius*1.38f,
            new int[]{alpha(COBALT,14),alpha(AZURE,24f+18f*pulse),
                alpha(electric,38f+27f*pulse),Color.TRANSPARENT},
            new float[]{0f,.53f,.76f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,radius*1.38f,p);p.setShader(null);

        // The approved actual plasma image supplies the reference texture.
        // Translucent and feathered, it has no background rectangle.
        // Branching filaments below animate ON TOP of its original pattern.
        if(referenceTexture!=null){
            fill(Color.WHITE);
            p.setFilterBitmap(true);
            p.setAlpha(232);
            rim.set(cx-194f,cy-157f,cx+194f,cy+157f);
            c.drawBitmap(referenceTexture,null,rim,p);
            p.setAlpha(255);
        }

        // Light animated branches overlay the actual approved plasma texture.
        for(int i=0;i<12;i++){
            float phase=(i+.21f)*TAU/12f+rotation*.32f;
            float length=139f+(i%5)*8.2f+7.5f*wave(t*.55f,i*.9f);
            float visibility=.37f+.18f*(.5f+.5f*wave(t*1.2f,i*1.3f));
            int col=i%5==0?ICE:i%2==0?CYAN:AZURE;
            lightning(c,cx,cy,length,phase,t,i,col,visibility);
        }

        // Thin, oblique energized orbital rings from the approved picture.
        // Never mask the sphere with a thick full circle.
        for(int i=0;i<3;i++){
            float orbit=185f+i*11f+3f*wave(t*.37f,i);
            float scale=i==1?.69f:.87f;
            rim.set(cx-orbit,cy-orbit*scale,cx+orbit,cy+orbit*scale);
            float angle=i*86f+t*(i%2==0?5.2f:-3.4f);
            glowArc(c,rim,angle,136f+i*24f,i==1?CYAN:AZURE,.70f+i*.08f);
        }

        // Fine outer blue boundary and two detached electric arcs.
        rim.set(cx-radius,cy-radius,cx+radius,cy+radius);
        for(int i=0;i<4;i++){
            float start=i*92f+19f+13f*wave(t*.46f,i*1.9f);
            glowArc(c,rim,start,43f+12f*pulse,i%2==0?COBALT:CYAN,.62f);
        }

        // Cyan central plasma burst. Keep a small white nucleus
        // (the earlier oversized white blob obscured the actual electricity).
        fill(Color.WHITE);
        float size=64f+8f*pulse;
        p.setShader(new RadialGradient(cx,cy,size,
            new int[]{alpha(ICE,130),alpha(CYAN,90),
                alpha(AZURE,38),Color.TRANSPARENT},
            new float[]{0f,.14f,.40f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,size,p);p.setShader(null);
        fill(alpha(ICE,225));c.drawCircle(cx,cy,6.5f+2.2f*pulse,p);

        // Light motes reinforce depth without affecting OBD2 polling.
        for(int i=0;i<24;i++){
            float a=i*2.399963f+t*(i%2==0?.16f:-.13f);
            float rr=47f+(i%8)*16.0f;
            float flicker=.46f+.54f*(.5f+.5f*wave(t*2.5f,i*1.3f));
            fill(alpha(i%4==0?ICE:CYAN,55f+165f*flicker));
            c.drawCircle(cx+rr*(float)Math.cos(a),cy+rr*(float)Math.sin(a),
                         .7f+1.5f*flicker,p);
        }
    }
}
