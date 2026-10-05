# Processing-Tick Validation

RNGTrader 1.1.0 uses a synchronized baby-cow clock for entity inference and ordered server replies for close timing. Entity-model intervals come from completed server ticks. The shared status-sample consumer uses a five-second wall-clock schedule.

## RNGTrader 1.0.0 regressions

RNGTrader 1.0.0 rounded packet receipt intervals by 50 ms, requested reopening after 2.1 seconds, and accepted a closed interval of 40..44 ticks. Client scheduling, server scheduling, and packet delivery contributed separate delays to those measurements.

The [calibration fixture](../src/test/resources/timing-failures/calibration.json) records a free probe rejected by the timing gate. The [reopen fixture](../src/test/resources/timing-failures/reopen.json) records a 44.813-tick wall-time measurement, which rounded to 45 and exceeded the accepted interval.

The [stock/model fixture](../src/test/resources/timing-failures/refresh.json) records a planned close delay of 10 ± 1 ticks from the last sound observation. The returned `7 Emeralds → Iron Axe` offer matches a 12-tick delay in replay. Delays 9..11 exclude every model across closed durations of 40..70 ticks. This case establishes the need to measure the close-processing tick as well as the reopen-processing tick.

LattiCG 1.07's `EnumerateRt.enumerate` constructs a parallel stream. In RNGTrader 1.0.0, seed enumeration therefore ran across multiple threads despite the single inference executor. RNGTrader 1.1.0 consumes the stream sequentially on the inference worker, with cancellation and computation-budget checkpoints.

## Clock and receipt semantics

Vanilla `EntityAgeable` advances a negative breeding Age toward zero on each server entity update and writes it to metadata index 12. The cow's AI and sound draws use its entity Random. The clock reads the resulting packets. Its enclosure stays outside the Blacksmith's AI area, and breeding uses a member of the twelve-player roster.

The clock counts Age-bearing metadata packets after the initial full metadata snapshot. Server metadata entries can be encoded after another tick updates their value, so two packets with the same Age can represent two tracker updates.

Two complete World Time intervals must each contain twenty markers. A World Time boundary supplies a common phase for clock handover. The active clock remains selected until Age -400; a younger synchronized cow takes over. Age -200, lost tracking, a backward Age, or a cadence mismatch invalidates that clock.

The close sequence is:

1. Receive a recent clock marker inside the forecast's admitted processing interval.
2. Verify unchanged observation, model, roster, and clock revisions, with empty observation and inference queues.
3. Send Tab-Complete, Close Window, and Tab-Complete on the play connection.
4. Use the clock marker preceding each completion response to bracket the Close Window handler.
5. Request merchant interaction after forty observed ticks from the latest possible close tick.
6. Record the processing tick attached to the returned merchant offers before deferred GUI handling.

The completion strings are `rngtrader_tick_receipt_before` and `rngtrader_tick_receipt_after`. Vanilla handles these through player-name completion, returning empty suggestions and preserving both modeled RNG states.

Post-refresh filtering evaluates correlated pairs: for each possible close tick `c`, the wait is `c - soundAnchor` and the closed interval is `openedTick - c`. The implementation accepts measured closed intervals of 40..120 ticks and checks the exact appended type and price.

## Regression coverage

| Test | Coverage |
| --- | --- |
| `LegacyTimingTraceTest` | Calibration, rounded reopen, and excluded-model observations from RNGTrader 1.0.0. |
| `ProcessingTickRegressionTest` | Close/reopen correlation and a measured 48-tick closed interval. |
| `ServerTickClockTest` | Initial snapshots, repeated Age values, batched delivery, lost markers, backward Age, tracker loss, clock handover, and receipt bounds. |
| `TimingLifecycleTest` | Forecast commit checks for observation/model/roster changes, worker backlog, missed markers, and clock loss. |
| `SessionLogTest` | Observation snapshots and asynchronous log draining. |
| `RngModelTest` | Sequential recovery, RNG arithmetic, and rejection sampling. |
| `TraceReplayTest` | Complete mathematical replay of 25 appending refreshes with Iron last. |

Validation covers 45 passing tests, including eight in `TimingLifecycleTest`. [Build metadata](validation/build.json) records the artifact digest and test counts.

## Multiplayer results

The test environment uses the official Vanilla 1.7.10 server, an obfuscated Forge 10.13.4.1614 client, Java 8u472, and twelve Survival players. Eleven protocol clients supply the roster; one also breeds replacement cows. The [integration harness](../tools/integration/README.md) includes the prepared world and setup instructions.

| Record | Seed enumeration | Duration | Completed refreshes | End state | Closed intervals |
| --- | --- | --- | --- | --- | --- |
| [clock-live](validation/clock-live.json) | Parallel | 16.8 min | 25 | 26 distinct types; `8 Iron Ingots → Emerald` last | 41..43 ticks |
| [stress-live](validation/stress-live.json) | Sequential | 33.7 min | 25 | 26 distinct types; `9 Iron Ingots → Emerald` last | 41..52 ticks |
| [sequential-live](validation/sequential-live.json) | Sequential | 43.1 min | 23 | Stopped at 24 types while waiting for Coal | 41..42 ticks |

All three records report zero session errors and zero dropped log records. Close receipts include processing ticks two ticks after the marker used for transmission. Each sequential session completed two clock handovers while retaining a valid model.

In `stress-live`, the 250 ms and 600 ms client pauses occurred after close transmission. The first refresh reopened after 52 server ticks and appended a new type. The session then completed all 25 refreshes.

| Record | Initial seed candidates | Initialization wall time | Inference-thread CPU time | Queued observations |
| --- | --- | --- | --- | --- |
| stress-live | 426,601 | 681.5 s | 382.0 s | 748 |
| sequential-live | 53,874 | 701.6 s | 395.3 s | 755 |

Replaying the queued observations reduced each model to one state.

## Scheduling conditions and wait times

Forecasts admit an interval for future packet processing. The local 20 TPS profile reserves margins around status updates. A receipt outside its admitted interval, an unexpected shared-status update during refresh, or an unmodeled entity action stops automation.

With 24 existing offers, Coal's candidate probability is approximately `0.9 - (0.7 + 0.2 * sqrt(24) - 0.9) = 0.1202`. Coal must then be selected by the shared shuffle under every admitted timing branch. The planner treats Coal like any other unseen non-Iron type, so late Coal can produce long waits even with a converged model.

Inference uses one worker. Enumeration checkpoints use five-millisecond compute slices and up to five milliseconds of rest. Lattice setup, garbage collection, and operating-system scheduling affect the time between checkpoints. Schema 2 records queue length, initialization wall and thread CPU durations, client tick gaps, and garbage-collection totals.
