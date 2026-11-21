#!/usr/bin/env python3
"""Civic Drive 2.0.0: quiet automatic OTA checks + Waze deep-link fallback.
Built on the verified 1.9.12 base. Keeps OBD2, gear estimator, dashboards,
four themes, trip data, and permanent signing unchanged.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "build-civic")
def replace(path,old,new):
    s=path.read_text(encoding="utf-8")
    n=s.count(old)
    if n!=1:
        raise RuntimeError(f"{path}: expected exactly one anchor, found {n}: {old[:100]!r}")
    path.write_text(s.replace(old,new,1),encoding="utf-8")

replace(root/"app/build.gradle",
        "versionCode 22\n        versionName '1.9.12'",
        "versionCode 23\n        versionName '2.0.0'")

main=root/"app/src/main/java/br/com/civicdrive/MainActivity.java"
replace(main,
'''    @Override protected void onResume(){super.onResume();dashboardOpen = false;if(surface!=null){surface.resumeAnimation();}if(gpsAllowed())startGps();if(updates!=null)updates.onResume();''',
'''    @Override protected void onResume(){super.onResume();dashboardOpen = false;if(surface!=null){surface.resumeAnimation();}if(gpsAllowed())startGps();if(updates!=null){updates.onResume();updates.checkForUpdatesIfDue();}''')
replace(main,
'''    private void openWaze(){
        openAppOrWebsite("com.waze","https://waze.com/ul");
    }''',
'''    private void openWaze(){
        // Try Waze's own URI first. Some head units expose the app's deep-link
        // handler but not a normal launcher activity.
        try {
            Intent deepLink=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("waze://"));
            deepLink.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(deepLink);
            return;
        } catch(Exception ignored) { }
        // Fallback to the installed launcher, then the official universal URL.
        openAppOrWebsite("com.waze","https://waze.com/ul");
    }''')
replace(main,
        'new AlertDialog.Builder(this).setTitle("Civic G8 1.9.12")',
        'new AlertDialog.Builder(this).setTitle("Civic G8 2.0.0")')

updater=root/"app/src/main/java/br/com/civicdrive/UpdateManager.java"
replace(updater,
'''    void checkForUpdates() {
        if (checking) return;''',
'''    /** Silent startup check: success is cached for 12h; network errors retry after 10m. */
    void checkForUpdatesIfDue() {
        if (checking) return;
        long now=System.currentTimeMillis();
        long lastSuccess=prefs.getLong("auto_last_success",0L);
        long lastAttempt=prefs.getLong("auto_last_attempt",0L);
        if (now-lastSuccess < 12L*60L*60L*1000L ||
                now-lastAttempt < 10L*60L*1000L) return;
        prefs.edit().putLong("auto_last_attempt",now).apply();
        checkForUpdates(true);
    }

    void checkForUpdates() { checkForUpdates(false); }

    private void checkForUpdates(boolean silent) {
        if (checking) return;''')
replace(updater,
'''                long localCode = installedVersionCode();
                if (remoteCode <= localCode) {
                    ui.post(() -> new AlertDialog.Builder(activity)
                            .setTitle("Civic Drive atualizado")
                            .setMessage("Instalado: código " + localCode + "\\n"
                                  + "GitHub: " + versionName + " (código " + remoteCode + ").\\n\\n"
                                  + "Consulta realizada sem cache.")
                            .setPositiveButton("OK", null).show());
                } else {''',
'''                prefs.edit().putLong("auto_last_success",System.currentTimeMillis()).apply();
                long localCode = installedVersionCode();
                if (remoteCode <= localCode) {
                    if (!silent) ui.post(() -> new AlertDialog.Builder(activity)
                            .setTitle("Civic Drive atualizado")
                            .setMessage("Instalado: código " + localCode + "\\n"
                                  + "GitHub: " + versionName + " (código " + remoteCode + ").\\n\\n"
                                  + "Consulta realizada sem cache.")
                            .setPositiveButton("OK", null).show());
                } else {''')
replace(updater,
'''                ui.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("Não foi possível atualizar")
                        .setMessage("Não foi possível consultar o GitHub nesta central. "
                                    + "Verifique a conexão e tente novamente.\\n\\nDiagnóstico: " + message)
                        .setPositiveButton("OK", null).show());''',
'''                if (!silent) ui.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("Não foi possível atualizar")
                        .setMessage("Não foi possível consultar o GitHub nesta central. "
                                    + "Verifique a conexão e tente novamente.\\n\\nDiagnóstico: " + message)
                        .setPositiveButton("OK", null).show());''')

print("Civic Drive 2.0.0 applied: automatic silent update checks + Waze deep link.")
print("OBD2, gear estimation, themes, dashboards and telemetry sources unchanged.")
