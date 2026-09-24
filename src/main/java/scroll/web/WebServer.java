package scroll.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import scroll.midi.MidiExporter;
import scroll.midi.MidiFile;
import scroll.midi.MidiParser;
import scroll.store.Database;

/** 内置 HTTP 服务：列表页、文件详情（SVG 卷轴）、导出与上传。 */
public final class WebServer {
    private final Database db;
    private final HttpServer server;
    private final Map<String, byte[]> fixtures;

    public WebServer(Database db, Map<String, byte[]> fixtures, int port) throws IOException {
        this.db = db;
        this.fixtures = fixtures;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::route);
        server.setExecutor(null);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (path.equals("/")) {
                html(ex, indexPage());
            } else if (path.equals("/import-fixture")) {
                String name = q.get("name");
                byte[] bytes = fixtures.get(name);
                if (bytes == null) { text(ex, 404, "未知 fixture: " + name); return; }
                long id = db.importFile(MidiParser.parse(bytes, name));
                redirect(ex, "/file?id=" + id);
            } else if (path.equals("/file")) {
                long id = Long.parseLong(q.getOrDefault("id", "0"));
                html(ex, filePage(id));
            } else if (path.equals("/export")) {
                long id = Long.parseLong(q.getOrDefault("id", "0"));
                // 重新解析内存中的字节再导出，验证 parse-export 链路
                Database.FileRow row = db.file(id);
                byte[] source = fixtures.get(row.name());
                if (source == null) { text(ex, 404, "非 fixture 文件暂不支持导出"); return; }
                byte[] out = MidiExporter.export(MidiParser.parse(source, row.name()));
                ex.getResponseHeaders().set("Content-Type", "audio/midi");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + row.name() + ".mid\"");
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            } else if (path.equals("/upload") && ex.getRequestMethod().equals("POST")) {
                byte[] body = ex.getRequestBody().readAllBytes();
                String name = q.getOrDefault("name", "uploaded.mid");
                long id = db.importFile(MidiParser.parse(body, name));
                redirect(ex, "/file?id=" + id);
            } else {
                text(ex, 404, "Not Found");
            }
        } catch (Exception e) {
            text(ex, 500, "内部错误: " + e);
        }
    }

    private String indexPage() {
        StringBuilder sb = new StringBuilder();
        sb.append(header("节拍卷轴"));
        sb.append("<h1>节拍卷轴</h1>");
        sb.append("<p>Standard MIDI File 离线解析卷轴：header / track chunk / delta-time / 事件原始范围，"
                + "跨轨 tempo map 与 SMPTE 换算。</p>");
        sb.append("<h2>内置 fixture</h2><ul>");
        for (String name : fixtures.keySet()) {
            sb.append("<li><a href=\"/import-fixture?name=").append(esc(name)).append("\">")
                    .append(esc(name)).append("</a></li>");
        }
        sb.append("</ul><h2>已导入文件</h2><ul>");
        for (Database.FileRow f : db.listFiles()) {
            sb.append("<li><a href=\"/file?id=").append(f.id()).append("\">#").append(f.id())
                    .append(" ").append(esc(f.name())).append("</a> — format ").append(f.format())
                    .append(", ").append(f.ntracks()).append(" 轨, ")
                    .append(f.smpte() ? "SMPTE" : "PPQN " + f.ppqn())
                    .append(" <a href=\"/export?id=").append(f.id()).append("\">[导出]</a></li>");
        }
        sb.append("</ul>");
        sb.append("<p>上传：<code>curl -X POST --data-binary @your.mid "
                + "'http://127.0.0.1:' + location.port + '/upload?name=your.mid'</code></p>");
        sb.append(footer());
        return sb.toString();
    }

    private String filePage(long id) {
        Database.FileRow f = db.file(id);
        List<Database.EventRow> events = db.events(id);
        List<Database.TempoRow> tempos = db.tempoCandidates(id);
        List<Database.DiagRow> diags = db.diagnostics(id);

        StringBuilder sb = new StringBuilder();
        sb.append(header("节拍卷轴 — " + f.name()));
        sb.append("<p><a href=\"/\">← 返回列表</a></p>");
        sb.append("<h1>节拍卷轴：").append(esc(f.name())).append("</h1>");
        sb.append("<p>format ").append(f.format()).append(" · ").append(f.ntracks())
                .append(" 轨 · ");
        if (f.smpte()) {
            sb.append("SMPTE ").append(f.fps() == 29 ? "29.97(drop)" : f.fps())
                    .append(" fps · ").append(f.ticksPerFrame()).append(" ticks/frame");
        } else {
            sb.append("PPQN ").append(f.ppqn());
        }
        sb.append(" · <a href=\"/export?id=").append(f.id()).append("\">导出 .mid</a></p>");

        if (!diags.isEmpty()) {
            sb.append("<h2>诊断</h2><ul>");
            for (Database.DiagRow d : diags) {
                sb.append("<li><code>").append(esc(d.code())).append("</code>")
                        .append(d.trackIndex() >= 0 ? " (轨 " + d.trackIndex() + ")" : "")
                        .append(" @").append(d.offset()).append("：")
                        .append(esc(d.message())).append("</li>");
            }
            sb.append("</ul>");
        }

        if (!tempos.isEmpty()) {
            sb.append("<h2>Tempo 候选（同 tick 按文件顺序）</h2><table>")
                    .append("<tr><th>tick</th><th>µs/qn</th><th>来源轨</th><th>文件顺序</th><th>生效</th></tr>");
            for (Database.TempoRow t : tempos) {
                sb.append("<tr").append(t.effective() ? "" : " class=\"ambiguous\"").append("><td>")
                        .append(t.tick()).append("</td><td>").append(t.usPerQuarter())
                        .append("</td><td>").append(t.trackIndex()).append("</td><td>")
                        .append(t.fileOrder()).append("</td><td>")
                        .append(t.effective() ? "✓" : "歧义候选").append("</td></tr>");
            }
            sb.append("</table>");
        }

        sb.append("<h2>卷轴</h2>").append(svg(events, f.ntracks()));

        sb.append("<h2>事件表</h2><table><tr><th>轨</th><th>#</th><th>tick</th><th>µs</th>"
                + "<th>delta</th><th>事件</th><th>status</th><th>数据(hex)</th><th>原始范围</th></tr>");
        for (Database.EventRow e : events) {
            sb.append("<tr><td>").append(e.trackIndex()).append("</td><td>").append(e.seq())
                    .append("</td><td>").append(e.absTick()).append("</td><td>").append(e.micros())
                    .append("</td><td>").append(e.delta()).append("</td><td>")
                    .append(esc(describe(e))).append("</td><td><code>")
                    .append(e.running() ? "(run)" : String.format("0x%02X", e.status()))
                    .append("</code></td><td><code>").append(esc(e.dataHex())).append("</code></td>")
                    .append("<td><code>[").append(e.deltaOffset()).append(", ")
                    .append(e.eventOffset() + e.eventLength()).append(")</code></td></tr>");
        }
        sb.append("</table>").append(footer());
        return sb.toString();
    }

    /** SVG 卷轴：每轨一条泳道，x 轴为 tick，事件按类型着色。 */
    private String svg(List<Database.EventRow> events, int ntracks) {
        long maxTick = events.stream().mapToLong(Database.EventRow::absTick).max().orElse(1);
        if (maxTick == 0) maxTick = 1;
        int width = 960, laneH = 44, padL = 60, padR = 20, top = 24;
        int height = top + ntracks * laneH + 30;
        StringBuilder sb = new StringBuilder();
        sb.append("<svg viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" width=\"").append(width).append("\" height=\"").append(height)
                .append("\" xmlns=\"http://www.w3.org/2000/svg\" role=\"img\">");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#14161a\"/>");
        double scale = (width - padL - padR) / (double) maxTick;
        // tick 网格
        for (int i = 0; i <= 8; i++) {
            long tick = maxTick * i / 8;
            int x = (int) (padL + tick * scale);
            sb.append("<line x1=\"").append(x).append("\" y1=\"").append(top)
                    .append("\" x2=\"").append(x).append("\" y2=\"").append(height - 26)
                    .append("\" stroke=\"#2c313a\"/>");
            sb.append("<text x=\"").append(x).append("\" y=\"").append(height - 10)
                    .append("\" fill=\"#8b949e\" font-size=\"10\" text-anchor=\"middle\">")
                    .append(tick).append("</text>");
        }
        for (int t = 0; t < ntracks; t++) {
            int y = top + t * laneH;
            sb.append("<text x=\"8\" y=\"").append(y + laneH / 2 + 4)
                    .append("\" fill=\"#c9d1d9\" font-size=\"12\">轨 ").append(t).append("</text>");
            sb.append("<line x1=\"").append(padL).append("\" y1=\"").append(y + laneH / 2)
                    .append("\" x2=\"").append(width - padR).append("\" y2=\"").append(y + laneH / 2)
                    .append("\" stroke=\"#30363d\"/>");
        }
        for (Database.EventRow e : events) {
            int x = (int) (padL + e.absTick() * scale);
            int y = top + e.trackIndex() * laneH + laneH / 2;
            String color = switch (e.kind()) {
                case "CHANNEL" -> "#58a6ff";
                case "SYSEX" -> "#d2a8ff";
                default -> "#f0883e";
            };
            sb.append("<circle cx=\"").append(x).append("\" cy=\"").append(y)
                    .append("\" r=\"5\" fill=\"").append(color).append("\">")
                    .append("<title>轨 ").append(e.trackIndex()).append(" tick ").append(e.absTick())
                    .append(" · ").append(e.micros()).append(" µs · ").append(esc(describe(e)))
                    .append("</title></circle>");
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static String describe(Database.EventRow e) {
        return switch (e.kind()) {
            case "CHANNEL" -> switch (e.command()) {
                case 0x80 -> "Note Off ch=" + (e.channel() + 1);
                case 0x90 -> "Note On ch=" + (e.channel() + 1);
                case 0xB0 -> "CC ch=" + (e.channel() + 1);
                case 0xC0 -> "Program ch=" + (e.channel() + 1);
                case 0xE0 -> "PitchBend ch=" + (e.channel() + 1);
                default -> "Channel 0x" + Integer.toHexString(e.command());
            };
            case "SYSEX" -> "SysEx";
            default -> e.metaType() == 0x2F ? "EOT" : "Meta 0x" + String.format("%02X", e.metaType());
        };
    }

    // ---- HTTP / HTML 辅助 ----

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static String header(String title) {
        return "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + "</title><style>"
                + "body{font-family:ui-monospace,Menlo,monospace;background:#0d1117;color:#c9d1d9;"
                + "max-width:1024px;margin:24px auto;padding:0 16px}"
                + "a{color:#58a6ff}table{border-collapse:collapse;font-size:12px;width:100%}"
                + "td,th{border:1px solid #30363d;padding:3px 8px;text-align:left}"
                + "th{background:#161b22}code{color:#a5d6ff}"
                + "tr.ambiguous td{background:#3d2c00;color:#f0b429}"
                + "h1{font-size:22px}h2{font-size:16px;color:#8b949e;margin-top:28px}"
                + "</style></head><body>";
    }

    private static String footer() {
        return "</body></html>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void html(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void text(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }
}
