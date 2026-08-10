package com.finalweek.upload;

import com.finalweek.common.api.BusinessException;
import com.finalweek.common.config.FinalWeekProperties;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class UploadFilePolicy {
    private static final Set<String> DOCUMENTS = Set.of("pdf", "pptx", "txt", "md");
    private static final Set<String> MEDIA = Set.of("mp3", "mp4");
    private final FinalWeekProperties properties;

    public UploadFilePolicy(FinalWeekProperties properties) { this.properties = properties; }

    public ValidatedFile validateDeclaration(String filename, long size) {
        var displayName = Path.of(filename).getFileName().toString().trim();
        if (displayName.isBlank() || displayName.length() > 255) throw invalid("文件名必须为 1–255 个字符");
        var separator = displayName.lastIndexOf('.');
        var extension = separator < 0 ? "" : displayName.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!DOCUMENTS.contains(extension) && !MEDIA.contains(extension)) {
            throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_TYPE_UNSUPPORTED",
                    "仅支持 PDF、PPTX、TXT/MD、MP3 和 MP4");
        }
        var maxMb = DOCUMENTS.contains(extension)
                ? properties.limits().documentMaxSizeMb() : properties.limits().mediaMaxSizeMb();
        if (size <= 0 || size > maxMb * 1024L * 1024L) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_SIZE_EXCEEDED",
                    "文件大小必须大于 0 且不超过 " + maxMb + "MB");
        }
        return new ValidatedFile(displayName, extension, mediaType(extension));
    }

    public void validateContent(Path file, String extension) {
        try {
            var header = new byte[12];
            int length;
            try (var input = Files.newInputStream(file)) { length = input.read(header); }
            var valid = switch (extension) {
                case "pdf" -> startsWith(header, length, "%PDF-".getBytes(StandardCharsets.US_ASCII));
                case "pptx" -> isPptx(file, header, length);
                case "txt", "md" -> isUtf8Text(file);
                case "mp3" -> isMp3(header, length);
                case "mp4" -> length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
                default -> false;
            };
            if (!valid) throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_TYPE_UNSUPPORTED",
                    "文件内容与声明格式不符或文件已损坏");
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_TYPE_UNSUPPORTED", "无法校验文件内容");
        }
    }

    private boolean isPptx(Path file, byte[] header, int length) throws IOException {
        if (length < 2 || header[0] != 'P' || header[1] != 'K') return false;
        try (var zip = new ZipFile(file.toFile())) {
            return zip.getEntry("[Content_Types].xml") != null && zip.getEntry("ppt/presentation.xml") != null;
        }
    }

    private boolean isUtf8Text(Path file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new java.io.InputStreamReader(Files.newInputStream(file), decoder)) {
            var chars = new char[8192];
            int length;
            while ((length = reader.read(chars)) >= 0) {
                for (int index = 0; index < length; index++) if (chars[index] == '\0') return false;
            }
            return true;
        } catch (java.nio.charset.CharacterCodingException exception) {
            return false;
        }
    }

    private boolean isMp3(byte[] header, int length) {
        return length >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3'
                || length >= 2 && (header[0] & 0xff) == 0xff && (header[1] & 0xe0) == 0xe0;
    }

    private boolean startsWith(byte[] value, int valueLength, byte[] prefix) {
        if (valueLength < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (value[index] != prefix[index]) return false;
        return true;
    }

    private String mediaType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "mp3" -> "audio/mpeg";
            case "mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public record ValidatedFile(String filename, String extension, String mediaType) {}
}
