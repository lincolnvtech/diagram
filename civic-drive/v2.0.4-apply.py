#!/usr/bin/env python3
"""Civic Drive 2.0.4 — clearly visible automatic plasma colors plus audio feedback.
Builds on 2.0.3; no changes to RPM input, OBD protocol, gears or dashboards.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "build-civic")
java=root/"app/src/main/java/br/com/civicdrive"
main=java/"MainActivity.java"
music=java/"MusicReactive.java"
gradle=root/"app/build.gradle"

def replace_once(path,old,new):
    s=path.read_text(encoding="utf-8")
    n=s.count(old)
    if n!=1:
        raise RuntimeError(f"{path}: expected one anchor but found {n}: {old[:110]!r}")
    path.write_text(s.replace(old,new,1),encoding="utf-8")

replace_once(gradle,
    "versionCode 26\n        versionName '2.0.3'",
    "versionCode 27\n        versionName '2.0.4'")

replace_once(main,
'''            // Audio-brightness halo only, preserving the original RPM artwork.
            if(sound>.025f){
                color(Color.WHITE);
                p.setShader(new android.graphics.RadialGradient(cx,cy,194f,
                    new int[]{alpha(tint,Math.min(105,(int)(sound*94f))),
                        alpha(tint,Math.min(55,(int)(sound*27f))),Color.TRANSPARENT},
                    null,android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(cx,cy,194f,p);
                p.setShader(null);
            }''',
'''            // 2.0.4: continuous electric-color life, even without external audio.
            // Keep RPM's dominant tint; overlay color is intentionally visible
            // but deliberately avoids strobe, rapid rotation and extra bitmap memory.
            final float slowWave=.5f+.5f*(float)Math.sin(now*.90f);
            final float otherWave=.5f+.5f*(float)Math.sin(now*.51f+1.3f);
            final float musicGlow=Math.min(1f,Math.max(0f,sound));
            final float bright=.40f+.60f*slowWave+musicGlow*.90f;
            final int haloTone=CivicTheme.blend(tint,
                CivicTheme.color(palette,1),.18f+.27f*otherWave);
            color(Color.WHITE);
            p.setShader(new android.graphics.RadialGradient(cx,cy,237f,
                new int[]{alpha(haloTone,Math.min(132,(int)(46f+bright*56f))),
                    alpha(CivicTheme.color(palette,2),
                        Math.min(94,(int)(22f+bright*29f))),
                    Color.TRANSPARENT},
                new float[]{0f,.48f,1f},android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx,cy,237f,p);
            p.setShader(null);

            // Accentuate the existing orbital details with two independently
            // breathing neon accents; no circular spin and no UI relayout.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(2.8f+bright*2.5f);
            p.setColor(alpha(haloTone,Math.min(240,(int)(73f+bright*105f))));
            c.drawArc(new RectF(cx-214f,cy-214f,cx+214f,cy+214f),
                205f,68f,false,p);
            p.setStrokeWidth(2.2f+bright*1.8f);
            p.setColor(alpha(CivicTheme.color(palette,1),
                Math.min(225,(int)(55f+(1f-slowWave)*95f+musicGlow*40f))));
            c.drawArc(new RectF(cx-199f,cy-202f,cx+199f,cy+202f),
                20f,61f,false,p);
            p.setStyle(Paint.Style.FILL);''')

replace_once(main,
    '"Música reativa · ativar/desativar"},',
    '"Música reativa · "+(musicReactive.enabled(this)?"ON":"OFF"), "Diagnóstico da esfera"},')
replace_once(main,
    '''                    if(which==13){toggleMusicReactive();return;}
                    switch(which-2){''',
    '''                    if(which==13){toggleMusicReactive();return;}
                    if(which==14){showOrbDiagnostics();return;}
                    switch(which-2){''')

replace_once(main,
    '''    private void toggleMusicReactive(){''',
    '''    private void showOrbDiagnostics(){
        String status="Cores dinâmicas: SEMPRE LIGADAS\\n"
            +"RPM: "+(rpm>=0?String.format(PT,"%.0f rpm",rpm):"sem leitura")+"\\n"
            +"Áudio: "+musicReactive.diagnostics(this)+"\\n\\n"
            +"Quando o Android bloquear a captura de outros apps, o reator "
            +"continua pulsando com cores dinâmicas, mas sem seguir as batidas.";
        new AlertDialog.Builder(this)
            .setTitle("Civic Drive · Diagnóstico da esfera")
            .setMessage(status)
            .setPositiveButton("Fechar",null).show();
    }
    private void toggleMusicReactive(){''')

replace_once(main,
    'Toast.makeText(this,"Música reativa desligada",Toast.LENGTH_SHORT).show();',
    'Toast.makeText(this,"Áudio OFF · animação dinâmica continua",Toast.LENGTH_LONG).show();')
replace_once(main,
    'Toast.makeText(this,"Esfera sincronizada com áudio",Toast.LENGTH_SHORT).show();',
    'Toast.makeText(this,"Áudio ON · confira Diagnóstico da esfera",Toast.LENGTH_LONG).show();')
replace_once(main,
    'Toast.makeText(this,"Áudio indisponível nesta central; RPM preservado",',
    'Toast.makeText(this,"Sem captura de áudio · cores dinâmicas continuam",')
replace_once(main,
    'new AlertDialog.Builder(this).setTitle("Civic G8 2.0.3")',
    'new AlertDialog.Builder(this).setTitle("Civic G8 2.0.4")')

replace_once(music,
    '''    float high() {return level()>.02f?Math.max(0f,Math.min(1f,treble)):0f;}''',
    '''    String diagnostics(Context context) {
        if(!enabled(context))return "desligado (opcional)";
        if(!permitted(context))return "sem permissão de áudio";
        if(visualizer==null)return "captura de áudio indisponível";
        if(lastAudioAt<=0)return "aguardando amostras do Android";
        long age=SystemClock.uptimeMillis()-lastAudioAt;
        if(age>1700L)return "Android não enviou amostras ("+(age/1000L)+"s)";
        return level()>.028f?"recebendo sinal (nível "+Math.round(level()*100f)+"%)"
                              :"amostras recebidas, sinal baixo ou silêncio";
    }
    float high() {return level()>.02f?Math.max(0f,Math.min(1f,treble)):0f;}''')
print("OK Civic Drive 2.0.4: dynamic halo always visible; audio is optional.")
