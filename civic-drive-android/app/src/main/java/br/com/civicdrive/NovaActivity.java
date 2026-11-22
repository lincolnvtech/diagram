package br.com.civicdrive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

/** Interface local de texto. Sem microfone permanente, sem telemetria, sem enviar GPS. */
public final class NovaActivity extends Activity {
    private static final int BG = Color.rgb(6, 13, 23);
    private static final int CYAN = Color.rgb(44, 216, 245);
    private static final int GRAY = Color.rgb(150, 172, 185);
    private NovaConfig config;
    private NovaClient client;
    private EditText messageInput;
    private TextView state;
    private TextView sendButton;
    private ScrollView chatScroll;
    private LinearLayout messages;
    private String sessionId;
    private boolean busy;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        config = new NovaConfig(this);
        client = new NovaClient();
        sessionId = getPreferences(MODE_PRIVATE).getString("session_id", "");
        if (sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            getPreferences(MODE_PRIVATE).edit().putString("session_id", sessionId).apply();
        }
        buildInterface();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(15));
        root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top);
        TextView brand = text("NOVA  /  CIVIC", 23, CYAN, true);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView configure = button("CONFIGURAR", CYAN);
        top.addView(configure, new LinearLayout.LayoutParams(dp(135), dp(47)));
        configure.setOnClickListener(v -> configure());
        TextView car = button("DADOS DO CIVIC", CYAN);
        LinearLayout.LayoutParams carParams=new LinearLayout.LayoutParams(dp(150),dp(47));
        carParams.leftMargin=dp(8);top.addView(car,carParams);
        car.setOnClickListener(v -> carSummary());

        state = text("", 12, GRAY, false);
        state.setPadding(0, dp(8), 0, dp(9));
        root.addView(state);
        updateStatus();

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatScroll.setBackground(card());
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(14), dp(12), dp(14), dp(12));
        chatScroll.addView(messages);
        root.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addBubble(false, "Sou a interface da Nova para o Civic. Configure sua ponte HTTPS para falar com o cérebro privado. Este painel continua funcionando offline.");

        TextView safety = text("CONFIGURAÇÃO E CONVERSA SOMENTE COM O VEÍCULO PARADO.", 10, GRAY, true);
        safety.setPadding(0, dp(12), 0, dp(7));
        root.addView(safety);

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(composer);
        messageInput = new EditText(this);
        messageInput.setTextColor(Color.WHITE);
        messageInput.setHintTextColor(GRAY);
        messageInput.setTextSize(15);
        messageInput.setSingleLine(false);
        messageInput.setMaxLines(3);
        messageInput.setHint("Mensagem para a Nova...");
        messageInput.setBackground(card());
        messageInput.setPadding(dp(13), dp(8), dp(13), dp(8));
        composer.addView(messageInput, new LinearLayout.LayoutParams(0, dp(60), 1));
        sendButton = button("ENVIAR", CYAN);
        LinearLayout.LayoutParams sendP = new LinearLayout.LayoutParams(dp(110), dp(59));
        sendP.leftMargin = dp(8);
        composer.addView(sendButton, sendP);
        sendButton.setOnClickListener(v -> send());
    }

    private void carSummary(){
        final String summary=TelemetryArchive.localSummary(this);
        new AlertDialog.Builder(this).setTitle("Memória local do Civic")
                .setMessage(summary+"\n\nEsta memória está apenas na central. A Nova Total no servidor ainda não recebeu os dados.")
                .setNegativeButton("Fechar",null)
                .setNeutralButton("VER HISTÓRICO",(d,w)->startActivity(new android.content.Intent(this,CarHistoryActivity.class)))
                .setPositiveButton("PREPARAR ENVIO À NOVA",(d,w)->{
                    messageInput.setText(summary.length()>1450?summary.substring(0,1450):summary);
                    messageInput.setSelection(messageInput.length());
                    android.widget.Toast.makeText(this,"Revise e toque ENVIAR para compartilhar com sua Nova configurada.",android.widget.Toast.LENGTH_LONG).show();
                }).show();
    }

    private void updateStatus() {
        if (state != null) state.setText(config.url().isEmpty() || !config.hasToken()
            ? "NOVA NÃO CONFIGURADA  ·  Sem envio de dados" : "NOVA CONFIGURADA  ·  HTTPS / conexão sob demanda");
    }

    private void send() {
        if (busy) return;
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;
        if (message.length() > 1500) {
            Toast.makeText(this, "Mensagem muito longa (máx. 1500 caracteres)", Toast.LENGTH_LONG).show();
            return;
        }
        if (config.url().isEmpty() || !config.hasToken()) {
            configure();
            return;
        }
        final String token;
        try { token = config.token(); }
        catch (Exception e) {
            Toast.makeText(this, "Token protegido indisponível. Salve outro token.", Toast.LENGTH_LONG).show();
            configure();
            return;
        }
        addBubble(true, message);
        messageInput.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(messageInput.getWindowToken(), 0);
        busy = true;
        sendButton.setText("···");
        state.setText("Aguardando resposta da Nova...");
        client.send(config.url(), token, message, sessionId, (reply, error) -> {
            busy = false;
            sendButton.setText("ENVIAR");
            updateStatus();
            if (error != null) addBubble(false, "Não consegui conectar: " + error);
            else addBubble(false, reply);
        });
    }

    private void configure() {
        if (busy) {
            Toast.makeText(this, "Aguarde o término desta mensagem", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(22), 0, dp(22), 0);
        TextView urlLabel = text("URL COMPLETA DA PONTE NOVA (HTTPS)", 11, GRAY, true);
        fields.addView(urlLabel);
        EditText endpoint = new EditText(this);
        endpoint.setText(config.url());
        endpoint.setTextSize(14);
        endpoint.setSingleLine(true);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setHint("https://seu-dominio/api/civic/chat");
        fields.addView(endpoint);
        TextView tokenLabel = text("TOKEN BEARER (VAZIO = MANTER ATUAL)", 11, GRAY, true);
        tokenLabel.setPadding(0, dp(11), 0, 0);
        fields.addView(tokenLabel);
        EditText token = new EditText(this);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSingleLine(true);
        token.setHint(config.hasToken() ? "Token já salvo com proteção" : "Cole o token privado");
        fields.addView(token);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Conexão privada com a Nova")
                .setMessage("Informe o endpoint HTTPS autenticado que será configurado no seu servidor. A chave não aparece na tela e fica protegida no Android Keystore.")
                .setView(fields)
                .setPositiveButton("Salvar", null)
                .setNeutralButton("Apagar dados", (d, w) -> {
                    config.clear();
                    updateStatus();
                    Toast.makeText(this, "Conexão Nova apagada desta central", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            try {
                config.save(endpoint.getText().toString(), token.getText().toString());
                updateStatus();
                Toast.makeText(this, "Configuração salva. Você já pode testar.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                endpoint.setError(e.getMessage());
            } catch (Exception e) {
                Toast.makeText(this, "Falha ao proteger token nesta central; não foi salvo.", Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private void addBubble(boolean fromUser, String content) {
        TextView line = text(content, 15, fromUser ? Color.WHITE : CYAN, false);
        line.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = card();
        if (fromUser) bg.setColor(Color.rgb(26, 63, 83));
        line.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = fromUser ? Gravity.END : Gravity.START;
        lp.bottomMargin = dp(10);
        messages.addView(line, lp);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private GradientDrawable card() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(13, 27, 44));
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), Color.rgb(28, 60, 80));
        return drawable;
    }

    private TextView button(String label, int color) {
        TextView t = text(label, 12, color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(card());
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private TextView text(String content, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private int dp(float d) { return (int) (getResources().getDisplayMetrics().density * d + 0.5f); }

    @Override protected void onDestroy() {
        if (client != null) client.close();
        super.onDestroy();
    }
}
