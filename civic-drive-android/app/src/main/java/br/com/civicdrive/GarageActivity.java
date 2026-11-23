package br.com.civicdrive;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Garage: editable original Civic vector, not a cropped photo.
 * Everything persists locally and works offline, even with OBD2 disconnected.
 */
public final class GarageActivity extends Activity {
    private static final String PREF="civic_garage";
    private static final int BG=0xff020407, CYAN=0xff10d7ff, MUTED=0xff91b5d0;
    private static final int[] COLORS={
        0xff10d7ff,0xffebf5ff,0xffa4b2bc,0xffe54759,0xff1c2836
    };
    private static final String[] NAMES={"NEON","BRANCO","PRATA","VERMELHO","GRAFITE"};
    private CivicCarView car;
    private SharedPreferences preferences;
    private int chosenColor, size, x, y;

    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private GradientDrawable panel(int fill,int outline){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(fill);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),outline);
        return bg;
    }
    private TextView label(String text,int size,int color){
        TextView view=new TextView(this);
        view.setText(text);view.setTextSize(size);view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private Button action(String value){
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(value);b.setTextColor(0xffeaf8ff);
        b.setTextSize(13);b.setBackground(panel(0xff061728,0xff145d91));
        return b;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        preferences=getSharedPreferences(PREF,MODE_PRIVATE);
        chosenColor=preferences.getInt("color",COLORS[0]);
        size=preferences.getInt("size",100);
        x=preferences.getInt("x",100);
        y=preferences.getInt("y",100);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16),dp(7),dp(16),dp(6));
        setContentView(root);

        LinearLayout title=new LinearLayout(this);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title,new LinearLayout.LayoutParams(-1,dp(42)));
        Button back=action("‹ VOLTAR");
        title.addView(back,new LinearLayout.LayoutParams(dp(112),dp(38)));
        back.setOnClickListener(v->finish());
        TextView heading=label("   GARAGEM  /  CIVIC G8   •   DESENHO AJUSTÁVEL",19,CYAN);
        heading.setTypeface(null,1);
        title.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
        Button reset=action("CENTRALIZAR");
        title.addView(reset,new LinearLayout.LayoutParams(dp(135),dp(38)));
        reset.setOnClickListener(v->reset());

        car=new CivicCarView(this);
        root.addView(car,new LinearLayout.LayoutParams(-1,0,1));
        syncCar();

        LinearLayout palette=new LinearLayout(this);
        palette.setGravity(Gravity.CENTER_VERTICAL);
        palette.setPadding(0,dp(3),0,dp(2));
        root.addView(palette,new LinearLayout.LayoutParams(-1,dp(52)));
        for(int i=0;i<COLORS.length;i++){
            final int value=COLORS[i];
            Button chip=action(NAMES[i]);
            chip.setTextSize(12);
            chip.setBackground(panel(i==0?0xff071b32:0xff0b111b,value));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(41),1f);
            p.setMargins(dp(3),0,dp(3),0);
            palette.addView(chip,p);
            chip.setOnClickListener(v->{chosenColor=value;syncCar();save();});
        }

        root.addView(adjust("TAMANHO",size,55,160,value->{size=value;syncCar();save();}),
            new LinearLayout.LayoutParams(-1,dp(45)));
        root.addView(adjust("HORIZONTAL",x,0,200,value->{x=value;syncCar();save();}),
            new LinearLayout.LayoutParams(-1,dp(45)));
        root.addView(adjust("ALTURA",y,0,200,value->{y=value;syncCar();save();}),
            new LinearLayout.LayoutParams(-1,dp(45)));

        TextView foot=label("Configurações salvas no aparelho  •  Não altera a ECU nem a foto de fundo.",12,MUTED);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot,new LinearLayout.LayoutParams(-1,dp(24)));
    }
    private interface Changed{void set(int value);}
    private LinearLayout adjust(String name,int current,int min,int max,Changed changed){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=label(name,13,0xffd7f2ff);
        row.addView(label,new LinearLayout.LayoutParams(dp(115),-1));
        SeekBar seek=new SeekBar(this);
        seek.setMax(max-min);
        seek.setProgress(Math.max(0,Math.min(max-min,current-min)));
        row.addView(seek,new LinearLayout.LayoutParams(0,-1,1));
        TextView reading=label(String.valueOf(current),13,CYAN);
        reading.setGravity(Gravity.CENTER);
        row.addView(reading,new LinearLayout.LayoutParams(dp(50),-1));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean user){
                if(user){int value=progress+min;reading.setText(String.valueOf(value));changed.set(value);}
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        return row;
    }
    private void reset(){
        size=100;x=100;y=100;
        save();recreate();
    }
    private void syncCar(){
        if(car==null)return;
        car.setCarColor(chosenColor);
        car.setZoom(size/100f);
        car.setOffset((x-100)/100f*.75f,(y-100)/100f*.75f);
    }
    private void save(){
        preferences.edit().putInt("color",chosenColor).putInt("size",size)
            .putInt("x",x).putInt("y",y).apply();
    }
}
