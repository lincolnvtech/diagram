# CIVIC G8 — DOWNLOAD DO APK

## [📥 BAIXAR CIVIC G8 — VERSÃO MAIS RECENTE (v1.9.7)](https://raw.githubusercontent.com/lincolnvtech/diagram/civic-drive-build/civic-drive/civic-drive-latest.apk)

**Versão atual:** 1.9.7 · Honda Civic G8 2011 · Android 7.0+.

**Atualizar na multimídia:** no Civic Drive, escolha **Menu → Atualizar pelo GitHub → Baixar → Instalar**. A v1.9.7 usa a mesma assinatura digital permanente da v1.9.6; instale por cima sem desinstalar.

### O que mudou na v1.9.7

- **Correção específica no OBD2:** quando a leitura de tensão `ATRV` expirava, uma resposta ausente podia gerar exceção e derrubar toda a conexão Bluetooth. Agora essa ausência não provoca fechamento da sessão.
- **Consultas mais leves:** RPM e velocidade mantidos como prioridade; arrefecimento e tensão em rodízio; sensores extras consultados com menor frequência.
- **Tolerância:** pequenas falhas de leitura não provocam reconexão imediata. Após perda prolongada de comunicação, o aplicativo ainda tenta reconectar automaticamente.
- **Dados antigos não são usados na marcha estimada:** RPM/velocidade sem leitura recente ficam indisponíveis.
- **Marcha na home:** a faixa inferior agora mostra OBD2 | **MARCHA ESTIMADA** | CONSUMO. Exemplo: `3ª EST.`; sem confiança, mostra `— EST.`. A temperatura de arrefecimento permanece no mostrador esquerdo.
- **Home, estilo Full Tech, painéis e assinatura:** preservados.

**Atenção:** a marcha é calculada a partir do RPM e velocidade fornecidos pelo ELM327, não pelo módulo da transmissão. Em baixa velocidade, patinação do conversor e trocas, ela pode desaparecer ou ficar incerta. A posição real PRND não é lida.

**Validação:** compilação, assinatura e SHA-256 passaram no GitHub Actions, mas a estabilidade Bluetooth e o comportamento na central física ainda precisam ser testados. Feche o Torque Pro e outros aplicativos que possam estar usando o mesmo adaptador ELM327 durante o teste. Não opere a multimídia dirigindo.

[Baixar especificamente o APK v1.9.7](https://raw.githubusercontent.com/lincolnvtech/diagram/civic-drive-build/civic-drive/civic-drive-v1.9.7.apk)
