package com.finalweek.task;
public class RetryableTaskException extends RuntimeException {
    private final String code;
    public RetryableTaskException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
