# CIVIC G8 — DOWNLOAD DO APK

## [📥 BAIXAR CIVIC G8 — VERSÃO MAIS RECENTE (v1.9.8)](https://raw.githubusercontent.com/lincolnvtech/diagram/civic-drive-build/civic-drive/civic-drive-latest.apk)

**Versão atual:** 1.9.8 · Honda Civic G8 2011 · Android 7.0+.

### Como atualizar na central

**Civic Drive → Menu → Atualizar pelo GitHub → Baixar → Instalar.** A v1.9.8 usa a mesma assinatura permanente; instale por cima da 1.9.7, mantendo dados e configurações.

### O que foi corrigido na v1.9.8

- **Marcha estimada mais disponível:** o cálculo deixou de exigir janelas excessivamente rígidas. Ele usa RPM + velocidade e as relações originais do câmbio automático de cinco marchas.
- **GPS como alternativa:** quando a ECU/ELM327 não entrega velocidade OBD2 ou o dado contradiz fortemente GPS recente, o app pode estimar usando velocidade GPS válida.
- **No mínimo duas amostras consistentes** e velocidade de 12 km/h ou mais. Quando houver ambiguidade de marcha, patinação do conversor ou ausência de leitura, aparece um motivo em vez de só `— EST.`.
- **Diagnóstico na home:** toque em **Marcha estimada** na barra inferior para ver RPM, velocidade OBD2, velocidade GPS, fonte escolhida, confiança indicativa e o motivo se não houver marcha.
- **Home, visual Full Tech, animação Nova e reconexão OBD2:** preservados da versão 1.9.7.

**Importante:** indica **1ª a 5ª EST.** e não lê o seletor real P/R/N/D nem o acionamento das borboletas. Este é apenas um cálculo aproximado e pode estar errado, especialmente em aceleração, desaceleração, mudança de marcha ou pneus diferentes do original 205/55R16. Não substitui os instrumentos do veículo.

**Validação:** o código passou em testes locais de cálculo para cinco marchas, a compilação e a verificação de assinatura passaram no GitHub, e o SHA-256 do APK entregue confere com o manifesto. O desempenho precisa ser testado fisicamente na multimídia.

**Teste com segurança:** permita localização à central para habilitar a alternativa GPS. Não interaja com a tela enquanto dirige; consulte os diagnósticos com o carro parado.

[Baixar especificamente v1.9.8](https://raw.githubusercontent.com/lincolnvtech/diagram/civic-drive-build/civic-drive/civic-drive-v1.9.8.apk)
