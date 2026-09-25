package com.beatscroll.midi;

/** 解析诊断：不静默修复，逐条记录位置与原因。 */
public record Diagnostic(Severity severity, String code, int trackIndex, long offset, String message) {

    public enum Severity {INFO, WARN, ERROR}
}
