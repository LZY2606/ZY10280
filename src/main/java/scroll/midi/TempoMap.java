package scroll.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 跨轨 tempo map（PPQN）。
 * 同 tick 出现多个 tempo 时，候选一律按文件顺序（轨出现次序、轨内事件次序）排列，
 * 生效值取文件顺序第一，其余作为候选展示歧义——不按轨号暗中选赢家。
 * format 2 每轨独立时间线；SMPTE 不使用 tempo map。
 */
public final class TempoMap {
    public static final int DEFAULT_US_PER_QUARTER = 500_000;

    public record Candidate(int trackIndex, int fileOrder, long tick, int usPerQuarter,
            boolean effective) {}

    private record Segment(long startTick, int usPerQuarter, long startMicros) {}

    private final int ppqn;
    private final List<Segment> segments;
    private final List<Candidate> candidates;

    private TempoMap(int ppqn, List<Segment> segments, List<Candidate> candidates) {
        this.ppqn = ppqn;
        this.segments = segments;
        this.candidates = candidates;
    }

    /** 为整个文件建立 tempo map；format 2 时请使用 perTrack。 */
    public static TempoMap build(MidiFile file) {
        return buildFor(file, -1);
    }

    /** trackFilter >= 0 时只取该轨的 tempo 事件（format 2 独立时间线）。 */
    public static TempoMap buildFor(MidiFile file, int trackFilter) {
        List<Candidate> raw = new ArrayList<>();
        int order = 0;
        for (MidiTrack t : file.tracks) {
            if (trackFilter >= 0 && t.index != trackFilter) continue;
            for (MidiEvent ev : t.events) {
                if (ev.kind == MidiEvent.Kind.META && ev.metaType == 0x51 && ev.data.length == 3) {
                    int uspq = ((ev.data[0] & 0xFF) << 16) | ((ev.data[1] & 0xFF) << 8)
                            | (ev.data[2] & 0xFF);
                    raw.add(new Candidate(t.index, order++, ev.absTick, uspq, false));
                }
            }
        }
        // 按 tick 稳定排序：同 tick 保持文件顺序
        List<Candidate> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingLong(Candidate::tick));

        List<Candidate> marked = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        long micros = 0;
        long prevTick = 0;
        int prevUspq = DEFAULT_US_PER_QUARTER;
        int i = 0;
        while (i < sorted.size()) {
            long tick = sorted.get(i).tick;
            List<Candidate> group = new ArrayList<>();
            while (i < sorted.size() && sorted.get(i).tick == tick) {
                group.add(sorted.get(i));
                i++;
            }
            // 文件顺序第一者生效
            Candidate winner = group.get(0);
            long distinct = group.stream().map(Candidate::usPerQuarter).distinct().count();
            for (Candidate cand : group) {
                marked.add(new Candidate(cand.trackIndex(), cand.fileOrder(), cand.tick(),
                        cand.usPerQuarter(), cand == winner));
            }
            if (distinct > 1 && trackFilter < 0 && file.diagnostics.stream()
                    .noneMatch(d -> d.code().equals("TEMPO_AMBIGUITY")
                            && d.message().startsWith("tick " + tick + " "))) {
                file.diagnostics.add(Diagnostic.global("TEMPO_AMBIGUITY",
                        "tick " + tick + " 处有 " + group.size() + " 个冲突 tempo 候选，按文件顺序取第 1 个 ("
                                + winner.usPerQuarter + " µs/qn)，其余保留展示", 0));
            }
            micros += (tick - prevTick) * (long) prevUspq / file.division.ppqn();
            segments.add(new Segment(tick, winner.usPerQuarter(), micros));
            prevTick = tick;
            prevUspq = winner.usPerQuarter();
        }
        return new TempoMap(file.division.ppqn(), segments, List.copyOf(marked));
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    /** tick → 微秒，按 tempo 分段积分。 */
    public long tickToMicros(long tick) {
        Segment active = null;
        for (Segment s : segments) {
            if (s.startTick() <= tick) active = s; else break;
        }
        if (active == null) {
            return tick * DEFAULT_US_PER_QUARTER / ppqn;
        }
        return active.startMicros()
                + (tick - active.startTick()) * (long) active.usPerQuarter() / ppqn;
    }
}
