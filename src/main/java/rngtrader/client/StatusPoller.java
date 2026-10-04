package rngtrader.client;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import rngtrader.core.Roster;
import rngtrader.core.SampleClock;

/** A single ordinary status connection; this is not an additional logged-in player. */
final class StatusPoller implements AutoCloseable {
    interface Sink { void sample(SampleClock.Sample value); void invalid(String reason); }
    private final String host;
    private final int port;
    private final Roster roster;
    private final Sink sink;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile Socket socket;

    StatusPoller(String host, int port, Roster roster, Sink sink) {
        this.host = host; this.port = port; this.roster = roster; this.sink = sink;
        thread = new Thread(this::run, "RNGTrader status"); thread.setDaemon(true); thread.start();
    }
    private void run() {
        while (running) {
            SampleClock clock = new SampleClock();
            Map<String, String> identities = null;
            long revision = roster.revision();
            try (Socket connection = new Socket()) {
                socket = connection;
                connection.connect(new InetSocketAddress(host, port), 2000);
                connection.setSoTimeout(2000); connection.setTcpNoDelay(true);
                DataInputStream input = new DataInputStream(connection.getInputStream());
                OutputStream output = connection.getOutputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream handshake = new DataOutputStream(bytes);
                varInt(handshake, 0); varInt(handshake, 5);
                byte[] hostname = host.getBytes(StandardCharsets.UTF_8);
                varInt(handshake, hostname.length); handshake.write(hostname); handshake.writeShort(port); varInt(handshake, 1);
                varInt(output, bytes.size()); output.write(bytes.toByteArray()); output.flush();
                while (running) {
                    if (revision != roster.revision()) throw new IOException("Roster order changed");
                    long sent = System.nanoTime();
                    output.write(new byte[] {1, 0}); output.flush();
                    int size = varInt(input);
                    if (size <= 0 || size > 1048576) throw new IOException("Invalid status frame size");
                    byte[] body = new byte[size]; input.readFully(body);
                    long received = System.nanoTime();
                    DataInputStream packet = new DataInputStream(new ByteArrayInputStream(body));
                    if (varInt(packet) != 0) throw new IOException("Expected status response");
                    int length = varInt(packet);
                    if (length < 0 || length != packet.available()) throw new IOException("Invalid status JSON length");
                    byte[] json = new byte[length]; packet.readFully(json);
                    JsonObject players = new JsonParser().parse(new String(json, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("players");
                    JsonArray sample = players.getAsJsonArray("sample");
                    if (players.get("online").getAsInt() != 12 || sample == null || sample.size() != 12)
                        throw new IOException("Status must contain exactly 12 online players");
                    List<String> names = new ArrayList<String>();
                    Map<String, String> ids = new LinkedHashMap<String, String>();
                    for (JsonElement entry : sample) {
                        JsonObject p = entry.getAsJsonObject();
                        String name = p.get("name").getAsString(), id = p.get("id").getAsString();
                        names.add(name); ids.put(name, id);
                    }
                    if (new HashSet<String>(ids.values()).size() != 12 || (identities != null && !identities.equals(ids)))
                        throw new IOException("Status player identities changed");
                    identities = ids;
                    int[] draws = Roster.decode(roster.names(), names);
                    SampleClock.Sample event = clock.accept(draws, sent, received);
                    if (event != null) sink.sample(event);
                    long remaining = 250_000_000L - (System.nanoTime() - sent);
                    if (remaining > 0) Thread.sleep(remaining / 1_000_000L);
                }
            } catch (Exception error) {
                if (running) sink.invalid(error.getMessage() == null ? error.toString() : error.getMessage());
            } finally { socket = null; }
            if (running) try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }
    private static int varInt(InputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = input.read(); if (b < 0) throw new EOFException();
            value |= (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IOException("VarInt is too long");
    }
    private static void varInt(OutputStream output, int value) throws IOException {
        do { int b = value & 127; value >>>= 7; output.write(b | (value != 0 ? 128 : 0)); } while (value != 0);
    }
    @Override public void close() {
        running = false; thread.interrupt();
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }
}
