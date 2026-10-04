package rngtrader.client;

import com.google.gson.Gson;
import rngtrader.RNGTrader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class SessionLog implements Closeable {
    private final Gson gson = new Gson();
    private final Writer writer;
    SessionLog(File gameDirectory) throws IOException {
        File directory = new File(gameDirectory, "rngtrader/sessions");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(directory,
            format.format(new Date()) + ".jsonl")), StandardCharsets.UTF_8));
    }
    synchronized void record(String event, Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("event", event); row.put("nanoTime", System.nanoTime());
        for (int i = 0; i + 1 < pairs.length; i += 2) row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        try { writer.write(gson.toJson(row)); writer.write('\n'); writer.flush(); }
        catch (IOException e) { RNGTrader.LOG.warn("Cannot write session log", e); }
    }
    @Override public synchronized void close() throws IOException { writer.close(); }
}
