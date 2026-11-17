# Webhooks com Entrega Confiável

Os dois lados de um webhook na mesma aplicação. O **emissor** assina cada
requisição com HMAC, entrega com retry e backoff exponencial, guarda o que não
conseguiu entregar numa fila de falhas e permite reenviar. O **receptor**
verifica a assinatura sobre os bytes recebidos, recusa requisições antigas,
deduplica pelo id do evento e responde rápido, deixando o processamento para
depois.

A ideia central é que webhook é entrega **pelo menos uma vez**. O emissor não
tem como saber se uma requisição que estourou o timeout foi processada, então
repete. Quem garante que o efeito aconteça uma vez só é o receptor. As medições
mostram o que acontece quando um dos lados não cumpre a sua parte.

## Tecnologias

- **Java 21** e **Spring Boot 3.5** com Spring MVC
- **PostgreSQL 16**, com acesso por `JdbcTemplate`
- **Flyway** para versionar o schema
- `java.net.http.HttpClient` com virtual threads para as entregas
- **Testcontainers** para subir o PostgreSQL real nos testes de integração
- **WireMock** fazendo o papel do receptor externo nos testes do emissor
- **Gradle** (Kotlin DSL) com wrapper

## Assinatura

O formato é o do [Standard Webhooks](https://www.standardwebhooks.com/):

```
webhook-id:        msg_87dd82ab61cd4801b76424e5e0a4932b
webhook-timestamp: 1794930000
webhook-signature: v1,<base64 do HMAC-SHA256 de "id.timestamp.corpo">
```

- O id e o timestamp entram no conteúdo assinado. Se só o corpo fosse assinado,
  quem capturasse uma requisição poderia reenviá-la com um timestamp novo.
- O segredo tem a forma `whsec_<base64>` e a chave do HMAC são os bytes
  decodificados, não o texto.
- A comparação é feita com `MessageDigest.isEqual`, que percorre todos os bytes
  mesmo quando o primeiro já difere. `String.equals` para no primeiro byte
  diferente e revela, pelo tempo de resposta, quantos bytes iniciais estão
  certos.
- O receptor verifica o **corpo cru**. Um JSON desserializado e serializado de
  novo muda espaços, escapes e números (`1e2` vira `100.0`, `café` vira
  `café`), e a assinatura deixa de bater. Um teste mostra cada caso.
- Na rotação de segredo, o emissor passa a assinar com os dois segredos durante
  a carência (24 h por padrão), em entradas separadas por espaço no mesmo
  cabeçalho. O receptor aceita qualquer segredo da sua lista, e assim nenhum dos
  dois lados precisa trocar no mesmo instante.

## Emissor

- `POST /events` grava o evento e uma entrega para cada assinatura ativa **na
  mesma transação**. O corpo é serializado uma única vez, e todas as tentativas
  enviam os mesmos bytes. Entre tentativas mudam apenas o timestamp e a
  assinatura.
- O despachante reivindica entregas com `FOR UPDATE SKIP LOCKED` e marca cada
  uma com um **lease**. Em seguida faz o commit e envia **fora de transação**.
  Manter a linha travada durante uma chamada de rede prenderia uma conexão do
  banco ao receptor mais lento da lista.
- Se a instância morre no meio de um envio, o lease expira e outra instância
  reivindica a entrega. O número da tentativa funciona como *fencing token*: o
  desfecho só é gravado se a linha ainda estiver naquela tentativa. Assim, uma
  instância que estava apenas lenta não sobrescreve o resultado de quem assumiu.
- A resposta decide o que acontece com a entrega:

  | Resposta | Desfecho |
  |---|---|
  | 2xx | entregue |
  | 408, 429, 5xx, timeout, erro de rede | nova tentativa com backoff |
  | 3xx | falha, sem seguir o redirect |
  | 410 | falha, e a assinatura é desativada |
  | outros 4xx | falha, sem nova tentativa |

- Backoff exponencial (base × 2^(tentativa−1), com teto) e jitter configurável:
  `NONE` mantém a curva exata e `FULL` sorteia entre zero e a curva.
  `Retry-After` funciona como piso, nunca acima do teto.
- Quem esgota as tentativas fica `FAILED`, e essa é a fila de falhas.
  `POST /deliveries/{id}/redeliver` e `POST /deliveries/redeliver-failed`
  colocam as entregas de volta na fila com o **mesmo** `webhook-id`, para que o
  receptor reconheça o que já processou.
- Proteção contra SSRF: antes de cada tentativa, o host é resolvido e endereços
  de loopback, privados, link-local (incluindo `169.254.169.254`) e de CGNAT são
  recusados. A verificação acontece a cada envio, não só no cadastro, porque a
  resposta do DNS pode mudar. Para rodar localmente, `application.yml` libera
  esses endereços com `webhooks.sender.allow-private-targets: true`.

## Receptor

- `POST /inbound/webhooks` recebe o corpo como `byte[]`. A verificação segue a
  ordem do mais barato para o mais caro: primeiro os cabeçalhos presentes,
  depois o timestamp dentro da tolerância (5 min, nos dois sentidos) e por fim o
  HMAC.
- Depois de verificar, a requisição é gravada no inbox com o `webhook-id` como
  chave primária. A primeira cópia recebe `202`. As seguintes recebem `200` com
  `duplicate`, e não `409`, porque qualquer resposta diferente de sucesso faz o
  emissor tentar de novo. Quem decide entre duas cópias concorrentes é a chave
  primária, não um select seguido de insert.
- A janela de replay e a idempotência se complementam. Dentro da janela, um
  replay é barrado pelo id já visto; fora dela, pelo timestamp. A retenção dos
  ids processados (7 dias por padrão) precisa cobrir todo o horizonte de retry
  do emissor e o reenvio manual. Um reenvio carrega timestamp novo, e se o id
  já tiver sido esquecido o evento é processado de novo. Um teste reproduz esse
  caso.
- O processamento é assíncrono. Cada worker trava um evento, executa o handler
  e marca como processado na mesma transação. Se o handler escreve no mesmo
  banco, o efeito e a marcação são confirmados juntos. O agendador repõe
  workers nos slots livres a cada ciclo, de modo que todos continuam
  trabalhando mesmo com chegada contínua.
- `POST /inbound/naive` é o contraexemplo: verifica a assinatura, mas processa
  dentro da requisição e não deduplica.
- `PUT /inbound/admin/behavior` injeta falhas (status e duração, com
  `Retry-After` opcional) e muda o tempo do handler. Existe só para as
  demonstrações.

## Como executar

Pré-requisitos: Docker e JDK 21.

```bash
docker compose up -d
./gradlew bootJar
```

As medições usam o receptor e o emissor em processos separados sobre o mesmo
banco:

```bash
java -jar build/libs/webhooks-delivery-1.0.0.jar --server.port=8081 \
  --webhooks.sender.dispatcher-enabled=false
java -jar build/libs/webhooks-delivery-1.0.0.jar --server.port=8080 \
  --webhooks.receiver.processor-enabled=false
```

### Exemplos

Criar uma assinatura apontando para o receptor, com o segredo que ele conhece:

```bash
curl -X POST localhost:8080/subscriptions -H 'Content-Type: application/json' \
  -d '{"url":"http://localhost:8081/inbound/webhooks",
       "secret":"whsec_CkBAddq2t4kj8wFTSVnO9b5rk7VUZIEFv1a7cC6UPX4="}'
```

Publicar um evento e consultar as entregas:

```bash
curl -X POST localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"type":"order.paid","data":{"orderId":42}}'
curl 'localhost:8080/deliveries?status=FAILED'
curl localhost:8080/deliveries/1/attempts
```

Chamar o receptor diretamente, assinando com `openssl`:

```bash
ID=msg_demo; TS=$(date +%s); BODY='{"type":"order.paid","data":{"orderId":7}}'
KEY=$(echo 'CkBAddq2t4kj8wFTSVnO9b5rk7VUZIEFv1a7cC6UPX4=' | base64 -d | xxd -p -c 256)
SIG="v1,$(printf '%s.%s.%s' "$ID" "$TS" "$BODY" \
  | openssl dgst -sha256 -mac HMAC -macopt hexkey:$KEY -binary | base64)"
curl -X POST localhost:8081/inbound/webhooks -H 'Content-Type: application/json' \
  -H "webhook-id: $ID" -H "webhook-timestamp: $TS" -H "webhook-signature: $SIG" \
  --data-raw "$BODY"
```

Repetir o mesmo comando devolve `{"status":"duplicate"}`. Alterar um byte do
corpo, usar um timestamp com mais de 5 minutos ou mandar o JSON reformatado
devolve `401`.

Simular uma queda do receptor por 30 segundos:

```bash
curl -X PUT localhost:8081/inbound/admin/behavior -H 'Content-Type: application/json' \
  -d '{"failStatus":503,"failForSeconds":30,"retryAfterSeconds":5}'
```

## Testes

```bash
./gradlew test
```

São 71 testes. Os de integração usam um único container PostgreSQL e um único
WireMock, iniciados por inicializador estático e compartilhados por todas as
classes. O agendamento fica desligado no perfil de teste, e o despachante e o
inbox são acionados pelos próprios testes. Os cenários cobrem a classificação de
cada resposta, `Retry-After`, o esgotamento seguido de reenvio, o lease expirado
com o dono antigo impedido de gravar, quatro despachantes concorrentes sem
tentativa duplicada, rotação de segredo, replay dentro e fora da janela, vinte
cópias simultâneas do mesmo evento com exatamente uma aceita, a quebra da
assinatura por reserialização, a guarda de SSRF e o paralelismo do inbox sob
chegada contínua.

## Medições

Setup: todas as medições foram feitas numa única sessão, com o jar empacotado
(`bootJar`, JDK 21), o daemon do Gradle parado e o PostgreSQL 16 em container,
tudo no mesmo host. O receptor roda na porta 8081 com 8 workers e ciclo de
200 ms. O emissor roda na 8080 com timeout de 2 s, 5 tentativas e backoff de
500 ms dobrando a cada tentativa, com teto de 30 s. Antes das medições, uma
rodada de aquecimento de 300 eventos foi descartada. O cliente de carga é um
script em Python que usa só a biblioteca padrão, e os números saem das tabelas
de tentativas do emissor e do log de requisições do receptor.

### Responder antes de processar

300 eventos a 20 por segundo, com handler de 300 ms:

| Receptor | Tempo de resposta visto pelo emissor | Atraso até processar |
|---|---|---|
| Inbox + processamento assíncrono | p50 10 ms, p99 17 ms | p50 376 ms, p99 473 ms |
| Processamento dentro da requisição | p50 308 ms, p99 317 ms | — |

### Handler mais lento que o timeout do emissor

50 eventos, handler de 3 s e timeout do emissor de 2 s:

| Receptor | Entregues | Tentativas | Execuções do handler |
|---|---|---|---|
| Processamento dentro da requisição | 0 de 50 | 250, todas por timeout | **250** (5 por evento) |
| Inbox + processamento assíncrono | 50 de 50, na 1ª tentativa | 50 | 50 |

No contraexemplo o handler rodou até o fim em todas as tentativas, mas o
emissor só via timeouts. Cada evento foi processado 5 vezes e ainda terminou na
fila de falhas.

### Queda do receptor: com e sem jitter

500 entregas vencendo no mesmo instante em que o receptor começa a responder
`503` por 5 s. Cada cenário foi executado duas vezes:

| Jitter | Pico de retries por 100 ms | Janelas de 100 ms com retry | Falharam (5 tentativas esgotadas) |
|---|---|---|---|
| `NONE` | 200 e 259 | 17 e 12 | 0 e 0 |
| `FULL` | 200 e 164 | 44 e 44 | **300 e 357** |

- Sem jitter, as 500 entregas voltam em ondas sincronizadas e todas chegam na
  5ª tentativa, em cerca de 8 s. O pico fica limitado pela vazão do próprio
  despachante, não pela curva de backoff.
- Com jitter completo, os retries se espalham por quase 3 vezes mais janelas.
  O preço é que o atraso médio cai pela metade, e com ele o horizonte de retry.
  As mesmas 5 tentativas que cobriam a queda passam a esgotar antes de ela
  acabar para 60% a 71% das entregas. O número de tentativas precisa ser
  calculado sobre o horizonte com jitter.
- As entregas que falharam foram reenviadas por `redeliver-failed`: 500 de 500
  entregues, sem nenhuma duplicata no receptor.

### Duas instâncias do emissor

2000 eventos publicados com 16 clientes concorrentes, com duas instâncias do
emissor sobre a mesma tabela:

| Medição | Resultado |
|---|---|
| Requisições recebidas / ids distintos | 2000 / 2000 |
| Tentativas repetidas com o mesmo número | 0 |
| Divisão entre as instâncias | 1001 e 999 |
| Da primeira publicação à última entrega | 3,86 s (518 entregas/s, limitado pela taxa de publicação) |
| Duração de cada tentativa | p50 75 ms, p99 238 ms |

## Estrutura

```
com.gkcontas.webhooks
├── signature   formato Standard Webhooks: segredo, assinatura e verificação
├── sender      assinaturas, eventos, despachante com lease, retry, fila de falhas, SSRF
├── receiver    verificação, inbox idempotente, workers, contraexemplo e controles de laboratório
├── config      relógio e agendamento
└── error       contrato de erro da API
```
