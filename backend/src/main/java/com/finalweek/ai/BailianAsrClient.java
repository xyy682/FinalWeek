package com.finalweek.ai;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.task.BackgroundTaskRepository;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BailianAsrClient implements AsrClient {
    private static final Logger log = LoggerFactory.getLogger(BailianAsrClient.class);
    private final FinalWeekProperties properties;
    private final BackgroundTaskRepository tasks;
    private final ObjectMapper mapper;

    public BailianAsrClient(FinalWeekProperties properties, BackgroundTaskRepository tasks, ObjectMapper mapper) {
        this.properties = properties; this.tasks = tasks; this.mapper = mapper;
    }

    @Override public List<AsrSentence> recognize(UUID taskId, Path wavFile) {
        var ai = properties.ai();
        if (ai.apiKey() == null || ai.apiKey().isBlank()) throw new PermanentTaskException(
                "AI_CONFIG_MISSING", "音视频需要 ASR，但未配置 BAILIAN_API_KEY");
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ai.maxAttempts(); attempt++) {
            tasks.incrementApiAttempt(taskId);
            var recognizer = new Recognition();
            try {
                var param = RecognitionParam.builder().apiKey(ai.apiKey()).model(ai.asrModel())
                        .format("wav").sampleRate(16000)
                        .parameter("language_hints", new String[]{"zh", "en"})
                        .parameter("semantic_punctuation_enabled", true)
                        .build();
                // SDK 的本地文件非流式接口会一次提交完整文件并汇总句级时间戳。
                // 不再由应用按 100 ms 节奏实时推流，也不再按一分钟拆分 ASR 会话。
                var raw = recognizer.call(param, wavFile.toFile());
                var sentences = parseSentences(raw);
                if (sentences.isEmpty()) throw new IllegalStateException("ASR result contains no completed sentence");
                log.info("ASR local-file call completed taskId={} model={} sentenceCount={} firstPackageDelayMs={} lastPackageDelayMs={}",
                        taskId, ai.asrModel(), sentences.size(), recognizer.getFirstPackageDelay(), recognizer.getLastPackageDelay());
                return sentences;
            } catch (Exception exception) {
                log.warn("ASR local-file call failed taskId={} model={} attempt={}/{}",
                        taskId, ai.asrModel(), attempt, ai.maxAttempts(), exception);
                last = new RetryableTaskException("ASR_TEMPORARY_FAILURE", "录音文件转写暂时失败");
            } finally {
                try { recognizer.getDuplexApi().close(1000, "bye"); } catch (Exception ignored) {}
            }
        }
        throw last == null ? new RetryableTaskException("ASR_TEMPORARY_FAILURE", "录音文件转写暂时失败") : last;
    }

    List<AsrSentence> parseSentences(String raw) {
        try {
            var root = mapper.readTree(raw);
            var values = root.path("sentences");
            if (!values.isArray()) throw new IllegalArgumentException("sentences missing");
            var result = new ArrayList<AsrSentence>();
            for (JsonNode value : values) {
                var text = value.path("text").asText("").strip();
                if (text.isBlank()) continue;
                long begin = longValue(value, "begin_time", "beginTime");
                long end = longValue(value, "end_time", "endTime");
                if (begin < 0 || end < begin) continue;
                result.add(new AsrSentence(begin, end, text));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("ASR result JSON is invalid", exception);
        }
    }

    private long longValue(JsonNode node, String snakeCase, String camelCase) {
        var value = node.get(snakeCase);
        if (value == null || !value.canConvertToLong()) value = node.get(camelCase);
        return value != null && value.canConvertToLong() ? value.asLong() : -1;
    }
}