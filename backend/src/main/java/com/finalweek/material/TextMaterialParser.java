package com.finalweek.material;

import com.finalweek.task.ParseTask;
import com.finalweek.task.PermanentTaskException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TextMaterialParser implements MaterialParser {
    @Override public boolean supports(String mediaType) { return mediaType.equals("text/plain") || mediaType.equals("text/markdown"); }
    @Override public ExtractionResult extract(ParseTask task, Material material, Path source, Path workDirectory) {
        try {
            var text = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            if (text.indexOf('\0') >= 0) throw new PermanentTaskException("TEXT_ENCODING_INVALID", "文本文件包含二进制内容");
            var units = new ArrayList<ExtractedUnit>();
            int paragraph = 0;
            for (var value : text.split("\\n\\s*\\n")) {
                var normalized = value.strip();
                if (!normalized.isBlank()) units.add(ExtractedUnit.paragraph(++paragraph, normalized));
            }
            if (units.isEmpty()) throw new PermanentTaskException("CONTENT_EMPTY", "文本资料没有可提取内容");
            return new ExtractionResult(units, List.of(), null, null);
        } catch (PermanentTaskException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException("TEXT_READ_FAILED", "文本资料读取失败"); }
    }
}
