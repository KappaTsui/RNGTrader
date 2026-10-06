package rngtrader.core;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public sound observations with exact processing-tick intervals. */
public final class RecoveryFixture {
    public final int[] calibration;
    public final RecoveryProbe[] probes;

    public RecoveryFixture() throws IOException {
        JsonObject root;
        try (Reader in = new InputStreamReader(getClass().getResourceAsStream("/recovery-probes.json"), StandardCharsets.UTF_8)) {
            root = new JsonParser().parse(in).getAsJsonObject();
        }
        JsonArray pitches = root.getAsJsonArray("calibration"), sounds = root.getAsJsonArray("probes");
        calibration = new int[pitches.size()];
        for (int i = 0; i < calibration.length; i++) calibration[i] = pitches.get(i).getAsInt();
        probes = new RecoveryProbe[sounds.size()];
        for (int i = 0; i < probes.length; i++) {
            JsonObject sound = sounds.get(i).getAsJsonObject();
            probes[i] = new RecoveryProbe(sound.get("pitch").getAsInt(), sound.get("ticks").getAsInt());
        }
    }

    public RecoveryProbe[] prefix() { return Arrays.copyOf(probes, 3); }
    public void replay(RngEngine engine) {
        for (RecoveryProbe probe : probes) engine.observe(probe.pitch, probe.ticks, probe.ticks, true);
    }
    public static Set<String> stateKeys(RngEngine engine) throws ReflectiveOperationException {
        Field field = RngEngine.class.getDeclaredField("tracker"); field.setAccessible(true);
        BlacksmithRng.Tracker tracker = (BlacksmithRng.Tracker)field.get(engine);
        Set<String> keys = new TreeSet<String>();
        for (BlacksmithRng.State state : tracker.states) keys.add(state.key());
        return keys;
    }
}
