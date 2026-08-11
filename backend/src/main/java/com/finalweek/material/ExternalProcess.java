package com.finalweek.material;

import com.finalweek.task.PermanentTaskException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ExternalProcess {
    private ExternalProcess() {}
    static String run(Path directory, Duration timeout, String code, List<String> command) {
        try {
            var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly(); throw new PermanentTaskException(code, "外部解析工具执行超时");
            }
            if (process.exitValue() != 0) throw new PermanentTaskException(code,
                    "外部解析工具失败: " + output.substring(0, Math.min(300, output.length())));
            return output;
        } catch (PermanentTaskException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException(code, "无法启动外部解析工具"); }
    }
}
