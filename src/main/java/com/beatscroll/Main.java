package com.beatscroll;

import com.beatscroll.db.Database;
import com.beatscroll.db.MidiRepository;
import com.beatscroll.fixtures.Fixtures;
import com.beatscroll.midi.MidiParser;
import com.beatscroll.midi.ParseResult;
import com.beatscroll.midi.Timebase;
import com.beatscroll.web.WebServer;

import java.nio.file.Path;
import java.util.Map;

/** 入口：迁移 SQLite → 空库时播种内置 fixture → 启动 HTTP 服务。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 5980;
        String dbPath = "beat-scroll.db";
        boolean seedFixtures = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--db" -> dbPath = args[++i];
                case "--no-fixtures" -> seedFixtures = false;
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    System.err.println("用法: --port N --db PATH --no-fixtures");
                    System.exit(2);
                }
            }
        }
        Database db = Database.open(Path.of(dbPath));
        MidiRepository repo = new MidiRepository(db);
        if (seedFixtures && repo.isEmpty()) {
            MidiParser parser = new MidiParser();
            for (Map.Entry<String, byte[]> f : Fixtures.all().entrySet()) {
                ParseResult result = parser.parse(f.getValue());
                Timebase timebase = Timebase.build(result.header(), result.tracks());
                repo.save(f.getKey(), f.getValue(), result, timebase);
            }
            System.out.println("已播种 " + Fixtures.all().size() + " 个内置 fixture");
        }
        WebServer server = new WebServer(repo, port);
        server.start();
        System.out.println("节拍卷轴已启动: http://127.0.0.1:" + server.port());
        Thread.currentThread().join();
    }
}
