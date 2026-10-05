package rngtrader.core;

import java.util.*;

/** Same-connection tick markers. Encoded Age values may repeat after delayed encoding. */
public final class ServerTickClock {
    public static final long UNKNOWN = Long.MIN_VALUE;
    public static final class Animal {
        public final int entity;
        public int age, verified;
        public long count, received;
        public String fault;
        private boolean initial = true, eligible;
        private long anchorAge = UNKNOWN, anchorCount;
        Animal(int entity, int age) { this.entity = entity; this.age = age; }
        public boolean ready() { return eligible && !initial && fault == null && verified >= 2 && age < -200; }
        public long tick() { return ready() ? anchorAge + count - anchorCount : UNKNOWN; }
    }
    private final Map<Integer, Animal> animals = new LinkedHashMap<Integer, Animal>();
    private Animal active;
    private long revision;
    public void spawn(int entity, int age) { animals.put(entity, new Animal(entity, age)); }
    public void loaded(int entity, int age) {
        if (!animals.containsKey(entity)) {
            Animal a = new Animal(entity, age); a.initial = false; animals.put(entity, a);
        }
    }
    public void eligible(int entity, boolean value) { Animal a = animals.get(entity); if (a != null) a.eligible = value; }
    public Collection<Animal> animals() { return Collections.unmodifiableCollection(animals.values()); }
    public void metadata(int entity, int age, long received) {
        Animal a = animals.get(entity); if (a == null) return;
        if (a.initial) { a.initial = false; a.age = age; a.received = received; return; }
        if (age < a.age) a.fault = "Age moved backwards";
        a.age = age; a.received = received; a.count++;
    }
    public void time(long age) {
        for (Animal a : animals.values()) {
            if (a.initial) continue;
            if (a.anchorAge != UNKNOWN) {
                long elapsed = age - a.anchorAge, markers = a.count - a.anchorCount;
                if (elapsed == 20 && markers == 20) a.verified++;
                else if (a.verified >= 2) a.fault = "World Time advanced " + elapsed + " ticks; Age markers=" + markers;
                else a.verified = 0;
            }
            a.anchorAge = age; a.anchorCount = a.count;
        }
    }
    public void remove(int entity) { Animal a = animals.remove(entity); if (a != null) a.fault = "Clock left the tracker"; }
    /** Select only between complete packet batches, so all candidates refer to the same server phase. */
    public boolean select() {
        if (active != null && active.ready() && active.age < -400) return false;
        Animal next = null;
        for (Animal a : animals.values()) if (a.ready() && (next == null || a.age < next.age)) next = a;
        if (next == active) return false;
        if (active != null && active.ready() && next != null && active.tick() != next.tick()) {
            return false;
        }
        active = next; revision++; return true;
    }
    public long tick() { return active == null ? UNKNOWN : active.tick(); }
    public long received() { return active == null ? 0 : active.received; }
    public long revision() { return revision; }
    public Animal active() { return active; }
    public boolean fresh(long now) { return tick() != UNKNOWN && now >= received() && now - received() <= 150_000_000L; }
    public void clear() { animals.clear(); active = null; revision++; }
}
