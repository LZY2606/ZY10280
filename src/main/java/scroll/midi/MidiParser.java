package scroll.midi;

import java.util.Arrays;

/**
 * Standard MIDI File 解析器。
 * 规则要点：
 * - running status 只能沿用 channel voice 状态(0x80-0xEF)；
 *   遇到 SysEx(F0/F7) 或 Meta(FF) 时清除，并记录 RUNNING_STATUS_CLEARED 诊断。
 * - VLQ 最多 4 字节，超过报 VLQ_TOO_LONG；轨道声明长度内未终止报 TRUNCATED_VLQ。
 * - EOT 之后仍有字节报 BYTES_AFTER_EOT；轨道声明长度越界报 TRACK_TRUNCATED。
 */
public final class MidiParser {

    public static MidiFile parse(byte[] bytes, String name) {
        MidiFile file = new MidiFile();
        file.name = name;
        file.source = bytes;
        Cursor c = new Cursor(bytes, 0, bytes.length);

        if (c.remaining() < 14 || !c.tag().equals("MThd")) {
            file.diagnostics.add(Diagnostic.global("BAD_HEADER", "缺少 MThd 头块", 0));
            return file;
        }
        c.skip(4);
        long headerLen = c.u32();
        if (headerLen < 6 || c.remaining() < 6) {
            file.diagnostics.add(Diagnostic.global("BAD_HEADER", "MThd 长度不足 6 字节", c.pos));
            return file;
        }
        file.format = c.u16();
        file.ntracks = c.u16();
        int divRaw = c.u16();
        file.division = Division.fromRaw(divRaw);
        if (file.format > 2) {
            file.diagnostics.add(Diagnostic.global("UNKNOWN_FORMAT",
                    "未知 format " + file.format + "，按 format 1 处理", 8));
        }
        if (headerLen > 6) {
            file.diagnostics.add(Diagnostic.global("HEADER_EXTRA",
                    "MThd 声明 " + headerLen + " 字节，跳过额外 " + (headerLen - 6) + " 字节", c.pos));
            c.skip((int) Math.min(headerLen - 6, c.remaining()));
        }

        for (int i = 0; i < file.ntracks; i++) {
            if (c.remaining() < 8 || !c.tag().equals("MTrk")) {
                file.diagnostics.add(Diagnostic.global("TRACKS_MISSING",
                        "声明 " + file.ntracks + " 轨，只找到 " + i + " 个 MTrk", c.pos));
                break;
            }
            parseTrack(file, bytes, c, i);
        }
        if (file.format == 2 && file.tracks.size() > 1) {
            file.diagnostics.add(Diagnostic.global("FORMAT2_INDEPENDENT",
                    "format 2：每轨拥有独立时间线，tempo map 按轨分别建立", 0));
        }
        return file;
    }

    private static void parseTrack(MidiFile file, byte[] bytes, Cursor fileCursor, int index) {
        MidiTrack track = new MidiTrack(index);
        track.chunkOffset = fileCursor.pos;
        fileCursor.skip(4);
        long len = fileCursor.u32();
        track.declaredLength = (int) Math.min(len, Integer.MAX_VALUE);
        track.dataOffset = fileCursor.pos;
        int end = track.dataOffset + track.declaredLength;
        if (end > bytes.length) {
            file.diagnostics.add(new Diagnostic(index, "TRACK_TRUNCATED",
                    "轨道声明长度 " + track.declaredLength + " 超出文件剩余 "
                            + (bytes.length - track.dataOffset) + " 字节", track.dataOffset));
            end = bytes.length;
        }
        fileCursor.pos = end; // 无论轨内解析如何，都按声明长度推进

        Cursor c = new Cursor(bytes, track.dataOffset, end);
        long absTick = 0;
        int runningStatus = 0;
        boolean eotSeen = false;
        boolean eotTailReported = false;

        while (c.remaining() > 0) {
            int deltaOffset = c.pos;
            long delta = readVlq(file, index, c);
            if (delta < 0) break; // 诊断已记录
            absTick += delta;
            if (c.remaining() == 0) {
                trackTruncated(file, index, "delta-time 后缺少事件字节", c.pos);
                break;
            }

            MidiEvent ev = new MidiEvent();
            ev.trackIndex = index;
            ev.seq = track.events.size();
            ev.absTick = absTick;
            ev.delta = delta;
            ev.deltaOffset = deltaOffset;
            ev.eventOffset = c.pos;

            int b = c.peek();
            if (b < 0x80) {
                if (runningStatus == 0) {
                    file.diagnostics.add(new Diagnostic(index, "RUNNING_STATUS_VIOLATION",
                            "数据字节 0x" + Integer.toHexString(b) + " 出现时无可用 running status", c.pos));
                    c.skip(1);
                    continue;
                }
                ev.runningStatus = true;
                ev.effectiveStatus = runningStatus;
            } else if (b < 0xF0) {
                ev.status = b;
                ev.effectiveStatus = b;
                runningStatus = b;
                c.skip(1);
            } else {
                // system/meta 一律清除 running status，规则显式记录
                if (runningStatus != 0) {
                    file.diagnostics.add(new Diagnostic(index, "RUNNING_STATUS_CLEARED",
                            "0x" + Integer.toHexString(b) + " 事件清除 running status 0x"
                                    + Integer.toHexString(runningStatus), c.pos));
                    runningStatus = 0;
                }
                ev.status = b;
                ev.effectiveStatus = b;
                c.skip(1);
            }

            int status = ev.effectiveStatus;
            if (status == 0xFF) {
                ev.kind = MidiEvent.Kind.META;
                if (c.remaining() < 1) { trackTruncated(file, index, "meta 事件缺少类型字节", c.pos); break; }
                ev.metaType = c.u8();
                long mlen = readVlq(file, index, c);
                if (mlen < 0) break;
                if (c.remaining() < mlen) {
                    trackTruncated(file, index, "meta 0x" + Integer.toHexString(ev.metaType)
                            + " 声明 " + mlen + " 字节，超出轨道边界", c.pos);
                    mlen = c.remaining();
                }
                ev.data = c.take((int) mlen);
                if (ev.metaType == 0x2F) {
                    eotSeen = true;
                }
            } else if (status == 0xF0 || status == 0xF7) {
                ev.kind = MidiEvent.Kind.SYSEX;
                long slen = readVlq(file, index, c);
                if (slen < 0) break;
                if (c.remaining() < slen) {
                    trackTruncated(file, index, "SysEx 声明 " + slen + " 字节，超出轨道边界", c.pos);
                    slen = c.remaining();
                }
                ev.data = c.take((int) slen);
            } else if (status >= 0x80 && status < 0xF0) {
                ev.kind = MidiEvent.Kind.CHANNEL;
                ev.command = status & 0xF0;
                ev.channel = status & 0x0F;
                int need = (ev.command == 0xC0 || ev.command == 0xD0) ? 1 : 2;
                if (c.remaining() < need) {
                    trackTruncated(file, index, "channel 事件需要 " + need + " 数据字节，轨道边界截断", c.pos);
                    ev.data = c.take(c.remaining());
                } else {
                    ev.data = c.take(need);
                }
            } else {
                // F1-F6, F8-FE 不应出现在 MIDI 文件轨道中
                int skip = switch (status) { case 0xF1, 0xF3 -> 1; case 0xF2 -> 2; default -> 0; };
                file.diagnostics.add(new Diagnostic(index, "UNEXPECTED_SYSTEM",
                        "轨道中出现 system 字节 0x" + Integer.toHexString(status) + "，跳过其 "
                                + skip + " 个数据字节", ev.eventOffset));
                c.skip(Math.min(skip, c.remaining()));
                continue;
            }

            ev.eventLength = c.pos - ev.eventOffset;
            ev.raw = Arrays.copyOfRange(bytes, deltaOffset, c.pos);
            track.events.add(ev);

            if (eotSeen && !eotTailReported && c.remaining() > 0) {
                eotTailReported = true;
                file.diagnostics.add(new Diagnostic(index, "BYTES_AFTER_EOT",
                        "End-of-Track 之后仍有 " + c.remaining() + " 字节", c.pos));
            }
        }
        file.tracks.add(track);
    }

    /** 读取 VLQ；出错返回 -1 并记录诊断。最多 4 字节。 */
    static long readVlq(MidiFile file, int trackIndex, Cursor c) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            if (c.remaining() == 0) {
                file.diagnostics.add(new Diagnostic(trackIndex, "TRUNCATED_VLQ",
                        "VLQ 在轨道边界处被截断", c.pos));
                return -1;
            }
            int b = c.u8();
            value = (value << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return value;
        }
        file.diagnostics.add(new Diagnostic(trackIndex, "VLQ_TOO_LONG",
                "VLQ 超过 4 字节上限", c.pos - 4));
        return -1;
    }

    private static void trackTruncated(MidiFile file, int trackIndex, String msg, int offset) {
        file.diagnostics.add(new Diagnostic(trackIndex, "TRACK_TRUNCATED", msg, offset));
    }

    /** 有界游标。 */
    static final class Cursor {
        final byte[] b; int pos; final int end;
        Cursor(byte[] b, int pos, int end) { this.b = b; this.pos = pos; this.end = end; }
        int remaining() { return end - pos; }
        int peek() { return b[pos] & 0xFF; }
        int u8() { return b[pos++] & 0xFF; }
        int u16() { int v = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF); pos += 2; return v; }
        long u32() {
            long v = ((long) (b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                    | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            pos += 4; return v;
        }
        String tag() {
            if (remaining() < 4) return "";
            return new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
        }
        void skip(int n) { pos += n; }
        byte[] take(int n) { byte[] r = Arrays.copyOfRange(b, pos, pos + n); pos += n; return r; }
    }
}
