package br.com.civicdrive;

import android.os.SystemClock;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/**
 * Civic Drive -> Nova Civic Bridge v1.
 * Read-only outbound sensors. Disabled until explicit opt-in.
 * Does not read VIN, GPS, Bluetooth IDs, fault codes or send ECU commands.
 * Does not store credentials: host app must use Android Keystore.
 */
public final class NovaCivicTelemetryClient {
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean enabled = false;
    private volatile long nextSendAt = 0L;
    private volatile String status = "Desativado";

    public void setEnabled(boolean optIn) {
        enabled = optIn;
        if (!optIn) status = "Desativado";
    }
    public String getStatus() { return status; }
    public void close() { enabled = false; queue.shutdownNow(); }

    public void sendIfDue(String httpsBase, String bearerToken,
            Double rpm, Double kmh, Double coolantC, Double fuelPercent,
            Double mafGramsPerSec) {
        if (!enabled || httpsBase == null || bearerToken == null || bearerToken.length() < 30) return;
        final String trimmed = httpsBase.trim();
        if (!trimmed.startsWith("https://") || trimmed.contains("@") || trimmed.contains("#")) {
            status = "URL HTTPS inválida";
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < nextSendAt || !busy.compareAndSet(false, true)) return;
        nextSendAt = now + 6000L;
        JSONObject sample = new JSONObject();
        try {
            put(sample, "rpm", rpm, 0, 9000);
            put(sample, "speed_kmh", kmh, 0, 260);
            put(sample, "coolant_c", coolantC, -40, 155);
            put(sample, "fuel_pct", fuelPercent, 0, 100);
            put(sample, "maf_gps", mafGramsPerSec, 0, 300);
            if (sample.length() == 0) {
                busy.set(false);
                status = "Sem dados OBD2 válidos";
                return;
            }
        } catch (Exception ex) {
            busy.set(false);
            status = "Amostra inválida";
            return;
        }
        try {
            queue.execute(() -> {
                HttpsURLConnection c = null;
                try {
                    URL u = new URL(trimmed.replaceAll("/+$", "") + "/api/civic/telemetry");
                    if (!"https".equalsIgnoreCase(u.getProtocol()) || u.getUserInfo() != null)
                        throw new IllegalArgumentException("URL não autorizada");
                    c = (HttpsURLConnection)u.openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(3500);
                    c.setReadTimeout(3500);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("Authorization", "Bearer " + bearerToken);
                    c.setDoOutput(true);
                    byte[] body = sample.toString().getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = c.getOutputStream()) { out.write(body); }
                    int code = c.getResponseCode();
                    status = code == 202 ? "Nova Civic sincronizada" :
                            code == 401 ? "Token da Nova inválido" :
                            code == 429 ? "Aguardando nova coleta" :
                            "Ponte indisponível (HTTP " + code + ")";
                    if (code == 429) nextSendAt = SystemClock.elapsedRealtime() + 12000L;
                } catch (Exception ignored) {
                    status = "Sem acesso HTTPS à Nova";
                } finally {
                    if (c != null) c.disconnect();
                    busy.set(false);
                }
            });
        } catch (Exception e) {
            busy.set(false);
            status = "Envio indisponível";
        }
    }

    private static void put(JSONObject j, String key, Double value, double lo, double hi) throws Exception {
        if (value != null && !value.isNaN() && !value.isInfinite() && value >= lo && value <= hi)
            j.put(key, Math.round(value * 100.0) / 100.0);
    }
}
