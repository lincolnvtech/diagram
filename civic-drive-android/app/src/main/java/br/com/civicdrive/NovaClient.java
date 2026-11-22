package br.com.civicdrive;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Ponte HTTPS com a Nova. Nunca fala diretamente com a porta do Ollama. */
final class NovaClient {
    interface Callback { void onResult(String answer, String error); }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    void send(String endpoint, String token, String message, String sessionId, Callback callback) {
        executor.execute(() -> {
            String answer = null;
            String error = null;
            HttpsURLConnection conn = null;
            try {
                String checked = NovaConfig.validateHttpsEndpoint(endpoint);
                conn = (HttpsURLConnection) new URL(checked).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(9000);
                conn.setReadTimeout(20000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                JSONObject payload = new JSONObject();
                payload.put("message", message);
                payload.put("session_id", sessionId);
                payload.put("source", "civic_drive");
                byte[] json = payload.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(json.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(json); }
                int http = conn.getResponseCode();
                if (http >= 300 && http < 400) throw new IOException("Redirecionamento bloqueado (HTTP " + http + ").");
                if (http == 401 || http == 403) throw new IOException("Acesso negado: confira o token da ponte Nova.");
                if (http != 200) throw new IOException("Servidor respondeu HTTP " + http + ".");
                try (InputStream in = conn.getInputStream()) {
                    JSONObject response = new JSONObject(readLimited(in));
                    answer = response.optString("reply", "");
                    if (answer.trim().isEmpty()) answer = response.optString("response", "");
                    if (answer.trim().isEmpty()) throw new IOException("Resposta JSON sem o campo reply.");
                    if (answer.length() > 6000) answer = answer.substring(0, 6000) + "…";
                }
            } catch (Exception e) {
                // Nunca registramos token nem texto da conversa nos logs.
                error = e instanceof javax.net.ssl.SSLException ? "Erro de certificado TLS. Verifique o HTTPS da Nova." :
                        e instanceof java.net.SocketTimeoutException ? "A Nova não respondeu a tempo." :
                        e instanceof java.net.UnknownHostException ? "Servidor Nova não encontrado. Confira rede e domínio." :
                        e instanceof IOException ? e.getMessage() : "Não foi possível conversar com a Nova.";
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String finalAnswer = answer;
            final String finalError = error;
            main.post(() -> { if (!closed) callback.onResult(finalAnswer, finalError); });
        });
    }

    private static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int read;
        while ((read = in.read(buf)) != -1) {
            if (out.size() + read > 65536) throw new IOException("Resposta da Nova excedeu o limite seguro.");
            out.write(buf, 0, read);
        }
        return out.toString("UTF-8");
    }

    void close() { closed = true; executor.shutdownNow(); }
}
