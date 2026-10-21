package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.util.List;

public final class PlatformException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<String> details;

    public PlatformException(int status, String code, String message) {
        this(status, code, message, List.of());
    }
    public PlatformException(int status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }
    public int status() { return status; }
    public String code() { return code; }
    public List<String> details() { return details; }
}
