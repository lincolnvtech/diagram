package br.com.civicdrive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Dados guardados offline. A exportação só acontece após toque explícito do usuário. */
public final class CarHistoryActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("Civic · Histórico local");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(3,9,18));root.setPadding(24,16,24,18);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header);
        TextView back=label("‹  VOLTAR",16);header.addView(back);back.setOnClickListener(v->finish());
        TextView title=label("  HISTÓRICO DO CIVIC",22);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView export=label("EXPORTAR DADOS",13);export.setPadding(20,10,0,10);header.addView(export);export.setOnClickListener(v->export());
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView info=label(TelemetryArchive.localSummary(this),18);info.setPadding(18,22,18,20);scroll.addView(info);
        TextView clear=label("APAGAR HISTÓRICO LOCAL",13);clear.setGravity(Gravity.CENTER);root.addView(clear);
        clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Apagar histórico do Civic?")
                .setMessage("Apaga amostras e último resultado de falhas guardados nesta central. Não altera a ECU.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Apagar",(d,w)->{TelemetryArchive.clear(this);info.setText("Histórico local apagado.");}).show());
        setContentView(root);
    }
    private void export(){new AlertDialog.Builder(this).setTitle("Compartilhar telemetria?")
            .setMessage("Exporta manualmente os dados do carro em JSON. Não inclui localização exata nem VIN. Confirme o aplicativo destinatário antes de enviar.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Compartilhar",(d,w)->{
                Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT,TelemetryArchive.exportJson(this));
                startActivity(Intent.createChooser(intent,"Compartilhar histórico do Civic"));
            }).show();}
    private TextView label(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(size);return t;}
}
