package com.finalweek.mockexam;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("finalweek.mock-exam.pdf")
public record MockExamPdfProperties(String executable, Duration compileTimeout, DataSize maxFileSize,
                                    DataSize maxLogSize, Path tempDirectory, String templateVersion,
                                    String formulaPolicyVersion) {
    public MockExamPdfProperties {
        if (executable == null || executable.isBlank() || executable.indexOf('\0') >= 0)
            throw new IllegalArgumentException("XeLaTeX 可执行文件配置无效");
        if (compileTimeout == null || compileTimeout.isZero() || compileTimeout.isNegative()
                || maxFileSize == null || maxFileSize.toBytes() < 1 || maxLogSize == null
                || maxLogSize.toBytes() < 1 || maxLogSize.toBytes() > DataSize.ofMegabytes(10).toBytes()
                || tempDirectory == null)
            throw new IllegalArgumentException("模拟卷 PDF 限制必须为有效正值");
        if (templateVersion == null || templateVersion.isBlank() || templateVersion.length() > 30)
            throw new IllegalArgumentException("模拟卷模板版本必须为 1–30 位非空文本");
        if (formulaPolicyVersion == null || formulaPolicyVersion.isBlank() || formulaPolicyVersion.length() > 30)
            throw new IllegalArgumentException("模拟卷公式策略版本必须为 1–30 位非空文本");
    }
}
