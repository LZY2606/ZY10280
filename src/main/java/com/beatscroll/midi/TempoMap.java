package com.beatscroll.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PPQN 文件的跨轨 tempo map。
 *
 * <p>同一 tick 出现多个 tempo 事件时，按文件顺序（解析序号 seq，即轨块在文件中的先后 +
 * 轨内事件顺序）建立候选列表；取值不同的候选构成歧义并显式标记。积分采用文件顺序的
 * 第一个候选，但绝不按轨号暗中选赢家——歧义会随候选列表一起展示。
 */
public final class TempoMap {

    public static final int DEFAULT_TEMPO_US = 500_000;

    public record Candidate(long tick, int tempoUsPerQuarter, int trackIndex, long seq) {
    }

    /** 同一 tick 的候选集合。 */
    public record Point(long tick, List<Candidate> candidates) {

        public boolean ambiguous() {
            return candidates.stream().map(Candidate::tempoUsPerQuarter).distinct().count() > 1;
        }

        /** 文件顺序的第一个候选；仅作为积分取值，不代表“赢家”。 */
        public int effectiveTempo() {
            return candidates.get(0).tempoUsPerQuarter();
        }
    }

    private final int ppqn;
    private final List<Point> points;
    private final long[] segTick;
    private final double[] segMicros;
    private final int[] segTempo;

    private TempoMap(int ppqn, List<Point> points, long[] segTick, double[] segMicros, int[] segTempo) {
        this.ppqn = ppqn;
        this.points = points;
        this.segTick = segTick;
        this.segMicros = segMicros;
        this.segTempo = segTempo;
    }

    /** 从全体事件中提取 tempo（meta 0x51，长度 3）并构建。 */
    public static TempoMap build(int ppqn, List<MidiEvent> events) {
        List<Candidate> candidates = new ArrayList<>();
        for (MidiEvent e : events) {
            if (e.kind() == MidiEvent.Kind.META && e.metaType() == 0x51
                    && e.payload() != null && e.payload().length == 3) {
                int tempo = ((e.payload()[0] & 0xFF) << 16)
                        | ((e.payload()[1] & 0xFF) << 8)
                        | (e.payload()[2] & 0xFF);
                candidates.add(new Candidate(e.absTick(), tempo, e.trackIndex(), e.seq()));
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::tick).thenComparingLong(Candidate::seq));

        List<Point> points = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!points.isEmpty() && points.get(points.size() - 1).tick() == c.tick()) {
                points.get(points.size() - 1).candidates().add(c);
            } else {
                List<Candidate> group = new ArrayList<>();
                group.add(c);
                points.add(new Point(c.tick(), group));
            }
        }

        int n = points.size() + 1;
        long[] segTick = new long[n];
        double[] segMicros = new double[n];
        int[] segTempo = new int[n];
        long curTick = 0;
        double cumMicros = 0;
        int curTempo = DEFAULT_TEMPO_US;
        int i = 0;
        for (Point p : points) {
            segTick[i] = curTick;
            segMicros[i] = cumMicros;
            segTempo[i] = curTempo;
            cumMicros += (p.tick() - curTick) * (double) curTempo / ppqn;
            curTick = p.tick();
            curTempo = p.effectiveTempo();
            i++;
        }
        segTick[i] = curTick;
        segMicros[i] = cumMicros;
        segTempo[i] = curTempo;
        return new TempoMap(ppqn, points, segTick, segMicros, segTempo);
    }

    /** tick → 微秒：按 tempo 分段做分段积分。 */
    public long microsAt(long tick) {
        int lo = 0;
        int hi = segTick.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (segTick[mid] <= tick) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        double micros = segMicros[lo] + (tick - segTick[lo]) * (double) segTempo[lo] / ppqn;
        return Math.round(micros);
    }

    public List<Point> points() {
        return points;
    }

    public boolean hasAmbiguity() {
        return points.stream().anyMatch(Point::ambiguous);
    }
}
