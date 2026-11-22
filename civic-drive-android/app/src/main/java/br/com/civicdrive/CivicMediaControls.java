package br.com.civicdrive;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Sends standard Android media-key events to the currently registered
 * Android playback session. This deliberately does not claim to control
 * audio played on the iPhone by Apple CarPlay or an OEM radio.
 */
final class CivicMediaControls {
    private final AudioManager audio;
    CivicMediaControls(Context context) {
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    }
    boolean isMusicActive() {
        try{return audio!=null&&audio.isMusicActive();}
        catch(RuntimeException ex){return false;}
    }
    boolean send(int keyCode) {
        if(audio==null)return false;
        if(keyCode!=KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
           keyCode!=KeyEvent.KEYCODE_MEDIA_NEXT &&
           keyCode!=KeyEvent.KEYCODE_MEDIA_PREVIOUS)return false;
        try {
            long at=SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(at,at,KeyEvent.ACTION_DOWN,keyCode,0));
            audio.dispatchMediaKeyEvent(new KeyEvent(at,at+25L,KeyEvent.ACTION_UP,keyCode,0));
            return true;
        } catch(RuntimeException | LinkageError ex) {
            return false;
        }
    }
}
