package com.beatscroll.midi;

/** division 字段的两种计时方式。 */
public sealed interface Timing {

    /** PPQN：每四分音符的 tick 数，微秒需经 tempo map 分段积分。 */
    record Ppqn(int ticksPerQuarter) implements Timing {
        @Override
        public String describe() {
            return "PPQN " + ticksPerQuarter + " ticks/四分音符";
        }
    }

    /** SMPTE：fpsCode 为带符号帧率编码（-24/-25/-29/-30），-29 表示 29.97 drop-frame。 */
    record Smpte(int fpsCode, int ticksPerFrame) implements Timing {
        public boolean dropFrame() {
            return fpsCode == -29;
        }

        /** 有效帧率：drop-frame 29 按 30000/1001 ≈ 29.97 换算。 */
        public double effectiveFps() {
            return dropFrame() ? 30000.0 / 1001.0 : Math.abs(fpsCode);
        }

        public long microsAt(long tick) {
            double micros = tick * 1_000_000.0 / (effectiveFps() * ticksPerFrame);
            return Math.round(micros);
        }

        @Override
        public String describe() {
            String fps = dropFrame() ? "29.97 (drop-frame)" : String.valueOf(Math.abs(fpsCode));
            return "SMPTE " + fps + " fps, " + ticksPerFrame + " ticks/帧";
        }
    }

    String describe();
}
