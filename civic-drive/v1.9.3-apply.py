from pathlib import Path
import re
import sys

if len(sys.argv)!=2: raise SystemExit('uso: python3 civic-v193-apply.py build-civic')
ROOT=Path(sys.argv[1]).resolve()
ORIGINAL=ROOT
TARGET=ROOT

MAIN="""        private int rpmOrbBand(){
            // Offline: low-energy cyan only, never infer RPM from GPS speed.
            if(!obdConnected || rpm<0 || Double.isNaN(rpm)) return -1;
            if(rpm<900d) return 0;
            if(rpm<2000d) return 1;
            if(rpm<3500d) return 2;
            if(rpm<5000d) return 3;
            return 4;
        }
        private static final int[] ORB_TONES={
            Color.rgb(55,195,222), // idle: cyan suave
            Color.rgb(18,155,239), // azul neon
            Color.rgb(50,94,251),  // azul eletrico
            Color.rgb(175,103,241),// roxo eletrico
            Color.rgb(248,165,58)  // alto giro: ambar, sem vermelho
        };
        private int activeOrbBand=0, previousOrbBand=0;
        private long orbChangeAt=0L;
        private float orbBlend(){
            long elapsed=android.os.SystemClock.uptimeMillis()-orbChangeAt;
            return Math.max(0f,Math.min(1f,elapsed/460f));
        }
        private void orb(Canvas c){
            // RPM real vindo do ELM327. Sem dado: esfera ciano calma; sem velocidade como substituta.
            final int rawBand=rpmOrbBand();
            final int band=rawBand<0?0:rawBand;
            final float rev=rawBand<0?0f:Math.max(0f,Math.min(1f,(float)(rpm/5500d)));
            final long tick=android.os.SystemClock.uptimeMillis();
            final float now=tick/1000f;
            if(band!=activeOrbBand){previousOrbBand=activeOrbBand;activeOrbBand=band;orbChangeAt=tick;}
            final float blend=orbBlend();
            final int tint=mix(ORB_TONES[previousOrbBand],ORB_TONES[activeOrbBand],blend);
            final float cx=811f,cy=263f;
            final float breathing=.018f+.026f*rev;
            final float pulse=1f+breathing*(float)Math.sin(now*(1.45f+rev*2.1f));
            // Orbita e oscilacao menores em marcha lenta e progressivas com RPM.
            final float swing=(2.1f+rev*5.8f)*(float)Math.sin(now*(.53f+rev*.55f));
            c.save(); c.rotate(swing,cx,cy); c.scale(pulse,pulse,cx,cy);
            drawOrbSprite(c,electricOrbs[previousOrbBand],(int)(245f*(1f-blend)));
            drawOrbSprite(c,electricOrbs[activeOrbBand],(int)(245f*blend));
            c.restore();
            // Movimento real sem estrobo, desenhado a ~14 fps para preservar hardware antigo.
            final int r=Color.red(tint),g=Color.green(tint),b=Color.blue(tint);
            color(Color.argb(90,r,g,b));
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(2.4f+rev*1.2f);
            c.drawArc(new RectF(cx-214,cy-218,cx+214,cy+218),
                (now*(19f+rev*43f))%360f,49f+rev*13f,false,p);
            p.setStrokeWidth(1.9f);
            p.setColor(Color.argb(70,r,g,b));
            c.drawArc(new RectF(cx-196,cy-200,cx+196,cy+200),
                (320f-now*(16f+rev*38f))%360f,66f,false,p);
            p.setStyle(Paint.Style.FILL);
            for(int n=0;n<6;n++){
                float a=now*((n%2==0?1f:-1f)*(.25f+rev*.62f))+(float)(n*Math.PI/3);
                float radius=193f+9f*(float)Math.sin(now*(.9f+rev)+n);
                float px=cx+radius*(float)Math.cos(a),py=cy+radius*(float)Math.sin(a);
                p.setColor(Color.argb(85+(int)(rev*35f),r,g,b));
                c.drawCircle(px,py,1.5f+rev*1.4f,p);
            }
        }
        private void drawOrbSprite(Canvas c,Bitmap sprite,int opacity){
            if(sprite==null || opacity<=0)return;
            color(Color.WHITE);
            p.setAlpha(Math.max(0,Math.min(255,opacity)));
            c.drawBitmap(sprite,null,new RectF(493f,14f,1127f,529f),p);
            p.setAlpha(255);
        }
"""

# Replace MainActivity block with stable anchors that work even after v1.9.2 animation changes.
source=(ORIGINAL/'app/src/main/java/br/com/civicdrive/MainActivity.java').read_text()
start=source.index('        private int targetOrbColor(){')
end=source.index('        private void civicWallpaper(Canvas c){',start)
# Original has velocity/targetOrbColor/mix/alpha/orb. Keep mix for the transition above.
replace_begin=source.index('        private int mix(int a,int b,float f){',start)
replace_end=source.index('        private void orb(Canvas c){',replace_begin)
mix_method=source[replace_begin:replace_end]
new_block=mix_method+MAIN
source=source[:start]+new_block+source[end:]
old_array='''        private final Bitmap[] electricOrbs = {
            BitmapFactory.decodeResource(getResources(), R.drawable.civic_orb_cyan),
            BitmapFactory.decodeResource(getResources(), R.drawable.civic_orb_blue),
            BitmapFactory.decodeResource(getResources(), R.drawable.civic_orb_red)
        };'''
new_array='''        private final Bitmap[] electricOrbs = {
            BitmapFactory.decodeResource(getResources(), R.drawable.civic_orb_cyan),
            BitmapFactory.decodeResource(getResources(), R.drawable.civic_orb_neon),
            BitmapFactory.decodeResource(getResources(), R.drawable.civic_orb_blue),
            BitmapFactory.decodeResource(getResources(), R.drawable.civic_orb_purple),
            BitmapFactory.decodeResource(getResources(), R.drawable.civic_orb_amber)
        };'''
assert source.count(old_array)==1
source=source.replace(old_array,new_array)
source=source.replace('private int orbColor = Color.rgb(27, 194, 239);','// Plasma color and movement are driven by valid engine RPM only.')
source=source.replace(' * Civic G8 1.9.1: true approved-reference HUD scaled to 1648x928.',' * Civic G8 1.9.3: owner-approved premium HUD and RPM-driven Nova plasma.')
# This tiny change is the only part of the animation timing touched by 1.9.2,
# and is intentionally not needed; preserve its 60ms frame rate in the release.
(TARGET/'app/src/main/java/br/com/civicdrive').mkdir(parents=True,exist_ok=True)
(TARGET/'app/src/main/java/br/com/civicdrive/MainActivity.java').write_text(source)

DASH=(ORIGINAL/'app/src/main/java/br/com/civicdrive/DashboardActivity.java').read_text()
DASH=DASH.replace('private static final int BG=Color.rgb(3,9,18), BLUE=Color.rgb(33,136,248), CYAN=Color.rgb(31,190,253), LIGHT=Color.WHITE,\n            GRAY=Color.rgb(146,170,198), WARN=Color.rgb(255,180,58);',
'''private static final int BG=Color.rgb(4,10,18), BLUE=Color.rgb(43,108,163), CYAN=Color.rgb(55,188,226), LIGHT=Color.rgb(235,243,249),
            GRAY=Color.rgb(145,166,187), WARN=Color.rgb(245,171,65);''')
DASH=DASH.replace('TextView title=text("DASHBOARDS  /  CIVIC G8",20,LIGHT,true);','TextView title=text("DASHBOARDS",20,LIGHT,true);')
DASH=DASH.replace('root.setPadding(dp(17),dp(10),dp(17),dp(15))','root.setPadding(dp(18),dp(12),dp(18),dp(15))')
DASH=DASH.replace('choice.setBackground(outline());choice.setOnClickListener(v->{category=tab;updateVisibility();});',
'''choice.setBackground(outline());choice.setOnClickListener(v->{category=tab;updateVisibility();});''')
# Rebuild grid whenever user changes category. No blank cards between hidden categories.
start=DASH.index('        for(int i=0;i<SPECS.length;i+=3){')
end=DASH.index('        TextView note=', start)
DASH=DASH[:start]+'''        for(Spec spec:SPECS)tiles.add(new GaugeTile(spec));
'''+DASH[end:]
start=DASH.index('    private void updateVisibility(){')
end=DASH.index('    private void refresh(){',start)
DASH=DASH[:start]+'''    private void updateVisibility(){
        // Reflow only visible cards; removing a category must not leave empty columns.
        for(GaugeTile tile:tiles){
            android.view.ViewParent parent=tile.getParent();
            if(parent instanceof android.view.ViewGroup)
                ((android.view.ViewGroup)parent).removeView(tile);
        }
        content.removeAllViews();
        LinearLayout row=null;
        int visible=0;
        for(GaugeTile tile:tiles){
            String key=tile.spec.key;
            boolean include=category.equals("GERAL") || category.equals(tile.spec.category) ||
                    key.equals("rpm") || key.equals("temp");
            if(!include)continue;
            if(visible%3==0){
                row=new LinearLayout(this);row.setPadding(0,dp(5),0,dp(5));content.addView(row);
            }
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(145),1f);
            lp.setMargins(dp(4),0,dp(4),0);row.addView(tile,lp);
            visible++;
        }
        if(row!=null && visible%3!=0){
            for(int i=visible%3;i<3;i++){
                View placeholder=new View(this);row.addView(placeholder,new LinearLayout.LayoutParams(0,dp(145),1f));
            }
        }
        refresh();
    }
'''+DASH[end:]
DASH=DASH.replace('''(s.key.equals("temp")&&value>=105) ||''','''(s.key.equals("temp")&&value>=105) ||
                    (s.key.equals("rpm")&&value>=5000) ||''')
start=DASH.index('    private final class GaugeTile extends LinearLayout {')
end=DASH.index('    private TextView text(',start)
DASH=DASH[:start]+'''    private final class GaugeTile extends LinearLayout {
        final Spec spec;
        final MeterArc arc;
        final TextView reading,status;
        GaugeTile(Spec spec){
            super(DashboardActivity.this);
            this.spec=spec;
            setOrientation(LinearLayout.VERTICAL);
            setPadding(dp(14),dp(11),dp(13),dp(10));
            setBackground(outline());
            setGravity(Gravity.CENTER_VERTICAL);
            TextView label=text(spec.title,11,GRAY,true);
            label.setLetterSpacing(.06f);
            addView(label);
            LinearLayout inline=new LinearLayout(DashboardActivity.this);
            inline.setGravity(Gravity.CENTER_VERTICAL);
            addView(inline,new LayoutParams(-1,0,1));
            reading=text("— "+spec.unit,22,LIGHT,true);
            reading.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
            inline.addView(reading,new LayoutParams(0,-2,1));
            arc=new MeterArc();
            inline.addView(arc,new LayoutParams(dp(82),dp(82)));
            status=text("Sem leitura",10,GRAY,false);
            addView(status);
            setOnClickListener(v->new AlertDialog.Builder(DashboardActivity.this)
                .setTitle(spec.title)
                .setMessage(spec.info+"\\n\\nLeitura atual: "+reading.getText()+"\\nEstado: "+status.getText())
                .setPositiveButton("Fechar",null).show());
        }
        void setReading(float value,boolean warning){
            if(value<=-900){
                reading.setText("— "+spec.unit);
                status.setText("Aguardando sensor");
                status.setTextColor(GRAY);
                arc.setValue(-1,BLUE);
                return;
            }
            reading.setText(String.format(new Locale("pt","BR"),
                Math.abs(value)>=100?"%.0f %s":"%.1f %s",value,spec.unit));
            status.setText(warning?
                (spec.key.equals("rpm")?"GIRO ELEVADO":"ATENÇÃO · CONFERIR"):
                "LEITURA AO VIVO");
            status.setTextColor(warning?WARN:GRAY);
            arc.setValue((value-spec.min)/(spec.max-spec.min),warning?WARN:CYAN);
        }
    }
    private final class MeterArc extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction=-1f;
        private int shade=CYAN;
        MeterArc(){super(DashboardActivity.this);}
        void setValue(float f,int color){fraction=f;shade=color;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            final float w=getWidth(),h=getHeight();
            final float cx=w*.5f,cy=h*.5f;
            final float radius=Math.min(w,h)*.39f;
            final float start=145f,sweep=250f;
            final RectF ring=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(2.4f));
            p.setColor(Color.rgb(35,55,71));
            c.drawArc(ring,start,sweep,false,p);
            p.setStrokeWidth(dp(1f));
            p.setColor(Color.rgb(65,88,111));
            for(int i=0;i<=10;i++){
                double a=Math.toRadians(start+sweep*i/10f);
                float inner=radius*.78f,outer=radius*.87f;
                float sn=(float)Math.sin(a),cs=(float)Math.cos(a);
                c.drawLine(cx+inner*cs,cy+inner*sn,cx+outer*cs,cy+outer*sn,p);
            }
            if(fraction>=0f){
                float f=Math.max(0f,Math.min(1f,fraction));
                p.setStrokeWidth(dp(3.7f));
                p.setColor(shade);
                c.drawArc(ring,start,sweep*f,false,p);
                double a=Math.toRadians(start+sweep*f);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx+radius*(float)Math.cos(a),cy+radius*(float)Math.sin(a),dp(2.2f),p);
            }
            p.setStyle(Paint.Style.FILL);
        }
    }
    private GradientDrawable outline(){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(8,20,32),Color.rgb(5,13,23)});
        d.setCornerRadius(dp(15));
        d.setStroke(dp(1),Color.rgb(30,61,85));
        return d;
    }
'''+DASH[end:]
(TARGET/'app/src/main/java/br/com/civicdrive/DashboardActivity.java').write_text(DASH)

# Readme is edited directly in GitHub, not as a source diff, to avoid patch conflicts.
print('Updated MainActivity.java chars',len(source))
print('Updated DashboardActivity.java chars',len(DASH))

# Apply only after the stable v1.9.2 OBD2 patch has been restored.
gradle=ROOT/'app/build.gradle'
source=gradle.read_text()
assert source.count('versionCode 12')==1 and source.count("versionName '1.9.2'")==1, 'Expected v1.9.2 baseline'
gradle.write_text(source.replace('versionCode 12','versionCode 13').replace("versionName '1.9.2'","versionName '1.9.3'"))
print('Civic G8 v1.9.3 transformation applied to',ROOT)