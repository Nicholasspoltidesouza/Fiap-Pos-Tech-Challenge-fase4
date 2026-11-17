# Roteiro de apresentação — Vídeo Fase 4 (≤ 15 min)

Fale olhando para a câmera/tela. Troque de aba conforme os marcadores **[ABA]**.

---

## Bloco 0 — Abertura (0:00–0:40)

**Falar:**
> “Olá, sou Nicholas Spolti de Souza, Grupo 50. Este é o Tech Challenge da Fase 4: refatoramos a oficina mecânica para microsserviços com Saga Pattern, bancos isolados, mensageria e CI/CD.”

**[ABA]** GitHub OS → Billing → Execution → Hub fase4  
Mostre os 4 repositórios rapidamente.

---

## Bloco 1 — Arquitetura e Saga (0:40–2:30)

**[ABA]** PDF `Tech-Challenge-Fase4-Grupo50.pdf` (páginas dos diagramas) **ou** PNG `arquitetura.png`

**Falar:**
> “Temos três microsserviços independentes: OS Service com PostgreSQL, Billing com MongoDB e Mercado Pago, e Execution com PostgreSQL. A comunicação crítica passa pelo RabbitMQ, exchange `oficina.events`. Nenhum serviço acessa o banco do outro.”

> “Escolhemos **Saga orquestrado** no OS Service. O fluxo é linear e crítico — criar, orçar, aprovar, pagar, executar. A compensação fica centralizada e auditável na tabela `saga_instance`.”

**[ABA]** Diagrama `saga.png` / PDF figura 2 (caminho feliz) e `compensacao.png` (falha).

---

## Bloco 2 — Deploy / stack no ar (2:30–4:30)

**[ABA]** Terminal com `docker compose ps` **ou** Docker Desktop

**Falar:**
> “Subimos o hub com `docker compose up --build`. Aqui estão os três serviços, RabbitMQ, Mongo, dois Postgres, Grafana, Prometheus e Jaeger.”

**[ABA]** http://localhost:8081/actuator/health (e opcionalmente 8082/8083)  
Mostre `{"status":"UP"}`.

**[ABA]** RabbitMQ http://localhost:15672 — login `guest` / `guest` → aba **Queues**  
Mostre filas `billing.*`, `os.*`, `execution.*`.

---

## Bloco 3 — Fluxo completo da OS (4:30–9:30) ★ obrigatório

### 3.1 Criar OS
**[ABA]** Swagger OS http://localhost:8081/swagger-ui/index.html  
`POST /api/ordens-servico` → Try it out → exemplo:

```json
{
  "clienteId": "11111111-1111-1111-1111-111111111111",
  "veiculoId": "22222222-2222-2222-2222-222222222222",
  "descricaoProblema": "Barulho no motor - demo video",
  "valorEstimado": 350.00
}
```

Execute. **Copie o `id` da OS.**  
Mostre status `AGUARDANDO_ORCAMENTO` (ou já `AGUARDANDO_APROVACAO` se o evento já chegou).

**Falar:**
> “O OS Service persistiu a OS, criou a saga e publicou `orcamento.gerar`.”

### 3.2 Orçamento no Billing
**[ABA]** Swagger Billing http://localhost:8082/swagger-ui/index.html  
`GET /api/orcamentos/por-ordem/{ordemServicoId}` com o UUID.

**Falar:**
> “O Billing gravou o orçamento no MongoDB e publicou `orcamento.gerado`. Banco NoSQL separado.”

### 3.3 Aprovar orçamento
**[ABA]** Swagger OS  
`POST /api/ordens-servico/{id}/aprovar-orcamento`

```json
{ "aprovado": true }
```

**Falar:**
> “Aprovação dispara `pagamento.solicitar`. No mock local o Mercado Pago auto-aprova e publica `pagamento.aprovado`. A OS vai para `EM_EXECUCAO` e manda `execucao.iniciar`.”

Confira com `GET /api/ordens-servico/{id}` → `EM_EXECUCAO`.

### 3.4 Execução na oficina
**[ABA]** Swagger Execution http://localhost:8083/swagger-ui/index.html  
1. `GET /api/execucoes/por-ordem/{ordemServicoId}` → copie `id` da execução  
2. `POST .../iniciar-diagnostico` `{"notas":"Correia danificada"}`  
3. `POST .../iniciar-reparo` `{"notas":"Troca da correia"}`  
4. `POST .../finalizar` `{"notas":"Servico concluido"}`

**Falar:**
> “O chão de fábrica avança a fila. Ao finalizar, publica `execucao.finalizada` e o OS Service marca `FINALIZADA`.”

### 3.5 Fechar a OS
**[ABA]** Swagger OS  
`POST /api/ordens-servico/{id}/entregar` → `ENTREGUE`  
`GET /api/ordens-servico/{id}/historico` → mostre a trilha de status.

---

## Bloco 4 — Falha e compensação (9:30–12:00) ★ obrigatório

**[ABA]** Swagger OS — crie **outra** OS (mesmo body, outro problema).  
Espere orçamento. Depois:

```json
{ "aprovado": false }
```

em `aprovar-orcamento`.

Mostre status `FALHA_COMPENSADA`.

**[ABA]** Billing `GET /api/orcamentos/por-ordem/{id}` → status `CANCELADO`.

**[ABA]** PDF figura 3 (compensação) se quiser reforçar.

**Falar:**
> “Na rejeição o orquestrador publica `compensar.orcamento` e marca a saga como compensada. Isso é o tratamento de falha do Saga.”

---

## Bloco 5 — Testes, qualidade e CI (12:00–13:40)

**[ABA]** Feature BDD no GitHub:  
`os-service` → `fluxo_os_saga.feature`  
`billing-service` → `pagamento.feature`

**Falar:**
> “Temos testes unitários, BDD Cucumber e JaCoCo com meta de 80% por serviço.”

**[ABA]** Pasta `k8s/` do OS Service (manifests).  
**[ABA]** Actions (se o workflow já estiver publicado) **ou** mostre o arquivo local `.github/workflows/ci-cd.yml` / Dockerfile + `mvn verify` no terminal:

```powershell
cd ...\Fiap-Pos-Tech-Challenge-os-service
.\mvnw.cmd -q verify
```

**Falar:**
> “O pipeline prevê build, testes, qualidade e deploy Kubernetes. Aqui evidencio o verify local com sucesso e os manifests K8s no repositório.”

> Observação honesta se Actions ainda não tiver runs: “O YAML de CI está no projeto; a publicação do workflow no GitHub depende do scope `workflow` — a evidência de testes e deploy local/K8s está nos repositórios.”

---

## Bloco 6 — Observabilidade e fechamento (13:40–15:00)

**[ABA]** Prometheus http://localhost:9090/targets → targets dos 3 serviços  
**[ABA]** Grafana http://localhost:3000 — login `admin` / `admin`  
**[ABA]** Jaeger http://localhost:16686

**Falar:**
> “Mantemos a observabilidade da Fase 3: métricas Prometheus, Grafana e Jaeger para rastrear o fluxo distribuído.”

**Fechar:**
> “Entregamos três microsserviços com bancos próprios SQL e NoSQL, Saga orquestrado com compensação, mensageria, Swagger, testes e o PDF de entrega com diagramas. Obrigado.”

---

## Credenciais rápidas

| Sistema | URL | Login |
|---------|-----|-------|
| OS Swagger | http://localhost:8081/swagger-ui/index.html | — |
| Billing Swagger | http://localhost:8082/swagger-ui/index.html | — |
| Execution Swagger | http://localhost:8083/swagger-ui/index.html | — |
| RabbitMQ | http://localhost:15672 | guest / guest |
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | — |
| Jaeger | http://localhost:16686 | — |

## Atalho se o tempo apertar

Em vez do Swagger manual no fluxo feliz, rode no terminal do hub:

```powershell
.\run-demo.ps1 --flow=all
```

Mostre o output (statuses + snapshot das bases) e use o Swagger só para 1–2 passos visuais.
