package rngtrader.core;

import com.seedfinding.latticg.RandomReverser;
import com.seedfinding.latticg.util.LCG;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.LongStream;

/** Vanilla 1.7.10 trade model and sound-pitch seed recovery. Java 8. */
public strictfp class BlacksmithRng {
    static final long A = 0x5DEECE66DL, C = 11, MASK = (1L << 48) - 1;
    static final long MOD46 = 1L << 46, MASK46 = MOD46 - 1;
    static final int FLOAT_UNIT = 1 << 24;
    static final long DIFF_INVERSE = BigInteger.valueOf((1 - A) / 4)
        .mod(BigInteger.valueOf(MOD46)).modInverse(BigInteger.valueOf(MOD46)).longValue();
    static final LCG DIFFERENCE = new LCG(A, (A - 1) / 4, MOD46);

    static final class Rng {
        long seed, calls;
        Rng(long internalSeed) { seed = internalSeed & MASK; }
        int next(int bits) { seed = (seed * A + C) & MASK; calls++; return (int)(seed >>> (48 - bits)); }
        float nextFloat() { return next(24) / (float)FLOAT_UNIT; }
        boolean nextBoolean() { return next(1) != 0; }
        double nextDouble() { return (((long)next(26) << 27) + next(27)) / (double)(1L << 53); }
        int nextInt(int bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound");
            if ((bound & -bound) == bound) return (int)((bound * (long)next(31)) >> 31);
            int bits, value;
            do { bits = next(31); value = bits % bound; } while (bits - value + bound - 1 < 0);
            return value;
        }
        int sound() { return (int)(((nextFloat() - nextFloat()) * 0.2f + 1.0f) * 63.0f); }
        void skip(long count) { seed = LCG.JAVA.combine(count).nextSeed(seed); calls += count; }
        Rng copy() { Rng r = new Rng(seed); r.calls = calls; return r; }
    }

    public enum Kind {
        COAL(263, true, .7f, 16, 24), IRON_INGOT(265, true, .5f, 8, 10),
        GOLD_INGOT(266, true, .5f, 8, 10), DIAMOND(264, true, .5f, 4, 6),
        IRON_SWORD(267, false, .5f, 7, 11), DIAMOND_SWORD(276, false, .5f, 12, 14),
        IRON_AXE(258, false, .3f, 6, 8), DIAMOND_AXE(279, false, .3f, 9, 12),
        IRON_PICKAXE(257, false, .5f, 7, 9), DIAMOND_PICKAXE(278, false, .5f, 10, 12),
        IRON_SHOVEL(256, false, .2f, 4, 6), DIAMOND_SHOVEL(277, false, .2f, 7, 8),
        IRON_HOE(292, false, .2f, 4, 6), DIAMOND_HOE(293, false, .2f, 7, 8),
        IRON_BOOTS(309, false, .2f, 4, 6), DIAMOND_BOOTS(313, false, .2f, 7, 8),
        IRON_HELMET(306, false, .2f, 4, 6), DIAMOND_HELMET(310, false, .2f, 7, 8),
        IRON_CHESTPLATE(307, false, .2f, 10, 14), DIAMOND_CHESTPLATE(311, false, .2f, 16, 19),
        IRON_LEGGINGS(308, false, .2f, 8, 10), DIAMOND_LEGGINGS(312, false, .2f, 11, 14),
        CHAINMAIL_BOOTS(305, false, .1f, 5, 7), CHAINMAIL_HELMET(302, false, .1f, 5, 7),
        CHAINMAIL_CHESTPLATE(303, false, .1f, 11, 15), CHAINMAIL_LEGGINGS(304, false, .1f, 9, 11);
        public final int item, min, max;
        public final boolean buy;
        final float chance;
        Kind(int item, boolean buy, float chance, int min, int max) {
            this.item = item; this.buy = buy; this.chance = chance; this.min = min; this.max = max;
        }
    }

    static final class Offer {
        final Kind kind;
        final int price;
        int uses, maxUses = 7;
        Offer(Kind kind, int price) { this.kind = kind; this.price = price; }
        boolean disabled() { return uses >= maxUses; }
        public String toString() { return "{\"kind\":\"" + kind + "\",\"price\":" + price + "}"; }
    }

    static float chance(float base, int size) {
        float value = base + (float)Math.sqrt(size) * .2f;
        return value > .9f ? .9f - (value - .9f) : value;
    }

    static List<Offer> candidates(Rng rng, int size) {
        List<Offer> result = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (rng.nextFloat() < chance(kind.chance, size))
                result.add(new Offer(kind, kind.min + rng.nextInt(kind.max - kind.min)));
        }
        if (result.isEmpty()) {
            rng.nextFloat(); // The guaranteed Gold fallback still performs the chance draw.
            result.add(new Offer(Kind.GOLD_INGOT, 8 + rng.nextInt(2)));
        }
        return result;
    }

    static void improve(List<Offer> offers, Offer added) {
        for (int i = 0; i < offers.size(); i++) {
            Offer old = offers.get(i);
            if (old.kind == added.kind) {
                if (added.price < old.price) offers.set(i, added);
                return;
            }
        }
        offers.add(added);
    }

    static List<Offer> restock(Rng rng, List<Offer> offers) {
        if (offers.size() > 1) for (Offer offer : offers)
            if (offer.disabled()) offer.maxUses += rng.nextInt(6) + rng.nextInt(6) + 2;
        return candidates(rng, offers.size());
    }

    static int pitchForDifference(int difference) {
        return (int)(((difference / (float)FLOAT_UNIT) * .2f + 1.0f) * 63.0f);
    }

    static int firstDifference(int pitch) {
        int low = 1 - FLOAT_UNIT, high = FLOAT_UNIT;
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (pitchForDifference(mid) < pitch) low = mid + 1; else high = mid;
        }
        return low;
    }

    static long ceilDiv(long n, long d) { return -Math.floorDiv(-n, d); }

    /** offsets are raw Random.next calls before the first float of each sound. */
    static LongStream recover(int[] pitches, long[] offsets) {
        if (pitches.length == 0 || offsets.length != pitches.length || offsets[0] != 0)
            throw new IllegalArgumentException("nonempty pitches; matching offsets beginning at zero");
        RandomReverser reverser = new RandomReverser(DIFFERENCE, Collections.emptyList());
        for (int i = 0; i < pitches.length; i++) {
            if (pitches[i] < 50 || pitches[i] > 75) throw new IllegalArgumentException("adult pitch byte 50..75");
            if (i > 0) {
                if (offsets[i] < offsets[i - 1] + 2) throw new IllegalArgumentException("overlapping sounds");
                reverser.addUnmeasuredSeeds(offsets[i] - offsets[i - 1] - 1);
            }
            long lower = (long)firstDifference(pitches[i]) * FLOAT_UNIT - (FLOAT_UNIT - 1);
            long upper = (long)(firstDifference(pitches[i] + 1) - 1) * FLOAT_UNIT + (FLOAT_UNIT - 1);
            reverser.addMeasuredSeed(ceilDiv(lower - 1, 4), Math.floorDiv(upper - 1, 4));
        }
        return reverser.findAllValidSeeds().flatMap(z -> {
            long residue = (DIFF_INVERSE * (z + 3)) & MASK46;
            return LongStream.range(0, 4).map(lift -> residue + lift * MOD46);
        }).filter(seed -> {
            Rng rng = new Rng(seed);
            for (int i = 0; i < pitches.length; i++) {
                rng.skip(offsets[i] - rng.calls);
                if (rng.sound() != pitches[i]) return false;
            }
            return true;
        }).distinct();
    }

    static int[] parsePitches(String csv) { return Arrays.stream(csv.split(",")).mapToInt(Integer::parseInt).toArray(); }
    static long[] offsets(int count) { long[] o = new long[count]; for (int i=0;i<count;i++) o[i]=2L*i; return o; }

    /** A dry, stationary adult at breeding age zero, outside villages,
     * with a stationary player within 3 blocks.
     * StayIndoors must fail without a draw: Nether, or dry daylight in a rain-capable biome.
     * Paths that start wandering or emit an unaccounted ambient sound are rejected.
     */
    static final class State {
        Rng rng;
        int village, look, phase, lookMode = 1, ambient = -80, regeneration, countdown;
        boolean customer = true, followingCustomer = true;
        List<Offer> pool;
        long shuffleSeed = -1;
        Offer selected;
        State(long seed, int village, int look, int phase) {
            this.rng=new Rng(seed); this.village=village; this.look=look; this.phase=phase;
        }
        State copy() {
            State s=new State(rng.seed,village,look,phase);
            s.lookMode=lookMode; s.ambient=ambient; s.regeneration=regeneration; s.countdown=countdown;
            s.customer=customer; s.followingCustomer=followingCustomer; s.pool=pool;
            s.shuffleSeed=shuffleSeed; s.selected=selected;
            return s;
        }
        boolean tick(int size, int disabled) {
            if (regeneration > 0 && --regeneration > 0 && rng.nextBoolean())
                for (int i=0;i<3;i++) rng.nextDouble();
            if (rng.nextInt(1000) < ambient++) return false;
            if (phase == 0) {
                followingCustomer=customer;
                if (lookMode==1 && look<=0) lookMode=0;
                boolean startCustomer=customer && lookMode!=1;
                if (startCustomer) lookMode=1;
                if (!followingCustomer) {
                    if (lookMode==1) {
                        // LookAtCustomer continues after the menu closes. It blocks mating
                        // and GoToEntity, but not WanderAround (control bit 1).
                        if (rng.nextInt(120)==0) return false;
                    } else {
                        rng.nextInt(500); // VillagerMating: no village exists.
                        if (lookMode!=2 || look<=0) {
                            rng.nextFloat(); // GoToEntity(player), chance 1.0.
                            lookMode=2;
                            look=40+rng.nextInt(40);
                        }
                    }
                }
                if (startCustomer) look=40+rng.nextInt(40);
                // Existing customer look with an expired timer restarted above.
            } else {
                if (followingCustomer && !customer) followingCustomer=false;
                if (lookMode!=0 && look<=0) lookMode=0;
            }
            if (lookMode!=0) --look;
            phase=(phase+1)%3;
            if (--village<=0) village=70+rng.nextInt(50);
            if (!customer && countdown>0 && --countdown==0) {
                if (size>1) for(int i=0;i<disabled;i++) { rng.nextInt(6); rng.nextInt(6); }
                pool=candidates(rng,size);
                if (shuffleSeed>=0) {
                    Rng shared=new Rng(shuffleSeed);
                    List<Offer> shuffled=new ArrayList<>(pool);
                    ShuffleRng.shuffle(shuffled,shared);
                    selected=shuffled.get(0);
                    shuffleSeed=shared.seed;
                }
                regeneration=200;
            }
            return true;
        }
        boolean advance(int ticks,int size,int disabled) {
            for(int i=0;i<ticks;i++) if(!tick(size,disabled)) return false;
            return true;
        }
        String key() {
            return rng.seed+":"+village+":"+look+":"+phase+":"+lookMode+":"+ambient+":"
                +regeneration+":"+countdown+":"+customer+":"+followingCustomer+":"+pool+":"+shuffleSeed+":"+selected;
        }
    }

    static final class Tracker {
        List<State> states=new ArrayList<>();
        long[] coarse;
        void expand(long[] seeds) {
            states.clear();
            for(long seed:seeds)
                for(int v=1;v<=119;v++) for(int l=0;l<=78;l++) for(int p=0;p<3;p++)
                    states.add(new State(seed,v,l,p));
            coarse=null;
        }
        void initialize(int[] pitches) {
            if(pitches.length<7) throw new IllegalArgumentException("At least seven consecutive trade sounds required");
            long[] seeds=recover(pitches,offsets(pitches.length)).peek(s -> Cancel.check()).limit(5000001).toArray();
            if(seeds.length==0 || seeds.length>5000000) throw new IllegalStateException("Need a more informative batch: "+seeds.length+" seeds");
            states.clear();
            for(int i=0;i<seeds.length;i++) {
                Rng rng=new Rng(seeds[i]); rng.skip(2L*pitches.length);
                seeds[i]=rng.seed;
            }
            if(seeds.length<=8) expand(seeds); else coarse=seeds;
        }
        void observe(int pitch,int min,int max,boolean updateOnly) {
            if(coarse!=null) {
                if(min<21 || max>=40) throw new IllegalArgumentException("Coarse recovery requires GUI probes 21..39 ticks apart");
                long[] found=new long[Math.max(16,coarse.length)];
                int count=0;
                for(long seed:coarse) {
                    Cancel.check();
                    // At most one look restart and one village update in <40 ticks.
                    // nextInt(1000)'s rejection region contains the rejection regions
                    // of nextInt(40) and nextInt(50), bounding all possible raw calls.
                    Rng bound=new Rng(seed);
                    for(int j=0;j<max+2;j++) bound.nextInt(1000);
                    Rng advancing=new Rng(seed); advancing.skip(min);
                    for(long raw=min;raw<=bound.calls;raw++) {
                        Rng r=advancing.copy();
                        if(r.sound()==pitch) {
                            if(count==found.length) found=Arrays.copyOf(found,found.length*2);
                            found[count++]=r.seed;
                        }
                        advancing.next(1);
                    }
                }
                Arrays.sort(found,0,count);
                int distinct=0;
                for(int i=0;i<count;i++) if(i==0 || found[i]!=found[i-1]) found[distinct++]=found[i];
                coarse=Arrays.copyOf(found,distinct);
                if(distinct<=8) expand(coarse);
                return;
            }
            Map<String,State> matches=new LinkedHashMap<>();
            for(State old:states) {
                Cancel.check();
                State advancing=old.copy();
                for(int t=0;t<=max;t++) {
                    if(t>=min) {
                        State s=advancing.copy();
                        if((!updateOnly || s.ambient>-60) && s.rng.sound()==pitch) {
                            s.ambient=-80;
                            matches.put(s.key(),s);
                        }
                    }
                    if(t<max && !advancing.tick(0,0)) break;
                }
            }
            states=new ArrayList<>(matches.values());
        }
        int plan(int size,int disabled,int horizon,int jitter) {
            if(states.isEmpty()) return -1;
            for(int wait=Math.max(2,jitter);wait<=horizon;wait++) {
                boolean valid=true;
                for(State old:states) {
                Cancel.check();
                    for(int delta=wait-jitter;delta<=wait+jitter;delta++) {
                        State s=old.copy();
                        if(!s.advance(delta,size,disabled)) { valid=false; break; }
                        s.customer=false; s.countdown=40;
                        if(!s.advance(40,size,disabled) || s.pool==null
                            || (size<25 && s.pool.stream().anyMatch(o->o.kind==Kind.IRON_INGOT))) {
                            valid=false; break;
                        }
                    }
                    if(!valid) break;
                }
                if(valid) return wait;
            }
            return -1;
        }
        void resume(int size,int disabled,int minWait,int maxWait,int minClosed,int maxClosed) {
            Map<String,State> matches=new LinkedHashMap<>();
            for(State old:states) for(int wait=minWait;wait<=maxWait;wait++) {
                State atClose=old.copy();
                if(!atClose.advance(wait,size,disabled)) continue;
                atClose.customer=false; atClose.countdown=40;
                for(int closed=minClosed;closed<=maxClosed;closed++) {
                    State s=atClose.copy();
                    if(!s.advance(closed,size,disabled) || s.pool==null) continue;
                    if(size<25 && s.pool.stream().anyMatch(o->o.kind==Kind.IRON_INGOT)) continue;
                    s.customer=true;
                    matches.put(s.key(),s);
                }
            }
            states=new ArrayList<>(matches.values());
        }
        void filterOffer(Kind kind,int price) {
            states.removeIf(s->s.pool==null || s.pool.stream().noneMatch(o->o.kind==kind && o.price==price));
        }
        void initializeShuffle(int[][] samples) {
            if (coarse!=null || states.isEmpty()) throw new IllegalStateException("Entity timer states required");
            long[] recovered=ShuffleRng.recover(samples);
            if (recovered.length==0) throw new IllegalStateException("No shared RNG state matches the samples");
            Map<String,State> result=new LinkedHashMap<>();
            for (State old:states) for (long seed:recovered) {
                State s=old.copy(); s.shuffleSeed=seed;
                result.put(s.key(),s);
            }
            states=new ArrayList<>(result.values());
        }
        void observeShuffle(int[] sample) {
            Map<String,State> result=new LinkedHashMap<>();
            for (State old:states) {
                Cancel.check();
                if (old.shuffleSeed<0) continue;
                long seed=ShuffleRng.replay(old.shuffleSeed,sample);
                if (seed<0) continue;
                State s=old.copy(); s.shuffleSeed=seed;
                result.put(s.key(),s);
            }
            // A mismatch leaves entity inference intact for shared-state recovery.
            if (result.isEmpty()) throw new IllegalStateException("Unexpected shared RNG sample");
            states=new ArrayList<>(result.values());
        }
        static boolean newOffer(State s,int known) {
            if (s.selected==null || (known&(1<<s.selected.kind.ordinal()))!=0) return false;
            return Integer.bitCount(known)==25 ? s.selected.kind==Kind.IRON_INGOT : s.selected.kind!=Kind.IRON_INGOT;
        }
        int planNew(int known,int disabled,int minimum,int horizon,int jitter) {
            if (jitter<0 || minimum<0 || disabled<0) throw new IllegalArgumentException("Nonnegative timing and stock bounds required");
            if (states.isEmpty() || coarse!=null) return -1;
            int size=Integer.bitCount(known);
            for (int wait=Math.max(minimum,jitter);wait<=horizon;wait++) {
                boolean valid=true;
                for (State old:states) {
                Cancel.check();
                    if (old.shuffleSeed<0) { valid=false; break; }
                    for (int delta=wait-jitter;delta<=wait+jitter;delta++) {
                        State s=old.copy();
                        if (!s.advance(delta,size,disabled)) { valid=false; break; }
                        s.customer=false; s.countdown=40; s.pool=null; s.selected=null;
                        if (!s.advance(40,size,disabled) || !newOffer(s,known)) { valid=false; break; }
                    }
                    if (!valid) break;
                }
                if (valid) return wait;
            }
            return -1;
        }
        void resumeNew(int size,int disabled,int minWait,int maxWait,int minClosed,int maxClosed,Kind kind,int price) {
            Map<String,State> result=new LinkedHashMap<>();
            for (State old:states) for (int wait=minWait;wait<=maxWait;wait++) {
                State atClose=old.copy();
                if (!atClose.advance(wait,size,disabled)) continue;
                atClose.customer=false; atClose.countdown=40; atClose.pool=null; atClose.selected=null;
                for (int closed=minClosed;closed<=maxClosed;closed++) {
                    State s=atClose.copy();
                    if (!s.advance(closed,size,disabled) || s.selected==null
                        || s.selected.kind!=kind || s.selected.price!=price) continue;
                    s.customer=true;
                    result.put(s.key(),s);
                }
            }
            if (result.isEmpty()) throw new IllegalStateException("Selected offer excluded all joint models");
            states=new ArrayList<>(result.values());
        }
        String status() {
            if(coarse!=null) return "\"mode\":\"coarse\",\"states\":"+coarse.length+",\"seeds\":"+coarse.length;
            long seeds=states.stream().mapToLong(s->s.rng.seed).distinct().count();
            long shared=states.stream().mapToLong(s->s.shuffleSeed).filter(s->s>=0).distinct().count();
            return "\"mode\":\"timers\",\"states\":"+states.size()+",\"seeds\":"+seeds+",\"shuffle_states\":"+shared;
        }
    }

    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
