package br.com.civicdrive;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;

/** One intentionally fixed cockpit palette: deep black + electric blue. */
final class CivicTheme {
    static final String[] NAMES={"Preto e azul neon"};
    private static final int[] COLORS={
        0xff020407, // background
        0xff10d7ff, // neon electric cyan
        0xff136ff4, // electric blue
        0xfff4f9ff, // titles and readings
        0xff9cb4c9, // secondary labels
        0xff153c68  // dividers
    };
    private CivicTheme(){}
    static int index(Context c){return 0;}
    static int color(int theme,int slot){return COLORS[Math.max(0,Math.min(COLORS.length-1,slot))];}
    static void select(Context c,int ignored){ /* Only one theme, no obsolete selection. */ }
    static ColorFilter artFilter(int ignored){return null;}
    static int homeAccent(int argb,int ignored){return argb;}
    static int blend(int a,int b,float ratio){
        float t=Math.max(0f,Math.min(1f,ratio));
        return Color.argb(
            Math.round(Color.alpha(a)*(1f-t)+Color.alpha(b)*t),
            Math.round(Color.red(a)*(1f-t)+Color.red(b)*t),
            Math.round(Color.green(a)*(1f-t)+Color.green(b)*t),
            Math.round(Color.blue(a)*(1f-t)+Color.blue(b)*t));
    }
    static int orbAccent(int current,int ignored){
        // Preserve real high-RPM amber advisory, not a decorative orange theme.
        if(Color.red(current)>150 && Color.red(current)>Color.blue(current)*1.5f)return current;
        return blend(COLORS[2],COLORS[1],.50f);
    }
}
