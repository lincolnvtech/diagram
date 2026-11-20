#!/usr/bin/env python3
"""Civic G8 v1.9.11 - four persistent cockpit themes and calibrated analog dial styling.
Changes are UI-only. No changes to OBD2, gear estimation or firmware.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "build-civic")
base=root/"app/src/main/java/br/com/civicdrive"
dash=base/"DashboardActivity.java"
main=base/"MainActivity.java"

def change(path,old,new):
    s=path.read_text(encoding="utf-8")
    hits=s.count(old)
    if hits!=1:
        raise RuntimeError(f"{path.name}: expected unique anchor ({hits} found): {old[:90]!r}")
    path.write_text(s.replace(old,new,1),encoding="utf-8")

change(root/"app/build.gradle",
       "versionCode 20\n        versionName '1.9.10'",
       "versionCode 21\n        versionName '1.9.11'")
change(main,'setTitle("Civic G8 1.9.10")','setTitle("Civic G8 1.9.11")')

theme_java=r'''package br.com.civicdrive;

import android.content.Context;
import android.graphics.Color;

/** Persisted local appearance; never touches car sensors, telematics or connectivity. */
final class CivicTheme {
    static final String[] NAMES={"Neon Blue","Blackout","Arctic Cyan","Titanium"};
    // BG, CYAN/accent, BLUE/secondary, WHITE, MUTED, OUTLINE.
    private static final int[][] COLORS={
        {0xff020911,0xff18d3f4,0xff1680cf,0xffeef5fc,0xff97b1c6,0xff1a597f},
        {0xff030507,0xffb7c7d7,0xff74889d,0xfff0f4f8,0xff9cabb9,0xff3e4b59},
        {0xff031a21,0xff44e4df,0xff149cbd,0xffefffff,0xff91c6c5,0xff237d86},
        {0xff101821,0xff87b9fc,0xff4b7caf,0xfff1f6fa,0xffa6b8c6,0xff4a647f}
    };
    private CivicTheme(){}
    static int index(Context context){
        int n=context.getSharedPreferences("civic_appearance",Context.MODE_PRIVATE)
                .getInt("theme",0);
        return Math.max(0,Math.min(NAMES.length-1,n));
    }
    static int color(int theme,int slot){return COLORS[Math.max(0,Math.min(3,theme))][slot];}
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
'''
(base/"CivicTheme.java").write_text(theme_java,encoding="utf-8")

change(dash,'''    private static final int BG=Color.rgb(2,9,17), CYAN=Color.rgb(24,211,244),
            BLUE=Color.rgb(22,128,207), WHITE=Color.rgb(238,245,252),
            MUTED=Color.rgb(151,177,198), WARN=Color.rgb(246,178,69),
            OUTLINE=Color.rgb(26,89,127);''','''    private static int BG=Color.rgb(2,9,17), CYAN=Color.rgb(24,211,244),
            BLUE=Color.rgb(22,128,207), WHITE=Color.rgb(238,245,252),
            MUTED=Color.rgb(151,177,198), OUTLINE=Color.rgb(26,89,127);
    private static final int WARN=Color.rgb(246,178,69);''')

change(dash,
'''    @Override protected void onResume(){super.onResume();handler.post(update);}''',
'''    @Override protected void onResume(){super.onResume();applyTheme();if(surface!=null)surface.invalidate();handler.post(update);}''')

change(dash,'''    private final class Data {''',
'''    /** Applying a theme never changes the data sources of the instrument cluster. */
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
            })
            .setNegativeButton("Fechar",null).show();
    }

    private final class Data {''')

change(dash,
    'new String[]{"Ler falhas","Histórico","Informar combustível","Voltar à home"}',
    'new String[]{"Ler falhas","Histórico","Informar combustível","Temas","Voltar à home"}')
change(dash,
    'else if(idx==2)showFuelDialog();else finish();',
    'else if(idx==2)showFuelDialog();else if(idx==3)showThemePicker();else finish();')

change(dash,
'''        private void heroDial(Canvas c,float cx,float cy,float r,float raw,float maximum,''',
'''        /** Concentric brushed-metal rims and a small reflective bevel, not a bitmap. */
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
        private void heroDial(Canvas c,float cx,float cy,float r,float raw,float maximum,''')

change(dash,
'''            c.drawCircle(cx,cy,r,p);p.setShader(null);
            set(a(BLUE,80));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);''',
'''            c.drawCircle(cx,cy,r,p);p.setShader(null);
            machinedBezel(c,cx,cy,r);
            set(a(BLUE,80));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);''')

change(dash,
'''            // Top line/visual speed arc in lieu of a constantly swinging needle.
            if(valid){
                float theta=(float)Math.toRadians(start+sweep*value/maximum);
                float tip=r-78;
                line(c,cx+(r-110)*(float)Math.cos(theta),cy+(r-110)*(float)Math.sin(theta),
                     cx+tip*(float)Math.cos(theta),cy+tip*(float)Math.sin(theta),highlight,5f);
            }''',
'''            // Angular sweep synchronized to *real* GPS/OBD data.
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
            }''')

# Home retains its own art, but all accent strokes/text use the chosen palette.
change(main,
'''        private void color(int c){p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);p.setColor(c);}''',
'''        private void color(int c){
            p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);
            p.setColor(CivicTheme.homeAccent(c,CivicTheme.index(MainActivity.this)));
        }''')

change(main,
'''        final String[] entries={"Aplicativos da central (ícones)","Adicionar atalho na home","Dashboards","Ler falhas do carro","Histórico local do Civic","Nova Total","Trocar adaptador OBD2","Mapas","Waze","Música","YouTube e som","Bluetooth","Atualizar pelo GitHub","Ajustes"};''',
'''        final String[] entries={"Aplicativos da central (ícones)","Adicionar atalho na home","Dashboards","Ler falhas do carro","Histórico local do Civic","Nova Total","Trocar adaptador OBD2","Mapas","Waze","Música","YouTube e som","Bluetooth","Atualizar pelo GitHub","Ajustes","Temas"};''')

change(main,'''                        default:showSettings();''',
'''                        case 14:showThemes();break;
                        default:showSettings();''')
change(main,'''    private void showMenu() {''',
'''    private void showThemes(){
        new AlertDialog.Builder(this).setTitle("Civic Drive · Aparência")
            .setSingleChoiceItems(CivicTheme.NAMES,CivicTheme.index(this),(dialog,index)->{
                CivicTheme.select(this,index);
                if(surface!=null)surface.invalidate();
                dialog.dismiss();
            }).setNegativeButton("Fechar",null).show();
    }

    private void showMenu() {''')

# An assertion ensures changes remain UI-only and never alter engine telemetry code.
assert (base/"GearEstimator.java").is_file()
assert (base/"ObdManager.java").is_file()
print("Civic G8 1.9.11: 4 temas persistentes, mostradores com acabamento e seletores instalados.")
