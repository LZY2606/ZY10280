package scroll;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scroll.fixtures.Fixtures;
import scroll.midi.MidiParser;
import scroll.store.Database;
import scroll.web.WebServer;

class WebTest {

    @TempDir
    Path dir;

    @Test
    void indexShowsScrollTitleAndDetailRendersSvg() throws Exception {
        Database db = new Database(dir.resolve("t.db"));
        db.migrate();
        Fixtures.all().forEach((name, bytes) -> db.importFile(MidiParser.parse(bytes, name)));
        WebServer server = new WebServer(db, Fixtures.all(), 0);
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> index = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, index.statusCode());
            assertTrue(index.body().contains("节拍卷轴"));

            long id = db.listFiles().get(0).id();
            HttpResponse<String> detail = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/file?id=" + id)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, detail.statusCode());
            assertTrue(detail.body().contains("<svg"));
            assertTrue(detail.body().contains("µs"));
            assertTrue(detail.body().contains("原始范围"));
        } finally {
            server.stop();
            db.close();
        }
    }
}
