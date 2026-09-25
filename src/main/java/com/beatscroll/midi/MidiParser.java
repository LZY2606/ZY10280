package com.beatscroll.midi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standard MIDI File 解析器。
 *
 * <p>规则要点：
 * <ul>
 *   <li>running status 只能由 channel voice 消息（0x8n–0xEn）建立；
 *       SysEx（F0/F7）与 meta（FF）事件一律将其清除，system common 状态也清除。</li>
 *   <li>delta-time 为 VLQ，规范上限 4 字节（28 bit）；超长记 VLQ_TOO_LONG 并继续按位累积。</li>
 *   <li>track 声明长度超过文件剩余记 TRACK_TRUNCATED，按实际可用字节解析。</li>
 *   <li>EOT（FF 2F）之后、声明长度内的剩余字节记 TRAILING_BYTES_AFTER_EOT。</li>
 * </ul>
 */
public final class MidiParser {

    private byte[] data;
    private List<Diagnostic> diagnostics;
    private long seqCounter;

    public ParseResult parse(byte[] bytes) {
        this.data = bytes;
        this.diagnostics = new ArrayList<>();
        this.seqCounter = 0;
        List<TrackData> tracks = new ArrayList<>();

        if (bytes.length < 8 || !asciiAt(0, "MThd")) {
            diagnostics.add(diag(Diagnostic.Severity.ERROR, "BAD_HEADER", -1, 0,
                    "缺少 MThd 头块，不是可识别的 Standard MIDI File"));
            return new ParseResult(null, tracks, diagnostics);
        }
        long headerLength = u32(4);
        if (headerLength < 6 || 8 + headerLength > bytes.length) {
            diagnostics.add(diag(Diagnostic.Severity.ERROR, "BAD_HEADER", -1, 4,
                    "MThd 长度字段非法: " + headerLength));
            return new ParseResult(null, tracks, diagnostics);
        }
        int format = u16(8);
        int ntrks = u16(10);
        int division = u16(12);
        MidiHeader header = MidiHeader.of(format, ntrks, division);
        if (format > 2) {
            diagnostics.add(diag(Diagnostic.Severity.WARN, "UNKNOWN_FORMAT", -1, 8,
                    "未知 format " + format + "，仍按块结构解析"));
        }
        if (headerLength != 6) {
            diagnostics.add(diag(Diagnostic.Severity.INFO, "HEADER_EXTRA_BYTES", -1, 8,
                    "MThd 声明 " + headerLength + " 字节，跳过标准 6 字节之外的部分"));
        }
        int pos = 8 + (int) headerLength;

        int trackIndex = 0;
        while (trackIndex < ntrks) {
            if (pos + 8 > bytes.length) {
                diagnostics.add(diag(Diagnostic.Severity.ERROR, "MISSING_TRACK_CHUNK", trackIndex, pos,
                        "头块声明 " + ntrks + " 轨，但文件在第 " + trackIndex + " 轨前结束"));
                break;
            }
            String chunkId = new String(bytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long chunkLength = u32(pos + 4);
            if (!"MTrk".equals(chunkId)) {
                long skip = Math.min(chunkLength, bytes.length - (pos + 8L));
                diagnostics.add(diag(Diagnostic.Severity.INFO, "UNKNOWN_CHUNK", trackIndex, pos,
                        "跳过未知块 '" + chunkId + "'（" + skip + " 字节），不计入轨号"));
                pos += 8 + (int) skip;
                continue;
            }
            int chunkOffset = pos;
            pos += 8;
            long available = Math.min(chunkLength, bytes.length - (long) pos);
            if (available < chunkLength) {
                diagnostics.add(diag(Diagnostic.Severity.ERROR, "TRACK_TRUNCATED", trackIndex, chunkOffset,
                        "MTrk 声明 " + chunkLength + " 字节，文件仅剩 " + available + " 字节，按截断解析"));
            }
            int trackEnd = pos + (int) available;
            TrackData track = parseTrack(trackIndex, chunkOffset, pos, trackEnd, chunkLength);
            tracks.add(track);
            pos = trackEnd;
            trackIndex++;
        }
        if (pos < bytes.length) {
            diagnostics.add(diag(Diagnostic.Severity.WARN, "TRAILING_FILE_BYTES", -1, pos,
                    "全部 " + ntrks + " 轨解析完毕后文件仍剩 " + (bytes.length - pos) + " 字节"));
        }
        return new ParseResult(header, tracks, diagnostics);
    }

    private TrackData parseTrack(int trackIndex, int chunkOffset, int start, int end, long declaredLength) {
        List<MidiEvent> events = new ArrayList<>();
        long absTick = 0;
        int runningStatus = 0;
        boolean eot = false;
        int pos = start;
        int eventIndex = 0;

        while (pos < end && !eot) {
            int eventStart = pos;

            // ---- delta-time VLQ ----
            long delta = 0;
            int vlqBytes = 0;
            boolean terminated = false;
            boolean tooLong = false;
            while (pos < end) {
                int b = data[pos++] & 0xFF;
                vlqBytes++;
                if (vlqBytes > 4 && !tooLong) {
                    tooLong = true;
                diagnostics.add(diag(Diagnostic.Severity.ERROR, "VLQ_TOO_LONG", trackIndex, eventStart,
                            "delta-time VLQ 超过 4 字节上限（28 bit），取值截断到 28 bit"));
                }
                delta = (delta << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    terminated = true;
                    break;
                }
            }
            if (!terminated) {
                diagnostics.add(diag(Diagnostic.Severity.ERROR, "VLQ_UNTERMINATED", trackIndex, eventStart,
                        "delta-time VLQ 在轨末尾仍未终止"));
                break;
            }
            if (tooLong) {
                delta = Math.min(delta, 0x0FFF_FFFFL); // 取值截断到 28 bit，保证可再编码
            }
            int deltaEnd = pos;
            absTick += delta;
            if (pos >= end) {
                diagnostics.add(diag(Diagnostic.Severity.ERROR, "EVENT_TRUNCATED", trackIndex, eventStart,
                        "delta-time 之后缺少事件体"));
                break;
            }

            int head = data[pos] & 0xFF;
            int status;
            boolean usedRunning = false;

            if (head < 0x80) {
                if (runningStatus == 0) {
                    diagnostics.add(diag(Diagnostic.Severity.ERROR, "DATA_WITHOUT_STATUS", trackIndex, pos,
                            "running status 未建立或已被 system/meta 事件清除，遇到孤立数据字节 0x"
                                    + Integer.toHexString(head).toUpperCase() + "，跳过 1 字节重新同步"));
                    pos++;
                    continue;
                }
                status = runningStatus;
                usedRunning = true;
            } else if (head <= 0xEF) {
                status = head;
                pos++;
                runningStatus = status;
            } else if (head == 0xFF) {
                // ---- meta event ----
                pos++;
                if (pos >= end) {
                    diagnostics.add(diag(Diagnostic.Severity.ERROR, "EVENT_TRUNCATED", trackIndex, eventStart,
                            "meta 事件缺少类型字节"));
                    break;
                }
                int metaType = data[pos++] & 0xFF;
                long len = readLengthVlq(trackIndex, pos, end);
                pos += lengthVlqSize;
                if (pos + len > end) {
                    diagnostics.add(diag(Diagnostic.Severity.ERROR, "EVENT_TRUNCATED", trackIndex, eventStart,
                            "meta 0x" + Integer.toHexString(metaType) + " 声明 " + len
                                    + " 字节，轨内仅剩 " + (end - pos) + " 字节"));
                    len = end - pos;
                }
                byte[] payload = Arrays.copyOfRange(data, pos, pos + (int) len);
                pos += (int) len;
                runningStatus = 0; // meta 事件清除 running status
                events.add(new MidiEvent(trackIndex, eventIndex++, seqCounter++, delta, absTick,
                        eventStart, pos, eventStart, deltaEnd,
                        MidiEvent.Kind.META, 0xFF, false, -1, -1, -1, metaType, payload));
                if (metaType == 0x2F) {
                    eot = true;
                }
                continue;
            } else if (head == 0xF0 || head == 0xF7) {
                // ---- SysEx / 续包 ----
                pos++;
                long len = readLengthVlq(trackIndex, pos, end);
                pos += lengthVlqSize;
                if (pos + len > end) {
                    diagnostics.add(diag(Diagnostic.Severity.ERROR, "EVENT_TRUNCATED", trackIndex, eventStart,
                            "SysEx 声明 " + len + " 字节，轨内仅剩 " + (end - pos) + " 字节"));
                    len = end - pos;
                }
                byte[] payload = Arrays.copyOfRange(data, pos, pos + (int) len);
                pos += (int) len;
                runningStatus = 0; // SysEx 清除 running status
                events.add(new MidiEvent(trackIndex, eventIndex++, seqCounter++, delta, absTick,
                        eventStart, pos, eventStart, deltaEnd,
                        head == 0xF0 ? MidiEvent.Kind.SYSEX_F0 : MidiEvent.Kind.SYSEX_F7,
                        head, false, -1, -1, -1, -1, payload));
                continue;
            } else {
                // F1–FE：SMF 中不应出现的 system common / realtime 状态
                diagnostics.add(diag(Diagnostic.Severity.ERROR, "UNSUPPORTED_SYSTEM_STATUS", trackIndex, pos,
                        "SMF 轨内出现非法 system 状态字节 0x" + Integer.toHexString(head).toUpperCase()
                                + "，清除 running status 并跳过 1 字节"));
                runningStatus = 0;
                pos++;
                continue;
            }

            // ---- channel voice / mode ----
            int needed = ((status & 0xF0) == 0xC0 || (status & 0xF0) == 0xD0) ? 1 : 2;
            if (pos + needed > end) {
                diagnostics.add(diag(Diagnostic.Severity.ERROR, "EVENT_TRUNCATED", trackIndex, eventStart,
                        "channel 事件 0x" + Integer.toHexString(status).toUpperCase()
                                + " 需要 " + needed + " 个数据字节，轨内仅剩 " + (end - pos) + " 个"));
                break;
            }
            int d1 = data[pos++] & 0xFF;
            int d2 = needed == 2 ? data[pos++] & 0xFF : -1;
            if (d1 > 0x7F || d2 > 0x7F) {
                diagnostics.add(diag(Diagnostic.Severity.WARN, "DATA_BYTE_HIGH_BIT", trackIndex, eventStart,
                        "channel 数据字节超出 0–127 范围"));
            }
            events.add(new MidiEvent(trackIndex, eventIndex++, seqCounter++, delta, absTick,
                    eventStart, pos, eventStart, deltaEnd,
                    MidiEvent.Kind.CHANNEL, status, usedRunning, status & 0x0F, d1, d2, -1, null));
        }

        if (eot && pos < end) {
            diagnostics.add(diag(Diagnostic.Severity.WARN, "TRAILING_BYTES_AFTER_EOT", trackIndex, pos,
                    "EOT 之后轨声明长度内仍有 " + (end - pos) + " 个尾字节，未作为事件解析"));
        }
        return new TrackData(trackIndex, chunkOffset, declaredLength, end - start, events);
    }

    /** 读取事件长度 VLQ，大小记录在 {@link #lengthVlqSize}。 */
    private int lengthVlqSize;

    private long readLengthVlq(int trackIndex, int pos, int end) {
        long value = 0;
        int count = 0;
        boolean tooLong = false;
        while (pos + count < end) {
            int b = data[pos + count] & 0xFF;
            count++;
            if (count > 4 && !tooLong) {
                tooLong = true;
                diagnostics.add(diag(Diagnostic.Severity.ERROR, "VLQ_TOO_LONG", trackIndex, pos,
                        "事件长度 VLQ 超过 4 字节上限（28 bit），取值截断到 28 bit"));
            }
            value = (value << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) {
                break;
            }
        }
        lengthVlqSize = count;
        if (tooLong) {
            value = Math.min(value, 0x0FFF_FFFFL);
        }
        return value;
    }

    private Diagnostic diag(Diagnostic.Severity severity, String code, int track, long offset, String message) {
        return new Diagnostic(severity, code, track, offset, message);
    }

    private boolean asciiAt(int offset, String magic) {
        if (offset + magic.length() > data.length) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if (data[offset + i] != (byte) magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int u16(int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private long u32(int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
