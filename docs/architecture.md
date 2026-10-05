# Client Observation and Trade Scheduling

## Runtime components

`rngtrader.core` contains the Java 48-bit LCG, sound-pitch inversion, AI timer model, candidate construction, shared-shuffle recovery, roster interpretation, cached-sample clock, and gross-payment budget. `RngEngine` exposes initialization, observation filtering, shared-state recovery, planning, and post-refresh filtering to a single inference worker.

`ClientProxy` installs a passive Netty observer after Forge establishes the play connection. Packet observations receive `System.nanoTime()` timestamps before normal client processing. The observer copies payload data and queues work for the client thread. Connection epochs reject callbacks from old connections. Worker generations reject results from stopped sessions.

`TraderService` owns the session state machine, target, observations, roster, and deadlines. It runs at the end of the client tick. `TradeExecutor` serializes container clicks and waits for confirmation of each action. `StatusPoller` uses one status connection to request the cached public server response every 250 ms.

Trading uses merchant interaction, `MC|TrSel` recipe selection, container clicks, and container closure.

## Preparation model

The checker reads the client world, entity metadata, current merchant offers, and player inventory. `EntityAgeable` metadata exposes the integer breeding age in 1.7.10, allowing the checker to distinguish `Age = 0` from a positive breeding cooldown. Ground support is checked using block collisions.

The local block scan covers the inclusive ranges `floor(x) ± 16`, `floor(y) ± 8`, and `floor(z) ± 16`, clipped to valid world height. Missing chunks make this check incomplete. The AI-area check excludes other players and living mobs within the villager bounding box expanded by `(8, 3, 8)`.

The supported weather branch is the Nether, or a daytime, non-raining Overworld in a rain-capable biome. The session checks positions continuously and repeats the full local preparation scan once per second. An acknowledged inventory operation continues while payment items move between slots. Before a refresh is released, the current report must pass and the cursor and payment slots must be empty.

Server village state and remote shared-shuffle consumers are reported as `UNKNOWN` because they are unavailable in client world data. Remote respawns can leave the observed roster order stale until the next complete player-list update run.

## Player order and shared Random

Vanilla sends the joining client all online players in `ServerConfigurationManager.playerEntityList` order. Each later join appends a player; a leave removes that player. The roster treats repeated online `S38PacketPlayerListItem` packets for known names as ping updates.

Vanilla periodically sends a run of one player-list update per tick in server-list order, separated from the next run by a long silent interval. A complete, distinct run covering the current roster reconciles the insertion order. The next complete run also reveals an offscreen respawn's remove-and-append effect on the server list.

Exactly 12 distinct players make the complete server status sample observable. Descending Fisher–Yates permutation inversion yields `nextInt(12)` through `nextInt(2)`. Recovery uses eight consecutive samples and a ninth verification sample. The search includes every possible first rejection position and exact replay of subsequent rejection loops. UUID changes, incomplete samples, roster changes, missing updates, and ambiguous repeated permutations reset shared-state recovery.

The model targets the Java 8 `Collections.shuffle()` implementation with a shared `java.util.Random` and descending Fisher–Yates. Java SE specifies the `java.util.Random` algorithm; the default shuffle generator and algorithm are implementation-specific. Sample recovery verifies the observed shuffle sequence against this model.

## Entity observations and stock

Seven consecutive successful trades provide quantized adult sound pitches. The model reverses the paired `nextFloat()` draws, then filters possible entity states using free merchant sounds. It retains possible AI phases, look timers, village timers, sound cooldowns, and Regeneration duration. Calibration continues for at least 45 free observations.

The client inserts exactly seven payments for calibration. Merchant recipes expose a synchronized disabled flag, while `uses` and `maxUses` remain server-side. After seven confirmed trades, the previous last offer may be disabled or may still have stock. Planning therefore considers both possible disabled-offer counts. The first refresh with one offer skips disabled-offer restoration in either case. At later sizes, each disabled offer contributes the modeled restock draws. Post-refresh observations retain the union of matching stock and timing branches.

Every newly appended offer is fresh. One use of that last offer arms the next countdown. The initial calibration therefore requires seven trades, and each subsequent refresh requires one additional trade. Starting with one offer needs 25 refreshes and 31 trades.

## Inventory budget and output ownership

Before calibration, the budget includes seven uses of the current last offer at its observed price and one use of every missing non-Iron type at its maximum Vanilla price. After calibration it includes only remaining payments. Once a refresh is armed, its last-offer payment is already covered. The budget uses gross payments, keeping the bound independent of unlock order and future Emerald receipts.

Payment stacks may be split across inventory slots. The executor combines the exact required count in the merchant input using pickup and placement clicks. It primes the merchant sound cooldown before shift-clicking the result to isolate the calibration batch's trade sounds.

A positive transaction acknowledgment validates the returned stack. The executor counts successful trade sounds to determine how many shift-click iterations completed. If stock runs out early, the executor rebuilds the local result using the observed count, retains the outputs, and stops.

Purchased gear is tracked only in previously empty destination slots. Before dropping it, the executor checks that the slot still contains the expected item, damage, count, and NBT. A player item-pickup packet invalidates ownership during the transaction. Currency outputs are retained.

## Server tick clock

`ServerTickClock` observes Cow Age at metadata index 12. It excludes the initial full metadata snapshot and counts subsequent Age-bearing packets. Mutable server metadata entries can be encoded after a later tick, so repeated encoded Age values each remain a tick marker. Animals already loaded when observation starts enter through a separate discovery path.

Two consecutive World Time intervals must each contain twenty markers. World Time precedes world/entity ticking; a GUI sound or merchant response in the network phase follows that tick's tracker markers. These packet positions provide the entity model's tick anchors. Natural ambient sounds occur earlier in the tick and terminate the controlled session.

An eligible cow stays more than twelve blocks away on at least one horizontal axis. The active cow is retained until its Age reaches -400. A synchronized replacement is selected at a World Time boundary, where all clocks have the same phase. Age -200, a backward Age, tracker loss, dimension change, or failed cadence invalidates the clock. `check` requires a synchronized clock before calibration.

## Scheduling and interruption

The open merchant container pauses the villager's 40-tick countdown. GUI probes are requested after twenty-one observed ticks; inference receives the measured interval. The inference worker makes coarse/fine model decisions in observation order. A forecast binds the observation version, model revision, roster revision, and clock revision.

The planner admits the intended processing tick and both neighbors only when every retained state and stock branch appends an unseen non-Iron type, or Iron at twenty-five existing types. The client sends Close Window at the first marker in that interval. It requires a fresh marker, no queued observations or worker work, and an unchanged forecast. A missed window is discarded while the merchant stays open.

Two Tab-Complete requests bracket Close Window on the same connection. The strings `rngtrader_tick_receipt_before` and `rngtrader_tick_receipt_after` select Vanilla's player-name completion branch and yield empty responses. This branch preserves both modeled RNG states. The preceding Age marker of each response bounds the close-processing tick. A receipt outside the admitted interval ends automation and requests reopening.

Once forty ticks have elapsed from the receipt's latest possible close tick, the client requests the merchant again. `MC|TrList` records the reopen-processing tick before any deferred client-container handling. Post-refresh filtering correlates each possible close tick with its resulting closed duration and checks the exact appended type and price. The model accepts measured closed durations of 40..120 ticks.

Shared status samples use a separate five-second wall-clock schedule. The refresh planner uses a local 20 TPS profile with margins around status updates. The tick clock identifies completed ticks, and the forecast admits an interval for future packet processing. Unexpected status overlap or an out-of-interval receipt stops the session.

Pause retains observations and finishes an in-flight inventory operation or refresh. Stop cancels inference and future actions, settles an outstanding transaction, and reopens an already-released merchant. Release closes the retained merchant. Clock loss, unexpected sounds/offers, movement, or model loss end automatic scheduling.

## Computation and logging

One worker owns inference and consumes LattiCG's enumeration stream sequentially. Enumeration checkpoints use cooperative five-millisecond computation slices with up to five milliseconds of rest. Lattice setup precedes enumeration; JVM GC and OS scheduling affect the time between checkpoints. Pending observations take precedence over a new forecast.

Schema 2 session records include packet sequence and receipt times, model revisions, processing-tick intervals, worker queue/wall/CPU durations, and JVM GC totals. Producers serialize a snapshot into a bounded queue. A dedicated writer flushes in batches; overflow emits a `log_dropped` record.

## Build and distribution

RetroFuturaGradle supplies Forge 1.7.10 and the embedded Forge mappings, with MCP stable 12 parameter mappings. Source code targets Java 8. LattiCG 1.07 is relocated under `rngtrader.internal.latticg`; its MIT license is included in the distribution. The thin JAR contains the mod classes and resources; the reobfuscated shaded JAR bundles LattiCG for installation.

Public observation fixtures contain pitch values, shuffle draws, relative timestamps, refresh boundaries, and offer changes. Automated tests exercise the mathematical model and client-side container behavior. Multiplayer acceptance requires a Vanilla server, a prepared Blacksmith, and the stable 12-player roster.
