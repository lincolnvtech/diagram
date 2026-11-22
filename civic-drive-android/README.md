# Civic G8 — Launcher independente v1.8

Projeto Android nativo (Java) para multimídia Android do Honda Civic G8 2011. **Não é o Rota Launcher** nem modificação dele. A arte de referência aprovada é `app/src/main/res/drawable-nodpi/civic_ui_compacto.webp`.

## Implementado

- Tela horizontal 16:9 com Civic prata, fundo preto e detalhes em azul, botão de menu compacto.
- Áreas tocáveis para **Menu**, **Dashboards**, **Nova**, e conexão **OBD2** pelo bloco de status.
- Menu da central: aplicativos instalados, Maps, Waze, música, Bluetooth, conectar OBD2, atualizar pelo GitHub e ajustes.
- Velocidade por GPS (com permissão, sem leitura = `—`). Hora do Android real.
- OBD2 por ELM327 Bluetooth **clássico/SPP** (RPM 010C, motor 0105, velocidade 010D e tensão ATRV). Adaptadores BLE ou Wi-Fi não suportados neste protótipo.
- Consumo médio **estimado** por taxa de combustível 015E, se existir, senão por MAF 0110 (estequiometria para gasolina). Só exibe consumo quando há leitura de combustível e distância suficiente; parâmetros de álcool/flex, calibração e marcha lenta podem afetar o resultado. Dados não disponíveis ficam `—`.
- Dashboard secundário com métricas, sem números fictícios.
- Tela NOVA de texto preservada da v1.3, com conexão opcional HTTPS + token; **API real ainda não ligada**.
- Gerenciador de atualização GitHub preservado; procura por `civic-drive/latest.json` na branch `civic-drive-build` do repositório configurado. **Só funcionará após a publicação deste manifesto e de APK com assinatura consistente**. A instalação exige confirmação Android.

## Como compilar

Abrir a pasta do projeto no Android Studio, instalar SDK Android 35 e JDK 17, executar `gradle assembleDebug` ou Build > Build APK(s). APK de debug aparece em `app/build/outputs/apk/debug/`.

`gradle` precisa estar disponível no PATH; repositório não inclui Gradle Wrapper binário. Para instalar como atualização de uma versão existente, a assinatura digital deve ser exatamente a mesma — builds debug de máquinas ou runners diferentes não têm essa garantia.

## Assinatura e atualizações

Para atualizações sem desinstalar, criar e guardar uma chave de assinatura privada e configurar GitHub Actions com `secrets` adequados. **Não publicar chave privada no repositório.** Publicar APK assinado sempre com o mesmo keystore, e `civic-drive/latest.json` com:

```json
{
  "versionCode": 9,
  "versionName": "1.8.0",
  "apkUrl": "https://raw.githubusercontent.com/lincolnvtech/diagram/civic-drive-build/civic-drive/civic-drive-v1.8.apk",
  "sha256": "SHA256_REAL_DO_APK_DEPOIS_DA_COMPILACAO"
}
```

O `apkUrl` deve estar em HTTPS e pertencer à pasta já permitida pelo aplicativo.

## Segurança / limitações

- **Não testado** em central real, carro real ou adaptador ELM327.
- O app não interfere com ECU, câmbio ou ignição: só faz leituras OBD2 e abre apps Android.
- O painel do carro continua referência para dirigir.
- Não colocar token Nova embutido em APK ou GitHub.
- As imagens ilustram o Civic G8, não comprovam equipamento específico do carro.

## Alterações da v1.8
- Retirados do cabeçalho do wallpaper o nome CIVIC G8, relógio e ícones duplicados; preservada a logo Honda e todo o restante do visual.
- OBD2 reconecta automaticamente ao ELM327 previamente selecionado; no primeiro uso seleciona sozinho apenas quando houver um único adaptador OBD pareado, evitando escolha equivocada. OBD2 continua podendo ser selecionado no menu.
- Ao abrir o launcher tenta reconectar o adaptador; após desconexão faz novas tentativas com intervalo, sem necessidade de tocar no indicador.
- Bloco Motor passou a Arrefecimento, com valor ECT de PID 0105 e barra discreta, sem inventar temperatura sem leitura.
- Atualizador ainda exige confirmação do Android e **assinatura igual à versão instalada**. Para v1.8 e posteriores, configurar assinatura persistente em secrets GitHub para instalar em cima da v1.4. Debug key do runner não é estável.

## Alterações da v1.8
- Dashboards ganhou MAP 010B, carga calculada 0104, temperatura de admissão 010F e correção curta de combustível STFT banco 1 0106.
- Sensores avançados lidos em rodízio, um por ciclo, sem modificar ECU e sem alterar a home.
- Sensores não suportados apresentam traço, sem valor simulado.
- Ainda requer assinatura digital permanente via GitHub Actions Secrets para atualizar instalações anteriores sem desinstalar.

## Novidades v1.8
- Atalhos personalizáveis na HOME: 3 posições, qualquer atividade Android visível. Toque em + para escolher Rádio/Waze/outro. Menu → Adicionar atalho permite substituir.
- Catálogo de apps com busca e **ícones originais** dos aplicativos instalados. Alguns apps de fábrica sem atividade LAUNCHER exportada não podem ser abertos por apps externos.
- Dashboards com medidores digitais, detalhamento por toque, filtros e alertas preventivos em âmbar (limites exemplificativos, não diagnóstico).
- Diagnóstico OBD2: leitura dos modos 03, 07, 0A pelo ELM327 já conectado; **NÃO há comando para apagar códigos (modo 04)**. Nem todas as ECUs aceitam modo 0A.
- Memória offline privada: uma amostra de sensores por minuto enquanto ELM327 conectado; arquivo JSONL limitado, sem VIN/GPS exato. Histórico consultável/compartilhável com confirmação. Sem envio automático de telemetria.
- NOVA: botão DADOS DO CIVIC prepara um resumo para enviar explicitamente ao endpoint HTTPS autenticado configurado na tela da Nova. **O servidor Nova Total não possui nesta versão endpoint de ingestão/histórico do Civic: a persistência central remota ainda não existe**.
- Versão 9 / 1.8.0; release deve ser assinada com o mesmo certificado permanente da v1.7. NÃO gerar APK com chave debug diferente.

### Contrato futuro de integração Nova Total (a implementar no servidor)
`POST /api/civic/telemetry` com Authorization Bearer, Content-Type application/json e corpo {source:'civic_drive',vehicle:'Civic G8 2011',sample_period_seconds:60,samples:[...]}. Exigir HTTPS, token individual, rate limit, schema e limites de tamanho, persistência isolada e retenção definida. Não expor Ollama diretamente nem colocar token no código ou GitHub. Na v1.8 o usuário exporta dados manualmente ou envia RESUMO pela tela de chat já existente.

## v1.9.5

Visual full-tech aplicado nas páginas Velocímetro e Contagiros, seguindo as artes aprovadas. Sem telemetria de demonstração; OBD2 e identificação Android/assinatura preservados.

## v1.9.6

- Relógios com faixas visuais orientativas e explicação ao toque, sem inventar diagnósticos de MAP/carga/IAT.
- Marcha automática ESTIMADA por RPM + velocidade com as relações 5AT oficiais Honda Brasil e medida de pneu original 205/55R16. Confiança mínima, estabilização de 3 leituras e indicação — quando não confiável. PRND real permanece não lido. Não interfere na ECU.
- Menu YouTube/segundo plano: abre apps e configurações de bateria do Android; **não contorna a necessidade de Premium** para reprodução de músicas em segundo plano.
