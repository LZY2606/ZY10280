package scroll;

import java.nio.file.Path;
import scroll.fixtures.Fixtures;
import scroll.midi.MidiParser;
import scroll.store.Database;
import scroll.web.WebServer;

/** 入口：迁移 SQLite → 导入内置 fixture → 启动 HTTP 卷轴服务。 */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = 5980;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
        }
        Database db = new Database(Path.of("beatscroll.db"));
        db.migrate();
        // 内置 fixture 幂等导入（按 name+sha256 去重）
        Fixtures.all().forEach((name, bytes) -> db.importFile(MidiParser.parse(bytes, name)));

        WebServer server = new WebServer(db, Fixtures.all(), port);
        server.start();
        System.out.println("节拍卷轴已启动: http://127.0.0.1:" + server.port());
        Thread.currentThread().join();
    }
}
