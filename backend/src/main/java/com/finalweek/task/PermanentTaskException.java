package com.finalweek.task;
public class PermanentTaskException extends RuntimeException {
    private final String code;
    public PermanentTaskException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
