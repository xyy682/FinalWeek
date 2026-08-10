package com.finalweek.task;

import org.springframework.stereotype.Service;

@Service
public class DeferredParsePipeline implements TaskPipeline {
    @Override public void execute(ParseTask task) {
        throw new RetryableTaskException("PIPELINE_NOT_READY", "解析流水线将在实施阶段 5 启用");
    }
}
