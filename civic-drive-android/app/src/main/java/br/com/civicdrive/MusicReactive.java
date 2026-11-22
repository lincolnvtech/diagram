package br.com.civicdrive;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.SystemClock;

/**
 * Automatic, local-only cockpit visualizer.
 *
 * The sphere always breathes, including when idle or when a head unit cannot
 * expose its system audio mix. With Android permission, Visualizer session 0
 * provides actual audio energy. AudioManager.isMusicActive() supplies an
 * activity-only fallback; simulated motion is never represented as measured
 * musical beats. No sound is recorded, stored, sent or sent to the car ECU.
 */
final class MusicReactive {
    private Visualizer visualizer;
    private volatile float waveform;
    private volatile float highBand;
    private volatile long lastSampleAt;
    private volatile long lastAudibleAt;
    private long lastAttemptAt;
    private long lastMusicCheckAt;
    private boolean musicActive;
    private AudioManager audioManager;
    private float renderEnergy = 0.10f;

    boolean permitted(Context context) {
        return Build.VERSION.SDK_INT < 23 ||
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Start without any separate ON/OFF setting. Retries are rate-limited. */
    synchronized boolean start(Context context) {
        if (!permitted(context)) return false;
        if (visualizer != null) return true;
        long now=SystemClock.uptimeMillis();
        if(lastAttemptAt>0 && now-lastAttemptAt<18000L) return false;
        lastAttemptAt=now;
        Visualizer next=null;
        try {
            next=new Visualizer(0); // Android's output mix, if the vendor exposes it.
            int[] sizes=Visualizer.getCaptureSizeRange();
            int size=Math.max(sizes[0],Math.min(sizes[1],256));
            next.setCaptureSize(size);
            next.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            int rate=Math.max(1000,Math.min(10000,Visualizer.getMaxCaptureRate()));
            next.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v,byte[] values,int hz) {
                    if(values==null||values.length==0)return;
                    double total=0.0;
                    for(byte sample:values){
                        int centered=(sample&255)-128;
                        total+=centered*centered;
                    }
                    float rms=(float)Math.sqrt(total/values.length)/47f;
                    float sample=Math.max(0f,Math.min(1f,(rms-.018f)*1.80f));
                    waveform=waveform*.58f+sample*.42f;
                    long t=SystemClock.uptimeMillis();
                    lastSampleAt=t;
                    if(sample>.075f)lastAudibleAt=t;
                }
                @Override public void onFftDataCapture(Visualizer v,byte[] values,int hz){
                    if(values==null||values.length<16)return;
                    double sum=0d;
                    int count=0;
                    for(int i=values.length/2;i+1<values.length;i+=2){
                        int a=values[i],b=values[i+1];
                        sum+=Math.sqrt(a*a+b*b);count++;
                    }
                    if(count>0){
                        float value=Math.min(1f,(float)(sum/count/65d));
                        highBand=highBand*.78f+value*.22f;
                    }
                }
            },rate,true,true);
            next.setEnabled(true);
            visualizer=next;
            return true;
        }catch(Exception | LinkageError ignored){
            if(next!=null){
                try{next.setEnabled(false);}catch(Exception ignoredStop){}
                try{next.release();}catch(Exception ignoredRelease){}
            }
            visualizer=null;
            return false;
        }
    }

    synchronized void stop(){
        Visualizer old=visualizer;
        visualizer=null;
        if(old!=null)lastAttemptAt=0L;
        if(old!=null){
            try{old.setEnabled(false);}catch(Exception ignored){}
            try{old.release();}catch(Exception ignored){}
        }
        waveform=0f;highBand=0f;
        lastSampleAt=0L;lastAudibleAt=0L;
    }

    /** Called only on the foreground UI thread, at the HUD's existing ~14fps. */
    float frame(Context context,long uptimeMs){
        if(uptimeMs-lastMusicCheckAt>700L){
            lastMusicCheckAt=uptimeMs;
            try{
                if(audioManager==null)
                    audioManager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                musicActive=audioManager!=null&&audioManager.isMusicActive();
            }catch(Exception ignored){musicActive=false;}
        }
        float t=uptimeMs/1000f;
        // Clearly visible, slow ambient life even with audio fully unavailable.
        float ambient=.10f+.055f*(.5f+.5f*(float)Math.sin(t*1.20f));
        // A different cadence when Android reports active media playback.
        float activeBase=.30f+.18f*(.5f+.5f*(float)Math.sin(t*3.55f));
        float fallback=musicActive?activeBase:ambient;
        boolean samplesFresh=lastSampleAt>0&&uptimeMs-lastSampleAt<1100L;
        boolean audibleRecently=lastAudibleAt>0&&uptimeMs-lastAudibleAt<1350L;
        float target=audibleRecently&&samplesFresh?
            Math.max(fallback,Math.min(1f,.18f+waveform*.82f)):
            fallback;
        float smoothing=target>renderEnergy?.31f:.14f;
        renderEnergy+=(target-renderEnergy)*smoothing;
        return Math.max(0f,Math.min(1f,renderEnergy));
    }

    float treble(){
        long now=SystemClock.uptimeMillis();
        return lastAudibleAt>0&&now-lastAudibleAt<1300L?
            Math.max(0f,Math.min(1f,highBand)):0f;
    }

    String diagnostics(Context context){
        if(!permitted(context))
            return "automático · áudio sem permissão; animação ambiente ativa";
        if(visualizer==null)
            return "automático · Android não disponibilizou captura; animação ativa";
        long age=SystemClock.uptimeMillis()-lastSampleAt;
        if(lastSampleAt==0||age>1500L)
            return "automático · aguardando sinal do Android; animação ativa";
        if(lastAudibleAt>0&&SystemClock.uptimeMillis()-lastAudibleAt<1400L)
            return "automático · áudio real recebido ("+Math.round(waveform*100f)+"%)";
        return "automático · sem som detectado; animação ativa";
    }

    static int livelyColor(int rpmColor,int cyan,int blue,float energy,float treble){
        float slow=Math.max(0f,Math.min(1f,energy));
        float mix=(.17f+slow*.50f+treble*.16f);
        int musical=CivicTheme.blend(cyan,blue,Math.min(1f,slow*.90f+treble*.12f));
        // Do not override the existing high-RPM amber warning palette.
        if(Color.red(rpmColor)>145&&Color.red(rpmColor)>Color.blue(rpmColor)*1.4f)
            mix*=.13f;
        return CivicTheme.blend(rpmColor,musical,mix);
    }
}
