package com.beatscroll.midi;

import java.util.HexFormat;

/**
 * 单条事件。rawStart/rawEnd 为整条事件（含 delta-time）在文件中的字节范围 [start, end)，
 * deltaStart/deltaEnd 为 delta-time VLQ 自身的范围。
 */
public record MidiEvent(
        int trackIndex,
        int eventIndex,
        long seq,
        long deltaTicks,
        long absTick,
        int rawStart,
        int rawEnd,
        int deltaStart,
        int deltaEnd,
        Kind kind,
        int status,
        boolean usedRunningStatus,
        int channel,
        int data1,
        int data2,
        int metaType,
        byte[] payload) {

    public enum Kind {CHANNEL, SYSEX_F0, SYSEX_F7, META}

    public boolean isTempo() {
        return kind == Kind.META && metaType == 0x51 && payload != null && payload.length == 3;
    }

    public int tempoUsPerQuarter() {
        return ((payload[0] & 0xFF) << 16) | ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF);
    }

    public boolean isEndOfTrack() {
        return kind == Kind.META && metaType == 0x2F;
    }

    public String payloadHex() {
        return payload == null ? "" : HexFormat.of().formatHex(payload);
    }

    /** 语义描述，供页面与测试使用。 */
    public String describe() {
        return switch (kind) {
            case CHANNEL -> {
                String name = switch (status & 0xF0) {
                    case 0x80 -> "Note Off";
                    case 0x90 -> "Note On";
                    case 0xA0 -> "Poly Aftertouch";
                    case 0xB0 -> "Control Change";
                    case 0xC0 -> "Program Change";
                    case 0xD0 -> "Channel Aftertouch";
                    case 0xE0 -> "Pitch Bend";
                    default -> "Channel 0x" + Integer.toHexString(status);
                };
                String base = name + " ch=" + channel + " d1=" + data1
                        + (data2 >= 0 ? " d2=" + data2 : "");
                yield usedRunningStatus ? base + " (running status)" : base;
            }
            case SYSEX_F0 -> "SysEx F0 len=" + (payload == null ? 0 : payload.length);
            case SYSEX_F7 -> "SysEx F7 续包 len=" + (payload == null ? 0 : payload.length);
            case META -> "Meta 0x" + String.format("%02X", metaType)
                    + (isTempo() ? " tempo=" + tempoUsPerQuarter() + "µs/四分" : "")
                    + (isEndOfTrack() ? " (End of Track)" : "")
                    + " len=" + (payload == null ? 0 : payload.length);
        };
    }
}
