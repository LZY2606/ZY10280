package com.beatscroll.midi;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 导出器：把解析结果重新序列化为 SMF。
 * 显式写出状态字节（不使用 running status），保留原始 division 与 format；
 * 若轨内缺少 EOT 则补一个 delta=0 的 EOT。
 */
public final class MidiWriter {

    private MidiWriter() {
    }

    public static byte[] write(MidiHeader header, List<TrackData> tracks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "MThd");
        writeU32(out, 6);
        writeU16(out, header.format());
        writeU16(out, tracks.size());
        writeU16(out, header.divisionRaw());
        for (TrackData track : tracks) {
            byte[] body = encodeTrack(track.events());
            writeAscii(out, "MTrk");
            writeU32(out, body.length);
            out.write(body, 0, body.length);
        }
        return out.toByteArray();
    }

    private static byte[] encodeTrack(List<MidiEvent> events) {
        List<MidiEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(MidiEvent::absTick).thenComparingLong(MidiEvent::seq));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long prevTick = 0;
        boolean hasEot = false;
        for (MidiEvent e : sorted) {
            writeVlq(out, e.absTick() - prevTick);
            prevTick = e.absTick();
            switch (e.kind()) {
                case CHANNEL -> {
                    out.write(e.status() & 0xFF);
                    out.write(e.data1() & 0xFF);
                    if (e.data2() >= 0) {
                        out.write(e.data2() & 0xFF);
                    }
                }
                case SYSEX_F0, SYSEX_F7 -> {
                    out.write(e.kind() == MidiEvent.Kind.SYSEX_F0 ? 0xF0 : 0xF7);
                    writeVlq(out, e.payload().length);
                    out.write(e.payload(), 0, e.payload().length);
                }
                case META -> {
                    out.write(0xFF);
                    out.write(e.metaType() & 0xFF);
                    writeVlq(out, e.payload().length);
                    out.write(e.payload(), 0, e.payload().length);
                    if (e.isEndOfTrack()) {
                        hasEot = true;
                    }
                }
            }
        }
        if (!hasEot) {
            writeVlq(out, 0);
            out.write(0xFF);
            out.write(0x2F);
            out.write(0x00);
        }
        return out.toByteArray();
    }

    static void writeVlq(ByteArrayOutputStream out, long value) {
        long buffer = value & 0x7F;
        while ((value >>= 7) != 0) {
            buffer <<= 8;
            buffer |= ((value & 0x7F) | 0x80);
        }
        while (true) {
            out.write((int) (buffer & 0xFF));
            if ((buffer & 0x80) != 0) {
                buffer >>= 8;
            } else {
                break;
            }
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        for (int i = 0; i < s.length(); i++) {
            out.write((byte) s.charAt(i));
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >> 24) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }
}
