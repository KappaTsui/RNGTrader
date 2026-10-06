# RNGTrader

RNGTrader is a Forge 1.7.10 client mod for unlocking every Vanilla Blacksmith trade with **Iron Ingot → Emerald last**. It observes public packets to recover the villager RNG and the shared `Collections.shuffle()` RNG, and automates merchant transactions.

RNGTrader targets Vanilla 1.7.10 servers with exactly 12 players online. Install the mod on the trading client; the other 11 players can use Vanilla clients.

## Installation

Install `build/libs/RNGTrader-1.1.1.jar` in the **client** `mods` directory alongside Forge 10.13.4.1614. Use Java 8 and a client heap of at least 2 GiB. The JAR bundles LattiCG.

## Preparation

1. Isolate an adult Blacksmith with synchronized `Age = 0`, solid ground, full health, no leash or mount, and no initial status effects. Use the Nether, or a dry daytime Overworld enclosure in a biome where rain can occur. Keep the enclosure away from villages. Keep zombies, other villagers, and other living entities outside the checked AI area.
2. Keep the customer within three blocks of the Blacksmith. Keep other players outside the checked AI area. The remaining players must stay connected and avoid death, respawn, other villager trading, and other shared-shuffle consumers.
3. Start with no Iron trade. The last offer must cost at most five items and have at least seven remaining uses. A fresh `4 Emeralds → Iron Shovel` is a convenient initial offer. Use counters are held on the server; calibration verifies the available stock through seven completed trades.
4. For that initial Shovel offer, supply **220 Emeralds, 23 Coal, 9 Gold Ingots, and 5 Diamonds**, plus seven empty inventory slots. This budget covers gross payments. Different initial offer lists receive their own calculated budget. The operation finishes when the final Iron trade is unlocked.
5. Face a collection pit or another disposal area outside pickup reach. Purchased equipment is automatically dropped after transaction verification. Items held before the session and received Emeralds are retained. Picked-up equipment is treated as ordinary inventory.
6. Keep a baby cow in a loaded enclosure about 20 blocks from the Blacksmith. Its Age metadata supplies a server tick clock. Allow at least two complete World Time intervals for synchronization. A cow normally matures after 24,000 ticks; arrange ordinary breeding by one of the other eleven players before the active clock expires. The mod synchronizes replacement cows and switches clocks automatically.
7. Keep the client responsive and use a low-latency connection to a server running at a stable 20 TPS. Keep the enclosure dry and the roster stable for the entire operation. The mod holds the merchant during seed recovery and reports its stage, progress, and candidate count.

The block scan covers an inclusive box of **±16 blocks horizontally and ±8 blocks vertically** around the target's block position. Water, Lava, and Fire anywhere in this box fail the preparation check. All chunks intersecting the scan must be loaded.

## Commands

Look at the target Blacksmith and run `/rngtrader check`. The mod opens its merchant container and reports `PASS`, `FAIL`, and `UNKNOWN` conditions. Resolve failed checks, then run `/rngtrader start`.

| Command | Result |
| --- | --- |
| `/rngtrader check` | Select the Blacksmith under the crosshair, read its offers, and print environment and inventory requirements. During an active session, inspect the selected target. |
| `/rngtrader roster` | Print the current server insertion order and its observation source. |
| `/rngtrader start` | Read fresh offers, check preparation, wait at least 200 observed server ticks, and begin the full Iron-last operation. |
| `/rngtrader status` | Print the current phase, offer count, model counts, seed-recovery stage/progress/CPU time, clock entity, tick, remaining Age, and waiting reason. |
| `/rngtrader pause` | Pause automatic trading while retaining the merchant and collecting observations. An inventory operation already in progress finishes first. |
| `/rngtrader resume` | Resume a paused, still-valid session after checking preparation. |
| `/rngtrader stop` | Stop the worker and future inventory operations while retaining the merchant container. A refresh already released is reopened before stopping. |
| `/rngtrader release` | Close the merchant container and return control to normal gameplay. An armed refresh countdown then runs normally. |
| `/rngtrader help` | Print command usage. |

Inside the merchant screen, press **/** or **T** for local command input. This input preserves the open container and postpones new trades and refresh closures while typing. Scroll the mouse wheel while the command input is open to review command output. Output remains visible for eight seconds after submitting a command. Escape first dismisses command input; outside command input, Escape pauses an active session. Manual inventory clicks are disabled while the session owns the merchant.

`UNKNOWN` identifies conditions unavailable in client world data, including server village state, remote shared-shuffle consumers, and offscreen respawn events. RNG observations continually check model consistency.

An exhausted calibration batch, unexpected sound, unrecognized offer, transaction failure, or lost entity model ends automatic trading. `stop` discards inference state; `resume` applies to a paused session. After `release` or disconnect, start a new calibration on an eligible last offer.

## Recording

RNGTrader records a JSONL file per connection in `<game directory>/rngtrader/sessions/`. Roster recording begins at login, before any trading command. The log contains ordered roster changes, preparation results, received sound pitches, status samples, inventory transactions, purchased-output drops, and refresh results. Schema 2 also records packet sequence numbers, server tick markers, close processing receipts, model revisions, worker queue/CPU timings, seed-recovery branch progress, and cumulative GC counters. Disk writes run on a separate thread.

## Build

The project uses RetroFuturaGradle 1.4.9, Gradle 8.14.3, Forge 10.13.4.1614, and Java 8 bytecode. Run Gradle with JDK 21. RFG also uses a Java 17 decompiler toolchain; the Foojay resolver provisions missing toolchains.

```sh
./gradlew build
```

Set Gradle's `org.gradle.java.installations.paths` property to the Java 8 installation path. The reobfuscated shaded JAR is the installable artifact; the `-thin.jar` is a development artifact.

See [Architecture](docs/architecture.md) for packet observations, RNG inference, stock handling, and the server tick clock.

See [Entity Seed Recovery](docs/seed-recovery.md) for probe constraints, lattice construction, and rejection coverage.
