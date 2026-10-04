package rngtrader.core;

import com.seedfinding.latticg.util.LCG;
import java.util.*;

/** Exact shared Random recovery from eight complete 12-player permutations. */
public final class ShuffleRng {
    static final long A = 0x5DEECE66DL, MASK = (1L << 48) - 1;
    static final int LOW_BITS = 19, LOW_MASK = (1 << LOW_BITS) - 1;
    static final int SAMPLES = 8, DRAWS = 11;

    public static int[] decode(int[] permutation) {
        if (permutation.length != 12) throw new IllegalArgumentException("12 players required");
        int[] current = new int[12], draws = new int[11];
        for (int i=0;i<12;i++) current[i]=i;
        for (int i=12;i>1;i--) {
            int j=0;
            while (j<i && current[j]!=permutation[i-1]) j++;
            if (j==i) throw new IllegalArgumentException("Not a permutation");
            draws[12-i]=j;
            int old=current[i-1]; current[i-1]=current[j]; current[j]=old;
        }
        if (current[0]!=permutation[0]) throw new IllegalArgumentException("Not a permutation");
        return draws;
    }

    static boolean lowFits(long seed, int[] values) {
        for (int t=0;t<values.length;t++) {
            seed=(seed*A+11)&LOW_MASK;
            int b=12-t%11;
            if (b==12 && ((seed>>>17)&3)!=(values[t]&3)) return false;
            if ((b==10 || b==6) && ((seed>>>17)&1)!=(values[t]&1)) return false;
        }
        return true;
    }

    /** Return the state after the observations, or -1 for a mismatch. */
    static long replay(long seed, int[] values) {
        for (int t=0;t<values.length;t++) {
            int b=12-t%11, bits, value;
            do {
                seed=(seed*A+11)&MASK;
                bits=(int)(seed>>>17);
                value=bits%b;
            } while ((b&-b)!=b && bits-value+b-1<0);
            if ((b&-b)==b) value=(int)((b*(long)bits)>>31);
            if (value!=values[t]) return -1;
        }
        return seed;
    }

    static int[] flatten(int[][] samples, int count) {
        int[] values=new int[count*DRAWS];
        for (int i=0;i<count;i++) {
            if (samples[i].length!=DRAWS) throw new IllegalArgumentException("11 draws per sample required");
            for (int j=0;j<DRAWS;j++) {
                if (samples[i][j]<0 || samples[i][j]>=12-j) throw new IllegalArgumentException("Invalid shuffle draw");
                values[i*DRAWS+j]=samples[i][j];
            }
        }
        return values;
    }

    /** Complete enumeration: no-rejection paths plus every possible first rejection. */
    static long[] recover(int[][] samples) {
        if (samples.length<SAMPLES+1) throw new IllegalArgumentException("Eight samples and a held-out sample required");
        int[] prefix=flatten(samples,SAMPLES), all=flatten(samples,samples.length);
        Set<Long> seeds=new LinkedHashSet<>();
        for (long low=0;low<=LOW_MASK;low++) if (lowFits(low,prefix)) {
            for (long high=0;high<(1L<<(48-LOW_BITS));high++) {
                if ((high & 4095) == 0) Cancel.check();
                long seed=low+(high<<LOW_BITS);
                long end=replay(seed,all);
                if (end>=0) seeds.add(end);
            }
        }
        // Before the first rejection, each preceding bounded call consumes one
        // raw draw. Rewind the rejected state by its position plus one, then
        // replay exactly; later rejection loops need no separate enumeration.
        for (int t=0;t<prefix.length;t++) {
            int bound=12-t%DRAWS;
            long lower=(((1L<<31)/bound)*bound)<<17;
            LCG rewind=LCG.JAVA.combine(-(t+1));
            for (long rejected=lower;rejected<=MASK;rejected++) {
                if ((rejected & 4095) == 0) Cancel.check();
                long end=replay(rewind.nextSeed(rejected),all);
                if (end>=0) seeds.add(end);
            }
        }
        return seeds.stream().mapToLong(Long::longValue).toArray();
    }

    static <T> void shuffle(List<T> list, BlacksmithRng.Rng rng) {
        for (int i=list.size();i>1;i--) Collections.swap(list,i-1,rng.nextInt(i));
    }

    static int[][] samples(long seed, int count) {
        BlacksmithRng.Rng rng=new BlacksmithRng.Rng(seed);
        int[][] result=new int[count][DRAWS];
        for (int i=0;i<count;i++) for (int j=0;j<DRAWS;j++) result[i][j]=rng.nextInt(12-j);
        return result;
    }

}
