package rngtrader.client;

import com.google.gson.Gson;
import rngtrader.RNGTrader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Freeze observations on their owner thread; batch disk writes on a dedicated thread. */
final class SessionLog implements Closeable {
    private final Gson gson = new Gson();
    private final Writer writer;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(16384);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread thread;
    private volatile boolean closing;
    SessionLog(File gameDirectory) throws IOException {
        File directory = new File(gameDirectory, "rngtrader/sessions");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        writer = Files.newBufferedWriter(new File(directory, format.format(new Date()) + ".jsonl").toPath(),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        thread = new Thread(this::writeLoop, "RNGTrader session log"); thread.setDaemon(true); thread.start();
    }
    void record(String event, Object... pairs) {
        if (closing) return;
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("schema", 2); row.put("event", event); row.put("nanoTime", System.nanoTime());
        for (int i = 0; i + 1 < pairs.length; i += 2) row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        if (!queue.offer(gson.toJson(row))) dropped.incrementAndGet();
    }
    private void writeLoop() {
        try {
            long flushed = System.nanoTime();
            while (!closing || !queue.isEmpty()) {
                String line;
                try { line = queue.poll(250, TimeUnit.MILLISECONDS); }
                catch (InterruptedException wake) { continue; }
                if (line != null) { writer.write(line); writer.write('\n'); }
                long missing = dropped.getAndSet(0);
                if (missing > 0) {
                    writer.write("{\"schema\":2,\"event\":\"log_dropped\",\"count\":" + missing + "}\n");
                    RNGTrader.LOG.warn("Session log dropped {} observations", missing);
                }
                if (line == null || System.nanoTime() - flushed >= 250_000_000L) {
                    writer.flush(); flushed = System.nanoTime();
                }
            }
        } catch (IOException error) { RNGTrader.LOG.warn("Cannot write session log", error); }
        finally { try { writer.close(); } catch (IOException ignored) { } }
    }
    @Override public void close() { closing = true; thread.interrupt(); }
}
