package com.postech.fase4.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Executable demo covering all Fase 4 flows and database interactions.
 *
 * Usage:
 *   java -jar target/fase4-demo-runner.jar
 *   java -jar target/fase4-demo-runner.jar --skip-db
 *   java -jar target/fase4-demo-runner.jar --flow=happy
 *   java -jar target/fase4-demo-runner.jar --flow=reject
 *   java -jar target/fase4-demo-runner.jar --flow=all
 */
public final class DemoRunner {

    private static final String OS_BASE = env("OS_BASE_URL", "http://localhost:8081");
    private static final String BILLING_BASE = env("BILLING_BASE_URL", "http://localhost:8082");
    private static final String EXEC_BASE = env("EXECUTION_BASE_URL", "http://localhost:8083");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final boolean inspectDb;

    private DemoRunner(boolean inspectDb) {
        this.inspectDb = inspectDb;
    }

    public static void main(String[] args) throws Exception {
        String flow = "all";
        boolean inspectDb = true;
        for (String arg : args) {
            if ("--skip-db".equals(arg)) {
                inspectDb = false;
            } else if (arg.startsWith("--flow=")) {
                flow = arg.substring("--flow=".length()).toLowerCase(Locale.ROOT);
            }
        }

        DemoRunner runner = new DemoRunner(inspectDb);
        runner.banner();
        runner.waitForHealth();

        switch (flow) {
            case "happy" -> runner.runHappyPath();
            case "reject" -> runner.runRejectionPath();
            case "all" -> {
                runner.runHappyPath();
                System.out.println();
                runner.runRejectionPath();
            }
            default -> throw new IllegalArgumentException("Unknown flow: " + flow + " (use happy|reject|all)");
        }

        System.out.println();
        System.out.println("Demo finished successfully.");
    }

    private void banner() {
        System.out.println("============================================================");
        System.out.println(" FIAP Fase 4 — Demo Runner (APIs + databases)");
        System.out.println(" OS=" + OS_BASE);
        System.out.println(" Billing=" + BILLING_BASE);
        System.out.println(" Execution=" + EXEC_BASE);
        System.out.println("============================================================");
    }

    private void waitForHealth() throws Exception {
        System.out.println("\n[health] Waiting for services...");
        awaitHealth(OS_BASE + "/actuator/health", "os-service");
        awaitHealth(BILLING_BASE + "/actuator/health", "billing-service");
        awaitHealth(EXEC_BASE + "/actuator/health", "execution-service");
        System.out.println("[health] All services are UP");
    }

    private void runHappyPath() throws Exception {
        section("FLOW 1 — Happy path (create → approve → pay → execute → deliver)");

        UUID clienteId = UUID.randomUUID();
        UUID veiculoId = UUID.randomUUID();
        ObjectNode body = mapper.createObjectNode();
        body.put("clienteId", clienteId.toString());
        body.put("veiculoId", veiculoId.toString());
        body.put("descricaoProblema", "Barulho no motor ao acelerar - demo happy path");
        body.put("valorEstimado", 350.00);

        JsonNode os = postJson(OS_BASE + "/api/ordens-servico", body.toString());
        String osId = text(os, "id");
        System.out.println("OS created id=" + osId + " status=" + text(os, "status"));

        os = waitUntilOsStatus(osId, "AGUARDANDO_APROVACAO", 30);
        System.out.println("Quote ready status=" + text(os, "status") + " valorOrcamento=" + os.path("valorOrcamento"));

        JsonNode budgets = getJson(BILLING_BASE + "/api/orcamentos/por-ordem/" + osId);
        System.out.println("Billing Mongo orcamentos=" + budgets);
        String orcamentoId = budgets.isArray() && !budgets.isEmpty() ? text(budgets.get(0), "id") : null;

        os = postJson(OS_BASE + "/api/ordens-servico/" + osId + "/aprovar-orcamento", "{\"aprovado\":true}");
        System.out.println("Quote approved status=" + text(os, "status"));

        os = waitUntilOsStatus(osId, "EM_EXECUCAO", 30);
        System.out.println("Payment approved / execution started status=" + text(os, "status"));

        if (orcamentoId != null) {
            try {
                JsonNode checkout = postJson(BILLING_BASE + "/api/pagamentos/" + orcamentoId + "/checkout", "");
                System.out.println("Checkout snapshot=" + checkout);
            } catch (Exception ex) {
                System.out.println("Checkout already exists or not needed: " + ex.getMessage());
            }
        }

        JsonNode exec = waitForExecution(osId, 30);
        String execId = text(exec, "id");
        System.out.println("Execution job id=" + execId + " status=" + text(exec, "status"));

        exec = postJson(EXEC_BASE + "/api/execucoes/" + execId + "/iniciar-diagnostico",
                "{\"notas\":\"Correia dentada danificada\"}");
        System.out.println("Diagnosis status=" + text(exec, "status"));

        exec = postJson(EXEC_BASE + "/api/execucoes/" + execId + "/iniciar-reparo",
                "{\"notas\":\"Substituicao da correia e tensionador\"}");
        System.out.println("Repair status=" + text(exec, "status"));

        exec = postJson(EXEC_BASE + "/api/execucoes/" + execId + "/finalizar",
                "{\"notas\":\"Servico concluido e testado\"}");
        System.out.println("Execution finished status=" + text(exec, "status"));

        os = waitUntilOsStatus(osId, "FINALIZADA", 30);
        System.out.println("OS finalized status=" + text(os, "status"));

        os = postJson(OS_BASE + "/api/ordens-servico/" + osId + "/entregar", "");
        System.out.println("OS delivered status=" + text(os, "status"));

        JsonNode history = getJson(OS_BASE + "/api/ordens-servico/" + osId + "/historico");
        System.out.println("OS history entries=" + history.size());

        inspectDatabases(osId, "happy-path");
    }

    private void runRejectionPath() throws Exception {
        section("FLOW 2 — Rejection / compensation (approve=false)");

        ObjectNode body = mapper.createObjectNode();
        body.put("clienteId", UUID.randomUUID().toString());
        body.put("veiculoId", UUID.randomUUID().toString());
        body.put("descricaoProblema", "Orcamento sera rejeitado - demo compensacao");
        body.put("valorEstimado", 200.00);

        JsonNode os = postJson(OS_BASE + "/api/ordens-servico", body.toString());
        String osId = text(os, "id");
        System.out.println("OS created id=" + osId + " status=" + text(os, "status"));

        waitUntilOsStatus(osId, "AGUARDANDO_APROVACAO", 30);
        os = postJson(OS_BASE + "/api/ordens-servico/" + osId + "/aprovar-orcamento", "{\"aprovado\":false}");
        System.out.println("After rejection status=" + text(os, "status"));

        JsonNode budgets = getJson(BILLING_BASE + "/api/orcamentos/por-ordem/" + osId);
        System.out.println("Billing after compensation=" + budgets);

        inspectDatabases(osId, "rejection-compensation");
    }

    private void inspectDatabases(String osId, String label) {
        if (!inspectDb) {
            System.out.println("[db] skipped (--skip-db)");
            return;
        }
        String safeOsId = requireUuid(osId);
        section("DATABASE SNAPSHOT — " + label);

        runDocker(
                "PostgreSQL OS (ordem_servico)",
                "docker", "exec", "fase4-postgres-os",
                "psql", "-U", "os_user", "-d", "os_service_db", "-v", "osid=" + safeOsId, "-c",
                "SELECT id, status, valor_orcamento FROM ordem_servico WHERE id = :'osid';");

        runDocker(
                "PostgreSQL OS (saga_instance)",
                "docker", "exec", "fase4-postgres-os",
                "psql", "-U", "os_user", "-d", "os_service_db", "-v", "osid=" + safeOsId, "-c",
                "SELECT ordem_servico_id, current_step, status FROM saga_instance WHERE ordem_servico_id = :'osid';");

        runDocker(
                "MongoDB Billing (orcamentos)",
                "docker", "exec", "fase4-mongodb-billing",
                "mongosh", "--quiet", "billing_db", "--eval",
                "db.orcamentos.find({ordemServicoId: '" + safeOsId + "'}).toArray()");

        runDocker(
                "MongoDB Billing (pagamentos)",
                "docker", "exec", "fase4-mongodb-billing",
                "mongosh", "--quiet", "billing_db", "--eval",
                "db.pagamentos.find({ordemServicoId: '" + safeOsId + "'}).toArray()");

        runDocker(
                "PostgreSQL Execution (execucao_trabalho)",
                "docker", "exec", "fase4-postgres-execution",
                "psql", "-U", "admin", "-d", "execution_db", "-v", "osid=" + safeOsId, "-c",
                "SELECT id, status, ordem_servico_id FROM execucao_trabalho WHERE ordem_servico_id = :'osid';");
    }

    private static String requireUuid(String value) {
        return UUID.fromString(value).toString();
    }

    private void runDocker(String title, String... command) {
        System.out.println("\n>> " + title);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.out.println("(timeout waiting for docker command)");
                return;
            }
            if (process.exitValue() != 0) {
                System.out.println("(docker unavailable or container not running — start hub with docker compose up)");
                System.out.println(output.trim());
                return;
            }
            System.out.println(output.trim());
        } catch (Exception ex) {
            System.out.println("(could not inspect DB: " + ex.getMessage() + ")");
        }
    }

    private JsonNode waitUntilOsStatus(String osId, String expected, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getJson(OS_BASE + "/api/ordens-servico/" + osId);
            if (expected.equals(text(last, "status"))) {
                return last;
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("Timeout waiting OS " + osId + " status=" + expected
                + " last=" + (last == null ? "null" : last));
    }

    private JsonNode waitForExecution(String osId, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Exception lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return getJson(EXEC_BASE + "/api/execucoes/por-ordem/" + osId);
            } catch (Exception ex) {
                lastError = ex;
                Thread.sleep(1000L);
            }
        }
        throw new IllegalStateException("Timeout waiting execution for OS " + osId, lastError);
    }

    private void awaitHealth(String url, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode health = getJson(url);
                if ("UP".equalsIgnoreCase(text(health, "status"))) {
                    System.out.println("  - " + name + " UP");
                    return;
                }
            } catch (Exception ignored) {
                // retry
            }
            Thread.sleep(2000L);
        }
        throw new IllegalStateException(name + " not healthy at " + url);
    }

    private JsonNode getJson(String url) throws Exception {
        URI uri = allowedUri(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("GET " + uri + " -> " + response.statusCode() + " " + response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return mapper.nullNode();
        }
        return mapper.readTree(response.body());
    }

    private JsonNode postJson(String url, String jsonBody) throws Exception {
        URI uri = allowedUri(url);
        HttpRequest.BodyPublisher publisher = (jsonBody == null || jsonBody.isBlank())
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .POST(publisher)
                .header("Accept", "application/json");
        if (jsonBody != null && !jsonBody.isBlank()) {
            builder.header("Content-Type", "application/json");
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("POST " + uri + " -> " + response.statusCode() + " " + response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return mapper.nullNode();
        }
        return mapper.readTree(response.body());
    }

    private static URI allowedUri(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowedHost = host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.endsWith(".local");
        if (!allowedHost || uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("http")) {
            throw new IllegalArgumentException("Refusing non-local HTTP URL: " + url);
        }
        return uri;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.println(title);
        System.out.println("------------------------------------------------------------");
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
