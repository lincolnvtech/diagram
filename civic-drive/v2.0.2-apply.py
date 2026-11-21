#!/usr/bin/env python3
"""Civic Drive 2.0.2. Make color themes affect all graphic layers, not only labels.
Conservative ELM327 traffic reduction and more accurate ECU silence diagnostics.
No firmware writes; OBD commands are unchanged and read-only.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "build-civic")
base=root/"app/src/main/java/br/com/civicdrive"
def rep(file,old,new):
    p=base/file if not file.startswith("app/") else root/file
    s=p.read_text(encoding="utf-8")
    n=s.count(old)
    if n!=1:
        raise RuntimeError(f"{file}: anchor count {n}: {old[:90]!r}")
    p.write_text(s.replace(old,new,1),encoding="utf-8")

rep("app/build.gradle",
    "versionCode 24\n        versionName '2.0.1'",
    "versionCode 25\n        versionName '2.0.2'")

rep("CivicTheme.java",
'''import android.graphics.Color;

/**''',
'''import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

/**''')

rep("CivicTheme.java",
'''    private CivicTheme(){}
    static int index(Context context){''',
'''    // Cached filters: the launcher draws animated frames on low-memory head units.
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
        })
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
        if(theme==0)return current;
        // Preserve the amber high-RPM reference; recolor the electric bands.
        if(Color.red(current)>145 && Color.red(current)>Color.blue(current)*1.4f)
            return current;
        float energy=Math.max(.25f,Math.min(1f,Color.blue(current)/255f));
        return blend(color(theme,2),color(theme,1),energy);
    }
    private CivicTheme(){}
    static int index(Context context){''')

rep("MainActivity.java",
'''            if(approvedBackdrop!=null){
                color(Color.WHITE);
                c.drawBitmap(approvedBackdrop,null,new RectF(0,0,CW,CH),p);
            } else {''',
'''            if(approvedBackdrop!=null){
                color(Color.WHITE);
                p.setColorFilter(CivicTheme.artFilter(CivicTheme.index(MainActivity.this)));
                c.drawBitmap(approvedBackdrop,null,new RectF(0,0,CW,CH),p);
                p.setColorFilter(null);
            } else {''')
rep("MainActivity.java",
'''            final int tint=mix(ORB_TONES[previousOrbBand],ORB_TONES[activeOrbBand],blend);''',
'''            final int tint=CivicTheme.orbAccent(
                mix(ORB_TONES[previousOrbBand],ORB_TONES[activeOrbBand],blend),
                CivicTheme.index(MainActivity.this));''')
rep("MainActivity.java",
'''            color(Color.WHITE);
            p.setAlpha(Math.max(0,Math.min(255,opacity)));
            // Exibe apenas o plasma;''',
'''            color(Color.WHITE);
            p.setAlpha(Math.max(0,Math.min(255,opacity)));
            p.setColorFilter(CivicTheme.artFilter(CivicTheme.index(MainActivity.this)));
            // Exibe apenas o plasma;''')
rep("MainActivity.java",
'''                    new RectF(493f,14f,1127f,427f),p);
            p.setAlpha(255);''',
'''                    new RectF(493f,14f,1127f,427f),p);
            p.setColorFilter(null);
            p.setAlpha(255);''')
rep("MainActivity.java",
'''            color(Color.WHITE);
            p.setShader(new android.graphics.RadialGradient(820,300,210,
                new int[]{Color.argb(110,15,112,199),Color.argb(16,8,60,119),Color.TRANSPARENT},
                null,android.graphics.Shader.TileMode.CLAMP));''',
'''            color(Color.WHITE);
            int palette=CivicTheme.index(MainActivity.this);
            int accent=CivicTheme.color(palette,1);
            p.setShader(new android.graphics.RadialGradient(820,300,210,
                new int[]{alpha(accent,110),alpha(CivicTheme.color(palette,2),32),Color.TRANSPARENT},
                null,android.graphics.Shader.TileMode.CLAMP));''')
rep("MainActivity.java",
'''            glowBackground(c);header(c);
            if(wallpaper==1)civicWallpaper(c);else if(wallpaper==2)quietWallpaper(c);else orb(c);
            gauge(c,303,334,true);gauge(c,1324,334,false);
            status(c);dock(c);''',
'''            glowBackground(c);header(c);
            if(wallpaper==1)civicWallpaper(c);else if(wallpaper==2)quietWallpaper(c);else orb(c);
            gauge(c,303,334,true);gauge(c,1324,334,false);
            center(c,"TEMA  "+CivicTheme.NAMES[CivicTheme.index(MainActivity.this)].toUpperCase(PT),
                   810,543,23,CivicTheme.color(CivicTheme.index(MainActivity.this),1),true);
            status(c);dock(c);''')
rep("MainActivity.java",
    'new AlertDialog.Builder(this).setTitle("Civic G8 2.0.1")',
    'new AlertDialog.Builder(this).setTitle("Civic G8 2.0.2")')

rep("DashboardActivity.java",
'''        private void background(Canvas c){
            set(BG);c.drawColor(BG);
            p.setShader(new LinearGradient(0,0,0,929,new int[]{Color.rgb(3,13,27),Color.rgb(1,9,19),Color.rgb(0,5,12)},null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,W,H,p);p.setShader(null);
            set(Color.WHITE);p.setShader(new RadialGradient(824,535,760,
                new int[]{Color.argb(30,0,114,198),Color.argb(3,2,34,67),Color.TRANSPARENT},null,Shader.TileMode.CLAMP));''',
'''        private void background(Canvas c){
            set(BG);c.drawColor(BG);
            // Unlike older builds, the 4 color themes also control the entire dash.
            p.setShader(new LinearGradient(0,0,0,929,
                new int[]{CivicTheme.blend(BG,BLUE,.23f),BG,CivicTheme.blend(BG,Color.BLACK,.45f)},
                null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,W,H,p);p.setShader(null);
            set(Color.WHITE);p.setShader(new RadialGradient(824,535,760,
                new int[]{a(BLUE,34),a(BG,6),Color.TRANSPARENT},null,Shader.TileMode.CLAMP));''')
rep("DashboardActivity.java",
'''            p.setShader(new LinearGradient(x,y,x+w,y+h,new int[]{Color.argb(155,7,25,42),Color.argb(225,3,12,23)},null,Shader.TileMode.CLAMP));''',
'''            p.setShader(new LinearGradient(x,y,x+w,y+h,
                new int[]{a(CivicTheme.blend(BG,BLUE,.20f),193),a(BG,245)},
                null,Shader.TileMode.CLAMP));''')
rep("DashboardActivity.java",
'''            set(Color.rgb(2,10,19));c.drawCircle(cx,cy,radius,p);
            set(Color.WHITE);p.setShader(new RadialGradient(cx,cy,radius,new int[]{Color.rgb(5,21,37),Color.rgb(2,9,19),Color.rgb(0,5,13)},null,Shader.TileMode.CLAMP));''',
'''            set(BG);c.drawCircle(cx,cy,radius,p);
            set(Color.WHITE);p.setShader(new RadialGradient(cx,cy,radius,
                new int[]{CivicTheme.blend(BG,BLUE,.25f),BG,CivicTheme.blend(BG,Color.BLACK,.50f)},
                null,Shader.TileMode.CLAMP));''')
rep("DashboardActivity.java",
'''                 new int[]{Color.argb(24,0,152,229),Color.argb(11,0,69,130),Color.TRANSPARENT},
                 null,Shader.TileMode.CLAMP));''',
'''                 new int[]{a(CYAN,28),a(BLUE,14),Color.TRANSPARENT},
                 null,Shader.TileMode.CLAMP));''')
rep("DashboardActivity.java",
'''                 new int[]{a(BLUE,30),Color.rgb(1,9,19),Color.rgb(1,7,15)},null,Shader.TileMode.CLAMP));''',
'''                 new int[]{a(BLUE,36),BG,CivicTheme.blend(BG,Color.BLACK,.45f)},
                 null,Shader.TileMode.CLAMP));''')
rep("DashboardActivity.java",
'''        private void smallCard(Canvas c,float x,float y,float w,float h,String title,String value,String info){''',
'''        /** Non-interactive concept artwork obeys the selected palette. */
        private void drawThemedArt(Canvas c,Bitmap art,RectF bounds,int opacity){
            if(art==null)return;
            set(Color.WHITE);
            p.setAlpha(opacity);
            p.setColorFilter(CivicTheme.artFilter(CivicTheme.index(DashboardActivity.this)));
            c.drawBitmap(art,null,bounds,p);
            p.setColorFilter(null);
            p.setAlpha(255);
        }
        private void smallCard(Canvas c,float x,float y,float w,float h,String title,String value,String info){''')
rep("DashboardActivity.java",
'''            if(frontTech!=null){set(Color.WHITE);p.setAlpha(186);
                c.drawBitmap(frontTech,null,new RectF(631,575,1011,711),p);p.setAlpha(255);
            }''',
'''            drawThemedArt(c,frontTech,new RectF(631,575,1011,711),186);''')
rep("DashboardActivity.java",
'''            if(engine!=null){set(Color.WHITE);p.setAlpha(172);
                c.drawBitmap(engine,null,new RectF(650,562,990,695),p);p.setAlpha(255);
            }''',
'''            drawThemedArt(c,engine,new RectF(650,562,990,695),172);''')
rep("DashboardActivity.java",
'''            if(engine!=null){set(Color.WHITE);c.drawBitmap(engine,null,new RectF(548,210,1099,466),p);}''',
'''            drawThemedArt(c,engine,new RectF(548,210,1099,466),255);''')

# Conservative ELM327 polling: the cheaper clones often freeze on unsupported
# extra PIDs while the primary tach/speed responses are healthy.
rep("ObdManager.java",
'''                int temp=n%3==0?parseTemperature(optionalCommand(out,in,"0105",2000)):-999;
                float volts=n%8==0?parseVoltage(optionalCommand(out,in,"ATRV",1600)):-1;''',
'''                int temp=n%4==0?parseTemperature(optionalCommand(out,in,"0105",2000)):-999;
                float volts=n%12==0?parseVoltage(optionalCommand(out,in,"ATRV",1600)):-1;''')
rep("ObdManager.java",
'''                // Probe fuel rate (015E) and MAF (0110) early, then resume slower
                // optional polling so RPM and speed remain responsive.
                boolean probeExtra=ecuResponded && (n<=1 || n%5==0);''',
'''                // Cheap ELM327 clones can lock up when too many unsupported PIDs
                // are requested. Prioritize stable RPM/speed; probe extras slowly.
                // No optional query in the initial handshake.
                boolean probeExtra=ecuResponded && n>=4 && n%12==4;''')
rep("ObdManager.java",
'''                } else if (now-lastEcuAt>22000L) {
                    // Falha real prolongada; entregar reconexão ao gerenciador.
                    throw new IOException("ECU sem respostas por 22s");
                }''',
'''                } else if (now-lastEcuAt>30000L) {
                    // Only prolonged silence should trigger reconnect, not 1-2 slow cycles.
                    throw new IOException("ECU sem respostas por 30s");
                } else if (now-lastEcuAt>9000L) {
                    lastIssue="ECU sem dados recentes ("+((now-lastEcuAt)/1000L)+"s)";
                }''')
rep("ObdManager.java",
'''                Thread.sleep(650);''',
'''                // Moderate request rate keeps low-cost adapters responsive.
                Thread.sleep(ecuResponded?850L:1200L);''')

print("Civic Drive 2.0.2 patch applied: real theme-rendering + gentle ELM327 polling.")
