package com.beatscroll.fixtures;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置 fixture：覆盖 drop-frame SMPTE、同 tick 双轨 tempo 冲突、running status 穿插 meta、
 * 跨边界 SysEx（F0…F7 续包）、截断 VLQ、轨长度截断、EOT 尾字节、format 2 独立时间线。
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static Map<String, byte[]> all() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("smpte-drop-frame.mid", smpteDropFrame());
        map.put("tempo-conflict.mid", tempoConflict());
        map.put("running-status-meta.mid", runningStatusMeta());
        map.put("sysex-continuation.mid", sysexContinuation());
        map.put("truncated-vlq.mid", truncatedVlq());
        map.put("truncated-track.mid", truncatedTrack());
        map.put("trailing-after-eot.mid", trailingAfterEot());
        map.put("format2-independent.mid", format2Independent());
        return map;
    }

    /** SMPTE division 0xE328：-29（29.97 drop-frame）、40 ticks/帧。tick 1200 应等于 1_001_000 µs。 */
    static byte[] smpteDropFrame() {
        B track = new B()
                .vlq(0).u8(0x90).u8(60).u8(64)
                .vlq(1200).u8(0x80).u8(60).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        return file(0, 0xE328, track.done());
    }

    /** format 1：两轨在同一 tick 480 各放一个 tempo（500000 与 250000），构成候选歧义。 */
    static byte[] tempoConflict() {
        B t0 = new B()
                .vlq(0).u8(0xFF).u8(0x51).u8(3).u8(0x0F).u8(0x42).u8(0x40)   // 1_000_000 µs
                .vlq(480).u8(0xFF).u8(0x51).u8(3).u8(0x07).u8(0xA1).u8(0x20) // 500_000 µs
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        B t1 = new B()
                .vlq(480).u8(0xFF).u8(0x51).u8(3).u8(0x03).u8(0xD0).u8(0x90) // 250_000 µs
                .vlq(0).u8(0x90).u8(60).u8(100)
                .vlq(480).u8(0x80).u8(60).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        return file(1, 480, t0.done(), t1.done());
    }

    /** running status 只能沿用 channel voice；meta 事件清除后，孤立数据字节须诊断。 */
    static byte[] runningStatusMeta() {
        B t = new B()
                .vlq(0).u8(0x90).u8(60).u8(64)
                .vlq(480).u8(62).u8(65)                       // running status: Note On
                .vlq(0).u8(0xFF).u8(0x01).u8(2).u8('h').u8('i') // meta text → 清除 running status
                .vlq(0).u8(63)                                // 孤立数据字节 → DATA_WITHOUT_STATUS
                .vlq(0).u8(0x90).u8(64).u8(64)                // 显式状态恢复
                .vlq(480).u8(0x80).u8(60).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        return file(0, 480, t.done());
    }

    /** SysEx 跨边界：F0 包未以 F7 结束，下一包用 F7 续传；两者都清除 running status。 */
    static byte[] sysexContinuation() {
        B t = new B()
                .vlq(0).u8(0x90).u8(60).u8(64)
                .vlq(0).u8(0xF0).u8(3).u8(0x01).u8(0x02).u8(0x03) // F0 未终止
                .vlq(120).u8(0xF7).u8(2).u8(0x04).u8(0xF7)        // F7 续包
                .vlq(0).u8(62)                                    // running 已清除 → 诊断
                .vlq(0).u8(0x90).u8(62).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        return file(0, 480, t.done());
    }

    /** delta-time VLQ 写出 5 个连续字节，超过 4 字节上限 → VLQ_TOO_LONG。 */
    static byte[] truncatedVlq() {
        B t = new B()
                .u8(0x81).u8(0x81).u8(0x81).u8(0x81).u8(0x01) // 5 字节 VLQ
                .u8(0x90).u8(60).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        return file(0, 480, t.done());
    }

    /** MTrk 声明长度大于文件实际剩余 → TRACK_TRUNCATED。 */
    static byte[] truncatedTrack() {
        B t = new B()
                .vlq(0).u8(0x90).u8(60).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        byte[] body = t.done();
        B f = new B()
                .ascii("MThd").u32(6).u16(0).u16(1).u16(480)
                .ascii("MTrk").u32(body.length + 16) // 虚报 16 字节
                .bytes(body);
        return f.done();
    }

    /** EOT 之后、声明长度之内仍有 3 个尾字节 → TRAILING_BYTES_AFTER_EOT。 */
    static byte[] trailingAfterEot() {
        B t = new B()
                .vlq(0).u8(0x90).u8(60).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0)
                .u8(0x00).u8(0x11).u8(0x22);
        return file(0, 480, t.done());
    }

    /** format 2：两轨各自独立时间线，各自的 tempo 只作用于本轨。 */
    static byte[] format2Independent() {
        B t0 = new B()
                .vlq(0).u8(0xFF).u8(0x51).u8(3).u8(0x07).u8(0xA1).u8(0x20) // 500_000
                .vlq(480).u8(0x90).u8(60).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        B t1 = new B()
                .vlq(0).u8(0xFF).u8(0x51).u8(3).u8(0x03).u8(0xD0).u8(0x90) // 250_000
                .vlq(480).u8(0x90).u8(64).u8(64)
                .vlq(0).u8(0xFF).u8(0x2F).u8(0);
        return file(2, 480, t0.done(), t1.done());
    }

    private static byte[] file(int format, int division, byte[]... tracks) {
        B f = new B()
                .ascii("MThd").u32(6).u16(format).u16(tracks.length).u16(division);
        for (byte[] track : tracks) {
            f.ascii("MTrk").u32(track.length).bytes(track);
        }
        return f.done();
    }

    /** 字节流小工具。 */
    static final class B {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        B u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        B u16(int v) {
            return u8(v >> 8).u8(v);
        }

        B u32(long v) {
            return u8((int) (v >> 24)).u8((int) (v >> 16)).u8((int) (v >> 8)).u8((int) v);
        }

        B ascii(String s) {
            for (int i = 0; i < s.length(); i++) {
                u8(s.charAt(i));
            }
            return this;
        }

        B vlq(long value) {
            long buffer = value & 0x7F;
            while ((value >>= 7) != 0) {
                buffer <<= 8;
                buffer |= ((value & 0x7F) | 0x80);
            }
            while (true) {
                u8((int) (buffer & 0xFF));
                if ((buffer & 0x80) != 0) {
                    buffer >>= 8;
                } else {
                    break;
                }
            }
            return this;
        }

        B bytes(byte[] b) {
            out.write(b, 0, b.length);
            return this;
        }

        byte[] done() {
            return out.toByteArray();
        }
    }
}
