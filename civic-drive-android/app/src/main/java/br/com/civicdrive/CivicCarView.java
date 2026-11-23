package br.com.civicdrive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Editable vector silhouette of a 2006-2011 Civic sedan; no cut-out photos. */
public final class CivicCarView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body=new Path();
    private final Path glass=new Path();
    private int carColor=0xff10d7ff;
    private float zoom=1f,shiftX=0f,shiftY=0f;
    public CivicCarView(Context context){super(context);}
    public void setCarColor(int color){carColor=color;invalidate();}
    public void setZoom(float size){zoom=Math.max(.55f,Math.min(1.60f,size));invalidate();}
    public void setOffset(float x,float y){
        shiftX=Math.max(-.75f,Math.min(.75f,x));
        shiftY=Math.max(-.75f,Math.min(.75f,y));
        invalidate();
    }
    private void paint(int color,float width){
        p.reset();p.setAntiAlias(true);p.setColor(color);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);
        p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeCap(Paint.Cap.ROUND);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float w=getWidth(),h=getHeight();
        c.drawColor(0xff020407);
        float spacing=Math.max(38f,w/24f);
        paint(0xff0b1f38,1f);
        for(float x=0;x<w;x+=spacing)c.drawLine(x,0,x,h,p);
        for(float y=0;y<h;y+=spacing)c.drawLine(0,y,w,y,p);
        c.save();
        c.translate(w*.5f+shiftX*w*.34f,h*.52f+shiftY*h*.30f);
        float factor=Math.min(w*.82f/510f,h*.81f/215f)*zoom;
        c.scale(factor,factor);c.translate(-250f,-110f);
        p.reset();p.setAntiAlias(true);
        p.setShader(new LinearGradient(50,189,450,189,
            new int[]{Color.TRANSPARENT,0x4720c9ff,Color.TRANSPARENT},
            null,Shader.TileMode.CLAMP));
        c.drawOval(new RectF(45,179,455,194),p);p.setShader(null);

        body.reset();
        body.moveTo(24,142);
        body.cubicTo(22,129,37,121,61,116);
        body.lineTo(132,103);
        body.cubicTo(162,75,190,57,224,53);
        body.cubicTo(276,46,324,50,351,71);
        body.cubicTo(363,81,378,95,394,105);
        body.lineTo(445,115);
        body.cubicTo(469,122,480,129,478,148);
        body.lineTo(463,157);body.lineTo(438,157);
        body.cubicTo(435,125,396,124,385,157);
        body.lineTo(139,157);
        body.cubicTo(135,126,91,126,87,157);
        body.lineTo(37,157);body.close();
        int outline=carColor==0xff02070c?0xff4488c9:carColor;
        paint(Color.argb(44,Color.red(carColor),Color.green(carColor),Color.blue(carColor)),1f);
        p.setStyle(Paint.Style.FILL);c.drawPath(body,p);
        paint(outline,3.4f);c.drawPath(body,p);
        glass.reset();
        glass.moveTo(156,104);glass.cubicTo(184,75,204,65,236,63);
        glass.lineTo(256,62);glass.lineTo(256,102);glass.close();
        c.drawPath(glass,p);
        glass.reset();
        glass.moveTo(268,62);
        glass.cubicTo(305,59,326,69,347,84);
        glass.lineTo(377,105);glass.lineTo(268,102);glass.close();
        c.drawPath(glass,p);
        paint(0xff83dfff,1.8f);
        c.drawLine(262,63,263,154,p); // pillar / doors
        c.drawLine(158,108,151,150,p);
        c.drawLine(345,106,354,151,p);
        c.drawLine(268,121,285,121,p);
        c.drawLine(179,121,197,121,p);
        paint(0xff051321,8f);
        c.drawCircle(112,158,28,p);c.drawCircle(411,158,28,p);
        paint(0xff9bb8ce,3.3f);
        c.drawCircle(112,158,26,p);c.drawCircle(411,158,26,p);
        paint(outline,2f);
        c.drawCircle(112,158,14,p);c.drawCircle(411,158,14,p);
        paint(0xffe4f9ff,3.6f);
        c.drawLine(447,129,470,133,p); // headlights
        paint(0xfff36b76,3.4f);
        c.drawLine(29,134,51,132,p); // taillights
        paint(0x6636bfff,2f);
        c.drawLine(45,172,468,172,p);
        c.restore();
    }
}
