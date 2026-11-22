#!/usr/bin/env python3
"""Civic Drive 2.0.3 — color-reactive plasma from Android audio output.
Optional microphone/audio permission; no recording or transmission.
Preserves RPM palette, OBD2, themes, dashboards, and signed updater.
"""
from pathlib import Path
import re
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "build-civic")
pkg=root/"app/src/main/java/br/com/civicdrive"
main=pkg/"MainActivity.java"
gradle=root/"app/build.gradle"
manifest=root/"app/src/main/AndroidManifest.xml"

def replace_once(path, old, new):
    content=path.read_text(encoding="utf-8")
    count=content.count(old)
    if count!=1:
        raise RuntimeError(f"{path.name}: anchor expected once, got {count}: {old[:110]!r}")
    path.write_text(content.replace(old,new,1),encoding="utf-8")

replace_once(gradle, "versionCode 25\n        versionName '2.0.2'",
            "versionCode 26\n        versionName '2.0.3'")
manifest_text=manifest.read_text(encoding="utf-8")
if 'android.permission.RECORD_AUDIO' not in manifest_text:
    if "<application" not in manifest_text:
        raise RuntimeError("AndroidManifest: no application anchor")
    manifest_text=manifest_text.replace("<application",
        '<uses-permission android:name="android.permission.RECORD_AUDIO" />\n'
        '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n'
        '    <application',1)
    manifest.write_text(manifest_text,encoding="utf-8")

music_java=r'''package br.com.civicdrive;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.SystemClock;

/** Visual-only Android output-mix levels. No microphone streaming, file or network I/O. */
final class MusicReactive {
    private Visualizer visualizer;
    private volatile float energy;
    private volatile float treble;
    private volatile long lastAudioAt;

    boolean enabled(Context context) {
        return context.getSharedPreferences("civic_music_reactive",Context.MODE_PRIVATE)
                .getBoolean("enabled",false);
    }
    void setEnabled(Context context,boolean enabled) {
        context.getSharedPreferences("civic_music_reactive",Context.MODE_PRIVATE)
                .edit().putBoolean("enabled",enabled).apply();
        if(!enabled)stop();
    }
    boolean permitted(Context context) {
        return Build.VERSION.SDK_INT<23 ||
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    }
    synchronized boolean start(Context context) {
        if(!enabled(context) || !permitted(context))return false;
        if(visualizer!=null)return true;
        Visualizer next=null;
        try {
            next=new Visualizer(0); // Output mix: compatible with some, not all, Android head units.
            int[] range=Visualizer.getCaptureSizeRange();
            int preferred=256;
            int size=Math.min(range[1],Math.max(range[0],preferred));
            next.setCaptureSize(size);
            next.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            int rate=Math.max(1000,Math.min(12000,Visualizer.getMaxCaptureRate()));
            next.setDataCaptureListener(new Visualizer.OnDataCaptureListener(){
                @Override public void onWaveFormDataCapture(Visualizer v,byte[] wave,int sampleRate){
                    if(wave==null||wave.length==0)return;
                    double sum=0;
                    for(byte b:wave){
                        int d=(b&0xff)-128;
                        sum+=d*d;
                    }
                    float volume=(float)Math.sqrt(sum/wave.length)/52f;
                    float fresh=Math.min(1f,Math.max(0f,(volume-.025f)*1.45f));
                    energy=energy*.70f+fresh*.30f;
                    lastAudioAt=SystemClock.uptimeMillis();
                }
                @Override public void onFftDataCapture(Visualizer v,byte[] fft,int sampleRate){
                    if(fft==null||fft.length<16)return;
                    double highs=0;
                    int count=0;
                    for(int i=fft.length/2;i<fft.length-1;i+=2){
                        int re=fft[i], im=fft[i+1];
                        highs+=Math.sqrt(re*re+im*im);
                        count++;
                    }
                    float fresh=count==0?0f:(float)(highs/count/72.0);
                    treble=treble*.83f+Math.min(1f,fresh)*.17f;
                }
            },rate,true,true);
            next.setEnabled(true);
            visualizer=next;
            lastAudioAt=SystemClock.uptimeMillis();
            return true;
        } catch(Exception | LinkageError ex) {
            if(next!=null){
                try{next.setEnabled(false);}catch(Exception ignored){}
                try{next.release();}catch(Exception ignored){}
            }
            visualizer=null;energy=0f;treble=0f;
            return false;
        }
    }
    synchronized void stop() {
        Visualizer old=visualizer;
        visualizer=null;
        if(old!=null){
            try{old.setEnabled(false);}catch(Exception ignored){}
            try{old.release();}catch(Exception ignored){}
        }
        energy=0f;treble=0f;lastAudioAt=0L;
    }
    float level() {
        long age=SystemClock.uptimeMillis()-lastAudioAt;
        if(lastAudioAt<=0||age>1200L)return 0f;
        // Gradual release prevents flashing and keeps idle animation calm.
        return Math.max(0f,Math.min(1f,energy)) *
                Math.max(0f,1f-(Math.max(0L,age)/1200f));
    }
    float high() {return level()>.02f?Math.max(0f,Math.min(1f,treble)):0f;}

    static int livelyColor(int rpmTint,int cyan,int blue,float level,float high) {
        if(level<=.018f)return rpmTint;
        float activity=Math.min(1f,Math.max(0f,level));
        int musical=CivicTheme.blend(cyan,blue,Math.min(1f,activity*.80f+high*.16f));
        // Main hue remains RPM-driven; audio affects the plasma in moderation.
        float influence=.07f+activity*.23f;
        if(Color.red(rpmTint)>145 && Color.red(rpmTint)>Color.blue(rpmTint)*1.4f)
            influence*=.45f; // preserve high-RPM amber
        return CivicTheme.blend(rpmTint,musical,influence);
    }
}
'''
(pkg/"MusicReactive.java").write_text(music_java,encoding="utf-8")

content=main.read_text(encoding="utf-8")
old='''            final int tint=CivicTheme.orbAccent(
                mix(ORB_TONES[previousOrbBand],ORB_TONES[activeOrbBand],blend),
                CivicTheme.index(MainActivity.this));'''
new='''            final int rpmTint=CivicTheme.orbAccent(
                mix(ORB_TONES[previousOrbBand],ORB_TONES[activeOrbBand],blend),
                CivicTheme.index(MainActivity.this));
            final float sound=musicReactive.level();
            final int palette=CivicTheme.index(MainActivity.this);
            final int tint=MusicReactive.livelyColor(rpmTint,
                CivicTheme.color(palette,1),CivicTheme.color(palette,2),
                sound,musicReactive.high());'''
if content.count(old)!=1:
    raise RuntimeError("Home orb color anchor changed")
content=content.replace(old,new,1)

# Scope the drawing change inside orb() and avoid assumptions about older comments.
orb_start=content.index('        private void orb(Canvas c){')
orb_end=content.index('        private void drawOrbSprite(Canvas c,Bitmap sprite,int opacity){',orb_start)
orb_block=content[orb_start:orb_end]
original_restore='            c.restore();'
if orb_block.count(original_restore)<1:
    raise RuntimeError("Home orb restore statement not found")
halo='''            c.restore();
            // Audio-brightness halo only, preserving the original RPM artwork.
            if(sound>.025f){
                color(Color.WHITE);
                p.setShader(new android.graphics.RadialGradient(cx,cy,194f,
                    new int[]{alpha(tint,Math.min(105,(int)(sound*94f))),
                        alpha(tint,Math.min(55,(int)(sound*27f))),Color.TRANSPARENT},
                    null,android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(cx,cy,194f,p);
                p.setShader(null);
            }'''
orb_block=orb_block.replace(original_restore,halo,1)
content=content[:orb_start]+orb_block+content[orb_end:]

# Dedicated UI toggle, opt-in permission and local-only audio; existing menu indices preserved.
if content.count('    private void showThemes(){')!=1:
    raise RuntimeError("Appearance menu hook unavailable")
methods='''    private final MusicReactive musicReactive=new MusicReactive();
    private static final int MUSIC_PERMISSION_REQUEST=4816;
    private void toggleMusicReactive(){
        boolean enable=!musicReactive.enabled(this);
        musicReactive.setEnabled(this,enable);
        if(!enable){
            Toast.makeText(this,"Música reativa desligada",Toast.LENGTH_SHORT).show();
        } else if(!musicReactive.permitted(this)){
            Toast.makeText(this,"Permita o áudio para sincronizar a esfera",Toast.LENGTH_LONG).show();
            if(android.os.Build.VERSION.SDK_INT>=23)
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                        MUSIC_PERMISSION_REQUEST);
        } else if(musicReactive.start(this)){
            Toast.makeText(this,"Esfera sincronizada com áudio",Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this,"Áudio indisponível nesta central; RPM preservado",
                    Toast.LENGTH_LONG).show();
        }
        if(surface!=null)surface.invalidate();
    }
'''
content=content.replace('    private void showThemes(){',methods+'    private void showThemes(){',1)
old='"Permitir GPS", "Sobre"},'
new='"Permitir GPS", "Sobre", "Música reativa · ativar/desativar"},'
if content.count(old)!=1:raise RuntimeError("Settings menu items anchor changed")
content=content.replace(old,new,1)
old='''                    if(which==1){showObdDiagnostics();return;}
                    switch(which-2){'''
new='''                    if(which==1){showObdDiagnostics();return;}
                    if(which==13){toggleMusicReactive();return;}
                    switch(which-2){'''
if content.count(old)!=1:raise RuntimeError("Settings menu handler anchor changed")
content=content.replace(old,new,1)

# Lifecycle: stop audio effect when leaving launcher. Do not touch OBD sockets.
pattern=r'@Override\s+protected\s+void\s+onPause\s*\(\s*\)\s*\{'
matches=list(re.finditer(pattern,content))
if len(matches)!=1:raise RuntimeError(f"onPause count: {len(matches)}")
pos=matches[0].end()
content=content[:pos]+'musicReactive.stop();'+content[pos:]
old='''@Override protected void onResume(){super.onResume();dashboardOpen = false;'''
new='''@Override protected void onResume(){super.onResume();dashboardOpen = false;
        if(musicReactive.enabled(this) && musicReactive.permitted(this))
            musicReactive.start(this);'''
if content.count(old)!=1:raise RuntimeError("onResume lifecycle anchor changed")
content=content.replace(old,new,1)

# Resume from the Android audio permission prompt (potentially without onResume).
pattern=r'void\s+onRequestPermissionsResult\s*\([^)]*\)\s*\{'
matches=list(re.finditer(pattern,content))
handler='''if(requestCode==MUSIC_PERMISSION_REQUEST){
            if(musicReactive.enabled(this)&&musicReactive.permitted(this))
                musicReactive.start(this);
            if(surface!=null)surface.invalidate();
        }
        '''
if len(matches)==1:
    # Older MainActivity uses a different name for the request-code parameter.
    signature=matches[0].group(0)
    param=re.search(r'\(\s*int\s+(\w+)\s*,',signature)
    if not param:
        raise RuntimeError("Permission callback request parameter not found")
    actual_handler=handler.replace('requestCode==',param.group(1)+'==')
    pos=matches[0].end()
    content=content[:pos]+actual_handler+content[pos:]
elif len(matches)==0:
    extra='''    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        '''+handler+'''
    }
'''
    content=content.replace('    private void showThemes(){',extra+'    private void showThemes(){',1)
else:raise RuntimeError("Permission callback duplicate")

old='new AlertDialog.Builder(this).setTitle("Civic G8 2.0.2")'
if content.count(old)!=1:raise RuntimeError("Version dialog changed")
content=content.replace(old,'new AlertDialog.Builder(this).setTitle("Civic G8 2.0.3")',1)
main.write_text(content,encoding="utf-8")
print("OK Civic Drive 2.0.3 patch. RPM & OBD unchanged. Audio permission is opt-in.")
