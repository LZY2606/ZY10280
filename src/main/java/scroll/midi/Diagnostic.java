package scroll.midi;

/** 解析诊断：不中断解析，记录问题与文件偏移。 */
public record Diagnostic(int trackIndex, String code, String message, int offset) {
    public static Diagnostic global(String code, String message, int offset) {
        return new Diagnostic(-1, code, message, offset);
    }
}
