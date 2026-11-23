package br.com.civicdrive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * Civic Drive v2.0.8 - original procedural electromagnetic plasma.
 *
 * The reference is an electric blue/violet/magenta ball with a bright central
 * nucleus, orbiting filaments and no single rigid bitmap. The orb is drawn
 * directly into Canvas so that its interior ACTUALLY moves.
 *
 * RPM: primary nucleus color, independent of music.
 * Vehicle speed: secondary arc color and flow speed, only with real speed data.
 * Android music activity: mild additional pulse/brightness, NOT claimed to be
 * beat detection. No microphone, Visualizer, audio capture, background threads
 * or Bluetooth changes. Modest canvas work at the host's existing <=9fps.
 */
final class CivicOrbRenderer {
    private static final float TAU=(float)(Math.PI*2.0);
    // One electric-blue spectrum; amber is reserved for genuine high RPM.
    private static final int[] RPM_COLORS={
        Color.rgb(16,214,255),
        Color.rgb(30,166,255),
        Color.rgb(22,112,244),
        Color.rgb(50,168,255),
        Color.rgb(246,160,65)
    };
    private static final int[] SPEED_COLORS={
        Color.rgb(19,112,245),Color.rgb(16,175,250),
        Color.rgb(30,199,255),Color.rgb(97,220,255)
    };

    private final Context context;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path strand=new Path();
    private final RectF ellipse=new RectF();
    private int band=0,previousBand=0;
    private long bandChangedAt=0L;
    private float smoothSpeed=0f;
    private float smoothPower=.24f;
    private long lastFrameAt;

    CivicOrbRenderer(Context context) {
        this.context=context.getApplicationContext();
    }

    private static float clamp(float n,float lo,float hi){
        return Math.max(lo,Math.min(hi,n));
    }
    private static int opacity(int color,float alpha){
        return Color.argb((int)clamp(alpha,0,255),
            Color.red(color),Color.green(color),Color.blue(color));
    }
    private static int speedColor(float speed){
        float kmh=clamp(speed,0f,125f);
        if(kmh<35f)return CivicTheme.blend(SPEED_COLORS[0],SPEED_COLORS[1],kmh/35f);
        if(kmh<75f)return CivicTheme.blend(SPEED_COLORS[1],SPEED_COLORS[2],(kmh-35f)/40f);
        return CivicTheme.blend(SPEED_COLORS[2],SPEED_COLORS[3],(kmh-75f)/50f);
    }
    private static int index(float rpm){
        return rpm<900f?0:rpm<2000f?1:rpm<3500f?2:rpm<5000f?3:4;
    }
    private void fill(int color){
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
    }

    /**
     * @param energy 0..1 lightweight playback activity animation from Android
     * @param rpm real ELM327 RPM or a negative sentinel
     * @param kmh real OBD2/GPS speed or a negative sentinel; no inferred values
     * @param rpmValid false whenever OBD2 is disconnected
     */
    void draw(Canvas c,float cx,float cy,float energy,float rpm,
              float kmh,boolean rpmValid) {
        final long ms=SystemClock.uptimeMillis();
        final float t=ms/1000f;
        final float dt=lastFrameAt<=0? .11f:clamp((ms-lastFrameAt)/1000f,.01f,.27f);
        lastFrameAt=ms;

        final boolean realRpm=rpmValid&&rpm>=0f&&rpm<=8500f;
        final boolean realSpeed=kmh>=0f&&kmh<=260f;
        final float speed=realSpeed?clamp(kmh,0f,180f):0f;
        final float smoothing=clamp(dt*2.8f,.07f,.37f);
        smoothSpeed+=(speed-smoothSpeed)*smoothing;
        smoothPower+=(clamp(energy,0f,1f)-smoothPower)*.18f;
        final float speedNorm=realSpeed?clamp(smoothSpeed/125f,0f,1f):0f;
        final float power=clamp(smoothPower,0f,1f);
        final int theme=CivicTheme.index(context);

        final int requestedBand=realRpm?index(rpm):0;
        if(requestedBand!=band){
            previousBand=band;
            band=requestedBand;
            bandChangedAt=ms;
        }
        final float blend=clamp((ms-bandChangedAt)/520f,0f,1f);
        int core=CivicTheme.blend(RPM_COLORS[previousBand],RPM_COLORS[band],blend);
        core=CivicTheme.orbAccent(core,theme);
        // Maintain 0..125 km/h secondary hue control independently of RPM.
        int speedHue=realSpeed?speedColor(smoothSpeed):CivicTheme.color(theme,1);
        if(theme!=0&&theme!=4)speedHue=CivicTheme.blend(speedHue,CivicTheme.color(theme,1),.62f);
        final boolean amber=realRpm&&band==4;
        final int edge=amber?CivicTheme.blend(core,speedHue,.22f):
            CivicTheme.blend(core,speedHue,realSpeed?.61f:.34f);
        final int purple=amber?CivicTheme.blend(core,speedHue,.14f):
            CivicTheme.blend(speedHue,Color.rgb(34,138,255),.35f);
        final int white=amber?Color.rgb(255,241,212):Color.rgb(241,252,255);

        // Speed makes the magnetic lines travel faster, never shakes the orb.
        final float speedFlow=.62f+speedNorm*.91f;
        final float breathe=.5f+.5f*(float)Math.sin(t*(1.12f+power*.35f));
        final float pulse=.5f+.5f*(float)Math.sin(t*(1.45f+power*1.8f));
        final float radius=180f+(4f*breathe)+(6f*power);

        // Opaque near-black plasma body masks any static orb in the HUD backdrop.
        fill(Color.rgb(4,8,23));
        p.setShader(new RadialGradient(cx,cy,radius+31f,
            new int[]{
                Color.rgb(7,13,35),
                Color.rgb(3,8,25),
                opacity(core,210),
                opacity(edge,75),
                Color.TRANSPARENT
            },new float[]{0f,.56f,.79f,.92f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,radius+31f,p);
        p.setShader(null);

        // Colored electric atmosphere, with visible outer corona and sharp rim.
        fill(Color.WHITE);
        p.setShader(new RadialGradient(cx,cy,radius+51f,
            new int[]{opacity(purple,14f+17f*power),
                opacity(edge,35f+25f*pulse),
                opacity(edge,74f+65f*power),
                Color.TRANSPARENT},
            new float[]{0f,.64f,.86f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,radius+51f,p);p.setShader(null);

        // 12 full field-lines radiate from the magnetic nucleus, curve over the
        // whole orb and return inward: continuous phase motion makes it 'alive'.
        // One thick translucent stroke + one bright sharp stroke per filament.
        for(int i=0;i<10;i++){
            strand.rewind();
            float phase=i*TAU/10f;
            float variation=(i%3)*.31f;
            final int line=i%3==0?white:i%3==1?edge:purple;
            final float drift=t*speedFlow*(i%2==0?.34f:-.28f);
            final float theta=phase+drift;
            final float startX=cx+14f*(float)Math.cos(theta);
            final float startY=cy+12f*(float)Math.sin(theta);
            strand.moveTo(startX,startY);
            for(int j=1;j<=20;j++){
                final float u=j/20f;
                // Each line bends out to the perimeter then circles back to core.
                final float r=(25f+153f*(float)Math.pow(Math.sin(Math.PI*u),.74))
                    *(1f+.07f*(float)Math.sin(t*.55f+i*1.7f+j*.51f));
                final float spin=theta+(u-.5f)*(4.25f+variation)
                    +.23f*(float)Math.sin(t*.68f+i*.9f+u*TAU);
                float px=cx+r*(float)Math.cos(spin);
                float py=cy+r*(float)Math.sin(spin);
                strand.lineTo(px,py);
            }
            fill(opacity(line,65f+38f*power));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5.9f+3.7f*power);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(strand,p);
            p.setColor(opacity(line,195f+45f*pulse));
            p.setStrokeWidth(1.55f+1.05f*power);
            c.drawPath(strand,p);
        }

        // Surface electricity: eight orbiting curved magnetic arches, no whole-
        // sphere rotation. These are long, changing trajectories, not one halo.
        for(int i=0;i<6;i++){
            final float phase=i*.79f;
            final float modulation=6f*(float)Math.sin(t*.83f+i*1.17f);
            final float r=176f+(i%3)*7.5f+modulation;
            ellipse.set(cx-r,cy-r*.94f,cx+r,cy+r*.94f);
            final float start=(i*41f+t*speedFlow*(i%2==0?10.8f:-8.8f))%360f;
            final float sweep=43f+25f*(.5f+.5f*(float)Math.sin(t*.39f+i));
            int accent=i%4==0?white:i%2==0?edge:purple;
            fill(opacity(accent,54f+42f*power));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(5.2f+2.8f*power);
            c.drawArc(ellipse,start,sweep,false,p);
            p.setColor(opacity(accent,179f+45f*pulse));
            p.setStrokeWidth(1.7f+0.8f*power);
            c.drawArc(ellipse,start,sweep,false,p);
        }

        // Bright nucleus with converging filaments, but not a flat white blob.
        fill(Color.WHITE);
        float coreSize=37f+14f*power+7f*breathe;
        p.setShader(new RadialGradient(cx,cy,coreSize*2.35f,
            new int[]{opacity(white,235),opacity(core,198),
                opacity(purple,80),Color.TRANSPARENT},
            new float[]{0f,.26f,.57f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,coreSize*2.35f,p);p.setShader(null);
        fill(opacity(white,225f));
        c.drawCircle(cx,cy,6.5f+3f*pulse,p);

        // Sparse charged particles; deterministic positions, zero allocations.
        for(int i=0;i<22;i++){
            final float base=i*2.399963f;
            final float radial=61f+(i%7)*17.8f;
            final float a=base+t*speedFlow*(i%2==0?.23f:-.18f);
            float px=cx+radial*(float)Math.cos(a);
            float py=cy+radial*(float)Math.sin(a);
            float shimmer=.5f+.5f*(float)Math.sin(t*2.2f+i*1.4f);
            fill(opacity(i%4==0?white:i%2==0?edge:purple,105f+112f*shimmer));
            c.drawCircle(px,py,1.15f+1.05f*shimmer+power*.6f,p);
        }
    }
}
