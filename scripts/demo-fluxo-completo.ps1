$ErrorActionPreference = "Stop"

$clienteId = [guid]::NewGuid().ToString()
$veiculoId = [guid]::NewGuid().ToString()

Write-Host "==> Criando OS..."
$os = Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/ordens-servico" `
  -ContentType "application/json" `
  -Body (@{
    clienteId = $clienteId
    veiculoId = $veiculoId
    descricaoProblema = "Barulho no motor - demo Fase 4"
    valorEstimado = 350
  } | ConvertTo-Json)

$osId = $os.id
Write-Host "OS criada: $osId status=$($os.status)"
Start-Sleep -Seconds 3

$os = Invoke-RestMethod "http://localhost:8081/api/ordens-servico/$osId"
Write-Host "Apos orcamento: status=$($os.status) valor=$($os.valorOrcamento)"

Write-Host "==> Aprovando orcamento..."
$os = Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/ordens-servico/$osId/aprovar-orcamento" `
  -ContentType "application/json" `
  -Body '{"aprovado":true}'
Write-Host "Apos aprovacao: status=$($os.status)"
Start-Sleep -Seconds 3

$os = Invoke-RestMethod "http://localhost:8081/api/ordens-servico/$osId"
Write-Host "Apos pagamento: status=$($os.status)"

Write-Host "==> Avancando execucao na oficina..."
$exec = Invoke-RestMethod "http://localhost:8083/api/execucoes/por-ordem/$osId"
Invoke-RestMethod -Method Post -Uri "http://localhost:8083/api/execucoes/$($exec.id)/iniciar-diagnostico" `
  -ContentType "application/json" -Body '{"notas":"Diagnostico demo"}' | Out-Null
Invoke-RestMethod -Method Post -Uri "http://localhost:8083/api/execucoes/$($exec.id)/iniciar-reparo" `
  -ContentType "application/json" -Body '{"notas":"Reparo demo"}' | Out-Null
Invoke-RestMethod -Method Post -Uri "http://localhost:8083/api/execucoes/$($exec.id)/finalizar" `
  -ContentType "application/json" -Body '{"notas":"Finalizado demo"}' | Out-Null
Start-Sleep -Seconds 2

$os = Invoke-RestMethod "http://localhost:8081/api/ordens-servico/$osId"
Write-Host "OS final: status=$($os.status)"
Write-Host "Historico:"
Invoke-RestMethod "http://localhost:8081/api/ordens-servico/$osId/historico" | ConvertTo-Json -Depth 5
