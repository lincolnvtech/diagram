# Civic Drive + Nova Total — telemetria privada V1

Estado: **ramo de integração isolado**. Não altera `latest.json`, APK, assinatura, workflow nem código em produção. O atualizador da central permanece na versão v2.0.1.

## Contrato de telemetria

Servidor Nova Civic: serviço independente em `127.0.0.1:4178`, com autenticação Bearer. **Nunca expor a porta diretamente à internet.** A central deve usar um endpoint HTTPS validado via proxy privado/VPN configurado depois.

`POST /api/civic/telemetry` (máximo de uma amostra a cada 3 segundos):

```json
{"rpm":2100,"speed_kmh":75,"coolant_c":86}
```

São opcionais `rpm`, `speed_kmh`, `coolant_c`, `fuel_pct`, `maf_gps`, `intake_c`, `voltage_v`. Sem GPS, VIN, endereço Bluetooth, matrícula, identificadores de motorista ou envio de códigos DTC. Somente sensores que o ELM327 efetivamente leu. Nada de interpolar valores indisponíveis.

`GET /api/civic/latest` devolve último registro, `age_sec` e `stale` (>45 s), e requer Bearer. Retenção local de 7 dias.

## Requisitos para o aplicativo

- Java nativo `br.com.civicdrive`; preservar OBD2/ELM327, dashboards, assinatura permanente e atualizador GitHub.
- Transmissão **desativada por padrão** e ativada por consentimento local na central.
- Token individual **não deve estar no GitHub, APK, logs ou código**; usar Android Keystore para armazenamento privado.
- Enviar por `HttpsURLConnection` fora da UI thread, sem seguir redirecionamentos de autorização, com timeouts curtos e amostras de 5–10 s.
- Não rodar em segundo plano sem consentimento; não enviar com OBD desconectado.
- Só ligar esse cliente ao APK depois de configurar HTTPS válido e compilar/validar a atualização. Nenhuma atualização do carro é disparada por este ramo.

## Segurança operacional

Instalador V1 muda apenas os novos caminhos `/opt/nova-civic`, `/etc/nova-civic`, `/var/lib/nova-civic` e `nova-civic.service`. Nova Total, Ollama, Matrix, Evolution e Nginx são preservados.
