package com.finalweek.mockexam;

import com.finalweek.task.PermanentTaskException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MockExamFormulaValidator {
    private static final Set<String> ALLOWED = Set.of("frac", "sqrt", "sum", "prod", "int", "lim", "log",
            "ln", "sin", "cos", "tan", "cdot", "times", "div", "pm", "le", "ge", "ne", "approx",
            "infty", "alpha", "beta", "gamma", "delta", "theta", "lambda", "mu", "pi", "sigma",
            "phi", "omega", "mathrm", "text", "left", "right", "begin", "end");
    private static final Set<String> ENVIRONMENTS = Set.of("matrix", "pmatrix", "bmatrix", "cases", "aligned");
    private static final Pattern COMMAND = Pattern.compile("\\\\([A-Za-z]+)");
    private static final Pattern ENVIRONMENT = Pattern.compile("\\\\(?:begin|end)\\{([A-Za-z*]+)}");
    private static final Pattern DANGEROUS = Pattern.compile("(?i)\\\\(input|include|write|read|openout|openin|immediate|usepackage|documentclass|newcommand|renewcommand|def|csname|catcode|special|href|url|includegraphics|write18)\\b");

    public void validate(String formula) {
        if (formula == null || formula.isBlank()) return;
        if (formula.length() > 500 || DANGEROUS.matcher(formula).find() || formula.indexOf('\0') >= 0
                || formula.contains("^^") || formula.indexOf('%') >= 0 || formula.indexOf('#') >= 0
                || formula.indexOf('$') >= 0)
            throw unsafe();
        var environments = ENVIRONMENT.matcher(formula);
        while (environments.find()) if (!ENVIRONMENTS.contains(environments.group(1))) throw unsafe();
        var commands = COMMAND.matcher(formula);
        while (commands.find()) if (!ALLOWED.contains(commands.group(1))) throw unsafe();
        if (!balanced(formula, '{', '}')) throw unsafe();
    }
    private boolean balanced(String value, char open, char close) {
        int depth = 0; for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == open) depth++; if (value.charAt(i) == close && --depth < 0) return false;
        } return depth == 0;
    }
    private PermanentTaskException unsafe() {
        return new PermanentTaskException("MOCK_EXAM_FORMULA_UNSAFE", "试题包含不受支持或不安全的公式命令");
    }
}
