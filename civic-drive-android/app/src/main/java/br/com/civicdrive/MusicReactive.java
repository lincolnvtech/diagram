package br.com.civicdrive;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;

/**
 * Lightweight visual energy. Deliberately does not open Visualizer(0):
 * inexpensive Android head units can stall their Bluetooth/ELM327 work
 * when system-wide audio capture and full-screen software drawing coexist.
 *
 * AudioManager reports only whether Android believes music is active,
 * not the beat or waveform. iPhone CarPlay playback may not be reported;
 * the sphere still animates in its ambient state.
 *
 * All work occurs on the calling UI thread without microphone, capture
 * sessions, byte buffers, extra threads or network access.
 */
final class MusicReactive {
    private AudioManager audio;
    private long lastCheckAt;
    private boolean active;
    private float renderEnergy=.24f;

    boolean permitted(Context context){return true;}
    synchronized boolean start(Context context){
        if(audio==null){
            try{audio=(AudioManager)context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);}
            catch(RuntimeException ignored){}
        }
        return true;
    }
    synchronized void stop(){ /* no resources to release, no BT interference */ }

    float frame(Context context,long nowMs){
        if(nowMs-lastCheckAt>1100L){
            lastCheckAt=nowMs;
            start(context);
            try{active=audio!=null&&audio.isMusicActive();}
            catch(RuntimeException ignored){active=false;}
        }
        float t=nowMs/1000f;
        float idle=.22f+.09f*(.5f+.5f*(float)Math.sin(t*1.05f));
        float playback=.54f+.19f*(.5f+.5f*(float)Math.sin(t*2.75f));
        float target=active?playback:idle;
        renderEnergy+=(target-renderEnergy)*.15f;
        return Math.min(1f,Math.max(0f,renderEnergy));
    }

    float treble(){return active?.58f:0.13f;}
    String diagnostics(Context context){
        return active?"modo leve · mídia Android ativa (sem leitura de batidas)":
            "modo leve · animação ambiente; CarPlay pode não informar mídia";
    }
    static int livelyColor(int rpmColor,int cyan,int blue,float energy,float high){
        float e=Math.max(0f,Math.min(1f,energy));
        int musical=CivicTheme.blend(cyan,blue,.25f+.72f*e);
        float influence=.15f+.36f*e+.09f*high;
        // Preserve amber under high engine RPM.
        if(Color.red(rpmColor)>145&&
            Color.red(rpmColor)>Color.blue(rpmColor)*1.4f)influence*=.12f;
        return CivicTheme.blend(rpmColor,musical,influence);
    }
}
