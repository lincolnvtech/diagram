package br.com.civicdrive;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.LinkedHashSet;
import android.os.Handler;
import android.os.Looper;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ELM327 via Bluetooth clássico RFCOMM (SPP).
 * Leitura: RPM (010C), temperatura (0105), velocidade OBD (010D), tensão (ATRV).
 * Não suporta Bluetooth LE ou ELM327 Wi-Fi nesta primeira versão.
 */
public final class ObdManager {
    public interface Listener {
        void onStatus(String message, boolean connected);
        void onValues(double rpm, int coolant, float voltage, int speed);
        void onFuelRate(double litresPerHour);
        void onExtended(double mapKpa, double loadPct, double intakeC, double shortFuelTrimPct);
    }

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Listener listener;
    private volatile BluetoothSocket socket;
    private volatile Thread worker;
    private volatile boolean linkReady = false;
    private volatile boolean stopped = true;
    private volatile long generation = 0L;
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
    public interface FaultCallback { void completed(String result); }
    private volatile FaultCallback pendingFaultRead;
    private static volatile ObdManager shared;
    public static ObdManager shared() {return shared;}
    public boolean isConnected(){return linkReady && isActive();}
    public synchronized boolean requestFaultRead(FaultCallback callback){
        if(callback==null || !isConnected() || pendingFaultRead!=null)return false;
        pendingFaultRead=callback;return true;
    }
    public synchronized void cancelFaultRead(FaultCallback callback){
        if(callback!=null && pendingFaultRead==callback)pendingFaultRead=null;
    }
    public boolean isActive() { Thread t=worker;return !stopped && t!=null && t.isAlive(); }

    public ObdManager(Listener listener) { this.listener = listener; shared=this; }

    public synchronized void connect(BluetoothDevice device) {
        close();
        stopped = false;
        lastIssue="Conectando ao ELM327";
        connectStartedAt=android.os.SystemClock.elapsedRealtime();
        final long session=++generation;
        worker = new Thread(() -> runSession(device,session), "civic-elm327");
        worker.setDaemon(true);
        worker.start();
    }

    private void runSession(BluetoothDevice device,long session) {
        String deviceName;
        try { deviceName = device.getName(); }
        catch (SecurityException e) { deviceName = "OBD2"; }
        if (deviceName == null) deviceName = "OBD2";
        if (generation == session) notifyStatus("Conectando ao " + deviceName + "...", false);
        BluetoothSocket active = null;
        try {
            active = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket = active;
            try {
                active.connect();
            } catch (IOException secureFailure) {
                // Alguns clones de ELM327 pareados aceitam apenas RFCOMM insecure.
                // A tentativa ocorre somente se a conexão normal falhar.
                safeClose(active);
                if (stopped || generation != session) return;
                active = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                socket = active;
                active.connect();
            }
            if (stopped || generation != session) return;
            InputStream in = active.getInputStream();
            OutputStream out = active.getOutputStream();
            // Falha isolada de comando AT não deve desligar o Bluetooth inteiro.
            optionalCommand(out,in,"ATZ",5800);
            optionalCommand(out,in,"ATE0",1800);
            optionalCommand(out,in,"ATL0",1800);
            optionalCommand(out,in,"ATS0",1800);
            optionalCommand(out,in,"ATH0",1800);
            optionalCommand(out,in,"ATSP0",2500);
            optionalCommand(out,in,"ATAT1",1200);

            // OBD2 principal em todas as voltas; apenas UM sensor extra por volta.
            // Um PID ausente ou lento nunca derruba sozinho o ELM327.
            double[] extras = {-1, -1, -999, -999};
            double lastRpm=-1, lastFuel=-1;
            int lastTemp=-999, lastSpeed=-1;
            float lastVolts=-1;
            String[] extraPids = {"015E","0110","010B","0104","010F","0106"};
            long[] retryExtrasAt = new long[extraPids.length];
            // O ELM327 clonado pode ficar lento ou perder uma resposta sem perder o Bluetooth.
            // Menos comandos por ciclo e tolerância por tempo, não por 3 ciclos fixos.
            int cycle=0, optionalRound=0;
            long lastEcuAt=android.os.SystemClock.elapsedRealtime();
            long rpmAt=0, speedAt=0, tempAt=0, voltAt=0, fuelAt=0;
            boolean dataReady=false, directFuelSupported=false;
            while (!stopped && generation == session) {
                int n=cycle++;
                // RPM e velocidade são os dois valores principais. Temperatura e
                // tensão alternam; nunca consultar os quatro em todas as voltas.
                double rpm=parseRpm(optionalCommand(out,in,"010C",2500));
                int speed=parseSpeed(optionalCommand(out,in,"010D",2300));
                int temp=n%4==0?parseTemperature(optionalCommand(out,in,"0105",2000)):-999;
                float volts=n%12==0?parseVoltage(optionalCommand(out,in,"ATRV",1600)):-1;
                if (stopped || generation != session) break;
                long now=android.os.SystemClock.elapsedRealtime();
                if (rpm>=0) {lastRpm=rpm;rpmAt=now;}
                if (speed>=0) {lastSpeed=speed;speedAt=now;}
                if (temp>-100) {lastTemp=temp;tempAt=now;}
                if (volts>=0) {lastVolts=volts;voltAt=now;}
                // Não reaproveitar leitura antiga para estimar a marcha.
                if (rpmAt==0 || now-rpmAt>7500L) lastRpm=-1;
                if (speedAt==0 || now-speedAt>7500L) lastSpeed=-1;
                if (tempAt==0 || now-tempAt>20000L) lastTemp=-999;
                if (voltAt==0 || now-voltAt>40000L) lastVolts=-1;
                if (fuelAt==0 || now-fuelAt>120000L) lastFuel=-1;
                boolean ecuResponded=rpm>=0 || speed>=0 || temp>-100;
                if (ecuResponded) {
                    lastEcuAt=now;
                    if (!dataReady) {
                        dataReady=true;
                        linkReady=true;
                        connectStartedAt=0L;
                        lastIssue="Dados ECU recebidos";
                        notifyStatus("OBD2 conectado  •  dados reais",true);
                    }
                } else if (now-lastEcuAt>30000L) {
                    // Only prolonged silence should trigger reconnect, not 1-2 slow cycles.
                    throw new IOException("ECU sem respostas por 30s");
                } else if (now-lastEcuAt>9000L) {
                    lastIssue="ECU sem dados recentes ("+((now-lastEcuAt)/1000L)+"s)";
                }
                if (dataReady && generation==session)
                    listener.onValues(lastRpm,lastTemp,lastVolts,lastSpeed);
                // Extras: uma consulta a cada cinco ciclos, dando prioridade aos PIDs de condução.
                int which=optionalRound%extraPids.length;
                // Cheap ELM327 clones can lock up when too many unsupported PIDs
                // are requested. Prioritize stable RPM/speed; probe extras slowly.
                // No optional query in the initial handshake.
                boolean probeExtra=ecuResponded && n>=4 && n%12==4;
                if (probeExtra) optionalRound++;
                if (probeExtra && (which!=1 || !directFuelSupported) && System.currentTimeMillis()>=retryExtrasAt[which]) {
                    String result=optionalCommand(out,in,extraPids[which],1500);
                    if (result==null) retryExtrasAt[which]=System.currentTimeMillis()+45000L;
                    else if (result.toUpperCase(Locale.ROOT).contains("NO DATA") ||
                            result.toUpperCase(Locale.ROOT).contains("UNABLE TO CONNECT"))
                        retryExtrasAt[which]=System.currentTimeMillis()+300000L;
                    if (result!=null) {
                        if (which==0 || which==1) {
                            double rate=which==0?parseFuelRate(result):parseMafBasedFuelRate(result);
                            if (rate>=0) {
                                if(which==0)directFuelSupported=true;
                                lastFuel=rate;
                                fuelAt=android.os.SystemClock.elapsedRealtime();
                            }
                        } else {
                            int[] bytes=parsePid(result,extraPids[which].substring(2),1);
                            if (bytes!=null) {
                                if(which==2)extras[0]=bytes[0];
                                if(which==3)extras[1]=bytes[0]*100.0/255.0;
                                if(which==4)extras[2]=bytes[0]-40;
                                if(which==5)extras[3]=(bytes[0]-128)*100.0/128.0;
                            }
                        }
                    }
                }
                if (dataReady && generation == session) {
                    if(lastFuel>=0) listener.onFuelRate(lastFuel);
                    listener.onExtended(extras[0],extras[1],extras[2],extras[3]);
                }
                FaultCallback callback=pendingFaultRead;
                if(callback!=null){
                    String result=readFaultCodes(out,in);
                    boolean stillPending;
                    synchronized(this){
                        stillPending=pendingFaultRead==callback;
                        if(stillPending)pendingFaultRead=null;
                    }
                    if(stillPending){
                        new Handler(Looper.getMainLooper()).post(
                            ()->callback.completed(result));
                    }
                }
                // Moderate request rate keeps low-cost adapters responsive.
                Thread.sleep(ecuResponded?850L:1200L);
            }
        } catch (Exception e) {
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
        }
    }

    /** Somente leitura: 03 armazenados, 07 pendentes, 0A permanentes. NUNCA envia o modo 04. */
    private String readFaultCodes(OutputStream out, InputStream in){
        StringBuilder result=new StringBuilder("Leitura OBD2 · ECU do Civic\n");
        boolean can=false;
        try{
            can=DtcDecoder.isCanProtocol(command(out,in,"ATDPN",1500L));
        }catch(Exception ignored){
            // Fail closed to legacy non-CAN interpretation when protocol is unknown.
        }
        String[] modes={"03","07","0A"};
        String[] titles={"Armazenados (03)","Pendentes (07)","Permanentes (0A)"};
        for(int k=0;k<modes.length;k++){
            try {
                String raw=command(out,in,modes[k],4200);
                LinkedHashSet<String> codes=DtcDecoder.decode(raw,
                    k==0?"43":k==1?"47":"4A",can);
                result.append(titles[k]).append(": ");
                if(codes.isEmpty())result.append(raw.toUpperCase(Locale.ROOT).contains("NO DATA") ?
                        "sem resposta/não suportado" : raw.toUpperCase(Locale.ROOT).contains(k==0?"43":k==1?"47":"4A")?
                        "nenhum código retornado":"resposta indisponível");
                else result.append(android.text.TextUtils.join(", ",codes));
            }catch(Exception e){result.append(titles[k]).append(": falha na consulta");}
            result.append("\n");
        }
        result.append("\nConsulta somente leitura. Não apaga códigos nem substitui diagnóstico mecânico.");
        return result.toString();
    }
    /** Compatibility for legacy callers (non-CAN default). */
    static LinkedHashSet<String> decodeTroubleCodes(String raw,String marker){
        return DtcDecoder.decode(raw,marker,false);
    }

    /** Timeout de leitura opcional: falha pontual não significa Bluetooth desconectado. */
    private String optionalCommand(OutputStream out,InputStream in,String cmd,long timeoutMs) throws InterruptedException {
        try { return command(out,in,cmd,timeoutMs); }
        catch (IOException e) { return null; }
    }

    private String command(OutputStream out, InputStream in, String command, long timeoutMs) throws IOException, InterruptedException {
        if (stopped) throw new IOException("Conexão encerrada");
        // Limpa respostas atrasadas de comandos anteriores para não interpretar PID errado.
        int drained=0;
        while (in.available()>0 && drained++<1000) {
            if (in.read()<0) throw new IOException("Conexão finalizada");
        }
        out.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (!stopped && android.os.SystemClock.elapsedRealtime() < deadline && bytes.size() < 700) {
            while (in.available() > 0) {
                int c = in.read();
                if (c < 0) throw new IOException("Conexão finalizada");
                if (c == '>') return bytes.toString("US-ASCII");
                bytes.write(c);
            }
            Thread.sleep(35);
        }
        throw new IOException("ELM327 não respondeu: " + command);
    }

    private static int[] parsePid(String message, String pid, int count) {
        if (message == null) return null;
        // Remove espaços, quebras e cabeçalhos variáveis; exige resposta modo 41 + PID.
        String clean = message.replaceAll("(?i)[^0-9a-f]", "").toUpperCase(Locale.ROOT);
        int pos = clean.indexOf("41" + pid);
        if (pos < 0 || clean.length() < pos + 4 + count * 2) return null;
        int[] result = new int[count];
        try {
            for (int i=0;i<count;i++) result[i] = Integer.parseInt(clean.substring(pos + 4 + i * 2, pos + 6 + i * 2), 16);
            return result;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static double parseRpm(String text) {
        int[] b = parsePid(text, "0C", 2);
        return b == null ? -1 : (b[0] * 256.0 + b[1]) / 4.0;
    }
    static int parseTemperature(String text) {
        int[] b = parsePid(text, "05", 1);
        return b == null ? -999 : b[0] - 40;
    }
    static int parseSpeed(String text) {
        int[] b = parsePid(text, "0D", 1);
        return b == null ? -1 : b[0];
    }
    static float parseVoltage(String text) {
        // Bug da v1.9.6: timeout do ATRV devolvia null e derrubava
        // toda a sessão com NullPointerException.
        if (text==null || text.isEmpty()) return -1;
        Matcher m = Pattern.compile("([0-9]{1,2}(?:[.,][0-9]{1,3})?)\\s*V", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return -1;
        try { return Float.parseFloat(m.group(1).replace(',', '.')); }
        catch (NumberFormatException e) { return -1; }
    }

    static double parseFuelRate(String text) {
        int[] b = parsePid(text, "5E", 2);
        return b == null ? -1 : (b[0]*256.0+b[1])/20.0;
    }
    static double parseMafBasedFuelRate(String text) {
        int[] b = parsePid(text, "10", 2);
        if (b == null) return -1;
        double gramsPerSecond = (b[0]*256.0+b[1])/100.0;
        // Gasolina: AFR 14,7 e densidade 745 g/L (estimativa, não medição).
        return gramsPerSecond*3600.0/(14.7*745.0);
    }

    private void notifyStatus(String msg, boolean connected) { listener.onStatus(msg, connected); }
    public synchronized void close() {
        stopped = true;
        linkReady=false;
        connectStartedAt=0L;
        final FaultCallback pending=pendingFaultRead;
        pendingFaultRead=null;
        ++generation;
        safeClose(socket);
        socket = null;
        Thread old = worker;
        if (old != null) old.interrupt();
        worker = null;
        if(pending!=null){
            new Handler(Looper.getMainLooper()).post(
                ()->pending.completed("Leitura interrompida: a conexão com o ELM327 foi encerrada."));
        }
    }
    private static void safeClose(BluetoothSocket s) {
        if (s != null) try { s.close(); } catch (IOException ignore) { }
    }
}
