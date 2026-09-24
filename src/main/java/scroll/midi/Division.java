package scroll.midi;

/** SMPTE/PPQN 时间分割。SMPTE 时高字节为负的帧率（-29 表示 29.97 drop-frame）。 */
public record Division(boolean smpte, int ppqn, int fps, double fpsValue, boolean dropFrame,
        int ticksPerFrame, int raw) {

    public static Division fromRaw(int raw) {
        if ((raw & 0x8000) != 0) {
            int fpsByte = (raw >> 8) & 0xFF;
            int fps = -(byte) fpsByte; // 负值编码
            boolean drop = fps == 29;
            double fpsValue = drop ? 29.97 : fps;
            return new Division(true, 0, fps, fpsValue, drop, raw & 0xFF, raw);
        }
        return new Division(false, raw, 0, 0, false, 0, raw);
    }

    /** SMPTE 下每 tick 的微秒数。 */
    public double microsPerTick() {
        return 1_000_000.0 / (fpsValue * ticksPerFrame);
    }

    public long tickToMicros(long tick) {
        return Math.round(tick * microsPerTick());
    }

    public String describe() {
        if (smpte) {
            return "SMPTE " + (dropFrame ? "29.97(drop-frame)" : fps) + " fps, "
                    + ticksPerFrame + " ticks/frame";
        }
        return "PPQN " + ppqn + " ticks/quarter";
    }
}
