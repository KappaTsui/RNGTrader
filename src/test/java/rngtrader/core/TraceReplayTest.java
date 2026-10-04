package rngtrader.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Complete public observations: 25 consecutive appending refreshes with Iron last. */
public class TraceReplayTest {
    private static int[] ints(JsonArray values) {
        int[] result = new int[values.size()]; for (int i = 0; i < result.length; i++) result[i] = values.get(i).getAsInt(); return result;
    }
    @Test public void completeSession() throws Exception {
        JsonArray rows;
        try (Reader input = new InputStreamReader(getClass().getResourceAsStream("/vanilla-session.json"), StandardCharsets.UTF_8)) {
            rows = new JsonParser().parse(input).getAsJsonArray();
        }
        RngEngine engine = new RngEngine();
        List<OfferData> offers = new ArrayList<OfferData>(); offers.add(new OfferData(BlacksmithRng.Kind.IRON_SHOVEL, 4, false));
        double last = 0, closed = 0;
        int probe = 0, wait = 0, disabled = 0, refreshes = 0, verified = 0;
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject(); String op = row.get("op").getAsString(); double time = row.get("time").getAsDouble();
            switch (op) {
                case "init": engine.initialize(ints(row.getAsJsonArray("pitches"))); last = time; break;
                case "sound":
                    boolean free = row.get("free").getAsBoolean();
                    int ticks = (int)Math.round((time - last) * 20);
                    int low = Math.max(0, ticks - 1), high = ticks + 1;
                    if (probe < 45 && free) { low = 21; high = 23; probe++; }
                    engine.observe(row.get("pitch").getAsInt(), low, high, free); last = time; break;
                case "shared_init":
                    JsonArray samples = row.getAsJsonArray("samples"); int[][] draws = new int[samples.size()][];
                    for (int i = 0; i < draws.length; i++) draws[i] = ints(samples.get(i).getAsJsonArray());
                    engine.recoverShared(draws); break;
                case "shared": engine.observeShared(ints(row.getAsJsonArray("draws"))); verified++; break;
                case "close":
                    wait = row.get("wait").getAsInt(); disabled = row.get("disabled").getAsInt(); closed = time;
                    int[] hypotheses = refreshes == 0 ? new int[] {0, 1} : new int[] {disabled};
                    assertEquals("Recorded window rejected at refresh " + refreshes, wait,
                        engine.plan(OfferData.mask(offers), hypotheses, wait, wait, 1));
                    break;
                case "open":
                    OfferData appended = new OfferData(BlacksmithRng.Kind.valueOf(row.get("kind").getAsString()), row.get("price").getAsInt(), false);
                    engine.resume(offers.size(), refreshes == 0 ? new int[] {0, 1} : new int[] {disabled}, wait - 1, wait + 1,
                        Math.max(40, (int)Math.round((time - closed) * 20) - 2), (int)Math.round((time - closed) * 20) + 2, appended);
                    List<OfferData> after = new ArrayList<OfferData>(offers); after.add(appended); OfferData.verifyAppend(offers, after);
                    offers = after; refreshes++; last = time; break;
                default: fail("Unknown replay operation " + op);
            }
        }
        assertEquals(25, refreshes); assertTrue(OfferData.complete(offers)); assertTrue(verified >= 400);
    }
}
