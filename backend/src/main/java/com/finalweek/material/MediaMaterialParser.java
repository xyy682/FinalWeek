package com.finalweek.material;

import com.finalweek.ai.AsrClient;
import com.finalweek.ai.OcrClient;
import com.finalweek.task.ParseTask;
import com.finalweek.task.PermanentTaskException;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class MediaMaterialParser implements MaterialParser {
    private final AsrClient asr;
    private final OcrClient ocr;
    public MediaMaterialParser(AsrClient asr, OcrClient ocr) { this.asr = asr; this.ocr = ocr; }
    @Override public boolean supports(String mediaType) { return mediaType.equals("audio/mpeg") || mediaType.equals("video/mp4"); }

    @Override public ExtractionResult extract(ParseTask task, Material material, Path source, Path workDirectory) {
        boolean video = material.getMediaType().equals("video/mp4");
        long duration = probeDuration(source, workDirectory);
        if (duration <= 0 || duration > Duration.ofHours(2).toMillis()) throw new PermanentTaskException(
                "MEDIA_DURATION_INVALID", "音视频时长无效或超过 2 小时");
        var warnings = new ArrayList<String>(); var asrUnits = new ArrayList<ExtractedUnit>();
        try { asrUnits.addAll(extractAudio(task, source, workDirectory, video)); }
        catch (RuntimeException exception) {
            if (!video) throw exception;
            warnings.add("音轨识别失败，已继续保留视频画面 OCR");
        }
        var ocrUnits = new ArrayList<ExtractedUnit>();
        if (video) {
            try { ocrUnits.addAll(extractFrames(task, source, workDirectory)); }
            catch (RuntimeException exception) { warnings.add("视频画面 OCR 失败，已继续保留音轨转写"); }
        }
        var merged = merge(asrUnits, ocrUnits, video);
        if (merged.isEmpty()) throw new PermanentTaskException("CONTENT_EMPTY", "音视频的 ASR 与 OCR 均未产生有效内容");
        return new ExtractionResult(merged, warnings, null, duration);
    }

    private long probeDuration(Path source, Path work) {
        var output = ExternalProcess.run(work, Duration.ofSeconds(30), "MEDIA_PROBE_FAILED",
                List.of("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of",
                        "default=noprint_wrappers=1:nokey=1", source.toString())).strip();
        try { return Math.round(Double.parseDouble(output) * 1000); }
        catch (NumberFormatException exception) { throw new PermanentTaskException("MEDIA_PROBE_FAILED", "无法读取音视频时长"); }
    }

    private List<ExtractedUnit> extractAudio(ParseTask task, Path source, Path work, boolean video) {
        var pattern = work.resolve("audio-%03d.wav");
        ExternalProcess.run(work, Duration.ofMinutes(3), "AUDIO_EXTRACT_FAILED",
                List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", source.toString(),
                        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "segment",
                        "-segment_time", "60", pattern.toString()));
        var result = new ArrayList<ExtractedUnit>();
        try (var paths = Files.list(work)) {
            var audioFiles = paths.filter(path -> path.getFileName().toString().matches("audio-\\d{3}\\.wav"))
                    .sorted().toList();
            for (int index = 0; index < audioFiles.size(); index++) {
                long offset = index * 60_000L;
                for (var sentence : asr.recognize(task.getId(), audioFiles.get(index))) {
                    long start = offset + sentence.startTimeMs(), end = offset + sentence.endTimeMs();
                    result.add(new ExtractedUnit(video ? SourceType.VIDEO_TIME : SourceType.AUDIO_TIME,
                            sentence.text(), null, null, null, start, end, sentence.text(), null));
                }
            }
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException("AUDIO_EXTRACT_FAILED", "音频分段读取失败"); }
        return result;
    }

    private List<ExtractedUnit> extractFrames(ParseTask task, Path source, Path work) {
        var output = ExternalProcess.run(work, Duration.ofMinutes(5), "VIDEO_FRAME_FAILED",
                List.of("ffmpeg", "-hide_banner", "-y", "-i", source.toString(), "-vf",
                        "select='isnan(prev_selected_t)+gt(scene,0.30)+gte(t-prev_selected_t,30)',scale=1280:-2,showinfo",
                        "-vsync", "vfr", work.resolve("frame-%06d.png").toString()));
        var times = java.util.regex.Pattern.compile("pts_time:([0-9.]+)").matcher(output);
        var timestamps = new ArrayList<Long>();
        while (times.find()) timestamps.add(Math.round(Double.parseDouble(times.group(1)) * 1000));
        var units = new ArrayList<ExtractedUnit>(); var hashes = new ArrayList<Long>();
        try (var paths = Files.list(work)) {
            var frames = paths.filter(path -> path.getFileName().toString().matches("frame-\\d{6}\\.png"))
                    .sorted().toList();
            for (int index = 0; index < frames.size(); index++) {
                long hash = averageHash(ImageIO.read(frames.get(index).toFile()));
                if (hashes.stream().anyMatch(value -> Long.bitCount(value ^ hash) <= 5)) continue;
                hashes.add(hash);
                var text = ocr.recognize(task.getId(), frames.get(index)).strip();
                if (!text.isBlank()) {
                    long time = index < timestamps.size() ? timestamps.get(index) : index * 30_000L;
                    units.add(new ExtractedUnit(SourceType.VIDEO_TIME, text, null, null, null,
                            time, time + 1000, null, text));
                }
            }
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new PermanentTaskException("VIDEO_FRAME_FAILED", "视频关键帧读取失败"); }
        return units;
    }

    private long averageHash(BufferedImage original) {
        var scaled = original.getScaledInstance(8, 8, Image.SCALE_AREA_AVERAGING);
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = image.createGraphics(); graphics.drawImage(scaled, 0, 0, null); graphics.dispose();
        int sum = 0; var values = new int[64];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) { int v = image.getRGB(x, y) & 0xff; values[y * 8 + x] = v; sum += v; }
        int average = sum / 64; long hash = 0;
        for (int i = 0; i < 64; i++) if (values[i] >= average) hash |= 1L << i;
        return hash;
    }

    static List<ExtractedUnit> merge(List<ExtractedUnit> asrUnits, List<ExtractedUnit> ocrUnits, boolean video) {
        if (!video) return asrUnits;
        var result = new ArrayList<ExtractedUnit>(); var used = new HashSet<ExtractedUnit>();
        for (var speech : asrUnits) {
            var matching = ocrUnits.stream().filter(frame -> frame.startTimeMs() >= speech.startTimeMs() - 2000
                    && frame.startTimeMs() <= speech.endTimeMs() + 2000).toList();
            used.addAll(matching);
            var visual = matching.stream().map(ExtractedUnit::ocrText).distinct().reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);
            var content = visual.isBlank() ? speech.asrText() : speech.asrText() + "\n" + visual;
            result.add(new ExtractedUnit(SourceType.VIDEO_TIME, content, null, null, null,
                    speech.startTimeMs(), speech.endTimeMs(), speech.asrText(), visual.isBlank() ? null : visual));
        }
        ocrUnits.stream().filter(unit -> !used.contains(unit)).forEach(result::add);
        result.sort(Comparator.comparing(ExtractedUnit::startTimeMs));
        return result;
    }
}
