# Client Observation and RNG Recovery

## Runtime components

`rngtrader.core` contains the Java 48-bit LCG, sound-pitch inversion, AI timer model, candidate construction, shared-shuffle recovery, roster interpretation, cached-sample clock, and gross-payment budget. `RngEngine` exposes initialization, observation filtering, shared-state recovery, and post-refresh filtering to a single inference worker.

`ClientProxy` installs a passive Netty observer after Forge establishes the play connection. Packet observations receive `System.nanoTime()` timestamps before normal client processing. The observer copies payload data and queues work for the client thread. Connection epochs reject callbacks from old connections. Worker generations reject results from stopped sessions.

`TraderService` owns the session state machine, target, observations, and roster. It runs at the end of the client tick. `TradeExecutor` serializes container clicks and waits for confirmation of each action. `StatusPoller` uses one status connection to request the cached public server response every 250 ms.

Trading uses merchant interaction, `MC|TrSel` recipe selection, container clicks, and container closure.

## Preparation model

The checker reads the client world, entity metadata, current merchant offers, and player inventory. `EntityAgeable` metadata exposes the integer breeding age in 1.7.10, allowing the checker to distinguish `Age = 0` from a positive breeding cooldown. Ground support is checked using block collisions.

The block scan covers the inclusive ranges `floor(x) ± 16`, `floor(y) ± 8`, and `floor(z) ± 16`, clipped to valid world height. All chunks within the scan must be loaded. The AI-area check excludes other players and living mobs within the villager bounding box expanded by `(8, 3, 8)`.

The supported weather branch is the Nether, or a daytime, non-raining Overworld in a rain-capable biome. The session checks positions continuously and repeats the preparation scan once per second.

Server village state and remote shared-shuffle consumers are reported as `UNKNOWN` because they are unavailable in client world data. Remote respawns can leave the observed roster order stale until the next complete player-list update run.

## Player order and shared Random

Vanilla sends the joining client all online players in `ServerConfigurationManager.playerEntityList` order. Each later join appends a player; a leave removes that player. The roster treats repeated online `S38PacketPlayerListItem` packets for known names as ping updates.

Vanilla periodically sends a run of one player-list update per tick in server-list order, separated from the next run by a long silent interval. A complete, distinct run covering the current roster reconciles the insertion order. The next complete run also reveals an offscreen respawn's remove-and-append effect on the server list.

Exactly 12 distinct players make the complete server status sample observable. Cached status samples update on a five-second wall-clock interval. Descending Fisher–Yates permutation inversion yields `nextInt(12)` through `nextInt(2)`. Recovery uses eight consecutive samples and a ninth verification sample. The search includes every possible first rejection position and exact replay of subsequent rejection loops. UUID changes, incomplete samples, roster changes, missing updates, and ambiguous repeated permutations reset shared-state recovery.

The model targets the Java 8 `Collections.shuffle()` implementation with a shared `java.util.Random` and descending Fisher–Yates. Java SE specifies the `java.util.Random` algorithm; the default shuffle generator and algorithm are implementation-specific. Sample recovery verifies the observed shuffle sequence against this model.

## Entity observations and stock

Seven consecutive successful trades provide quantized adult sound pitches. Initial recovery combines their paired `nextFloat()` constraints with the first three free merchant sounds. Joint AI timer profiles bound the ordinary raw-call offsets; a separate rejection lattice covers bounded calls that consume additional draws. The model then filters possible AI phases, look timers, village timers, sound cooldowns, and Regeneration duration. Calibration continues for at least 45 free observations. [Entity Seed Recovery](seed-recovery.md) defines the lattice construction, coverage, and state anchors.

The client inserts exactly seven payments for calibration. Merchant recipes expose a synchronized disabled flag, while `uses` and `maxUses` remain server-side. After seven confirmed trades, the calibration offer may be disabled or may still have stock. The stock model retains both possible disabled-offer counts. The first refresh with one offer skips disabled-offer restoration in either case. At later sizes, each disabled offer contributes the modeled restock draws. Post-refresh observations retain the union of matching stock and timing branches.

Every newly appended offer is fresh. One use of that last offer arms the next countdown. The initial calibration therefore requires seven trades, and each subsequent refresh requires one additional trade. Starting with one offer needs 25 refreshes and 31 trades.

The open merchant container pauses the villager's 40-tick countdown. GUI probes are requested after twenty-one observed ticks; inference receives the measured interval. The inference worker processes the observations in order and expands seed candidates into timer states as the candidate count decreases.

## Inventory budget and output ownership

Before calibration, the budget includes seven uses of the current last offer at its observed price and one use of every missing non-Iron type at its maximum Vanilla price. After calibration it includes only remaining payments. Once a refresh is armed, its last-offer payment is already covered. The budget uses gross payments, keeping the bound independent of unlock order and future Emerald receipts.

Payment stacks may be split across inventory slots. The executor combines the exact required count in the merchant input using pickup and placement clicks. It primes the merchant sound cooldown before shift-clicking the result to isolate the calibration batch's trade sounds.

A positive transaction acknowledgment validates the returned stack. The executor counts successful trade sounds to determine how many shift-click iterations completed. If stock runs out early, the executor rebuilds the local result using the observed count, retains the outputs, and stops.

Purchased gear is tracked only in previously empty destination slots. Before dropping it, the executor checks that the slot still contains the expected item, damage, count, and NBT. A player item-pickup packet invalidates ownership during the transaction. Currency outputs are retained.

## Server tick clock

`ServerTickClock` observes Cow Age at metadata index 12. It excludes the initial full metadata snapshot and counts subsequent Age-bearing packets. Mutable server metadata entries can be encoded after a later tick, so repeated encoded Age values each remain a tick marker. Animals already loaded when observation starts enter through a separate discovery path.

Two consecutive World Time intervals must each contain twenty markers. World Time precedes world/entity ticking; a GUI sound or merchant response in the network phase follows that tick's tracker markers. These packet positions provide the entity model's tick anchors. Natural ambient sounds occur earlier in the tick and terminate the controlled session.

An eligible cow stays more than twelve blocks away on at least one horizontal axis. The active cow is retained until its Age reaches -400. A synchronized replacement is selected at a World Time boundary, where all clocks have the same phase. Age -200, a backward Age, tracker loss, dimension change, or failed cadence invalidates the clock. `check` requires a synchronized clock before calibration.

Two Tab-Complete requests bracket Close Window on the same connection. The strings `rngtrader_tick_receipt_before` and `rngtrader_tick_receipt_after` select Vanilla's player-name completion branch and yield empty responses while preserving both modeled RNG states. The preceding Age marker of each response bounds the close-processing tick. The `MC|TrList` response supplies the reopen-processing tick and the updated merchant offers.

## Computation and logging

One worker owns inference and consumes LattiCG's enumeration streams sequentially. It buffers three free sounds before initial recovery, then applies the buffered observations once in order. Enumeration checkpoints use cooperative five-millisecond computation slices with up to five milliseconds of rest. Lattice setup precedes enumeration; JVM GC and OS scheduling affect the time between checkpoints.

Schema 2 session records include packet sequence and receipt times, model revisions, processing-tick intervals, worker queue/wall/CPU durations, and JVM GC totals. `entity_recovery` records expose seed-recovery stages, branch progress, candidate counts, and cumulative wall/CPU durations. Producers serialize a snapshot into a bounded queue. A dedicated writer flushes in batches; overflow emits a `log_dropped` record.

## Build and distribution

RetroFuturaGradle supplies Forge 1.7.10 and the embedded Forge mappings, with MCP stable 12 parameter mappings. Source code targets Java 8. LattiCG 1.07 is relocated under `rngtrader.internal.latticg`; its MIT license is included in the distribution. The thin JAR contains the mod classes and resources; the reobfuscated shaded JAR bundles LattiCG for installation.
