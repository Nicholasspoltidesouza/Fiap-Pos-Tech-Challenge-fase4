# Roteiro do vídeo — Fase 4 (até 15 minutos)

## 0:00–0:40 — Introdução
- Nomes do grupo e objetivo: microsserviços + Saga + CI/CD
- Mostrar os 3 repositórios no GitHub

## 0:40–2:30 — Arquitetura
- Abrir o diagrama do README (OS / Billing / Execution / RabbitMQ / SQL+NoSQL)
- Explicar Saga **orquestrado** no OS Service e por que foi escolhido

## 2:30–5:00 — Deploy
- `docker compose up --build` no hub **ou** deploy K8s de um serviço
- Mostrar health dos 3 serviços + RabbitMQ Management

## 5:00–10:00 — Fluxo completo
1. Criar OS no Swagger 8081
2. Mostrar evento/orçamento no Billing 8082
3. Aprovar orçamento
4. Mostrar pagamento mock aprovado
5. Avançar execução no 8083 até finalizar
6. Mostrar OS `FINALIZADA` + histórico

## 10:00–12:30 — Falha e compensação
- Criar outra OS e rejeitar orçamento **ou** forçar `pagamento.falhou`
- Mostrar status `FALHA_COMPENSADA` e cancelamento no Billing/Execution

## 12:30–14:30 — Qualidade e CI
- Abrir GitHub Actions de um microsserviço (build/test/deploy)
- Abrir relatório JaCoCo / menção Sonar (≥80%)
- BDD Cucumber (feature)

## 14:30–15:00 — Observabilidade e encerramento
- Grafana/Prometheus ou Jaeger
- Resumo dos entregáveis e links
