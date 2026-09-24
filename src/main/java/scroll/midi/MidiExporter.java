package scroll.midi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** 由解析模型重建 SMF 字节流：事件原始字节原样拼接，保证 parse-export-parse 语义保持。 */
public final class MidiExporter {

    public static byte[] export(MidiFile file) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("MThd".getBytes(StandardCharsets.US_ASCII));
        writeU32(out, 6);
        writeU16(out, file.format);
        writeU16(out, file.ntracks);
        writeU16(out, file.division.raw());
        for (MidiTrack track : file.tracks) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (MidiEvent ev : track.events) {
                body.writeBytes(ev.raw);
            }
            out.writeBytes("MTrk".getBytes(StandardCharsets.US_ASCII));
            writeU32(out, body.size());
            out.writeBytes(body.toByteArray());
        }
        return out.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) (v >>> 24) & 0xFF);
        out.write((int) (v >>> 16) & 0xFF);
        out.write((int) (v >>> 8) & 0xFF);
        out.write((int) v & 0xFF);
    }
}
