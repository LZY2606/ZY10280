package com.beatscroll.web;

import com.beatscroll.db.MidiRepository;
import com.beatscroll.midi.MidiParser;
import com.beatscroll.midi.ParseResult;
import com.beatscroll.midi.Timebase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** 内置 HTTP 服务（JDK 自带，无外部依赖）。 */
public final class WebServer {

    private final MidiRepository repo;
    private final HttpServer server;

    public WebServer(MidiRepository repo, int port) throws IOException {
        this.repo = repo;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/file/", this::handleFile);
        server.createContext("/api/import", this::handleImport);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleIndex(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain; charset=utf-8",
                    "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "text/html; charset=utf-8",
                ScrollPage.index(repo.listFiles()).getBytes(StandardCharsets.UTF_8));
    }

    private void handleFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        long id;
        try {
            id = Long.parseLong(path.substring("/file/".length()));
        } catch (NumberFormatException e) {
            send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        MidiRepository.FileDetail detail = repo.load(id);
        if (detail == null) {
            send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        long[] micros = repo.eventMicros(id);
        send(ex, 200, "text/html; charset=utf-8",
                ScrollPage.detail(detail, micros).getBytes(StandardCharsets.UTF_8));
    }

    /** POST /api/import?name=xxx.mid，body 为 SMF 原始字节。 */
    private void handleImport(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain; charset=utf-8",
                    "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = "imported.mid";
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals("name")) {
                    name = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            send(ex, 400, "text/plain; charset=utf-8", "空文件".getBytes(StandardCharsets.UTF_8));
            return;
        }
        ParseResult result = new MidiParser().parse(body);
        if (result.header() == null) {
            send(ex, 422, "text/plain; charset=utf-8",
                    ("无法解析（缺少 MThd）：\n" + result.diagnostics())
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        Timebase timebase = Timebase.build(result.header(), result.tracks());
        long id = repo.save(name, body, result, timebase);
        ex.getResponseHeaders().add("Location", "/file/" + id);
        send(ex, 303, "text/plain; charset=utf-8", ("已导入 #" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
