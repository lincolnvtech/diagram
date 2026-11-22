package br.com.civicdrive;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Histórico offline privado, uma amostra a cada dez minutos, sem coordenadas ou VIN. */
final class TelemetryArchive {
    private static final String FILE="civic_history.jsonl";
    private static final ExecutorService disk=Executors.newSingleThreadExecutor();
    private static final int MAX_SAMPLES=1008; // 7 dias × 24 horas × 6 amostras/hora
    private TelemetryArchive() { }
    static void record(Context context,long timestamp,float rpm,int coolant,float voltage,float speed,float kmL,
                       float map,float load,float iat,float trim) {
        Context app=context.getApplicationContext();
        disk.execute(()->{
            try {
                JSONObject j=new JSONObject();j.put("at",timestamp);
                if(rpm>=0)j.put("rpm",rpm);if(coolant>-100)j.put("ect_c",coolant);
                if(voltage>0)j.put("volts",voltage);if(speed>=0)j.put("gps_kmh",speed);
                if(kmL>=0)j.put("km_l_est",kmL);if(map>=0)j.put("map_kpa",map);
                if(load>=0)j.put("load_pct",load);if(iat>-100)j.put("iat_c",iat);
                if(trim>-200)j.put("stft_pct",trim);
                File f=new File(app.getFilesDir(),FILE);
                try(BufferedWriter out=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f,true),StandardCharsets.UTF_8))){
                    out.write(j.toString());out.newLine();
                }
                if(f.length()>150_000)compact(f);
            } catch(Exception ignored){ }
        });
    }
    private static void compact(File source) throws Exception {
        List<String> rows=new ArrayList<>();long limit=System.currentTimeMillis()-7L*24*3600_000L;
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(source),StandardCharsets.UTF_8))){
            String line;while((line=in.readLine())!=null){
                try {JSONObject o=new JSONObject(line);if(o.optLong("at",0)>=limit)rows.add(line);}catch(Exception ignored){}
                if(rows.size()>MAX_SAMPLES)rows.remove(0);
            }
        }
        File tmp=new File(source.getParentFile(),source.getName()+".tmp");
        try(BufferedWriter out=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),StandardCharsets.UTF_8))){
            for(String row:rows){out.write(row);out.newLine();}
        }
        if(!tmp.renameTo(source)){source.delete();tmp.renameTo(source);}
    }
    static String localSummary(Context c) {
        List<JSONObject> history=read(c,MAX_SAMPLES);
        if(history.isEmpty())return "Histórico local: ainda sem amostras OBD2. Ligue o carro com o ELM327 conectado.";
        int count=0;double totalTemp=0,peakTemp=-999,peakRpm=-1,sumKmL=0;int consumed=0;
        long first=0,last=0;
        for(JSONObject o:history){
            long at=o.optLong("at");if(first==0)first=at;last=at;
            if(o.has("ect_c")){double x=o.optDouble("ect_c");totalTemp+=x;count++;peakTemp=Math.max(peakTemp,x);}
            peakRpm=Math.max(peakRpm,o.optDouble("rpm",-1));
            if(o.has("km_l_est")){sumKmL+=o.optDouble("km_l_est");consumed++;}
        }
        StringBuilder b=new StringBuilder("Civic G8 · histórico LOCAL (somente leitura)\n");
        b.append("Amostras: ").append(history.size()).append("; período: ")
                .append(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(first)))
                .append(" a ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(last))).append("\n");
        if(count>0)b.append(String.format(Locale.getDefault(),"Arrefecimento: média %.1f °C; máxima %.0f °C\n",totalTemp/count,peakTemp));
        if(peakRpm>=0)b.append(String.format(Locale.getDefault(),"Rotação máxima observada: %.0f rpm\n",peakRpm));
        if(consumed>0)b.append(String.format(Locale.getDefault(),"Média de leituras de consumo estimado: %.1f km/L (não aferido)\n",sumKmL/consumed));
        String codes=c.getSharedPreferences("civic_faults",Context.MODE_PRIVATE).getString("summary","");
        if(!codes.isEmpty())b.append("Última leitura de falhas:\n").append(codes).append("\n");
        b.append("Sem GPS exato, VIN ou dados pessoais. Ainda não sincronizado com a Nova Total.");
        return b.toString();
    }
    static List<JSONObject> read(Context ctx,int maximum){
        List<JSONObject> rows=new ArrayList<>();File f=new File(ctx.getFilesDir(),FILE);
        if(!f.isFile())return rows;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null){
                try{rows.add(new JSONObject(line));if(rows.size()>maximum)rows.remove(0);}catch(Exception ignored){}
            }
        }catch(Exception ignored){}
        return rows;
    }
    static String exportJson(Context ctx){
        JSONArray array=new JSONArray();for(JSONObject row:read(ctx,MAX_SAMPLES))array.put(row);
        JSONObject result=new JSONObject();try{result.put("source","civic_drive");result.put("vehicle","Civic G8 2011");
            result.put("sample_period_seconds",600);result.put("samples",array);result.put("faults",ctx.getSharedPreferences("civic_faults",Context.MODE_PRIVATE).getString("summary",""));}
        catch(Exception ignored){}
        return result.toString();
    }
    static void clear(Context ctx) {disk.execute(()->{File f=new File(ctx.getApplicationContext().getFilesDir(),FILE);if(f.exists())f.delete();});
        ctx.getSharedPreferences("civic_faults",Context.MODE_PRIVATE).edit().clear().apply();}
}
