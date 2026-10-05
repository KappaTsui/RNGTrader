package rngtrader.client;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SessionLogTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    @Test public void queuedRowsCaptureProducerStateAndDrainOnClose() throws Exception {
        SessionLog log = new SessionLog(directory.getRoot());
        Map<String, Integer> state = new HashMap<String, Integer>(); state.put("age", -24000);
        log.record("snapshot", "state", state);
        state.put("age", -12000);
        log.record("snapshot", "state", state);
        log.close();
        Field writer = SessionLog.class.getDeclaredField("thread"); writer.setAccessible(true);
        Thread thread = (Thread)writer.get(log); thread.join(3000);
        assertFalse("Logger must drain after close", thread.isAlive());
        Path file;
        try (java.util.stream.Stream<Path> paths = Files.list(directory.getRoot().toPath().resolve("rngtrader/sessions"))) {
            file = paths.findFirst().get();
        }
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, rows.size());
        JsonParser parser = new JsonParser();
        assertEquals(-24000, parser.parse(rows.get(0)).getAsJsonObject().getAsJsonObject("state").get("age").getAsInt());
        assertEquals(-12000, parser.parse(rows.get(1)).getAsJsonObject().getAsJsonObject("state").get("age").getAsInt());
    }
}
