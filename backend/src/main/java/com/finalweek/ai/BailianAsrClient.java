package com.finalweek.ai;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.task.BackgroundTaskRepository;
import com.finalweek.task.PermanentTaskException;
import com.finalweek.task.RetryableTaskException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class BailianAsrClient implements AsrClient {
    private final FinalWeekProperties properties;
    private final BackgroundTaskRepository tasks;
    public BailianAsrClient(FinalWeekProperties properties, BackgroundTaskRepository tasks) {
        this.properties = properties; this.tasks = tasks;
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
                var results = new ArrayList<AsrSentence>();
                var callbackError = new AtomicReference<Exception>();
                var callback = new ResultCallback<RecognitionResult>() {
                    @Override public void onEvent(RecognitionResult result) {
                        if (result.isSentenceEnd() && result.getSentence() != null
                                && result.getSentence().getText() != null && !result.getSentence().getText().isBlank()) {
                            results.add(new AsrSentence(result.getSentence().getBeginTime(),
                                    result.getSentence().getEndTime(), result.getSentence().getText().strip()));
                        }
                    }
                    @Override public void onComplete() {}
                    @Override public void onError(Exception exception) { callbackError.set(exception); }
                };
                var param = RecognitionParam.builder().apiKey(ai.apiKey()).model(ai.asrModel())
                        .format("wav").sampleRate(16000).parameter("language_hints", new String[]{"zh", "en"}).build();
                recognizer.call(param, callback);
                try (var input = Files.newInputStream(wavFile)) {
                    var buffer = new byte[3200]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count > 0) recognizer.sendAudioFrame(ByteBuffer.wrap(buffer, 0, count));
                        Thread.sleep(95);
                    }
                }
                recognizer.stop();
                if (callbackError.get() != null) throw callbackError.get();
                return List.copyOf(results);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RetryableTaskException("ASR_INTERRUPTED", "ASR 调用被中断");
            } catch (Exception exception) {
                last = new RetryableTaskException("ASR_TEMPORARY_FAILURE", "ASR 调用失败");
            } finally {
                try { recognizer.getDuplexApi().close(1000, "bye"); } catch (Exception ignored) {}
            }
        }
        throw last == null ? new RetryableTaskException("ASR_TEMPORARY_FAILURE", "ASR 调用失败") : last;
    }
}
