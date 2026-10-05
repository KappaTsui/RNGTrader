# Vanilla Multiplayer Acceptance

This harness runs the reobfuscated Forge client against the official Vanilla 1.7.10 server, with eleven stationary protocol clients. The integration driver selects the prepared Blacksmith and invokes `/rngtrader start`. RNGTrader performs the inventory operations and refresh scheduling.

## Requirements

- Linux, Python 3.12 or later, Xvfb, and working Mesa/LWJGL 2 libraries.
- JDK 21 for Gradle and Java 8 for Minecraft.
- The official Vanilla 1.7.10 server JAR, SHA-256 `c70870f00c4024d829e154f7e5f4e885b02dd87991726a3308d81f513972f3fc`.
- An unused loopback TCP port and X display.

## Build and run

From the project root:

```sh
./gradlew -PintegrationHarness -PexportLaunch runObfClient
python3 tools/integration/run.py \
  --run-dir /tmp/rngtrader-acceptance \
  --server-jar /path/to/minecraft_server.1.7.10.jar \
  --launch build/integration-launch.json \
  --java /path/to/java8/bin/java \
  --accept-eula
```

Read the [Minecraft EULA](https://www.minecraft.net/eula) before supplying `--accept-eula`. The output directory must be new. `--port` and `--display` default to `25578` and `1941`. `--xvfb` selects an Xvfb executable. The client and server write all runtime files below the output directory; Gradle's exported classpath and native-library paths remain build dependencies.

The runner binds its server to `127.0.0.1`, whitelists the fixed twelve test profiles, and uses an empty operator list. `RngTradeTest` logs in through Forge; `RngIdle01` through `RngIdle11` follow in order. `RngIdle01` feeds the two adult cows every 310 seconds using Wheat already in its inventory. The remaining helpers stand in their prepared cells.

The saved fixture starts with a fresh `4 Emeralds → Iron Shovel` Blacksmith, gross-payment inventory, a dry enclosure, and a cow pen about twenty blocks away. All players use Survival mode. Daylight and natural mob spawning are held constant in the fixture. `spawn-animals=true` preserves the clock cows.

The runner exits after completion, a session error, or the duration limit, then stops its server and child processes. To end a run early, create an empty `stop` file inside its output directory. Results include `client.log`, `server.log`, `result.json`, and `client/rngtrader/sessions/*.jsonl`.

## Timing disturbances

Add `--clock-age 2400 --stress` to start with a clock approaching maturity and inject two client pauses. A 250 ms pause follows receipt of the first forecast log record. A 600 ms pause occurs during a closed merchant interval with sufficient time before the next status update. Pause triggers read buffered session logs; use the timestamps in `disturbances.jsonl` and the session's packet records to locate each pause relative to close transmission and reopening. `TimingLifecycleTest` exercises forecast expiry at the controller's commit gate.

The clock-age option sets the baby cow's Age in the saved fixture before startup. During the run, the clock hands over to cows bred by `RngIdle01`.

## Audit and production build

```sh
python3 tools/integration/audit.py /tmp/rngtrader-acceptance/client/rngtrader/sessions/SESSION.jsonl
./gradlew test build
```

The audit verifies ordered close receipts, containment in admitted processing ticks, one appended type per refresh, and Iron last. A complete run requires 25 refreshes and 26 distinct types. It reports discarded forecasts, clock replacements, initialization duration, and log loss.

`./gradlew test build` produces the installable JAR. The `integrationHarness` property adds the integration driver to the client build.
