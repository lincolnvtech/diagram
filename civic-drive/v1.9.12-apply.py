#!/usr/bin/env python3
"""Civic G8 v1.9.12: updater no-cache and visible installed/remote versions.
Applies on top of 1.9.11. Never changes vehicle sensors, gear logic or Android signing.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "build-civic")
def replace(path,old,new):
    s=path.read_text(encoding="utf-8")
    count=s.count(old)
    if count!=1:
        raise RuntimeError(f"{path.name}: expected anchor once; found {count}: {old[:85]!r}")
    path.write_text(s.replace(old,new,1),encoding="utf-8")

replace(root/"app/build.gradle",
        "versionCode 21\n        versionName '1.9.11'",
        "versionCode 22\n        versionName '1.9.12'")
replace(root/"app/src/main/java/br/com/civicdrive/MainActivity.java",
        'setTitle("Civic G8 1.9.11")','setTitle("Civic G8 1.9.12")')
f=root/"app/src/main/java/br/com/civicdrive/UpdateManager.java"
replace(f,
    '''                HttpURLConnection conn = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
                conn.setConnectTimeout(8500);
                conn.setReadTimeout(8500);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept", "application/json");''',
    '''                // Query unique on each check to avoid an older latest.json cached by
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
                conn.setRequestProperty("Pragma", "no-cache");''')
replace(f,
    '''                if (remoteCode <= installedVersionCode()) {
                    ui.post(() -> new AlertDialog.Builder(activity)
                            .setTitle("Civic Drive atualizado")
                            .setMessage("Você já está com a versão mais recente disponível.")
                            .setPositiveButton("OK", null).show());''',
    '''                long localCode = installedVersionCode();
                if (remoteCode <= localCode) {
                    ui.post(() -> new AlertDialog.Builder(activity)
                            .setTitle("Civic Drive atualizado")
                            .setMessage("Instalado: código " + localCode + "\\n"
                                  + "GitHub: " + versionName + " (código " + remoteCode + ").\\n\\n"
                                  + "Consulta realizada sem cache.")
                            .setPositiveButton("OK", null).show());''')
replace(f,
    '''                        .setMessage("Verifique a internet da central e tente novamente.\\n\\n" + message)''',
    '''                        .setMessage("Não foi possível consultar o GitHub nesta central. "
                                    + "Verifique a conexão e tente novamente.\\n\\nDiagnóstico: " + message)''')
print("Civic Drive 1.9.12 updater patched; real car data/OBD untouched.")
