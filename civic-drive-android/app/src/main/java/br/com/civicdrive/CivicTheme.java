package br.com.civicdrive;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

/** Persisted local appearance; never touches car sensors, telematics or connectivity. */
final class CivicTheme {
    static final String[] NAMES={"Neon Blue","Blackout","Arctic Cyan","Titanium","Cyberpunk"};
    // BG, CYAN/accent, BLUE/secondary, WHITE, MUTED, OUTLINE.
    private static final int[][] COLORS={
        {0xff020911,0xff18d3f4,0xff1680cf,0xffeef5fc,0xff97b1c6,0xff1a597f},
        {0xff030507,0xffb7c7d7,0xff74889d,0xfff0f4f8,0xff9cabb9,0xff3e4b59},
        {0xff031a21,0xff44e4df,0xff149cbd,0xffefffff,0xff91c6c5,0xff237d86},
        {0xff101821,0xff87b9fc,0xff4b7caf,0xfff1f6fa,0xffa6b8c6,0xff4a647f},
        {0xff02060d,0xff20eaff,0xff974eff,0xfff3f8ff,0xff94a9c3,0xff285a9a}
    };
    // Cached filters: the launcher draws animated frames on low-memory head units.
    // Keep Android bitmap processing in native rendering, not CPU pixel loops.
    private static ColorFilter greyArt(){
        ColorMatrix m=new ColorMatrix();
        m.setSaturation(0.0f);
        return new ColorMatrixColorFilter(m);
    }
    private static final ColorFilter[] ART={
        null,
        greyArt(),
        // Arctic: convert intense electric blue to a vivid greenish cyan.
        new ColorMatrixColorFilter(new float[]{
            .67f,0,.06f,0,0,  .08f,.92f,.66f,0,0,  0,.20f,.72f,0,0,  0,0,0,1,0
        }),
        // Titanium: visibly muted brushed-steel blue, more silver than electric.
        new ColorMatrixColorFilter(new float[]{
            .78f,0,.28f,0,0,  0,.80f,.24f,0,0,  0,.14f,.60f,0,0,  0,0,0,1,0
        }),
        null // Cyberpunk uses real cyan / purple / magenta, no bitmap tint
    };
    static ColorFilter artFilter(int theme){
        return ART[Math.max(0,Math.min(ART.length-1,theme))];
    }
    static int blend(int first,int second,float ratio){
        float t=Math.max(0f,Math.min(1f,ratio));
        return Color.rgb(
            Math.round(Color.red(first)*(1-t)+Color.red(second)*t),
            Math.round(Color.green(first)*(1-t)+Color.green(second)*t),
            Math.round(Color.blue(first)*(1-t)+Color.blue(second)*t));
    }
    static int orbAccent(int current,int theme){
        if(theme==0||theme==4)return current;
        // Preserve the amber high-RPM reference; recolor the electric bands.
        if(Color.red(current)>145 && Color.red(current)>Color.blue(current)*1.4f)
            return current;
        float energy=Math.max(.25f,Math.min(1f,Color.blue(current)/255f));
        return blend(color(theme,2),color(theme,1),energy);
    }
    private CivicTheme(){}
    static int index(Context context){
        int n=context.getSharedPreferences("civic_appearance",Context.MODE_PRIVATE)
                .getInt("theme",0);
        return Math.max(0,Math.min(NAMES.length-1,n));
    }
    static int color(int theme,int slot){return COLORS[Math.max(0,Math.min(COLORS.length-1,theme))][slot];}
    static void select(Context context,int theme){
        if(theme<0||theme>=NAMES.length)return;
        context.getSharedPreferences("civic_appearance",Context.MODE_PRIVATE)
                .edit().putInt("theme",theme).apply();
    }
    /** Recolor only blue/cyan paints on the home screen, not photographs or warning hues. */
    static int homeAccent(int argb,int theme){
        if(theme==0)return argb;
        float[] hsv=new float[3];
        Color.colorToHSV(argb,hsv);
        if(hsv[0]<175f||hsv[0]>250f||hsv[1]<0.30f)return argb;
        int accent=color(theme,1);
        float[] target=new float[3];
        Color.colorToHSV(accent,target);
        hsv[0]=target[0];
        hsv[1]=Math.min(target[1],hsv[1]);
        return Color.HSVToColor(Color.alpha(argb),hsv);
    }
}
