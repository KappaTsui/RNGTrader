# Entity Seed Recovery

RNGTrader recovers the Blacksmith's internal `java.util.Random` state from seven consecutive trade sounds and free merchant sounds. The first three free sounds constrain the initial lattice search. Subsequent observations recover the AI timers and validate the remaining states.

The initial observation sequence comprises seven calibration payments and at least 45 free observations. Initial recovery uses the first three free observations, each at an exact interval of 21 through 39 server ticks from the preceding sound.

The implementation is in `BlacksmithRng`, `SeedRecovery`, `RngEngine`, and `InferenceWorker`. The entity assumptions and packet clock are described in [Client Observation and RNG Recovery](architecture.md).

## State and observation model

Let `s_t` be the internal RNG state after `t` raw calls, with `s_0` immediately before the first calibration sound:

```text
M = 2^48
a = 0x5DEECE66D
c = 11
F(s) = (a*s + c) mod M
s_(t+1) = F(s_t)
N = 2^24
```

A sound at raw-call offset `o` uses states `s_(o+1)` and `s_(o+2)`. Their upper 24 bits, `u` and `v`, produce the packet pitch byte through the Java float expression:

```java
(int)(((u / (float)N - v / (float)N) * 0.2f + 1.0f) * 63.0f)
```

For an adult villager, the byte lies in `50..75`. The calibration batch shares one processing tick, so its seven offsets are `0, 2, 4, 6, 8, 10, 12`. Its final state is `s_14`.

The held-customer model starts with an ambient-sound counter of `-80`, an AI phase in `0..2`, a look timer in `0..78`, and a village timer in `1..119`. Each entity tick consumes an ambient `nextInt(1000)`. A look restart adds `nextInt(40)`, and a village update adds `nextInt(50)`. Regeneration has expired before calibration. Free sounds reset the ambient counter.

## The 46-bit difference LCG

Consecutive raw states obey:

```text
D_t = (s_t - s_(t+1)) mod M
D_t = 1 mod 4
m = 2^46
k = (1-a)/4
z_t = (D_t-1)/4 = k*s_t-3 mod m
z_(t+1) = a*z_t + (a-1)/4 mod m
```

Thus each sound bounds one output of a 46-bit LCG. `BlacksmithRng.firstDifference` uses binary search over the exact float expression to find the inclusive integer interval `[L_p, U_p]` for `u-v` at pitch `p`. Accounting for the unobserved low 24 bits gives a signed difference interval:

```text
L_p*N - (N-1) <= s_t-s_(t+1) <= U_p*N + (N-1)
```

An equivalent signed representative of `z_t` lies in:

```text
ceil((L_p*N - (N-1) - 1)/4)
    <= z_t <=
floor((U_p*N + (N-1) - 1)/4)
```

Signed representatives allow intervals that cross zero to retain their full width. `differenceLower` and `differenceUpper` supply the same bounds to both recovery paths.

The multiplier `k` is odd and therefore invertible modulo `m`. A recovered initial difference state yields four full internal states:

```text
r = k^(-1)*(z_0+3) mod m
s_0 = r + h*m,  h in {0,1,2,3}
```

Each lift is evaluated through the exact Java float expression at every specified sound offset. Recovery retains the seeds that reproduce all observed pitch bytes.

## Probe offsets and timer profiles

For a probe at an interval of `g_i` ticks, let `e_i` count the optional look and village calls in that interval. A path with no bounded-call rejection has:

```text
o_(i+1) = o_i + 2 + g_i + e_i
e_i in {0,1,2}
```

The two calls account for the preceding sound. Because `g_i <= 39`, an interval contains at most one look restart and at most one village update.

The three `e_i` values are correlated by the timer periods:

| Timer | First event after calibration | Subsequent event spacing |
|---|---|---|
| LookAtCustomer | 1 through 81 ticks | 42, 45, ..., 81 ticks |
| Village update | 1 through 119 ticks | 70 through 119 ticks |

A look restart draws a value in `40..79`; task starts are checked once every three ticks. The resulting event spacing is `3*ceil(value/3)`.

`eventProfiles` computes reachable pairs of event tick and interval mask. Each bit records whether that timer fired in a probe interval. A mask is terminal only when an allowed next period puts the next event beyond the observation horizon. The empty mask is admitted when the initial timer can outlast the entire horizon.

`timerProfiles` adds the look and village bits per interval and deduplicates the resulting count vectors. Three 22-tick intervals produce 17 vectors. For each vector, `SeedRecovery` builds the ten sound offsets and calls `BlacksmithRng.recover`.

The timer profiles describe possible event schedules independently of the generated reset values. This retains a superset of the physically consistent seeds; observation tracking subsequently checks the generated timer values and phase.

## Bounded-call rejections

For a non-power-of-two bound `b`, Java's `nextInt(b)` rejects a raw state when its upper 31 bits fall in the incomplete final residue block. The rejected 48-bit states form:

```text
R_b = [floor(2^31/b)*b*2^17, M-1]
```

`R_40` and `R_50` are subsets of `R_1000`. The latter contains `648*2^17 = 84,934,656` states, approximately `3.0175e-7` of the full state space.

Recovery divides the search into paths without rejection and paths with a first rejection. Before the first rejection, every preceding bounded call consumes exactly one raw draw. For three probes with intervals `g_1, g_2, g_3`, the first rejected raw call has a position in:

```text
15 <= j <= 14 + sum(g_i) + 4 + 6
           = 24 + sum(g_i)
```

The terms are the calibration draws, one ambient call per tick, four draws for the first two probe sounds, and at most six optional AI calls. The third probe's float calls cannot reject. For three 22-tick intervals, the positions are `15..90`.

### The rejection lattice

For each position `j`, use the rejected state `q = s_j` as the anchor and constrain it to `R_1000`. For calibration sound `i`, numbered from zero, compute:

```text
F^(2*i+1-j)(q) = A_i*q + C_i mod M
beta_i  = k*A_i mod m
gamma_i = k*C_i-3 mod m
z_(2*i+1) = beta_i*q + gamma_i mod m
```

The eight lattice coordinates are:

```text
(q, z_1, z_3, z_5, z_7, z_9, z_11, z_13)
```

Use the affine origin `(0, gamma_0, ..., gamma_6)` and the row basis:

```text
(1, beta_0, beta_1, ..., beta_6)
(0, m,      0,     ..., 0)
(0, 0,      m,     ..., 0)
...
(0, 0,      0,     ..., m)
```

The box bounds are `R_1000` for the first coordinate and the seven signed pitch intervals for the remaining coordinates. `RejectionLattice` scales each coordinate by the least common multiple of the inclusive box widths divided by that coordinate's width. It performs LLL reduction with exact rational arithmetic, reverses the scaling, transposes the row basis, and passes the result to LattiCG's box enumeration.

Each enumerated anchor is rewound by `j` calls and verified against the seven calibration pitches. These candidates are merged with the ordinary-path candidates. Duplicate seeds are removed before initialization.

The lattice constrains the pitch and raw-state intervals. `Tracker.observe` refines seed and timer candidates using subsequent measured pitches and tick intervals.

### Coverage

Every seed consistent with the held-customer model either has no rejection before the third probe or has a first rejection in the finite position range above. The first class is represented by a timer profile and the ordinary difference lattice. The second class supplies an anchor in `R_1000` and all seven pitch intervals, so it is represented by a rejection lattice.

Subsequent rejection loops are handled by forward execution. During coarse tracking, `nextInt(1000)` bounds the raw-call count because its rejection region contains the other two regions. Once the candidate count permits timer expansion, `State.tick` executes each bounded call until a draw is accepted, retaining paths with repeated rejections.

## Initialization and observation order

`RecoveryProbe` contains an immutable pitch and exact tick interval. The `RngEngine.initialize(pitches, lookahead)` overload requires seven calibration pitches and three probes. The lookahead constrains initial seed selection. The initialized tracker is anchored at `s_14`.

`InferenceWorker` copies the calibration batch and buffers the next three sound observations. With three eligible free probes, it initializes using lookahead and then applies each buffered sound once, in order. Later sounds remain in the same executor queue. Shared status samples are stored in a nine-sample window for shuffle recovery.

An uncertain interval, a paid sound in the prefix, or a different calibration length selects the consecutive-pitch initializer, reported as the `legacy` stage. Buffered sounds are then passed to `RngEngine.observe` in order.

`RngEngine` builds a replacement tracker and installs it after both recovery branches finish and cancellation is checked. Initialization failures discard the worker's incomplete calibration buffer.

## Computation and observability

Both enumeration paths use the inference thread and sequential streams. Cancellation and `ComputeBudget` checkpoints use five-millisecond computation slices and up to five milliseconds of rest. Lattice construction and LLL reduction occur between checkpoints.

The `entity_recovery` session event records:

| Field | Meaning |
|---|---|
| `stage` | `collecting_probes`, `ordinary`, `rejections`, `legacy`, or `complete` |
| `completed`, `total` | Progress within the current stage |
| `candidates` | Distinct initial seeds accumulated so far |
| `elapsedNanos`, `cpuNanos` | Cumulative costs of the current recovery invocation |

Collection events use zero costs. Unsupported thread CPU measurements use `-1`. `/rngtrader status` displays the latest stage, progress, candidate count, and CPU time. The `worker` records include buffered-observation replay and subsequent inference work.

For interior pitch bins, a difference interval occupies approximately `1/12.6` of the modulus. A box-volume estimate therefore reduces the ordinary enumeration volume by approximately `12.6^3/B`, where `B` is the number of timer profiles. For `B = 17`, this is about 118. Total recovery work comprises LLL reduction, lattice enumeration, exact float replay, and rejection coverage.

## References

- [Java 8 Random specification](https://docs.oracle.com/javase/8/docs/api/java/util/Random.html): LCG transition and bounded-call rejection algorithm.
- [LattiCG](https://github.com/mjtb49/LattiCG): lattice reduction and branch-and-bound recovery from RNG output constraints.
- [Matthew Bolan's description of skip handling](https://github.com/elttam/rsu-cracker/issues/1): solve the no-skip case and separately enumerate seeds with skips.
- [BoundNextIntSkips](https://github.com/mjtb49/BoundNextIntSkips): exact bounds on bounded-call skips and rare-state enumeration.

RNGTrader applies the no-skip/skip decomposition to the held-customer call sequence. The mixed-modulus rejection lattice combines a rare 48-bit raw-state interval with seven 46-bit pitch constraints.
