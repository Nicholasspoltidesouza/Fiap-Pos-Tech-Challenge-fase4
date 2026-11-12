# Guia completo — como os sistemas da Fase 4 funcionam

## 1. Visão geral

A oficina passou de um monólito (Fases 1–3) para **3 microsserviços independentes**, cada um com:

- repositório Git próprio
- banco de dados próprio (sem acesso cruzado)
- pipeline CI/CD próprio
- deploy Kubernetes próprio

A comunicação crítica (orçamento, pagamento, execução) é **assíncrona via RabbitMQ**. Consultas pontuais usam REST dentro do próprio serviço.

Observabilidade da Fase 3 permanece: Actuator/Prometheus + Grafana + Jaeger no hub `fase4`.

---

## 2. Papel de cada microsserviço

### 2.1 OS Service (`:8081`) — PostgreSQL

**Dono do agregado Ordem de Serviço** e **orquestrador do Saga**.

Responsabilidades:
- Abrir OS, consultar status/histórico, entregar veículo
- Persistir máquina de estados da OS
- Persistir `saga_instance` (passo e status do Saga)
- Publicar comandos (`orcamento.gerar`, `pagamento.solicitar`, `execucao.iniciar`, `compensar.*`)
- Consumir eventos de progresso/falha e avançar ou compensar

Estados principais da OS:

`RECEBIDA` → `AGUARDANDO_ORCAMENTO` → `AGUARDANDO_APROVACAO` → `AGUARDANDO_PAGAMENTO` → `EM_EXECUCAO` → `FINALIZADA` → `ENTREGUE`

Em falha: `CANCELADA` / `FALHA_COMPENSADA`

APIs principais:
- `POST /api/ordens-servico`
- `GET /api/ordens-servico/{id}`
- `GET /api/ordens-servico/{id}/historico`
- `POST /api/ordens-servico/{id}/aprovar-orcamento`
- `POST /api/ordens-servico/{id}/entregar`

### 2.2 Billing Service (`:8082`) — MongoDB + Mercado Pago

**Dono de orçamentos e pagamentos** (coleções `orcamentos` e `pagamentos`).

Responsabilidades:
- Ao receber `orcamento.gerar`, cria orçamento e publica `orcamento.gerado`
- Ao receber `pagamento.solicitar`, cria preferência no Mercado Pago
- Em modo mock (`MERCADO_PAGO_MOCK_ENABLED=true` + `AUTO_APPROVE=true`), aprova automaticamente e publica `pagamento.aprovado`
- Webhook real: `POST /api/pagamentos/webhook`
- Compensações: cancela orçamento/pagamento

Por que MongoDB? Documentos de orçamento/pagamento variam (itens, metadados MP) e o domínio de billing é naturalmente documental. Cumpre o requisito **SQL + NoSQL** do desafio.

### 2.3 Execution Service (`:8083`) — PostgreSQL

**Dono da fila de chão de fábrica**.

Responsabilidades:
- Ao receber `execucao.iniciar`, cria job `EM_FILA` e publica `execucao.iniciada`
- Oficina avança: diagnóstico → reparo → finalizar
- Ao finalizar, publica `execucao.finalizada` (OS completa o Saga)
- Compensação: cancela job (`CANCELADA`)

Estados: `EM_FILA` → `EM_DIAGNOSTICO` → `EM_REPARO` → `FINALIZADA` | `CANCELADA`

---

## 3. Mensageria (contrato)

**Exchange:** `oficina.events` (topic)

| Routing key | Publicador | Consumidor |
|-------------|------------|------------|
| `orcamento.gerar` | OS | Billing |
| `orcamento.gerado` | Billing | OS |
| `pagamento.solicitar` | OS | Billing |
| `pagamento.aprovado` / `pagamento.falhou` | Billing | OS |
| `execucao.iniciar` | OS | Execution |
| `execucao.iniciada` / `execucao.finalizada` | Execution | OS |
| `compensar.orcamento` / `compensar.pagamento` | OS | Billing |
| `compensar.execucao` | OS | Execution |

Envelope JSON (campos comuns):

```json
{
  "eventId": "uuid",
  "eventType": "OrcamentoGerado",
  "ocorridoEm": "2026-11-12T14:00:00Z",
  "ordemServicoId": "uuid",
  "clienteId": "uuid",
  "payload": { }
}
```

Regra de ouro: **nenhum serviço lê o banco do outro**. Só eventos + REST próprio.

---

## 4. Saga orquestrado — passo a passo

```text
Cliente                OS Service              Billing               Execution
  |                        |                      |                      |
  |-- POST criar OS ------>|                      |                      |
  |                        |-- orcamento.gerar -->|                      |
  |                        |<-- orcamento.gerado -|                      |
  |-- aprovar orçamento -->|                      |                      |
  |                        |-- pagamento.solicitar -->|                 |
  |                        |<-- pagamento.aprovado ---|                 |
  |                        |-- execucao.iniciar ---------------------->|
  |                        |<-- execucao.iniciada ---------------------|
  |                        |                      |   (oficina trabalha)|
  |                        |<-- execucao.finalizada -------------------|
  |<-- OS FINALIZADA ------|                      |                      |
```

Compensação (ex.: pagamento falhou):

```text
Billing -- pagamento.falhou --> OS
OS marca FALHA_COMPENSADA
OS -- compensar.orcamento --> Billing (cancela)
OS -- compensar.pagamento --> Billing (cancela/reembolso lógico)
OS -- compensar.execucao --> Execution (se já existir job)
```

---

## 5. Como subir e validar

### Opção A — Hub Docker (recomendado)

```bash
cd Fiap-Pos-Tech-Challenge-fase4
docker compose up --build
```

### Opção B — Serviços isolados

1. Subir infra (RabbitMQ + DBs) de cada `docker-compose` dos serviços
2. Rodar `./mvnw spring-boot:run` em cada pasta

### Checklist de saúde

- http://localhost:8081/actuator/health
- http://localhost:8082/actuator/health
- http://localhost:8083/actuator/health
- RabbitMQ UI: filas `billing.*`, `os.*`, `execution.*` criadas após o boot

### Evidências de qualidade

Em cada serviço:

```bash
./mvnw verify
# relatório JaCoCo: target/site/jacoco/index.html
```

BDD Cucumber:
- OS: `fluxo_os_saga.feature`
- Billing: `pagamento.feature`
- Execution: feature de fluxo de execução

---

## 6. Integração Mercado Pago

| Ambiente | Comportamento |
|----------|---------------|
| Local (padrão) | Mock + auto-approve ao receber `pagamento.solicitar` |
| Homolog/Prod | `MERCADO_PAGO_MOCK_ENABLED=false` + `MERCADO_PAGO_ACCESS_TOKEN` + webhook |

Fluxo real:
1. Billing cria Preference na API MP
2. Cliente paga no `init_point`
3. MP notifica `POST /api/pagamentos/webhook`
4. Billing consulta status e publica `pagamento.aprovado` ou `pagamento.falhou`

---

## 7. Relação com as fases anteriores

| Fase | O que permanece |
|------|-----------------|
| 1–2 | Domínio rico da OS, JWT/auth (monólito legado ainda no repo `Fiap-Pos-Tech-Challenge-1`) |
| 3 | Kong, lambdas, K8s/Terraform, Grafana/Prometheus/Jaeger |
| **4** | Recorte em microsserviços + Saga + SQL/NoSQL + CI/CD por serviço |

O monólito da Fase 3 **não precisa ser apagado**; a Fase 4 entrega a evolução distribuída nos novos repositórios.

---

## 8. O que gravar no vídeo (até 15 min)

1. Diagrama da arquitetura (30s)
2. `docker compose up` / pods K8s (2 min)
3. Fluxo completo OS → Billing → Execution (5 min)
4. Falha + compensação (rejeitar orçamento ou simular pagamento falho) (3 min)
5. Pipeline CI (Actions) + JaCoCo/Sonar (2 min)
6. Grafana/Prometheus/Jaeger no fluxo distribuído (2 min)
