package br.com.civicdrive;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Two real navigation paths. Native Android Waze needs the head unit's location
 * providers; CPLink/CarPlay uses the iPhone route. No fake GPS coordinates,
 * proxy, firmware changes, or attempt to inject iPhone location into Android.
 */
final class CivicNavigation {
    private static final String PREFS="civic_navigation";
    private static final String MODE="mode";
    private static final String NATIVE="native";
    private static final String CARPLAY="carplay";

    private CivicNavigation(){}

    static void openPreferred(Activity activity){
        String preference=activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE)
            .getString(MODE,"");
        if(NATIVE.equals(preference))openNativeWaze(activity);
        else if(CARPLAY.equals(preference))openCarPlay(activity);
        else showChooser(activity);
    }

    static void showChooser(Activity activity){
        final String[] choices={
            "Waze instalado na central (GPS Android)",
            "Waze no iPhone via CPLink / CarPlay",
            "Diagnóstico GPS da central"
        };
        new AlertDialog.Builder(activity)
            .setTitle("Civic Drive · Navegação")
            .setMessage("Escolha onde o Waze vai funcionar. CarPlay e GPS Android são caminhos diferentes.")
            .setItems(choices,(dialog,which)->{
                if(which==2){showGpsDiagnostics(activity);return;}
                String mode=which==1?CARPLAY:NATIVE;
                activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE)
                    .edit().putString(MODE,mode).apply();
                if(which==1)openCarPlay(activity);
                else openNativeWaze(activity);
            }).setNegativeButton("Voltar",null).show();
    }

    static void openNativeWaze(Activity activity){
        try{
            Intent installed=activity.getPackageManager().getLaunchIntentForPackage("com.waze");
            if(installed!=null){activity.startActivity(installed);return;}
            Intent uri=new Intent(Intent.ACTION_VIEW,Uri.parse("waze://"));
            uri.addCategory(Intent.CATEGORY_BROWSABLE);
            if(uri.resolveActivity(activity.getPackageManager())!=null){
                activity.startActivity(uri);return;
            }
            Toast.makeText(activity,"Waze Android não instalado; tentando site oficial",Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://waze.com/ul")));
        }catch(Exception e){
            Toast.makeText(activity,"Waze Android indisponível nesta central",Toast.LENGTH_LONG).show();
        }
    }

    private static boolean matches(String input) {
        if(input==null)return false;
        String s=input.toLowerCase(Locale.ROOT).replace(" ","");
        return s.contains("cplink") || s.contains("carlink") ||
            s.contains("carplay") || s.contains("zlink") ||
            s.contains("tlink") || s.contains("autokit");
    }
    private static int score(ResolveInfo item,PackageManager pm){
        String label=String.valueOf(item.loadLabel(pm)).toLowerCase(Locale.ROOT);
        String pkg=item.activityInfo.packageName.toLowerCase(Locale.ROOT);
        if(label.contains("cplink")||pkg.contains("cplink"))return 100;
        if(label.contains("carplay"))return 90;
        if(label.contains("carlink")||pkg.contains("carlink"))return 80;
        return 30;
    }

    static void openCarPlay(Activity activity){
        try {
            PackageManager pm=activity.getPackageManager();
            Intent query=new Intent(Intent.ACTION_MAIN);
            query.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> matches=new ArrayList<>();
            for(ResolveInfo candidate:pm.queryIntentActivities(query,0)){
                ActivityInfo a=candidate.activityInfo;
                if(a==null)continue;
                if(matches(a.packageName)||matches(a.name)||
                    matches(String.valueOf(candidate.loadLabel(pm))))matches.add(candidate);
            }
            if(matches.isEmpty()){
                new AlertDialog.Builder(activity)
                    .setTitle("CPLink não localizado")
                    .setMessage("Não encontrei um launcher CPLink/CarPlay instalado no Android. "
                        +"O Waze do iPhone depende da conexão CarPlay; não é possível "
                        +"transformar automaticamente o GPS do iPhone em GPS da central.")
                    .setPositiveButton("Abrir apps",(d,w)->
                        activity.startActivity(new Intent(activity,AppCatalogActivity.class)))
                    .setNegativeButton("Fechar",null).show();
                return;
            }
            Collections.sort(matches,new Comparator<ResolveInfo>(){
                @Override public int compare(ResolveInfo a,ResolveInfo b){
                    return Integer.compare(score(b,pm),score(a,pm));
                }
            });
            if(matches.size()==1||score(matches.get(0),pm)>score(matches.get(1),pm)){
                launch(activity,matches.get(0));
                return;
            }
            CharSequence[] names=new CharSequence[matches.size()];
            for(int i=0;i<matches.size();i++)
                names[i]=matches.get(i).loadLabel(pm);
            new AlertDialog.Builder(activity)
                .setTitle("Escolha o aplicativo CarPlay")
                .setItems(names,(dialog,which)->launch(activity,matches.get(which)))
                .setNegativeButton("Cancelar",null).show();
        }catch(Exception e){
            Toast.makeText(activity,"Não foi possível abrir CPLink",Toast.LENGTH_LONG).show();
        }
    }

    private static void launch(Activity activity,ResolveInfo info){
        try{
            Intent intent=Intent.makeMainActivity(
                new ComponentName(info.activityInfo.packageName,info.activityInfo.name));
            activity.startActivity(intent);
        }catch(Exception e){
            Toast.makeText(activity,"CPLink não permitiu abrir por atalho",Toast.LENGTH_LONG).show();
        }
    }

    private static boolean available(LocationManager m,String provider){
        try{return m!=null&&m.getAllProviders().contains(provider);}
        catch(Exception e){return false;}
    }
    private static boolean enabled(LocationManager m,String provider){
        try{return available(m,provider)&&m.isProviderEnabled(provider);}
        catch(Exception e){return false;}
    }
    static void showGpsDiagnostics(Activity activity){
        LocationManager lm=(LocationManager)activity.getSystemService(Activity.LOCATION_SERVICE);
        boolean fine=activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            ==PackageManager.PERMISSION_GRANTED;
        boolean coarse=activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            ==PackageManager.PERMISSION_GRANTED;
        boolean gpsPresent=available(lm,LocationManager.GPS_PROVIDER);
        boolean gpsOn=enabled(lm,LocationManager.GPS_PROVIDER);
        boolean netPresent=available(lm,LocationManager.NETWORK_PROVIDER);
        boolean netOn=enabled(lm,LocationManager.NETWORK_PROVIDER);

        StringBuilder message=new StringBuilder();
        message.append("GPS físico Android: ")
            .append(gpsPresent?(gpsOn?"ativado":"desativado"):"não anunciado pelo sistema").append("\n");
        message.append("Localização por rede: ")
            .append(netPresent?(netOn?"ativada":"desativada"):"não disponível").append("\n");
        message.append("Permissão de localização: ")
            .append(fine?"precisa":coarse?"aproximada":"não autorizada").append("\n");
        Location location=null;
        if(lm!=null&&(fine||coarse)){
            try{
                if(fine&&gpsOn)location=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if(location==null&&netOn)location=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }catch(SecurityException ignored){}
        }
        if(location==null){
            message.append("Última posição: nenhuma leitura disponível\n");
        }else{
            long ageMs=Math.max(0L,SystemClock.elapsedRealtime()-
                location.getElapsedRealtimeNanos()/1000000L);
            message.append("Última posição: há ").append(ageMs/1000L).append(" s");
            if(location.hasAccuracy())
                message.append(" · precisão ~").append(Math.round(location.getAccuracy())).append(" m");
            message.append("\nFonte: ").append(location.getProvider()).append("\n");
        }
        message.append("\nWaze Android precisa da localização fornecida pela central. "
            +"Waze em CPLink/CarPlay funciona pelo caminho do iPhone; um não "
            +"habilita automaticamente o GPS do outro.");
        new AlertDialog.Builder(activity)
            .setTitle("Civic Drive · GPS e CarPlay")
            .setMessage(message.toString())
            .setPositiveButton("Localização Android",(d,w)->{
                try{activity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));}
                catch(Exception e){Toast.makeText(activity,"Ajustes de localização indisponíveis",Toast.LENGTH_LONG).show();}
            })
            .setNeutralButton(!fine&&!coarse?"Permitir localização":"Abrir CarPlay",(d,w)->{
                if(!fine&&!coarse && Build.VERSION.SDK_INT>=23)
                    activity.requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION},67);
                else openCarPlay(activity);
            })
            .setNegativeButton("Fechar",null).show();
    }
}
