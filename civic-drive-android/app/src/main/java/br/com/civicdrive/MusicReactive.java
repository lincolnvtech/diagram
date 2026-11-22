package br.com.civicdrive;

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
    String diagnostics(Context context) {
        if(!enabled(context))return "desligado (opcional)";
        if(!permitted(context))return "sem permissão de áudio";
        if(visualizer==null)return "captura de áudio indisponível";
        if(lastAudioAt<=0)return "aguardando amostras do Android";
        long age=SystemClock.uptimeMillis()-lastAudioAt;
        if(age>1700L)return "Android não enviou amostras ("+(age/1000L)+"s)";
        return level()>.028f?"recebendo sinal (nível "+Math.round(level()*100f)+"%)"
                              :"amostras recebidas, sinal baixo ou silêncio";
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
