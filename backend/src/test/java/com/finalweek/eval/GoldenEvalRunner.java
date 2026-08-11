package com.finalweek.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Standalone, data-driven real-model evaluation invoked by the golden-eval Maven profile. */
public final class GoldenEvalRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private final Path root;
    private final Map<String, String> environment;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private GoldenEvalRunner(Path root) throws IOException {
        this.root = root;
        this.environment = loadEnvironment(root.resolve(".env"));
    }

    public static void main(String[] args) throws Exception {
        var root = locateRoot();
        var runner = new GoldenEvalRunner(root);
        runner.run();
    }

    private void run() throws Exception {
        var started = Instant.now();
        var config = JSON.readTree(root.resolve("evals/config.json").toFile());
        int caseCount = intSetting("GOLDEN_CASE_COUNT", config.path("caseCount").asInt(8));
        if (caseCount < 1) throw new IllegalArgumentException("GOLDEN_CASE_COUNT must be positive");
        double coverageThreshold = doubleSetting("GOLDEN_COVERAGE_THRESHOLD", config.path("thresholds").path("coverage").asDouble(.8));
        double citationThreshold = doubleSetting("GOLDEN_CITATION_THRESHOLD", config.path("thresholds").path("citationAccuracy").asDouble(.9));
        double ungroundedThreshold = doubleSetting("GOLDEN_UNGROUNDED_THRESHOLD", config.path("thresholds").path("ungroundedUnmarkedRatio").asDouble(.1));
        String apiKey = setting("BAILIAN_API_KEY", "");
        if (apiKey.isBlank()) throw new IllegalStateException("BAILIAN_API_KEY is required for golden evaluation");
        String endpoint = setting("BAILIAN_ENDPOINT", "https://dashscope.aliyuncs.com").replaceAll("/$", "")
                + "/compatible-mode/v1/chat/completions";
        String model = setting("BAILIAN_LLM_MODEL", "qwen3.7-plus");
        int timeoutSeconds = intSetting("BAILIAN_TIMEOUT_SECONDS", 60);

        var manifest = JSON.readTree(root.resolve("evals/fixtures/manifest.json").toFile());
        verifyFixtures(manifest);
        var caseFiles = Files.list(root.resolve("evals/cases"))
                .filter(path -> path.toString().endsWith(".json")).sorted().limit(caseCount).toList();
        if (caseFiles.size() != caseCount) throw new IllegalStateException("Expected " + caseCount + " cases, found " + caseFiles.size());

        var outputs = JSON.createArrayNode();
        var failures = JSON.createArrayNode();
        double coverageNumerator = 0, coverageDenominator = 0, citationCorrect = 0, citationTotal = 0;
        double importanceCorrect = 0, importanceTotal = 0, ungrounded = 0, evaluatedClaims = 0;
        for (var caseFile : caseFiles) {
            var definition = JSON.readTree(caseFile.toFile());
            var annotation = JSON.readTree(root.resolve("evals/annotations").resolve(definition.path("annotation").asText()).toFile());
            var scored = evaluateCase(definition, annotation, endpoint, apiKey, model, timeoutSeconds);
            outputs.add(scored.output());
            coverageNumerator += scored.coverageNumerator(); coverageDenominator += scored.coverageDenominator();
            citationCorrect += scored.citationCorrect(); citationTotal += scored.citationTotal();
            importanceCorrect += scored.importanceCorrect(); importanceTotal += scored.importanceTotal();
            ungrounded += scored.ungrounded(); evaluatedClaims += scored.evaluatedClaims();
            if (scored.output().path("failureReason").isTextual()) failures.add(scored.output().path("id").asText()
                    + ": " + scored.output().path("failureReason").asText());
        }

        double coverage = ratio(coverageNumerator, coverageDenominator);
        double citations = ratio(citationCorrect, citationTotal);
        double importance = ratio(importanceCorrect, importanceTotal);
        double ungroundedRatio = ratio(ungrounded, evaluatedClaims);
        boolean passed = coverage >= coverageThreshold && citations >= citationThreshold
                && ungroundedRatio <= ungroundedThreshold && failures.isEmpty();
        var finished = Instant.now();
        var report = JSON.createObjectNode();
        report.put("schemaVersion", "1.0");
        var run = report.putObject("run");
        run.put("startedAt", started.toString()); run.put("finishedAt", finished.toString());
        run.put("durationMs", Duration.between(started, finished).toMillis());
        run.put("commit", gitCommit()); run.put("caseVersion", config.path("caseVersion").asText());
        run.put("inputVersion", manifest.path("version").asText());
        var models = run.putObject("modelIds");
        models.put("llm", model); models.put("embedding", setting("BAILIAN_EMBEDDING_MODEL", "text-embedding-v4"));
        models.put("ocr", setting("BAILIAN_OCR_MODEL", "qwen-vl-ocr-latest"));
        models.put("asr", setting("BAILIAN_ASR_MODEL", "paraformer-realtime-v2"));
        var snapshot = run.putObject("configuration");
        snapshot.put("caseCount", caseCount); snapshot.put("timeoutSeconds", timeoutSeconds);
        snapshot.put("endpoint", setting("BAILIAN_ENDPOINT", "https://dashscope.aliyuncs.com"));
        snapshot.set("thresholds", thresholds(coverageThreshold, citationThreshold, ungroundedThreshold));
        report.set("cases", outputs);
        var summary = report.putObject("summary");
        summary.put("coverage", coverage); summary.put("citationAccuracy", citations);
        summary.put("importanceConsistency", importance); summary.put("ungroundedUnmarkedRatio", ungroundedRatio);
        report.set("thresholds", thresholds(coverageThreshold, citationThreshold, ungroundedThreshold));
        report.put("passed", passed); report.set("failures", failures);
        validateAgainstSchema(report, JSON.readTree(root.resolve("evals/report.schema.json").toFile()), "$");
        var reports = root.resolve("evals/reports"); Files.createDirectories(reports);
        var named = reports.resolve("golden-" + FILE_TIME.format(started) + ".json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(named.toFile(), report);
        JSON.writerWithDefaultPrettyPrinter().writeValue(reports.resolve("latest.json").toFile(), report);
        System.out.printf(Locale.ROOT, "Golden evaluation: coverage=%.3f citations=%.3f importance=%.3f ungrounded=%.3f passed=%s%nReport: %s%n",
                coverage, citations, importance, ungroundedRatio, passed, named);
        if (!passed) throw new IllegalStateException("Golden evaluation did not meet configured thresholds; report was preserved at " + named);
    }

    private Score evaluateCase(JsonNode definition, JsonNode annotation, String endpoint, String apiKey,
                               String model, int timeoutSeconds) {
        var result = JSON.createObjectNode();
        String id = definition.path("id").asText(); result.put("id", id); result.put("kind", definition.path("kind").asText());
        try {
            String raw = call(endpoint, apiKey, model, timeoutSeconds, definition);
            JsonNode generated = JSON.readTree(stripFence(raw)); result.set("output", generated);
            var expected = strings(annotation.path("coreKnowledgePoints"));
            var correctSources = new HashSet<>(strings(annotation.path("correctSources")));
            double coverageHits = 0, citationHits = 0, citationCount = 0, importanceHits = 0, importanceCount = 0;
            double ungroundedCount = 0, claimCount = 1;
            if ("OUTLINE".equals(definition.path("kind").asText())) {
                var nodes = flatten(generated.path("nodes"));
                String titles = nodes.stream().map(node -> node.path("title").asText()).reduce("", (a, b) -> a + " " + b);
                for (var term : expected) if (titles.contains(term)) coverageHits++;
                for (var node : nodes) for (var source : node.path("sourceSegmentIds")) {
                    citationCount++; if (correctSources.contains(source.asText())) citationHits++; else ungroundedCount++;
                }
                var importance = annotation.path("expectedImportance");
                var fields = importance.fields();
                while (fields.hasNext()) {
                    var entry = fields.next(); importanceCount++;
                    if (nodes.stream().anyMatch(node -> node.path("title").asText().contains(entry.getKey())
                            && node.path("importance").asText().equals(entry.getValue().asText()))) importanceHits++;
                }
                claimCount = Math.max(1, nodes.size());
            } else {
                String answer = generated.path("answer").asText();
                for (var term : expected) if (answer.contains(term)) coverageHits++;
                for (var source : generated.path("sourceSegmentIds")) {
                    citationCount++; if (correctSources.contains(source.asText())) citationHits++; else ungroundedCount++;
                }
                boolean answerExpected = annotation.path("answerExpected").asBoolean();
                if (!answerExpected) {
                    coverageHits = answer.matches(".*(资料不足|无法回答|未提供|没有相关).*" ) ? expected.size() : 0;
                    citationCount = Math.max(1, citationCount); citationHits = generated.path("sourceSegmentIds").isEmpty() ? 1 : 0;
                    if (!generated.path("sourceSegmentIds").isEmpty()) ungroundedCount++;
                }
            }
            double c = ratio(coverageHits, expected.size()); double ca = ratio(citationHits, Math.max(1, citationCount));
            double ic = ratio(importanceHits, importanceCount); double ug = ratio(ungroundedCount, claimCount);
            var scores = result.putObject("scores"); scores.put("coverage", c); scores.put("citationAccuracy", ca);
            scores.put("importanceConsistency", ic); scores.put("ungroundedUnmarkedRatio", ug);
            result.put("passed", c >= .5 && ca >= .9 && ug <= .1); result.putNull("failureReason");
            return new Score(result, coverageHits, expected.size(), citationHits, Math.max(1, citationCount),
                    importanceHits, importanceCount, ungroundedCount, claimCount);
        } catch (Exception exception) {
            result.set("output", JSON.createObjectNode());
            var scores = result.putObject("scores"); scores.put("coverage", 0); scores.put("citationAccuracy", 0);
            scores.put("importanceConsistency", 0); scores.put("ungroundedUnmarkedRatio", 1);
            result.put("passed", false); result.put("failureReason", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return new Score(result, 0, Math.max(1, annotation.path("coreKnowledgePoints").size()), 0, 1, 0,
                    annotation.path("expectedImportance").size(), 1, 1);
        }
    }

    private String call(String endpoint, String apiKey, String model, int timeoutSeconds, JsonNode definition) throws Exception {
        StringBuilder context = new StringBuilder();
        for (var segment : definition.path("segments")) context.append("segmentId=").append(segment.path("id").asText())
                .append("\n").append(segment.path("content").asText()).append("\n---\n");
        boolean outline = "OUTLINE".equals(definition.path("kind").asText());
        String system = outline
                ? "你是课程提纲评测助手。仅按上下文输出 JSON，来源只能使用给定 segmentId。提纲最多四层；真题、教师强调和学生重点应提高 importance。"
                : "你是课程问答评测助手。仅按上下文输出 JSON。无相关证据时必须明确说资料不足并返回空来源；不得用未标记常识补答案。";
        String user = outline
                ? "输出 {\"nodes\":[{\"title\":\"知识点\",\"importance\":\"HIGH|MEDIUM|LOW\",\"sourceSegmentIds\":[\"UUID\"],\"children\":[]}]}。上下文：\n" + context
                : "输出 {\"answer\":\"回答\",\"sourceSegmentIds\":[\"UUID\"],\"generalKnowledgeSupplement\":null}。问题："
                    + definition.path("question").asText() + "\n上下文：\n" + context;
        var body = JSON.createObjectNode(); body.put("model", model); body.put("enable_thinking", false);
        body.set("response_format", JSON.createObjectNode().put("type", "json_object"));
        var messages = body.putArray("messages"); messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("LLM HTTP " + response.statusCode());
        var content = JSON.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        if (content.isBlank()) throw new IOException("LLM returned empty content"); return content;
    }

    private void verifyFixtures(JsonNode manifest) throws Exception {
        for (var fixture : manifest.path("files")) {
            var path = root.resolve("evals/fixtures").resolve(fixture.path("path").asText()).normalize();
            if (!path.startsWith(root.resolve("evals/fixtures")) || !Files.isRegularFile(path)) throw new IOException("Missing fixture " + path);
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            if (!actual.equals(fixture.path("sha256").asText())) throw new IOException("Fixture hash mismatch: " + path.getFileName());
        }
    }

    private static void validateAgainstSchema(JsonNode value, JsonNode schema, String path) {
        String type = schema.path("type").asText();
        boolean valid = switch (type) { case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isTextual(); case "number" -> value.isNumber(); case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean(); default -> true; };
        if (!valid) throw new IllegalStateException("Schema type mismatch at " + path);
        for (var required : schema.path("required")) if (!value.has(required.asText()))
            throw new IllegalStateException("Schema required property missing at " + path + "." + required.asText());
        if (value.isObject()) schema.path("properties").fields().forEachRemaining(entry -> {
            if (value.has(entry.getKey())) validateAgainstSchema(value.get(entry.getKey()), entry.getValue(), path + "." + entry.getKey());
        });
        if (value.isArray() && schema.has("items")) for (int i = 0; i < value.size(); i++)
            validateAgainstSchema(value.get(i), schema.path("items"), path + "[" + i + "]");
    }

    private ObjectNode thresholds(double coverage, double citations, double ungrounded) {
        var node = JSON.createObjectNode(); node.put("coverage", coverage); node.put("citationAccuracy", citations);
        node.put("ungroundedUnmarkedRatio", ungrounded); return node;
    }
    private static List<JsonNode> flatten(JsonNode nodes) { var result = new ArrayList<JsonNode>();
        for (var node : nodes) { result.add(node); result.addAll(flatten(node.path("children"))); } return result; }
    private static List<String> strings(JsonNode values) { var result = new ArrayList<String>(); values.forEach(v -> result.add(v.asText())); return result; }
    private static double ratio(double numerator, double denominator) { return denominator == 0 ? 1 : numerator / denominator; }
    private String setting(String key, String fallback) { return Optional.ofNullable(System.getenv(key)).filter(v -> !v.isBlank()).orElse(environment.getOrDefault(key, fallback)); }
    private int intSetting(String key, int fallback) { return Integer.parseInt(setting(key, String.valueOf(fallback))); }
    private double doubleSetting(String key, double fallback) { return Double.parseDouble(setting(key, String.valueOf(fallback))); }
    private String gitCommit() { try { var p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile()).start();
        if (p.waitFor() == 0) return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim(); } catch (Exception ignored) {} return "unknown"; }
    private static String stripFence(String value) { var text = value.strip(); if (text.startsWith("```")) {
        text = text.substring(text.indexOf('\n') + 1); text = text.substring(0, text.lastIndexOf("```")).strip(); } return text; }
    private static Path locateRoot() { var current = Path.of("").toAbsolutePath();
        if (Files.isDirectory(current.resolve("evals"))) return current; if (Files.isDirectory(current.resolve("../evals"))) return current.resolve("..").normalize();
        throw new IllegalStateException("Run from repository root or backend directory"); }
    private static Map<String, String> loadEnvironment(Path path) throws IOException { var result = new HashMap<String, String>();
        if (!Files.exists(path)) return result; for (var line : Files.readAllLines(path)) { var value = line.strip();
            if (value.isEmpty() || value.startsWith("#") || !value.contains("=")) continue; int split = value.indexOf('=');
            result.put(value.substring(0, split).strip(), value.substring(split + 1).strip()); } return result; }
    private record Score(ObjectNode output, double coverageNumerator, double coverageDenominator,
                         double citationCorrect, double citationTotal, double importanceCorrect,
                         double importanceTotal, double ungrounded, double evaluatedClaims) {}
}
