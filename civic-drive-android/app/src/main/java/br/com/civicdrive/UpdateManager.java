package br.com.civicdrive;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Consulta somente o manifesto de atualizações do repositório do projeto.
 * O APK é verificado por SHA-256. A instalação é sempre confirmada pelo usuário
 * no instalador oficial do Android. Não há instalação silenciosa.
 */
public final class UpdateManager {
    private static final String ROOT =
            "https://raw.githubusercontent.com/lincolnvtech/diagram/civic-drive-build/civic-drive/";
    private static final String MANIFEST_URL = ROOT + "latest.json";
    private static final String PREFS = "civic_updater";
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DownloadManager downloads;
    private final SharedPreferences prefs;
    private boolean resumed;
    private boolean verifying;
    private boolean checking;
    private final Runnable watcher = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            inspectDownload();
            ui.postDelayed(this, 1400);
        }
    };

    UpdateManager(Activity activity) {
        this.activity = activity;
        this.downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void onResume() {
        resumed = true;
        ui.removeCallbacks(watcher);
        ui.post(watcher);
    }

    void onPause() {
        resumed = false;
        ui.removeCallbacks(watcher);
    }
    private boolean alive(){
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    /** Silent startup check: success is cached for 12h; network errors retry after 10m. */
    void checkForUpdatesIfDue() {
        if (checking) return;
        long now=System.currentTimeMillis();
        long lastSuccess=prefs.getLong("auto_last_success",0L);
        long lastAttempt=prefs.getLong("auto_last_attempt",0L);
        if(!UpdatePolicy.isAutoCheckDue(now,lastSuccess,lastAttempt))return;
        prefs.edit().putLong("auto_last_attempt",now).apply();
        checkForUpdates(true);
    }

    void checkForUpdates() { checkForUpdates(false); }

    private void checkForUpdates(boolean silent) {
        if (checking) return;
        checking = true;
        if(!silent && alive())
            Toast.makeText(activity,"Procurando atualização no GitHub…",
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // Query unique on each check to avoid an older latest.json cached by
                // Android, an ISP proxy or GitHub CDN. Keep the trusted ROOT unchanged.
                String freshManifest = MANIFEST_URL + "?civic_check=" + System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection) new URL(freshManifest).openConnection();
                conn.setConnectTimeout(8500);
                conn.setReadTimeout(8500);
                conn.setInstanceFollowRedirects(true);
                conn.setUseCaches(false);
                conn.setDefaultUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
                conn.setRequestProperty("Pragma", "no-cache");
                int status = conn.getResponseCode();
                if (status != 200) throw new Exception("Servidor respondeu " + status);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream stream = conn.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = stream.read(buffer)) != -1) {
                        if (output.size() + n > 16384) throw new Exception("Manifesto muito grande");
                        output.write(buffer, 0, n);
                    }
                } finally { conn.disconnect(); }
                JSONObject json = new JSONObject(output.toString("UTF-8"));
                int remoteCode = json.getInt("versionCode");
                String versionName = json.getString("versionName");
                String url = json.getString("apkUrl");
                String sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
                if (remoteCode < 1 || !UpdatePolicy.isTrustedApkUrl(url,ROOT) ||
                        !sha256.matches("[0-9a-f]{64}")) {
                    throw new Exception("Manifesto inválido ou endereço não autorizado");
                }
                prefs.edit().putLong("auto_last_success",System.currentTimeMillis()).apply();
                long localCode = installedVersionCode();
                if (remoteCode <= localCode) {
                    if (!silent) ui.post(() -> {
                        if(!alive())return;
                        new AlertDialog.Builder(activity)
                            .setTitle("Civic Drive atualizado")
                            .setMessage("Instalado: código " + localCode + "\n"
                                  + "GitHub: " + versionName + " (código " + remoteCode + ").\n\n"
                                  + "Consulta realizada sem cache.")
                            .setPositiveButton("OK", null).show();
                    });
                } else {
                    ui.post(() -> {
                        if(!alive())return;
                        new AlertDialog.Builder(activity)
                            .setTitle("Atualização " + versionName)
                            .setMessage("Nova versão disponível no GitHub. Baixar e abrir o instalador Android? " +
                                    "A instalação precisa da sua confirmação.")
                            .setNegativeButton("Agora não", null)
                            .setPositiveButton("BAIXAR", (d, w) -> beginDownload(url, sha256)).show();
                    });
                }
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Erro desconhecido" : e.getMessage();
                if (!silent) ui.post(() -> {
                    if(!alive())return;
                    new AlertDialog.Builder(activity)
                        .setTitle("Não foi possível atualizar")
                        .setMessage("Não foi possível consultar o GitHub nesta central. "
                                    + "Verifique a conexão e tente novamente.\n\nDiagnóstico: " + message)
                        .setPositiveButton("OK", null).show();
                });
            } finally {
                ui.post(() -> checking = false);
            }
        }, "civic-update-check").start();
    }

    private long installedVersionCode() throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void beginDownload(String url, String sha256) {
        if (downloads == null) {
            Toast.makeText(activity, "Gerenciador de downloads indisponível", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            long previousId = prefs.getLong("download_id", -1);
            if (previousId > 0) downloads.remove(previousId);
            // DownloadManager does not overwrite a downloaded APK that remains
            // after the previous successful update.
            File dir=activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File stale=dir==null?null:new File(dir,"civic-drive-update.apk");
            if(stale!=null && stale.isFile() && !stale.delete())
                throw new Exception("Não foi possível substituir o APK antigo");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Atualização Civic Drive");
            request.setDescription("Baixando APK oficial do repositório do Civic Drive");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    "civic-drive-update.apk");
            long id = downloads.enqueue(request);
            prefs.edit().putLong("download_id", id).putString("sha256", sha256).apply();
            Toast.makeText(activity, "Baixando… aguarde nesta tela", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity, "Não foi possível iniciar download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void inspectDownload() {
        long id = prefs.getLong("download_id", -1);
        if (id < 0 || downloads == null || verifying) return;
        try (android.database.Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if(cursor==null)return;
            if(!cursor.moveToFirst()){
                prefs.edit().remove("download_id").remove("sha256").apply();
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                Uri uri = downloads.getUriForDownloadedFile(id);
                String hash = prefs.getString("sha256", "");
                verifying = true;
                new Thread(() -> {
                    boolean ok=false;
                    String error=null;
                    try (InputStream in=activity.getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new Exception("Arquivo não encontrado");
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] buffer = new byte[32768];
                        int n;
                        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
                        StringBuilder sb = new StringBuilder();
                        for (byte b : digest.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                        ok = sb.toString().equals(hash);
                    }catch(Exception ex){
                        error=ex.getMessage()==null?"Falha de leitura":ex.getMessage();
                    }
                    final boolean verified=ok;
                    final String detail=error;
                    ui.post(()->{
                        verifying=false;
                        prefs.edit().remove("download_id").remove("sha256").apply();
                        if(!verified){
                            try{downloads.remove(id);}catch(Exception ignored){}
                        }
                        if(!alive())return;
                        if(!verified){
                            new AlertDialog.Builder(activity).setTitle("Download rejeitado")
                                .setMessage(detail!=null?
                                    "Não foi possível verificar o APK: "+detail:
                                    "O arquivo não passou na verificação SHA-256.")
                                .setPositiveButton("OK",null).show();
                        }else{
                            new AlertDialog.Builder(activity)
                                .setTitle("APK pronto para instalar")
                                .setMessage("Confirme a instalação na próxima tela do Android. Se o Android impedir, autorize a instalação para o Civic Drive nas configurações.")
                                .setNegativeButton("Depois",null)
                                .setPositiveButton("INSTALAR",(dialog,which)->openInstaller(uri))
                                .show();
                        }
                    });
                }, "civic-update-sha256").start();
            } else if (status == DownloadManager.STATUS_FAILED) {
                prefs.edit().remove("download_id").remove("sha256").apply();
                if(alive())Toast.makeText(activity,"Download falhou. Verifique a conexão e tente novamente.",
                    Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) { }
    }

    private void openInstaller(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity).setTitle("Permitir instalação")
                        .setMessage("Ative 'Permitir desta fonte' para Civic Drive e depois toque em Atualizar novamente.")
                        .setPositiveButton("ABRIR CONFIGURAÇÕES", (d, w) -> {
                            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            try{activity.startActivity(i);}
                            catch(Exception ex){
                                if(alive())Toast.makeText(activity,
                                    "Configurações indisponíveis: autorize a instalação manualmente.",
                                    Toast.LENGTH_LONG).show();
                            }
                        }).setNegativeButton("Cancelar", null).show();
                return;
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
        } catch (Exception e) {
            Toast.makeText(activity, "Não foi possível abrir o instalador: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
