package com.finalweek.mockexam;

import org.springframework.stereotype.Component;

@Component
public class TexEscaper {
    public String escape(String value) {
        if (value == null) return "";
        var result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\textbackslash{}");
                case '{' -> result.append("\\{"); case '}' -> result.append("\\}");
                case '#' -> result.append("\\#"); case '$' -> result.append("\\$");
                case '%' -> result.append("\\%"); case '&' -> result.append("\\&");
                case '_' -> result.append("\\_"); case '^' -> result.append("\\textasciicircum{}");
                case '~' -> result.append("\\textasciitilde{}");
                case '\r' -> { }
                case '\n' -> result.append("\\par\n");
                default -> { if (!Character.isISOControl(c)) result.append(c); }
            }
        }
        return result.toString();
    }
}
