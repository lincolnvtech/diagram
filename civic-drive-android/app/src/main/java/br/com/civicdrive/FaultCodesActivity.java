package br.com.civicdrive;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import java.text.DateFormat;
import java.util.Date;

/** Lê DTCs via a conexão OBD2 existente, SEM comandos de apagar falhas. */
public final class FaultCodesActivity extends Activity {
    private TextView result,refresh;
    private boolean running;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ObdManager.FaultCallback current;
    private final Runnable timeout=()->{
        if(!running)return;
        running=false;
        ObdManager m=ObdManager.shared();
        if(m!=null && current!=null)m.cancelFaultRead(current);
        current=null;
        refresh.setText("LER NOVAMENTE");
        result.setText("A ECU não respondeu em 30 s. Confira o ELM327 e tente novamente.");
    };
    private int dp(int value){
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(25),dp(16),dp(25),dp(22));root.setBackgroundColor(Color.rgb(3,9,18));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);root.addView(heading);
        TextView back=text("‹  VOLTAR",16);back.setPadding(0,0,dp(22),0);heading.addView(back);back.setOnClickListener(v->finish());
        TextView title=text("DIAGNÓSTICO OBD2",23);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        refresh=text("LER FALHAS",17);refresh.setPadding(dp(12),dp(12),dp(12),dp(12));refresh.setTextColor(Color.rgb(48,161,255));heading.addView(refresh);
        refresh.setOnClickListener(v->read());
        TextView note=text("Leitura de códigos armazenados, pendentes e permanentes (se a ECU suportar). Sem apagar falhas.",13);
        note.setTextColor(Color.rgb(147,178,204));note.setPadding(0,dp(20),0,dp(18));root.addView(note);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        result=text("Conecte o ELM327 na home e toque em LER FALHAS.",18);result.setPadding(dp(15),dp(10),dp(15),dp(12));scroll.addView(result);
        TextView explain=text("⚠ Códigos OBD2 não são um diagnóstico definitivo. Não apague falhas sem identificar a causa. Faça a leitura com o carro estacionado.",13);
        explain.setTextColor(Color.rgb(177,187,205));root.addView(explain);setContentView(root);
        String cached=getSharedPreferences("civic_faults",MODE_PRIVATE).getString("summary","");
        if(!cached.isEmpty())result.setText("Última leitura guardada:\n\n"+cached);
    }
    private void read(){
        if(running)return;
        ObdManager obd=ObdManager.shared();
        if(obd==null||!obd.isConnected()){
            new AlertDialog.Builder(this).setTitle("OBD2 desconectado")
                .setMessage("Volte à tela inicial e conecte o ELM327. Não use dois apps OBD2 no mesmo Bluetooth.")
                .setPositiveButton("Entendi",null).show();
            return;
        }
        ObdManager.FaultCallback callback=text->onFaultResult(text);
        current=callback;
        if(obd.requestFaultRead(callback)){
            running=true;
            refresh.setText("CONSULTANDO…");
            result.setText("Consultando os módulos OBD2…\nAguarde as respostas da ECU.");
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout,30000L);
        }else{
            current=null;
            result.setText("Consulta indisponível. Confira a conexão ou tente novamente.");
        }
    }
    private void onFaultResult(String text){
        if(!running || current==null || isFinishing() || isDestroyed())return;
        handler.removeCallbacks(timeout);
        current=null;
        running=false;
        refresh.setText("LER NOVAMENTE");
        String message=text==null?"Sem resposta da ECU.":text;
        String timestamp=DateFormat.getDateTimeInstance(
            DateFormat.SHORT,DateFormat.SHORT).format(new Date());
        String summary=timestamp+"\n"+message;
        result.setText(summary);
        // Preserve the last valid scan if a Bluetooth disconnect interrupted this one.
        boolean hasCodes=java.util.regex.Pattern.compile(
            "\\b[PCBU][0-3][A-F0-9]{4}\\b").matcher(message).find();
        if(hasCodes || message.contains("nenhum código retornado")){
            getSharedPreferences("civic_faults",MODE_PRIVATE)
                .edit().putString("summary",summary).apply();
        }
    }
    @Override protected void onDestroy(){
        handler.removeCallbacks(timeout);
        ObdManager manager=ObdManager.shared();
        if(manager!=null && current!=null)manager.cancelFaultRead(current);
        current=null;
        super.onDestroy();
    }
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
}
