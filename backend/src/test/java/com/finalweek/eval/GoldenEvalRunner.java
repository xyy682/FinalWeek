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
        double structureThreshold = doubleSetting("GOLDEN_MOCK_STRUCTURE_THRESHOLD", config.path("thresholds").path("structuralValidity").asDouble(.95));
        double sourceFidelityThreshold = doubleSetting("GOLDEN_MOCK_SOURCE_THRESHOLD", config.path("thresholds").path("sourceFidelity").asDouble(.9));
        double answerThreshold = doubleSetting("GOLDEN_MOCK_ANSWER_THRESHOLD", config.path("thresholds").path("answerConsistency").asDouble(.95));
        double replicationThreshold = doubleSetting("GOLDEN_MOCK_REPLICATION_THRESHOLD", config.path("thresholds").path("sourceReplicationRatio").asDouble(0));
        double pdfThreshold = doubleSetting("GOLDEN_MOCK_PDF_THRESHOLD", config.path("thresholds").path("pdfUsability").asDouble(.95));
        var mockThresholds = new MockThresholds(structureThreshold, sourceFidelityThreshold,
                answerThreshold, replicationThreshold, pdfThreshold);
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
        double structureCorrect = 0, structureTotal = 0, sourceFaithful = 0, sourceChecks = 0;
        double answerCorrect = 0, answerTotal = 0, replicated = 0, replicationTotal = 0, pdfUsable = 0, pdfTotal = 0;
        for (var caseFile : caseFiles) {
            var definition = JSON.readTree(caseFile.toFile());
            var annotation = JSON.readTree(root.resolve("evals/annotations").resolve(definition.path("annotation").asText()).toFile());
            var scored = evaluateCase(definition, annotation, endpoint, apiKey, model, timeoutSeconds, mockThresholds);
            outputs.add(scored.output());
            coverageNumerator += scored.coverageNumerator(); coverageDenominator += scored.coverageDenominator();
            citationCorrect += scored.citationCorrect(); citationTotal += scored.citationTotal();
            importanceCorrect += scored.importanceCorrect(); importanceTotal += scored.importanceTotal();
            ungrounded += scored.ungrounded(); evaluatedClaims += scored.evaluatedClaims();
            structureCorrect += scored.structureCorrect(); structureTotal += scored.structureTotal();
            sourceFaithful += scored.sourceFaithful(); sourceChecks += scored.sourceChecks();
            answerCorrect += scored.answerCorrect(); answerTotal += scored.answerTotal();
            replicated += scored.replicated(); replicationTotal += scored.replicationTotal();
            pdfUsable += scored.pdfUsable(); pdfTotal += scored.pdfTotal();
            if (scored.output().path("failureReason").isTextual()) failures.add(scored.output().path("id").asText()
                    + ": " + scored.output().path("failureReason").asText());
        }

        double coverage = ratio(coverageNumerator, coverageDenominator);
        double citations = ratio(citationCorrect, citationTotal);
        double importance = ratio(importanceCorrect, importanceTotal);
        double ungroundedRatio = ratio(ungrounded, evaluatedClaims);
        double structuralValidity = ratio(structureCorrect, structureTotal);
        double sourceFidelity = ratio(sourceFaithful, sourceChecks);
        double answerConsistency = ratio(answerCorrect, answerTotal);
        double sourceReplicationRatio = replicationTotal == 0 ? 0 : replicated / replicationTotal;
        double pdfUsability = ratio(pdfUsable, pdfTotal);
        boolean passed = coverage >= coverageThreshold && citations >= citationThreshold
                && ungroundedRatio <= ungroundedThreshold && structuralValidity >= structureThreshold
                && sourceFidelity >= sourceFidelityThreshold && answerConsistency >= answerThreshold
                && sourceReplicationRatio <= replicationThreshold && pdfUsability >= pdfThreshold && failures.isEmpty();
        var finished = Instant.now();
        var report = JSON.createObjectNode();
        report.put("schemaVersion", "2.0");
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
        snapshot.put("mockExamQualityPolicyVersion", setting("MOCK_EXAM_QUALITY_POLICY_VERSION", "v1"));
        snapshot.put("mockExamHistorySimilarityThreshold", doubleSetting("MOCK_EXAM_HISTORY_SIMILARITY_THRESHOLD", .82));
        snapshot.put("mockExamTemplateVersion", setting("MOCK_EXAM_TEMPLATE_VERSION", "v1"));
        snapshot.put("mockExamFormulaPolicyVersion", setting("MOCK_EXAM_FORMULA_POLICY_VERSION", "v1"));
        snapshot.put("pdfUsabilityMode", "STRUCTURED_RENDER_INPUT");
        snapshot.set("thresholds", thresholds(coverageThreshold, citationThreshold, ungroundedThreshold,
                structureThreshold, sourceFidelityThreshold, answerThreshold, replicationThreshold, pdfThreshold));
        report.set("cases", outputs);
        var summary = report.putObject("summary");
        summary.put("coverage", coverage); summary.put("citationAccuracy", citations);
        summary.put("importanceConsistency", importance); summary.put("ungroundedUnmarkedRatio", ungroundedRatio);
        summary.put("structuralValidity", structuralValidity); summary.put("sourceFidelity", sourceFidelity);
        summary.put("answerConsistency", answerConsistency); summary.put("sourceReplicationRatio", sourceReplicationRatio);
        summary.put("pdfUsability", pdfUsability);
        report.set("thresholds", thresholds(coverageThreshold, citationThreshold, ungroundedThreshold,
                structureThreshold, sourceFidelityThreshold, answerThreshold, replicationThreshold, pdfThreshold));
        report.put("passed", passed); report.set("failures", failures);
        validateAgainstSchema(report, JSON.readTree(root.resolve("evals/report.schema.json").toFile()), "$");
        var reports = root.resolve("evals/reports"); Files.createDirectories(reports);
        var named = reports.resolve("golden-" + FILE_TIME.format(started) + ".json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(named.toFile(), report);
        JSON.writerWithDefaultPrettyPrinter().writeValue(reports.resolve("latest.json").toFile(), report);
        System.out.printf(Locale.ROOT, "Golden evaluation: coverage=%.3f citations=%.3f structure=%.3f sources=%.3f answers=%.3f replication=%.3f pdf=%.3f passed=%s%nReport: %s%n",
                coverage, citations, structuralValidity, sourceFidelity, answerConsistency,
                sourceReplicationRatio, pdfUsability, passed, named);
        if (!passed) throw new IllegalStateException("Golden evaluation did not meet configured thresholds; report was preserved at " + named);
    }

    private Score evaluateCase(JsonNode definition, JsonNode annotation, String endpoint, String apiKey,
                               String model, int timeoutSeconds, MockThresholds mockThresholds) {
        var result = JSON.createObjectNode();
        String id = definition.path("id").asText(); result.put("id", id); result.put("kind", definition.path("kind").asText());
        try {
            String raw = call(endpoint, apiKey, model, timeoutSeconds, definition);
            JsonNode generated = JSON.readTree(stripFence(raw)); result.set("output", generated);
            if ("MOCK_EXAM".equals(definition.path("kind").asText())) {
                return evaluateMockExam(result, generated, annotation, mockThresholds);
            }
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
            scores.put("structuralValidity", 1); scores.put("sourceFidelity", 1);
            scores.put("answerConsistency", 1); scores.put("sourceReplicationRatio", 0); scores.put("pdfUsability", 1);
            result.put("passed", c >= .5 && ca >= .9 && ug <= .1); result.putNull("failureReason");
            return new Score(result, coverageHits, expected.size(), citationHits, Math.max(1, citationCount),
                    importanceHits, importanceCount, ungroundedCount, claimCount,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        } catch (Exception exception) {
            result.set("output", JSON.createObjectNode());
            var scores = result.putObject("scores"); scores.put("coverage", 0); scores.put("citationAccuracy", 0);
            scores.put("importanceConsistency", 0); scores.put("ungroundedUnmarkedRatio", 1);
            scores.put("structuralValidity", 0); scores.put("sourceFidelity", 0);
            scores.put("answerConsistency", 0); scores.put("sourceReplicationRatio", 1); scores.put("pdfUsability", 0);
            result.put("passed", false); result.put("failureReason", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            if ("MOCK_EXAM".equals(definition.path("kind").asText())) {
                return new Score(result, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 1, 0, 1, 0, 1, 1, 1, 0, 1);
            }
            return new Score(result, 0, Math.max(1, annotation.path("coreKnowledgePoints").size()), 0, 1, 0,
                    annotation.path("expectedImportance").size(), 1, 1,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private Score evaluateMockExam(ObjectNode result, JsonNode generated, JsonNode annotation,
                                   MockThresholds thresholds) {
        String expectedStatus = annotation.path("expectedStatus").asText("GENERATED");
        String actualStatus = generated.path("status").asText();
        var scores = result.putObject("scores");
        if ("INSUFFICIENT_MATERIAL".equals(expectedStatus)) {
            boolean refused = expectedStatus.equals(actualStatus) && generated.path("questions").isArray()
                    && generated.path("questions").isEmpty() && !generated.path("missingKnowledgePoints").isEmpty();
            double value = refused ? 1 : 0;
            scores.put("coverage", 1); scores.put("citationAccuracy", 1);
            scores.put("importanceConsistency", 1); scores.put("ungroundedUnmarkedRatio", 0);
            scores.put("structuralValidity", value); scores.put("sourceFidelity", 1);
            scores.put("answerConsistency", 1); scores.put("sourceReplicationRatio", 0); scores.put("pdfUsability", 1);
            result.put("passed", refused);
            if (refused) result.putNull("failureReason"); else result.put("failureReason", "严格资料不足时未拒绝生成");
            return new Score(result, 0, 0, 0, 0, 0, 0, 0, 0,
                    value, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        var questions = generated.path("questions");
        var expectedCounts = annotation.path("expectedCounts");
        int expectedTotal = 0;
        var countFields = expectedCounts.fields();
        while (countFields.hasNext()) expectedTotal += countFields.next().getValue().asInt();
        double structureCorrect = expectedStatus.equals(actualStatus) ? 1 : 0;
        double structureTotal = 2;
        structureCorrect += questions.isArray() && questions.size() == expectedTotal ? 1 : 0;
        var actualCounts = new HashMap<String, Integer>();
        if (questions.isArray()) questions.forEach(question -> actualCounts.merge(question.path("questionType").asText(), 1, Integer::sum));
        var expectedFields = expectedCounts.fields();
        while (expectedFields.hasNext()) {
            var field = expectedFields.next(); structureTotal++;
            if (actualCounts.getOrDefault(field.getKey(), 0) == field.getValue().asInt()) structureCorrect++;
        }

        var allowedSources = new HashSet<>(strings(annotation.path("correctSources")));
        boolean allowGeneralKnowledge = annotation.path("allowGeneralKnowledge").asBoolean();
        boolean sawGeneralKnowledge = false;
        double sourceFaithful = 0, sourceChecks = 0, answerCorrect = 0, answerTotal = 0;
        double replicated = 0, replicationTotal = 0, pdfUsable = 0, pdfTotal = 0;
        var forbiddenStems = strings(annotation.path("forbiddenOriginalStems"));
        var formulaText = new StringBuilder();
        if (questions.isArray()) for (var question : questions) {
            structureTotal++;
            if (question.path("stem").isTextual() && !question.path("stem").asText().isBlank()
                    && question.path("score").canConvertToInt() && question.path("score").asInt() > 0
                    && expectedCounts.has(question.path("questionType").asText())) structureCorrect++;

            sourceChecks++;
            boolean generalKnowledge = question.path("usesGeneralKnowledge").asBoolean();
            sawGeneralKnowledge |= generalKnowledge;
            var questionSources = strings(question.path("sourceSegmentIds"));
            boolean sourcesValid = generalKnowledge
                    ? allowGeneralKnowledge && questionSources.isEmpty()
                    : !questionSources.isEmpty() && allowedSources.containsAll(questionSources);
            if (sourcesValid) sourceFaithful++;

            answerTotal++;
            if (answerConsistent(question)) answerCorrect++;

            replicationTotal++;
            if (copiesOriginal(question.path("stem").asText(), forbiddenStems)) replicated++;

            pdfTotal++;
            boolean safe = !question.path("stem").asText().isBlank();
            for (var formula : question.path("formulas")) {
                String expression = formula.path("expression").asText(); formulaText.append(' ').append(expression);
                safe &= safeFormula(expression);
            }
            if (safe) pdfUsable++;
        }
        if (annotation.path("requireGeneralKnowledge").asBoolean()) {
            sourceChecks++;
            if (sawGeneralKnowledge) sourceFaithful++;
        }
        if (!annotation.path("requiredFormulaFragments").isEmpty()) {
            pdfTotal++;
            boolean containsAll = strings(annotation.path("requiredFormulaFragments")).stream()
                    .allMatch(fragment -> formulaText.toString().contains(fragment));
            if (containsAll) pdfUsable++;
        }

        double structureScore = ratio(structureCorrect, structureTotal);
        double sourceScore = ratio(sourceFaithful, sourceChecks);
        double answerScore = ratio(answerCorrect, answerTotal);
        double replicationScore = replicationTotal == 0 ? 0 : replicated / replicationTotal;
        double pdfScore = ratio(pdfUsable, pdfTotal);
        scores.put("coverage", 1); scores.put("citationAccuracy", 1);
        scores.put("importanceConsistency", 1); scores.put("ungroundedUnmarkedRatio", 0);
        scores.put("structuralValidity", structureScore); scores.put("sourceFidelity", sourceScore);
        scores.put("answerConsistency", answerScore); scores.put("sourceReplicationRatio", replicationScore);
        scores.put("pdfUsability", pdfScore);
        boolean passed = structureScore >= thresholds.structure() && sourceScore >= thresholds.source()
                && answerScore >= thresholds.answer() && replicationScore <= thresholds.replication()
                && pdfScore >= thresholds.pdf();
        result.put("passed", passed);
        if (passed) result.putNull("failureReason"); else result.put("failureReason", "模拟卷结构、来源、答案、原创性或 PDF 可用性检查未通过");
        return new Score(result, 0, 0, 0, 0, 0, 0, 0, 0,
                structureCorrect, structureTotal, sourceFaithful, sourceChecks, answerCorrect, answerTotal,
                replicated, replicationTotal, pdfUsable, pdfTotal);
    }

    private static boolean answerConsistent(JsonNode question) {
        JsonNode answer = question.path("answer");
        JsonNode options = question.path("options");
        return switch (question.path("questionType").asText()) {
            case "SINGLE_CHOICE" -> options.size() == 4 && validOptionIndexes(answer.path("correctOptionIndexes"), options.size(), 1);
            case "MULTIPLE_CHOICE" -> options.size() == 4 && validOptionIndexes(answer.path("correctOptionIndexes"), options.size(), 2);
            case "TRUE_FALSE" -> answer.path("trueFalseAnswer").isBoolean();
            case "FILL_BLANK" -> nonEmptyArray(answer.path("blanks"));
            case "SHORT_ANSWER", "ESSAY", "COMPREHENSIVE" -> nonBlankText(answer.path("referenceAnswer"));
            case "CALCULATION" -> nonEmptyArray(answer.path("steps")) && nonBlankText(answer.path("finalAnswer"));
            default -> false;
        };
    }

    private static boolean validOptionIndexes(JsonNode indexes, int optionCount, int minimumCount) {
        if (!indexes.isArray() || indexes.size() < minimumCount) return false;
        var unique = new HashSet<Integer>();
        for (var index : indexes) if (!index.canConvertToInt() || index.asInt() < 0
                || index.asInt() >= optionCount || !unique.add(index.asInt())) return false;
        return true;
    }

    private static boolean nonEmptyArray(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) return false;
        for (var value : values) if (!nonBlankText(value)) return false;
        return true;
    }

    private static boolean nonBlankText(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }

    private static boolean copiesOriginal(String stem, List<String> originals) {
        String normalizedStem = normalizeText(stem);
        for (var original : originals) {
            String normalizedOriginal = normalizeText(original);
            if (normalizedOriginal.length() >= 12 && (normalizedStem.contains(normalizedOriginal)
                    || normalizedOriginal.contains(normalizedStem))) return true;
        }
        return false;
    }

    private static String normalizeText(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static boolean safeFormula(String expression) {
        String lower = expression.toLowerCase(Locale.ROOT);
        return expression.length() <= 500 && !lower.matches(".*\\\\(input|include|write|openout|read|usepackage|documentclass|newcommand|def|csname)\\b.*")
                && !lower.contains("\\0");
    }

    private String call(String endpoint, String apiKey, String model, int timeoutSeconds, JsonNode definition) throws Exception {
        StringBuilder context = new StringBuilder();
        for (var segment : definition.path("segments")) context.append("segmentId=").append(segment.path("id").asText())
                .append("\n").append(segment.path("content").asText()).append("\n---\n");
        String kind = definition.path("kind").asText();
        boolean outline = "OUTLINE".equals(kind);
        boolean mockExam = "MOCK_EXAM".equals(kind);
        String system = outline
                ? "你是课程提纲评测助手。仅按上下文输出 JSON，来源只能使用给定 segmentId。提纲最多四层；真题、教师强调和学生重点应提高 importance。"
                : mockExam
                ? "你是模拟卷评测助手。严格按请求输出 JSON；课程题只能引用给定 segmentId，不复制或轻微改写上下文原题。资料不足且不允许通用知识时返回 INSUFFICIENT_MATERIAL，不得减少题量；允许通用知识时，只有上下文确有缺口的题可使用通用知识，并必须设置 usesGeneralKnowledge=true、sourceSegmentIds=[]，不得伪造成课程来源。单选和多选必须且只能有四个选项，其他题型 options 必须为空；综合题包含案例或材料、多个关联小问及分点参考答案。公式只放 formulas.expression，不输出 TeX 模板或危险命令。"
                : "你是课程问答评测助手。仅按上下文输出 JSON。无相关证据时必须明确说资料不足并返回空来源；不得用未标记常识补答案。";
        String user = outline
                ? "输出 {\"nodes\":[{\"title\":\"知识点\",\"importance\":\"HIGH|MEDIUM|LOW\",\"sourceSegmentIds\":[\"UUID\"],\"children\":[]}]}。上下文：\n" + context
                : mockExam
                ? "请求：" + definition.path("request") + "。成功时输出 {\"status\":\"GENERATED\",\"questions\":[{\"questionType\":\"SINGLE_CHOICE|MULTIPLE_CHOICE|TRUE_FALSE|FILL_BLANK|SHORT_ANSWER|CALCULATION|ESSAY|COMPREHENSIVE\",\"stem\":\"题干\",\"options\":[],\"answer\":{\"correctOptionIndexes\":null,\"trueFalseAnswer\":null,\"blanks\":null,\"referenceAnswer\":null,\"steps\":null,\"finalAnswer\":null},\"score\":1,\"usesGeneralKnowledge\":false,\"sourceSegmentIds\":[\"UUID\"],\"formulas\":[]}]}；严格资料不足时输出 {\"status\":\"INSUFFICIENT_MATERIAL\",\"missingKnowledgePoints\":[\"缺口\"],\"questions\":[]}。上下文：\n" + context
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

    private ObjectNode thresholds(double coverage, double citations, double ungrounded,
                                  double structure, double sourceFidelity, double answers,
                                  double replication, double pdfUsability) {
        var node = JSON.createObjectNode(); node.put("coverage", coverage); node.put("citationAccuracy", citations);
        node.put("ungroundedUnmarkedRatio", ungrounded); node.put("structuralValidity", structure);
        node.put("sourceFidelity", sourceFidelity); node.put("answerConsistency", answers);
        node.put("sourceReplicationRatio", replication); node.put("pdfUsability", pdfUsability); return node;
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
                         double importanceTotal, double ungrounded, double evaluatedClaims,
                         double structureCorrect, double structureTotal, double sourceFaithful,
                         double sourceChecks, double answerCorrect, double answerTotal,
                         double replicated, double replicationTotal, double pdfUsable, double pdfTotal) {}
    private record MockThresholds(double structure, double source, double answer, double replication, double pdf) {}
}
