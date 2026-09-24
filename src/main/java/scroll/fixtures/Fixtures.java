package scroll.fixtures;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 内置 SMF fixture：覆盖解析器需要诊断与换算的各条路径。 */
public final class Fixtures {

    public static Map<String, byte[]> all() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("demo-ppqn", demoPpqn());
        m.put("smpte-dropframe", smpteDropFrame());
        m.put("tempo-same-tick", tempoSameTick());
        m.put("running-status-meta", runningStatusMeta());
        m.put("sysex-multiblock", sysexMultiblock());
        m.put("format2-independent", format2());
        m.put("truncated-vlq", truncatedVlq());
        m.put("eot-tail", eotTail());
        m.put("truncated-track", truncatedTrack());
        return m;
    }

    /** format 1，PPQN=480：轨 0 两次变速，轨 1 音符。 */
    static byte[] demoPpqn() {
        byte[] t0 = track(
                vlq(0), 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,       // tempo 500000
                vlq(0), 0xFF, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08, // 4/4
                vlq(960), 0xFF, 0x51, 0x03, 0x03, 0xD0, 0x90,     // tick 960: tempo 250000
                vlq(0), 0xFF, 0x2F, 0x00);
        byte[] t1 = track(
                vlq(0), 0x90, 0x3C, 0x64,
                vlq(480), 0x80, 0x3C, 0x40,
                vlq(0), 0x90, 0x40, 0x64,
                vlq(480), 0x80, 0x40, 0x40,
                vlq(480), 0x90, 0x43, 0x64,
                vlq(480), 0x80, 0x43, 0x40,
                vlq(0), 0xFF, 0x2F, 0x00);
        return smf(1, 2, 480, t0, t1);
    }

    /** SMPTE division 0xE328：29.97 drop-frame，40 ticks/frame。 */
    static byte[] smpteDropFrame() {
        byte[] t0 = track(
                vlq(0), 0x90, 0x3C, 0x64,
                vlq(40), 0x80, 0x3C, 0x40,   // 1 帧
                vlq(80), 0x90, 0x40, 0x64,   // 2 帧
                vlq(0), 0x80, 0x40, 0x40,
                vlq(0), 0xFF, 0x2F, 0x00);
        return smf(0, 1, 0xE328, t0);
    }

    /** format 1，两轨在 tick 0 给出不同 tempo → 歧义候选。 */
    static byte[] tempoSameTick() {
        byte[] t0 = track(
                vlq(0), 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20, // 500000
                vlq(0), 0xFF, 0x2F, 0x00);
        byte[] t1 = track(
                vlq(0), 0xFF, 0x51, 0x03, 0x03, 0xD0, 0x90, // 250000
                vlq(0), 0x90, 0x3C, 0x64,
                vlq(240), 0x80, 0x3C, 0x40,
                vlq(0), 0xFF, 0x2F, 0x00);
        return smf(1, 2, 480, t0, t1);
    }

    /** format 0：running status 被 meta 清除后必须重写状态字节；随后一个裸数据字节触发违规诊断。 */
    static byte[] runningStatusMeta() {
        byte[] t0 = track(
                vlq(0), 0x90, 0x3C, 0x64,
                vlq(120), 0x3E, 0x64,                 // running status
                vlq(120), 0xFF, 0x01, 0x03, 'a', 'b', 'c', // meta 清除 running status
                vlq(0), 0x40,                          // 裸数据字节 → RUNNING_STATUS_VIOLATION
                vlq(0), 0x90, 0x40, 0x64,              // 显式重写状态
                vlq(120), 0x41, 0x64,                  // running status 恢复
                vlq(0), 0xFF, 0x2F, 0x00);
        return smf(0, 1, 480, t0);
    }

    /** SysEx 分两段跨边界：F0 开头未终止，F7 escape 续传。 */
    static byte[] sysexMultiblock() {
        byte[] t0 = track(
                vlq(0), 0xF0, 0x05, 0x43, 0x12, 0x00, 0x3E, 0x7F,
                vlq(24), 0xF7, 0x03, 0x01, 0x02, 0xF7,
                vlq(0), 0xFF, 0x2F, 0x00);
        return smf(0, 1, 480, t0);
    }

    /** format 2：两轨各自独立时间线，各自 tempo。 */
    static byte[] format2() {
        byte[] t0 = track(
                vlq(0), 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20, // 500000
                vlq(0), 0x90, 0x3C, 0x64,
                vlq(480), 0x80, 0x3C, 0x40,
                vlq(0), 0xFF, 0x2F, 0x00);
        byte[] t1 = track(
                vlq(0), 0xFF, 0x51, 0x03, 0x03, 0xD0, 0x90, // 250000
                vlq(0), 0x90, 0x40, 0x64,
                vlq(480), 0x80, 0x40, 0x40,
                vlq(0), 0xFF, 0x2F, 0x00);
        return smf(2, 2, 480, t0, t1);
    }

    /** delta-time VLQ 达 5 字节 → VLQ_TOO_LONG。 */
    static byte[] truncatedVlq() {
        byte[] t0 = track(
                vlq(0), 0x90, 0x3C, 0x64,
                0xFF, 0xFF, 0xFF, 0xFF, 0x7F, 0x80, 0x3C, 0x40, // 5 字节 VLQ
                vlq(0), 0xFF, 0x2F, 0x00);
        return smf(0, 1, 480, t0);
    }

    /** EOT 之后还有尾字节 → BYTES_AFTER_EOT。 */
    static byte[] eotTail() {
        byte[] t0 = track(
                vlq(0), 0x90, 0x3C, 0x64,
                vlq(0), 0xFF, 0x2F, 0x00,
                vlq(0), 0x90, 0x40, 0x64); // EOT 之后的事件
        return smf(0, 1, 480, t0);
    }

    /** 轨道声明长度超出文件实际 → TRACK_TRUNCATED。 */
    static byte[] truncatedTrack() {
        ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
        bodyOut.writeBytes(vlq(0));
        bodyOut.writeBytes(new byte[]{(byte) 0x90, 0x3C, 0x64});
        bodyOut.writeBytes(vlq(480));
        bodyOut.writeBytes(new byte[]{(byte) 0x80, 0x3C}); // 缺一个数据字节
        byte[] body = bodyOut.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("MThd".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{0, 0, 0, 6, 0, 0, 0, 1, 0x01, (byte) 0xE0});
        out.writeBytes("MTrk".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{0, 0, 0, (byte) (body.length + 10)}); // 虚报长度
        out.writeBytes(body);
        return out.toByteArray();
    }

    // ---- 构造辅助 ----

    static byte[] smf(int format, int ntracks, int division, byte[]... tracks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("MThd".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{0, 0, 0, 6,
                (byte) (format >>> 8), (byte) format,
                (byte) (ntracks >>> 8), (byte) ntracks,
                (byte) (division >>> 8), (byte) division});
        for (byte[] t : tracks) out.writeBytes(t);
        return out.toByteArray();
    }

    static byte[] track(Object... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Object p : parts) {
            if (p instanceof byte[] bytes) body.writeBytes(bytes);
            else if (p instanceof Integer i) body.write(i);
            else if (p instanceof Character ch) body.write(ch);
        }
        byte[] b = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("MTrk".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{0, 0, (byte) (b.length >>> 8), (byte) b.length});
        out.writeBytes(b);
        return out.toByteArray();
    }

    static byte[] vlq(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long buffer = value & 0x7F;
        while ((value >>= 7) != 0) {
            buffer <<= 8;
            buffer |= ((value & 0x7F) | 0x80);
        }
        while (true) {
            out.write((int) (buffer & 0xFF));
            if ((buffer & 0x80) != 0) buffer >>= 8;
            else break;
        }
        return out.toByteArray();
    }

    private Fixtures() {}
}
