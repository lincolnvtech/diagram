#!/usr/bin/env python3
"""Civic Drive v2.0.1: visible 4 cockpit palettes, accurate OBD2 status,
generation-safe Bluetooth sessions and a stuck-connect recovery watchdog.
Read-only ECU data and all signed-release identifiers are preserved.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv)>1 else "build-civic")
java = root/"app/src/main/java/br/com/civicdrive"

def replace(path, old, new):
    s=path.read_text(encoding="utf-8")
    hits=s.count(old)
    if hits!=1:
        raise RuntimeError(f"{path.name}: unique anchor required, got {hits}: {old[:85]!r}")
    path.write_text(s.replace(old,new,1),encoding="utf-8")

replace(root/"app/build.gradle",
        "versionCode 23\n        versionName '2.0.0'",
        "versionCode 24\n        versionName '2.0.1'")

main=java/"MainActivity.java"
replace(main,
        'else if(x>1525 && y<110)showSettings();',
        'else if(x>1525 && y<110)showMenu();')
replace(main,
'''                (d,which)->{ if(surface!=null)surface.useWallpaper(which);d.dismiss(); })
            .setNegativeButton("Cancelar",null).show();
    }
    private void openRadio(){''',
'''                (d,which)->{ if(surface!=null)surface.useWallpaper(which);d.dismiss(); })
            .setNeutralButton("4 TEMAS DE CORES",(d,w)->showThemes())
            .setNegativeButton("Cancelar",null).show();
    }
    private void openRadio(){''')
replace(main,
'''    private void showThemes(){
        new AlertDialog.Builder(this).setTitle("Civic Drive · Aparência")
            .setSingleChoiceItems(CivicTheme.NAMES,CivicTheme.index(this),(dialog,index)->{
                CivicTheme.select(this,index);
                if(surface!=null)surface.invalidate();
                dialog.dismiss();
            }).setNegativeButton("Fechar",null).show();
    }

    private void showMenu() {''',
'''    private void showThemes(){
        new AlertDialog.Builder(this).setTitle("Civic Drive · 4 temas de cores")
            .setSingleChoiceItems(CivicTheme.NAMES,CivicTheme.index(this),(dialog,index)->{
                CivicTheme.select(this,index);
                if(surface!=null)surface.invalidate();
                Toast.makeText(this,"Tema "+CivicTheme.NAMES[index]+" aplicado",Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }).setNegativeButton("Fechar",null).show();
    }

    /** Safe local diagnostic: no Bluetooth address, private token or ECU commands. */
    private void showObdDiagnostics(){
        String mode=obdConnected?"Conectado":obd!=null && obd.isActive()?"Conectando":"Desconectado";
        String last=obd==null?"Adaptador não iniciado":obd.diagnostics();
        String message="Estado: "+mode+"\\n"
            +"Último evento: "+last+"\\n"
            +"Reconexão automática: "+(autoObdEnabled?"ativada":"desativada")+"\\n"
            +"RPM: "+(rpm>=0?String.format(PT,"%.0f",rpm):"sem leitura")+"\\n"
            +"Temperatura: "+(coolant>-100?coolant+" °C":"sem leitura")+"\\n\\n"
            +"Com a ignição ligada, confira se o ELM327 está pareado no Bluetooth. "
            +"Se o carro estiver desligado, a ECU pode não responder.";
        new AlertDialog.Builder(this).setTitle("Civic Drive · Diagnóstico OBD2")
            .setMessage(message)
            .setNeutralButton("SELECIONAR ADAPTADOR",(d,w)->chooseObdDevice())
            .setPositiveButton("Fechar",null).show();
    }

    private void showMenu() {''')
replace(main,
'''            .setItems(new String[]{"Trocar ELM327", "Atualizar GitHub", "Configurar Nova", "Reiniciar média de consumo", "Conexão OBD2 automática", "Atalhos da home", "Plano de fundo", "Definir tela inicial", "Configurações Android", "Permitir GPS", "Sobre"},
                (d,which)->{
                    switch(which){
                        case 0:chooseObdDevice();break;''',
'''            .setItems(new String[]{"TEMAS · 4 CORES", "DIAGNÓSTICO OBD2", "Trocar ELM327", "Atualizar GitHub", "Configurar Nova", "Reiniciar média de consumo", "Conexão OBD2 automática", "Atalhos da home", "Plano de fundo", "Definir tela inicial", "Configurações Android", "Permitir GPS", "Sobre"},
                (d,which)->{
                    if(which==0){showThemes();return;}
                    if(which==1){showObdDiagnostics();return;}
                    switch(which-2){
                        case 0:chooseObdDevice();break;''')
replace(main,
        'new AlertDialog.Builder(this).setTitle("Civic G8 2.0.0")',
        'new AlertDialog.Builder(this).setTitle("Civic G8 2.0.1")')

replace(main,
'''            // Reconnect only with a paired ELM327 or a previously selected adapter.
            tryAutoObd();''',
'''            // A BT socket can remain blocked in connect() on older head units.
            // Abort only if no ECU data ever arrived for 42 seconds.
            if(obd!=null && obd.abortStalledConnection(42000L))
                nextObdRetryMs=System.currentTimeMillis()+12000L;
            // Reconnect only with a paired ELM327 or a previously selected adapter.
            tryAutoObd();''')

replace(main,
'''                    obdStatus = connected ? "Conectado" :
                            (status != null && status.startsWith("Conectando") ? "Conectando…" : "Desconectado");''',
'''                    obdStatus = connected ? "Conectado" :
                        status != null && status.startsWith("Conectando") ? "Conectando…" :
                        status != null && status.contains("ECU") ? "ECU sem dados" :
                        status != null && status.contains("permissão") ? "Sem permissão BT" :
                        status != null && status.contains("expirou") ? "Tempo esgotado" :
                        "Desconectado";''')

obd=java/"ObdManager.java"
replace(obd,
'''    private volatile long generation = 0L;
    public interface FaultCallback''',
'''    private volatile long generation = 0L;
    private volatile long connectStartedAt = 0L;
    private volatile String lastIssue = "Aguardando adaptador ELM327";
    public String diagnostics() { return lastIssue; }

    /** Stop a platform socket stalled before the first ECU reply; caller retries later. */
    public synchronized boolean abortStalledConnection(long timeoutMs) {
        Thread t=worker;
        long at=connectStartedAt;
        if(stopped || linkReady || t==null || !t.isAlive() || at<=0L ||
                android.os.SystemClock.elapsedRealtime()-at<timeoutMs) return false;
        lastIssue="Conexão Bluetooth expirou antes de receber dados";
        close();
        notifyStatus("OBD2 • conexão expirou",false);
        return true;
    }
    public interface FaultCallback''')
replace(obd,
'''        close();
        stopped = false;
        final long session=++generation;''',
'''        close();
        stopped = false;
        lastIssue="Conectando ao ELM327";
        connectStartedAt=android.os.SystemClock.elapsedRealtime();
        final long session=++generation;''')
replace(obd,
'''                    if (!dataReady) {
                        dataReady=true;
                        linkReady=true;
                        notifyStatus("OBD2 conectado  •  dados reais",true);
                    }''',
'''                    if (!dataReady) {
                        dataReady=true;
                        linkReady=true;
                        connectStartedAt=0L;
                        lastIssue="Dados ECU recebidos";
                        notifyStatus("OBD2 conectado  •  dados reais",true);
                    }''')
replace(obd,
'''        } catch (Exception e) {
            if (!stopped && generation == session) {
                String reason = e instanceof SecurityException ? "permissão Bluetooth negada" : "sem resposta do adaptador";
                notifyStatus("OBD2 desconectado  •  " + reason, false);
            }
        } finally {
            linkReady=false;
            safeClose(active);
            if (socket == active) socket = null;
            if (!stopped && generation == session) {
                worker = null;
                notifyStatus("OBD2 desconectado", false);
            }
        }''',
'''        } catch (Exception e) {
            if (!stopped && generation == session) {
                String detail=e.getMessage()==null?"":e.getMessage();
                lastIssue=e instanceof SecurityException ? "Permissão Bluetooth negada" :
                    detail.contains("ECU sem respostas") ? "ECU sem respostas OBD2" :
                    e instanceof IOException ? "Falha Bluetooth/ELM327: "+detail :
                    "Adaptador não respondeu";
            }
        } finally {
            // An older thread MUST NOT clear the ready state of a newer session.
            if (generation == session) linkReady=false;
            safeClose(active);
            if (socket == active) socket = null;
            if (!stopped && generation == session) {
                connectStartedAt=0L;
                worker = null;
                notifyStatus("OBD2 desconectado • "+lastIssue, false);
            }
        }''')
replace(obd,
'''        stopped = true;
        linkReady=false;
        pendingFaultRead=null;''',
'''        stopped = true;
        linkReady=false;
        connectStartedAt=0L;
        pendingFaultRead=null;''')

print("Civic Drive 2.0.1: menus, palettes, OBD diagnostics, BT session safety patched.")
print("ELM327 read-only protocol and car firmware untouched.")
