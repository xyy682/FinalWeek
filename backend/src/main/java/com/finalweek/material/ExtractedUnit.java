package com.finalweek.material;

public record ExtractedUnit(SourceType sourceType, String content, Integer pageNumber, Integer slideNumber,
                            Integer paragraphNumber, Long startTimeMs, Long endTimeMs,
                            String asrText, String ocrText) {
    public static ExtractedUnit page(int page, String text) {
        return new ExtractedUnit(SourceType.PDF_PAGE, text, page, null, null, null, null, null, null);
    }
    public static ExtractedUnit slide(int slide, String text) {
        return new ExtractedUnit(SourceType.PPT_SLIDE, text, null, slide, null, null, null, null, null);
    }
    public static ExtractedUnit paragraph(int paragraph, String text) {
        return new ExtractedUnit(SourceType.TEXT_PARAGRAPH, text, null, null, paragraph, null, null, null, null);
    }
}
