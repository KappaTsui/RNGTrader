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

The model targets the Java 8 `Collections.shuffle()` implementation with a shared `java.util.Random` and descending Fisher–Yates. Java SE specifies the `java.util.Random` algorithm; the default shuffle generator and algorithm are implementation-specific. The observation sequence must match this implementation.

## Entity observations and stock

Seven consecutive successful trades provide quantized adult sound pitches. The model reverses the paired `nextFloat()` draws, then filters possible entity states using free merchant sounds. It retains possible AI phases, look timers, village timers, sound cooldowns, and Regeneration duration. Calibration continues for at least 45 free observations.

The client inserts exactly seven payments for calibration. Merchant recipes expose a synchronized disabled flag, while `uses` and `maxUses` remain server-side. After seven confirmed trades, the previous last offer may be disabled or may still have stock. Planning therefore considers both possible disabled-offer counts. The first refresh with one offer skips disabled-offer restoration in either case. At later sizes, each disabled offer contributes the modeled restock draws. Post-refresh observations retain the union of matching stock and timing branches.

Every newly appended offer is fresh. One use of that last offer arms the next countdown. The initial calibration therefore requires seven trades, and each subsequent refresh requires one additional trade. Starting with one offer needs 25 refreshes and 31 trades.

## Inventory budget and output ownership

Before calibration, the budget includes seven uses of the current last offer at its observed price and one use of every missing non-Iron type at its maximum Vanilla price. After calibration it includes only remaining payments. Once a refresh is armed, its last-offer payment is already covered. The budget uses gross payments, keeping the bound independent of unlock order and future Emerald receipts.

Payment stacks may be split across inventory slots. The executor combines the exact required count in the merchant input using pickup and placement clicks. It primes the merchant sound cooldown before shift-clicking the result to isolate the calibration batch's trade sounds.

A positive transaction acknowledgment validates the returned stack. The executor counts successful trade sounds to determine how many shift-click iterations completed. If stock runs out early, the executor rebuilds the local result using the observed count, retains the outputs, and stops.

Purchased gear is tracked only in previously empty destination slots. Before dropping it, the executor checks that the slot still contains the expected item, damage, count, and NBT. A player item-pickup packet invalidates ownership during the transaction. Currency outputs are retained.

## Scheduling and interruption

The open merchant container pauses the villager's 40-tick refresh countdown. Free sounds continue to advance and constrain entity inference while the container is held. Model computation runs on a dedicated worker; observations continue to be collected during seed recovery.

A proposed window must append an unseen non-Iron type before the final refresh, or Iron when 25 types already exist. Every retained entity state, shared state, stock branch, and close time in the default `±1 tick` interval must satisfy that condition. Planning excludes windows that overlap the next cached status update and keeps 250 ms boundary margins.

The client thread checks the observation version, roster revision, pending observation queues, preparation report, and deadline immediately before closure. A late plan is discarded. A small temporary screen suppresses movement input during the released interval, and the client interacts with the same villager approximately 2.10 seconds later. Post-refresh filtering accepts only the observed 40–44 tick closed interval and the exact appended type and price.

Pause retains observations and finishes an already-started inventory operation or refresh. Stop cancels future actions and settles an outstanding click acknowledgment; an already-released refresh is reopened before stopping. Release explicitly closes the container. A server-forced close, disconnect, lost entity model, or unexpected offer ends automatic scheduling. If an error occurs while the merchant is deliberately released, the client attempts to reopen it and reports that the resulting offer list must be inspected.

The timing profile requires a responsive client and a stable local 20 TPS server. Timing estimates are derived from packet receipt timestamps. Server pauses, client stalls, latency spikes, and unobserved external RNG consumers can invalidate timing estimates or RNG predictions.

## Build and distribution

RetroFuturaGradle supplies Forge 1.7.10 and the embedded Forge mappings, with MCP stable 12 parameter mappings. Source code targets Java 8. LattiCG 1.07 is relocated under `rngtrader.internal.latticg`; its MIT license is included in the distribution. The thin JAR contains the mod classes and resources; the reobfuscated shaded JAR bundles LattiCG for installation.

Public observation fixtures contain pitch values, shuffle draws, relative timestamps, refresh boundaries, and offer changes. Automated tests exercise the mathematical model and client-side container behavior. Multiplayer acceptance requires a Vanilla server, a prepared Blacksmith, and the stable 12-player roster.
