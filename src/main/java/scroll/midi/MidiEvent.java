package scroll.midi;

import java.util.Arrays;
import java.util.HexFormat;

/** 单条事件：保留 delta-time 与事件体在文件中的原始字节范围。 */
public final class MidiEvent {
    public enum Kind { CHANNEL, SYSEX, META }

    public int trackIndex;
    public int seq;            // 轨内序号
    public long absTick;       // 绝对 tick
    public long delta;         // delta-time
    public Kind kind;
    public int status;         // 原始状态字节；running status 时为 0
    public int effectiveStatus;
    public boolean runningStatus;
    public int channel = -1;
    public int command;
    public int metaType = -1;
    public byte[] data = new byte[0]; // channel: 数据字节; meta/sysex: 载荷
    public int deltaOffset;    // delta VLQ 在文件中的起始偏移
    public int eventOffset;    // 事件体（状态字节或首数据字节）偏移
    public int eventLength;    // 事件体长度（不含 delta）
    public byte[] raw;         // delta + 事件体原始字节

    public String dataHex() {
        return HexFormat.of().formatHex(data);
    }

    public String range() {
        return "[" + deltaOffset + ", " + (eventOffset + eventLength) + ")";
    }

    public String describe() {
        return switch (kind) {
            case CHANNEL -> switch (command) {
                case 0x80 -> "Note Off ch=" + (channel + 1) + " key=" + (data[0] & 0xFF);
                case 0x90 -> "Note On  ch=" + (channel + 1) + " key=" + (data[0] & 0xFF)
                        + " vel=" + (data[1] & 0xFF);
                case 0xA0 -> "Poly Aftertouch ch=" + (channel + 1);
                case 0xB0 -> "Control Change ch=" + (channel + 1) + " cc=" + (data[0] & 0xFF);
                case 0xC0 -> "Program Change ch=" + (channel + 1) + " pg=" + (data[0] & 0xFF);
                case 0xD0 -> "Channel Aftertouch ch=" + (channel + 1);
                case 0xE0 -> "Pitch Bend ch=" + (channel + 1);
                default -> "Channel 0x" + Integer.toHexString(command);
            };
            case SYSEX -> (effectiveStatus == 0xF0 ? "SysEx" : "SysEx Escape(F7)")
                    + " len=" + data.length;
            case META -> "Meta 0x" + String.format("%02X", metaType) + " " + metaName();
        };
    }

    private String metaName() {
        return switch (metaType) {
            case 0x01 -> "Text";
            case 0x03 -> "Track Name";
            case 0x2F -> "End of Track";
            case 0x51 -> "Set Tempo";
            case 0x58 -> "Time Signature";
            case 0x59 -> "Key Signature";
            default -> "len=" + data.length;
        };
    }

    /** 语义等价比较（用于 parse-export-parse 测试）。 */
    public boolean semanticallyEquals(MidiEvent o) {
        return absTick == o.absTick && delta == o.delta && kind == o.kind
                && effectiveStatus == o.effectiveStatus && metaType == o.metaType
                && channel == o.channel && command == o.command
                && Arrays.equals(data, o.data);
    }
}
