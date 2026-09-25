package com.beatscroll.web;

import com.beatscroll.db.MidiRepository;
import com.beatscroll.midi.Diagnostic;
import com.beatscroll.midi.MidiEvent;
import com.beatscroll.midi.TrackData;

import java.util.List;

/** 服务端渲染 HTML + SVG 卷轴。 */
public final class ScrollPage {

    private ScrollPage() {
    }

    private static final String STYLE = """
            body{font-family:-apple-system,"PingFang SC",sans-serif;margin:2rem;color:#222}
            h1{letter-spacing:.1em}
            table{border-collapse:collapse;margin:1rem 0;font-size:13px}
            th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;white-space:nowrap}
            th{background:#f0f0f0}
            .err{color:#c0392b}.warn{color:#b9770e}.info{color:#566573}
            .mono{font-family:ui-monospace,Menlo,monospace}
            .ambig{background:#fdebd0}
            section{margin-bottom:2rem}
            """;

    public static String index(List<MidiRepository.FileRow> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=zh><head><meta charset=utf-8>")
                .append("<title>节拍卷轴</title><style>").append(STYLE).append("</style></head><body>");
        sb.append("<h1>节拍卷轴</h1>");
        sb.append("<p>Standard MIDI File 离线解析：header、track chunk、delta-time、每条事件的原始字节范围、")
                .append("绝对 tick 与微秒、跨轨 tempo map 与歧义候选，一览无遗。</p>");
        sb.append("<section><h2>导入</h2>")
                .append("<input type=file id=f accept=\".mid,.midi,audio/midi\">")
                .append("<button onclick=\"go()\">导入并解析</button>")
                .append("<pre id=out></pre><script>")
                .append("async function go(){const f=document.getElementById('f').files[0];")
                .append("if(!f)return;const r=await fetch('/api/import?name='+encodeURIComponent(f.name),")
                .append("{method:'POST',body:f});if(r.redirected){location.href=r.url;return;}")
                .append("document.getElementById('out').textContent=await r.text();}")
                .append("</script></section>");
        sb.append("<section><h2>文件列表</h2><table><tr>")
                .append("<th>#</th><th>名称</th><th>format</th><th>计时</th><th>轨数</th>")
                .append("<th>事件数</th><th>错误</th><th>警告</th><th>SHA-256</th></tr>");
        for (MidiRepository.FileRow f : files) {
            sb.append("<tr><td>").append(f.id()).append("</td><td><a href=\"/file/")
                    .append(f.id()).append("\">").append(esc(f.name())).append("</a></td><td>")
                    .append(f.format()).append("</td><td>").append(esc(f.timing())).append("</td><td>")
                    .append(f.trackCount()).append("</td><td>").append(f.eventCount()).append("</td><td class=err>")
                    .append(f.errorCount()).append("</td><td class=warn>").append(f.warnCount())
                    .append("</td><td class=mono>").append(esc(f.sha256().substring(0, 12)))
                    .append("…</td></tr>");
        }
        sb.append("</table></section></body></html>");
        return sb.toString();
    }

    public static String detail(MidiRepository.FileDetail d, long[] micros) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=zh><head><meta charset=utf-8>")
                .append("<title>节拍卷轴 — ").append(esc(d.name())).append("</title><style>")
                .append(STYLE).append("</style></head><body>");
        sb.append("<h1>节拍卷轴</h1><p><a href=\"/\">← 文件列表</a></p>");
        sb.append("<section><h2>").append(esc(d.name())).append("</h2><table>")
                .append("<tr><th>format</th><td>").append(d.header().format()).append("</td></tr>")
                .append("<tr><th>声明轨数</th><td>").append(d.header().ntrks()).append("</td></tr>")
                .append("<tr><th>实际解析轨数</th><td>").append(d.tracks().size()).append("</td></tr>")
                .append("<tr><th>division</th><td class=mono>0x")
                .append(String.format("%04X", d.header().divisionRaw())).append("</td></tr>")
                .append("<tr><th>计时</th><td>").append(esc(d.header().timing().describe()))
                .append("</td></tr></table></section>");
        diagnosticsSection(sb, d.diagnostics());
        tempoSection(sb, d.tempoRows());
        svgSection(sb, d, micros);
        eventsSection(sb, d, micros);
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void diagnosticsSection(StringBuilder sb, List<Diagnostic> diags) {
        sb.append("<section><h2>诊断（").append(diags.size()).append("）</h2>");
        if (diags.isEmpty()) {
            sb.append("<p>无诊断，文件结构完好。</p></section>");
            return;
        }
        sb.append("<table><tr><th>级别</th><th>代码</th><th>轨</th><th>偏移</th><th>说明</th></tr>");
        for (Diagnostic g : diags) {
            String cls = switch (g.severity()) {
                case ERROR -> "err";
                case WARN -> "warn";
                case INFO -> "info";
            };
            sb.append("<tr><td class=").append(cls).append(">").append(g.severity())
                    .append("</td><td class=mono>").append(g.code()).append("</td><td>")
                    .append(g.trackIndex()).append("</td><td class=mono>").append(g.offset())
                    .append("</td><td>").append(esc(g.message())).append("</td></tr>");
        }
        sb.append("</table></section>");
    }

    private static void tempoSection(StringBuilder sb, List<MidiRepository.TempoRow> rows) {
        sb.append("<section><h2>Tempo map 候选</h2>");
        if (rows.isEmpty()) {
            sb.append("<p>无 tempo 事件（SMPTE 计时或默认 500000 µs/四分音符）。</p></section>");
            return;
        }
        sb.append("<p>同一 tick 的多个 tempo 按文件顺序列为候选；取值不同即标记歧义，")
                .append("积分取文件顺序首个候选，但不按轨号暗中选赢家。</p>");
        sb.append("<table><tr><th>作用域</th><th>tick</th><th>tempo (µs/四分)</th>")
                .append("<th>来源轨</th><th>文件序</th><th>积分取值</th><th>歧义</th></tr>");
        for (MidiRepository.TempoRow r : rows) {
            sb.append(r.ambiguous() ? "<tr class=ambig>" : "<tr>")
                    .append("<td>").append(r.scopeTrack() < 0 ? "跨轨共享" : "轨 " + r.scopeTrack())
                    .append("</td><td class=mono>").append(r.tick())
                    .append("</td><td class=mono>").append(r.tempoUs())
                    .append("</td><td>").append(r.trackIndex())
                    .append("</td><td class=mono>").append(r.seq())
                    .append("</td><td>").append(r.chosen() ? "✓" : "")
                    .append("</td><td>").append(r.ambiguous() ? "⚠ 歧义" : "")
                    .append("</td></tr>");
        }
        sb.append("</table></section>");
    }

    private static void svgSection(StringBuilder sb, MidiRepository.FileDetail d, long[] micros) {
        long maxTick = d.tracks().stream()
                .flatMap(t -> t.events().stream())
                .mapToLong(MidiEvent::absTick).max().orElse(1);
        int lanes = Math.max(1, d.tracks().size());
        int laneWidth = 150;
        int left = 110;
        int width = left + lanes * laneWidth + 30;
        double scale = Math.max(0.25, Math.min(2.0, 3200.0 / Math.max(1, maxTick)));
        int height = (int) (80 + maxTick * scale);
        sb.append("<section><h2>卷轴</h2>");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=").append(width)
                .append(" height=").append(height)
                .append(" style=\"background:#fafafa;border:1px solid #ddd\">");
        for (int i = 0; i < lanes; i++) {
            int x = left + i * laneWidth + laneWidth / 2;
            sb.append("<line x1=").append(x).append(" y1=30 x2=").append(x)
                    .append(" y2=").append(height - 20)
                    .append(" stroke=\"#ddd\"/>");
            sb.append("<text x=").append(x).append(" y=18 text-anchor=middle ")
                    .append("font-size=12 fill=\"#555\">轨 ").append(i).append("</text>");
        }
        int idx = 0;
        for (TrackData t : d.tracks()) {
            int x = left + t.trackIndex() * laneWidth + laneWidth / 2;
            for (MidiEvent e : t.events()) {
                long us = idx < micros.length ? micros[idx] : 0;
                idx++;
                int y = (int) (40 + e.absTick() * scale);
                String color = switch (e.kind()) {
                    case CHANNEL -> "#4f8cff";
                    case SYSEX_F0, SYSEX_F7 -> "#9b59b6";
                    case META -> e.isTempo() ? "#e74c3c" : "#e67e22";
                };
                sb.append("<circle cx=").append(x).append(" cy=").append(y)
                        .append(" r=5 fill=\"").append(color).append("\">")
                        .append("<title>").append(esc("轨" + e.trackIndex() + " tick "
                                + e.absTick() + " · " + us + "µs · " + e.describe()))
                        .append("</title></circle>");
                if (e.isTempo() || e.isEndOfTrack()) {
                    sb.append("<text x=").append(x + 9).append(" y=").append(y + 4)
                            .append(" font-size=10 fill=\"#888\">")
                            .append(esc(e.isTempo() ? "tempo" : "EOT")).append("</text>");
                }
            }
        }
        sb.append("</svg>");
        sb.append("<p>蓝=channel，紫=SysEx，橙=meta，红=tempo。悬停查看 tick / 微秒 / 来源轨。</p>");
        sb.append("</section>");
    }

    private static void eventsSection(StringBuilder sb, MidiRepository.FileDetail d, long[] micros) {
        sb.append("<section><h2>事件明细</h2><table><tr>")
                .append("<th>来源轨</th><th>#</th><th>delta</th><th>绝对 tick</th><th>微秒</th>")
                .append("<th>类型</th><th>描述</th><th>原始范围</th><th>delta 范围</th></tr>");
        int idx = 0;
        for (TrackData t : d.tracks()) {
            for (MidiEvent e : t.events()) {
                long us = idx < micros.length ? micros[idx] : 0;
                idx++;
                sb.append("<tr><td>").append(e.trackIndex())
                        .append("</td><td>").append(e.eventIndex())
                        .append("</td><td class=mono>").append(e.deltaTicks())
                        .append("</td><td class=mono>").append(e.absTick())
                        .append("</td><td class=mono>").append(us)
                        .append("</td><td>").append(e.kind())
                        .append("</td><td>").append(esc(e.describe()))
                        .append("</td><td class=mono>[").append(e.rawStart()).append(", ")
                        .append(e.rawEnd()).append(")</td><td class=mono>[")
                        .append(e.deltaStart()).append(", ").append(e.deltaEnd())
                        .append(")</td></tr>");
            }
        }
        sb.append("</table></section>");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
