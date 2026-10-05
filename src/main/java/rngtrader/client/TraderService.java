package rngtrader.client;

import io.netty.buffer.Unpooled;
import rngtrader.RNGTrader;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMerchant;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.network.play.client.C14PacketTabComplete;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import net.minecraft.inventory.ContainerMerchant;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.util.ChatComponentText;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import rngtrader.core.*;

/** Main-thread session controller. Worker and network callbacks only enqueue immutable observations. */
final class TraderService implements TradeExecutor.Sink {
    enum Phase { IDLE, CHECKING, PREPARING, CALIBRATING, RECOVERING, WAITING, CLOSING, REOPENING, PAUSED, STOPPED, ERROR, COMPLETE }
    static final class Sound {
        final String name; final int pitch; final double x, y, z; final long time;
        long tick = ServerTickClock.UNKNOWN;
        Sound(String name, int pitch, double x, double y, double z, long time) {
            this.name = name; this.pitch = pitch; this.x = x; this.y = y; this.z = z; this.time = time;
        }
    }
    final Minecraft mc = Minecraft.getMinecraft();
    final Roster roster = new Roster();
    final ServerTickClock clock = new ServerTickClock();
    private CloseReceipt receipt;
    private long lastSoundTick = ServerTickClock.UNKNOWN, lastProbeTick = ServerTickClock.UNKNOWN;
    private long packetSequence, packetReceived, lastMetrics;
    private final Queue<Runnable> mailbox = new ConcurrentLinkedQueue<Runnable>();
    private final Queue<Runnable> networkMailbox = new ConcurrentLinkedQueue<Runnable>();
    private final List<String> messages = new ArrayList<String>();
    volatile Phase phase = Phase.IDLE;
    private Phase resumePhase;
    private volatile EntityVillager target;
    private List<OfferData> offers = Collections.emptyList(), beforeRefresh;
    private InferenceWorker inference;
    private StatusPoller poller;
    private TradeExecutor trade;
    private SessionLog log;
    private SampleClock.Sample lastSample;
    private int[] disabledCounts = {0};
    private boolean calibrated, armed, startAfterOpen, paused, internalClose, planning, resumePending;
    private boolean stopAfterRefresh;
    volatile boolean forcedClose;
    private long epoch, generation, observationVersion, pickupRevision;
    private long lastSound, lastProbe, openedAt, preparedAt, ageAtStart, serverAge, serverAgeAt;
    private long lastEnvironmentCheck, lastReport, closeAt, lastTick;
    private int refreshes, freeAfterRefresh;
    private double targetX, targetY, targetZ, playerX, playerY, playerZ;
    private String reason = "No session";
    private Plan scheduled;
    private Preparation.Report environment;
    private static final int JITTER = 1;

    private static final class Plan {
        final int delay;
        final long version, rosterRevision, anchor, modelRevision, clockRevision;
        final TickInterval close;
        Plan(int delay, long anchor, long version, long roster, long model, long clock) {
            this.delay = delay; this.anchor = anchor; this.version = version; this.rosterRevision = roster;
            this.modelRevision = model; this.clockRevision = clock;
            this.close = new TickInterval(anchor + delay - JITTER, anchor + delay + JITTER);
        }
    }

    void enqueue(Runnable work) { mailbox.add(work); }
    void enqueueNetwork(long connection, Runnable work) { networkMailbox.add(() -> { if (epoch == connection) work.run(); }); }
    long connectionEpoch() { return epoch; }

    void enqueueConnection(Runnable work) { networkMailbox.add(work); }
    void connected(long connection, String endpoint) {
        disconnect(); epoch = connection; roster.clear(); reason = "Connected; recording player-list order";
        try { log = new SessionLog(mc.mcDataDir); log.record("connect", "endpoint", endpoint); }
        catch (Exception e) { RNGTrader.LOG.warn("Session logging unavailable", e); }
    }
    void disconnect() {
        generation++; epoch = -1; disposeWorkers(); trade = null; scheduled = null; target = null;
        offers = Collections.emptyList(); calibrated = armed = paused = planning = resumePending = false;
        phase = Phase.IDLE; forcedClose = false; serverAge = serverAgeAt = lastSound = lastTick = 0;
        mailbox.clear(); roster.clear(); clock.clear(); receipt = null;
        lastSoundTick = lastProbeTick = ServerTickClock.UNKNOWN;
        if (log != null) try { log.record("disconnect"); log.close(); } catch (Exception ignored) { }
        log = null;
    }
    private void disposeWorkers() {
        if (inference != null) inference.close(); inference = null;
        if (poller != null) poller.close(); poller = null;
    }

    void command(String verb) {
        if (mc.thePlayer == null) { say("Join a Vanilla 1.7.10 server first."); return; }
        switch (verb) {
            case "help":
                say("/rngtrader check | roster | start | status | pause | resume | stop | release");
                say("While the merchant is held, press / or T to enter a client command. Escape pauses automation."); break;
            case "roster":
                say("Roster: " + roster.names().size() + " players; source: " + roster.source());
                List<String> names = roster.names(); for (int i = 0; i < names.size(); i++) say((i + 1) + ". " + names.get(i)); break;
            case "status": status(); break;
            case "check": if (holding()) report(); else inspect(false); break;
            case "start":
                if (holding() || inference != null) { say("An existing session must be stopped and released before a new start."); break; }
                inspect(true); break;
            case "pause": pause(); break;
            case "resume": resume(); break;
            case "stop":
                if (inference == null && !armed && phase != Phase.ERROR && phase != Phase.STOPPED) { say("No running session."); break; }
                if (closingInterval()) {
                    stopAfterRefresh = true; paused = true;
                    say("Stop requested; completing the in-flight reopen before stopping.");
                } else stopNow();
                break;
            case "release":
                if (trade != null && trade.hasPendingClick()) { say("Wait for the pending inventory confirmation before release."); break; }
                generation++; disposeWorkers(); trade = null; scheduled = null; phase = Phase.IDLE;
                if (armed) say("Closing the merchant releases the armed refresh countdown.");
                armed = calibrated = paused = false; forcedClose = false;
                internalClose = true; mc.thePlayer.closeScreen(); internalClose = false;
                reason = "Released"; log("release"); break;
            default: say("Unknown command. Use /rngtrader help.");
        }
    }

    private void inspect(boolean start) {
        Entity pointed = mc.objectMouseOver == null ? null : mc.objectMouseOver.entityHit;
        if (pointed instanceof EntityVillager) target = (EntityVillager)pointed;
        if (target == null || target.worldObj != mc.theWorld || target.isDead || mc.thePlayer.getDistanceSqToEntity(target) > 9) {
            say("Look at a loaded Blacksmith within 3 blocks, then run /rngtrader check."); return;
        }
        if (mc.isSingleplayer()) { say("A dedicated Vanilla 1.7.10 server and 12-player roster are required."); return; }
        startAfterOpen = start; phase = Phase.CHECKING; offers = Collections.emptyList();
        preparedAt = System.nanoTime(); reason = "Waiting for merchant offers";
        if (mc.thePlayer.openContainer instanceof ContainerMerchant) {
            internalClose = true; mc.thePlayer.closeScreen(); internalClose = false;
        }
        // Defer interaction past GuiChat's own close operation.
        enqueue(() -> { if (phase == Phase.CHECKING) mc.playerController.interactWithEntitySendPacket(mc.thePlayer, target); });
    }

    void onOffers(byte[] payload, long time) { onOffers(payload, time, clock.tick()); }
    private void onOffers(byte[] payload, long time, long openedTick) {
        if (target == null || (phase != Phase.CHECKING && phase != Phase.REOPENING && phase != Phase.ERROR)) return;
        PacketBuffer buffer = new PacketBuffer(Unpooled.wrappedBuffer(payload));
        try {
            int window = buffer.readInt();
            if (!(mc.thePlayer.openContainer instanceof ContainerMerchant) || mc.thePlayer.openContainer.windowId != window) {
                enqueue(() -> onOffers(payload, time, openedTick)); return;
            }
            MerchantRecipeList recipes = MerchantRecipeList.func_151390_b(buffer);
            List<OfferData> parsed = new ArrayList<OfferData>();
            for (Object object : recipes) {
                MerchantRecipe recipe = (MerchantRecipe)object;
                ItemStack buy = recipe.getItemToBuy(), sell = recipe.getItemToSell();
                int payment = Item.getIdFromItem(buy.getItem()), result = Item.getIdFromItem(sell.getItem());
                BlacksmithRng.Kind match = null;
                for (BlacksmithRng.Kind kind : BlacksmithRng.Kind.values()) {
                    if (payment == (kind.buy ? kind.item : 388) && result == (kind.buy ? 388 : kind.item)) match = kind;
                }
                if (recipe.getSecondItemToBuy() != null || sell.stackSize != 1 || match == null
                    || sell.getItemDamage() != 0 || buy.getItemDamage() != 0 || sell.hasTagCompound() || buy.hasTagCompound())
                    throw new IllegalArgumentException("Unsupported non-Vanilla Blacksmith recipe");
                parsed.add(new OfferData(match, buy.stackSize, recipe.isRecipeDisabled()));
            }
            OfferData.mask(parsed);
            if (parsed.isEmpty()) throw new IllegalArgumentException("Empty offer list");
            offers = Collections.unmodifiableList(parsed); openedAt = time;
            log("offers", "window", window, "offers", offers, "received", time, "openedTick", openedTick);
            if (phase == Phase.ERROR) return;
            if (phase == Phase.REOPENING) {
                OfferData.verifyAppend(beforeRefresh, offers);
                OfferData appended = offers.get(offers.size() - 1);
                if (receipt == null || !receipt.complete()) throw new IllegalStateException("Reopen lacks a close processing receipt");
                final TickInterval closed = receipt.interval();
                if (!scheduled.close.contains(closed)) throw new IllegalStateException("Close was processed outside its admitted interval");
                final long current = generation;
                resumePending = true; lastSound = time; lastProbe = time; freeAfterRefresh = 0;
                lastSoundTick = lastProbeTick = openedTick;
                inference.resume(beforeRefresh.size(), disabledCounts, scheduled.anchor, closed, openedTick, appended, () -> enqueue(() -> {
                    if (generation != current) return;
                    resumePending = false; armed = false; refreshes++;
                    log("refresh", "number", refreshes, "before", beforeRefresh.size(), "after", offers.size(), "appended", appended,
                        "closeTime", closeAt, "openedTime", time, "closeTicks", closed, "openedTick", openedTick);
                    say("Refresh " + refreshes + ": " + offers.size() + "/26, appended " + appended);
                    scheduled = null;
                    if (stopAfterRefresh) { stopAfterRefresh = false; stopNow(); }
                    else if (OfferData.complete(offers)) finish();
                    else { phase = paused ? Phase.PAUSED : Phase.WAITING; reason = "Filtering post-refresh observations"; }
                }));
            } else {
                phase = Phase.IDLE; report();
                if (OfferData.complete(offers)) { phase = Phase.COMPLETE; say("All 26 types already exist with Iron last."); return; }
                if (startAfterOpen) prepare();
            }
        } catch (Exception e) { fail(e.getMessage()); }
        finally { buffer.release(); }
    }

    private void prepare() {
        Preparation.Report check = report();
        if (!check.ready) { phase = Phase.IDLE; reason = "Resolve failed checks before start"; return; }
        generation++; calibrated = armed = paused = planning = resumePending = false;
        forcedClose = false; stopAfterRefresh = false; refreshes = 0; trade = null; scheduled = null; lastSample = null;
        targetX = target.posX; targetY = target.posY; targetZ = target.posZ;
        playerX = mc.thePlayer.posX; playerY = mc.thePlayer.posY; playerZ = mc.thePlayer.posZ;
        preparedAt = System.nanoTime(); ageAtStart = serverAge; lastSound = lastProbe = 0;
        final long current = generation;
        final SessionLog sessionLog = log;
        inference = new InferenceWorker(error -> enqueue(() -> { if (generation == current) fail(error); }),
            (event, values) -> { if (sessionLog != null) sessionLog.record(event, values); });
        ServerAddress address = ServerAddress.func_78860_a(mc.func_147104_D().serverIP);
        poller = new StatusPoller(address.getIP(), address.getPort(), roster, new StatusPoller.Sink() {
            public void sample(SampleClock.Sample s) { enqueue(() -> { if (generation == current) onSample(s); }); }
            public void invalid(String why) { enqueue(() -> { if (generation == current) invalidateShared(why); }); }
        });
        phase = Phase.PREPARING; reason = "Waiting 200 observed server ticks before calibration";
        say(reason); log("start", "offers", offers, "roster", roster.names(), "budget", check.budget);
    }

    void playerInfo(String name, boolean online, long time) {
        if (roster.observe(name, online, time)) {
            log("roster", "name", name, "online", online, "order", roster.names(), "source", roster.source(), "received", time);
            if (inference != null) invalidateShared("Player-list insertion order changed");
        }
    }
    void worldTime(long age, long received) {
        serverAge = age; serverAgeAt = received; clock.time(age);
        selectClock();
        log("world_time", "age", age, "received", received, "clocks", clock.animals());
    }
    void observation(long sequence, long received) { packetSequence = sequence; packetReceived = received; }
    void clockSpawn(int id, int age) { clock.spawn(id, age); log("clock_spawn", "entity", id, "age", age); }
    void clockMetadata(int id, int age, long received) {
        clock.metadata(id, age, received);
        if (clock.active() != null && clock.active().entity == id)
            log("clock_marker", "entity", id, "age", age, "count", clock.active().count, "received", received);
    }
    void clockRemove(int id) { clock.remove(id); }
    void dimensionChanged() { if (inference != null) fail("Player changed dimension"); clock.clear(); }
    void completionReply(int count) {
        if (receipt == null || receipt.complete() || !closingInterval()) return;
        if (count != 0) { fail("Unexpected command completion during close receipt"); return; }
        try { receipt.reply(clock.tick()); }
        catch (IllegalArgumentException | IllegalStateException invalid) {
            fail("Close receipt lost its synchronized tick anchor: " + invalid.getMessage()); return;
        }
        log("close_receipt", "complete", receipt.complete(), "bounds", receipt.complete() ? receipt.interval() : null);
        if (receipt.complete() && !scheduled.close.contains(receipt.interval()))
            fail("Close processing tick " + receipt.interval() + " is outside admitted " + scheduled.close);
    }
    private void updateClocks() {
        // Forge may establish its Vanilla connection after initial Spawn Mob packets were handled.
        if (mc.theWorld != null) for (Object value : mc.theWorld.loadedEntityList) if (value instanceof EntityCow) {
            EntityCow cow = (EntityCow)value; if (!cow.isDead) clock.loaded(cow.getEntityId(), cow.getGrowingAge());
        }
        for (ServerTickClock.Animal a : clock.animals()) {
            Entity entity = mc.theWorld == null ? null : mc.theWorld.getEntityByID(a.entity);
            clock.eligible(a.entity, entity instanceof EntityCow && !entity.isDead && target != null
                && (Math.abs(entity.posX-target.posX) > 12 || Math.abs(entity.posZ-target.posZ) > 12));
        }
        if (clock.active() == null) selectClock();
    }
    private void selectClock() {
        // World Time precedes every entity tracker in its tick, providing a common handover boundary.
        if (clock.select()) {
            scheduled = closingInterval() ? scheduled : null;
            log("clock_selected", "clock", clock.active(), "revision", clock.revision());
        }
    }
    void pickup(int collector) { if (mc.thePlayer != null && mc.thePlayer.getEntityId() == collector) pickupRevision++; }
    void confirm(int window, short id, boolean accepted, long received) {
        if (trade != null) trade.confirm(window, id, accepted, received);
    }
    void serverClosed() { forcedClose = true; fail("Server closed the merchant; the countdown is no longer held"); }

    void sound(Sound sound) {
        if (target == null || Math.abs(sound.x - target.posX) > .5 || Math.abs(sound.z - target.posZ) > .5
            || Math.abs(sound.y - target.posY) > 2.5) return;
        if (!sound.name.startsWith("mob.villager.")) return;
        sound.tick = clock.tick();
        if (trade != null) {
            trade.sound(sound);
            if (trade.collectingSounds() && sound.name.equals("mob.villager.yes")) armed = true;
        }
        long previousTick = lastSoundTick; lastSound = sound.time; lastSoundTick = sound.tick;
        log("sound", "name", sound.name, "pitch", sound.pitch, "received", sound.time, "soundTick", sound.tick);
        if (calibrated && inference != null) {
            if (!sound.name.equals("mob.villager.yes") && !sound.name.equals("mob.villager.no")) {
                fail("Unscheduled villager sound: " + sound.name); return;
            }
            if (phase == Phase.CLOSING || (phase == Phase.REOPENING && !resumePending)) {
                fail("Unexpected villager sound during refresh interval"); return;
            }
            if (sound.tick == ServerTickClock.UNKNOWN || previousTick == ServerTickClock.UNKNOWN) {
                fail("Sound observation lost its server tick anchor"); return;
            }
            int ticks = Math.toIntExact(sound.tick - previousTick);
            if (ticks < 0 || ticks > 120) { fail("Sound processing tick interval is invalid: " + ticks); return; }
            int low = ticks, high = ticks;
            observationVersion++;
            inference.sound(sound.pitch, low, high, sound.name.equals("mob.villager.no"));
            if (sound.name.equals("mob.villager.no")) freeAfterRefresh++;
        }
    }

    private void onSample(SampleClock.Sample sample) {
        if (inference == null) return;
        log("status_sample", "draws", sample.draws, "earliest", sample.earliest, "latest", sample.latest, "contiguous", sample.contiguous);
        if (phase == Phase.CLOSING || (phase == Phase.REOPENING && !resumePending)) {
            fail("Status update overlapped the planned refresh interval"); return;
        }
        observationVersion++; lastSample = sample; inference.sample(sample);
    }
    private void invalidateShared(String why) {
        if (inference == null) return;
        observationVersion++; lastSample = null;
        if (phase == Phase.CLOSING || phase == Phase.REOPENING) { fail(why + " during refresh"); return; }
        scheduled = null; inference.invalidSample();
        if (!reason.equals(why)) log("shared_resync", "reason", why);
        reason = why;
    }

    void tick() {
        Runnable work;
        while ((work = networkMailbox.poll()) != null) work.run();
        // Only run callbacks present at entry; commands may enqueue a deferred interaction.
        int count = mailbox.size(); for (int i = 0; i < count && (work = mailbox.poll()) != null; i++) work.run();
        long now = System.nanoTime();
        updateClocks();
        if (target == null || mc.thePlayer == null) return;
        if (phase == Phase.CHECKING && now - preparedAt > 5_000_000_000L) { fail("Merchant did not open within five seconds"); return; }
        if (inference == null) {
            if (trade != null) trade.settleStopped(now);
            return;
        }
        if (target.isDead || target.worldObj != mc.theWorld) { fail("Target was removed or dimension changed"); return; }
        if (now - lastEnvironmentCheck > 1_000_000_000L) {
            environment = Preparation.check(mc, target, offers, roster, calibrated, armed, true);
            lastEnvironmentCheck = now;
        }
        if (clock.tick() == ServerTickClock.UNKNOWN) { fail("Synchronized tick clock unavailable; prepare a replacement cow"); return; }
        if (phase == Phase.CLOSING && receipt != null && receipt.complete() && clock.tick() >= receipt.interval().last + 40) {
            phase = Phase.REOPENING;
            log("reopen_request", "closeTicks", receipt.interval());
            mc.playerController.interactWithEntitySendPacket(mc.thePlayer, target);
        }
        if (closingInterval() && !resumePending && now - closeAt > 10_000_000_000L) { fail("Merchant refresh response timeout"); return; }
        if (now - lastMetrics >= 1_000_000_000L) {
            long gcTime = 0, gcCount = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcTime += Math.max(0, bean.getCollectionTime()); gcCount += Math.max(0, bean.getCollectionCount());
            }
            log("performance", "workerPending", inference.pending(), "workerBusy", inference.busy,
                "gcMillis", gcTime, "gcCount", gcCount, "clientGapNanos", lastTick == 0 ? 0 : now-lastTick);
            lastMetrics = now;
        }
        if (phase == Phase.CLOSING || (phase == Phase.REOPENING && !resumePending)) { lastTick = now; return; }
        if (!(mc.thePlayer.openContainer instanceof ContainerMerchant)) { fail("Merchant container is no longer held"); return; }
        if (distance(target.posX, target.posY, target.posZ, targetX, targetY, targetZ) > .01
            || distance(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, playerX, playerY, playerZ) > .01) {
            fail("Customer or target moved from the calibrated position"); return;
        }
        boolean gap = lastTick != 0 && now - lastTick > 150_000_000L;
        lastTick = now;
        if (gap && scheduled != null) scheduled = null;
        if (trade != null && !trade.finished()) {
            // Pause takes effect between complete inventory operations, never halfway through a payment stack.
            trade.tick(now);
            if (!trade.finished()) { probes(now); return; }
        }
        probes(now);
        if (mc.currentScreen instanceof TraderScreen && ((TraderScreen)mc.currentScreen).typingCommand()) {
            scheduled = null; return;
        }
        if (paused || phase == Phase.PAUSED || phase == Phase.ERROR || phase == Phase.STOPPED) return;
        if (phase == Phase.PREPARING) {
            if (serverAge - ageAtStart >= 200 && clock.fresh(now) && environment != null && environment.ready) beginTrade(true);
            return;
        }
        if (!calibrated) return;
        if (phase == Phase.RECOVERING && inference.timersReady && inference.sharedReady && inference.probes >= 45) {
            phase = Phase.WAITING; reason = "Waiting for a window that appends a new trade";
        }
        if (phase == Phase.RECOVERING && now - lastReport > 30_000_000_000L) { lastReport = now; status(); }
        if (phase != Phase.WAITING) return;
        if (environment == null || !environment.ready) { reason = "Waiting for preparation/inventory checks"; scheduled = null; return; }
        if (!armed) {
            if (freeAfterRefresh >= 3) beginTrade(false);
            return;
        }
        if (scheduled != null) {
            Plan plan = scheduled;
            long currentTick = clock.tick();
            if (plan.version != observationVersion || plan.rosterRevision != roster.revision()
                || plan.clockRevision != clock.revision() || currentTick > plan.close.first) {
                log("plan_discarded", "reason", "observation, clock, roster, or deadline changed"); scheduled = null; return;
            }
            if (currentTick == plan.close.first) {
                long commitTime = System.nanoTime();
                if (!clock.fresh(commitTime) || commitTime - clock.received() > 40_000_000L
                    || inference.pending() != 0 || inference.busy || inference.revision != plan.modelRevision
                    || !networkMailbox.isEmpty() || !mailbox.isEmpty()) {
                    log("plan_discarded", "reason", "commit observation or worker backlog"); scheduled = null;
                } else closeForRefresh(commitTime);
            }
            return;
        }
        if (!planning && inference.sharedReady && inference.pending() == 0 && lastSample != null
            && clock.fresh(now) && clock.tick() - lastSoundTick < 4 && !gap) plan(now);
        if (now - lastReport > 30_000_000_000L) { lastReport = now; status(); }
    }

    private void probes(long now) {
        if (!calibrated || inference == null || forcedClose || phase == Phase.CLOSING
            || (phase == Phase.REOPENING && !resumePending)) return;
        if (trade != null && !trade.finished() && (!trade.paymentEmpty() || trade.collectingSounds())) return;
        if (mc.thePlayer.openContainer.getSlot(0).getHasStack() || mc.thePlayer.openContainer.getSlot(1).getHasStack()) return;
        if (!clock.fresh(now) || lastSoundTick == ServerTickClock.UNKNOWN) return;
        if (clock.tick() - lastSoundTick >= 21 && (lastProbeTick == ServerTickClock.UNKNOWN || clock.tick() - lastProbeTick >= 2)) {
            select(0); lastProbe = now; lastProbeTick = clock.tick();
        }
        if (clock.tick() - lastSoundTick >= 39) fail("GUI probe did not arrive within 21..39 server ticks");
    }

    private void beginTrade(boolean calibration) {
        if (paused || inference == null) return;
        phase = calibration ? Phase.CALIBRATING : Phase.WAITING;
        reason = calibration ? "Collecting seven calibration trades" : "Trading the newly appended last offer once";
        trade = new TradeExecutor(mc, this, offers.get(offers.size() - 1), offers.size() - 1, calibration);
        log("trade_begin", "calibration", calibration, "offer", offers.get(offers.size() - 1));
    }

    @Override public void complete(List<Sound> batch, boolean calibration) {
        armed = true;
        int knownDisabled = 0; for (OfferData offer : offers) if (offer.disabled) knownDisabled++;
        disabledCounts = calibration ? new int[] {knownDisabled, knownDisabled + 1} : new int[] {knownDisabled};
        if (calibration) {
            calibrated = true; lastSound = batch.get(batch.size() - 1).time;
            lastSoundTick = batch.get(batch.size() - 1).tick;
            for (Sound sound : batch) if (sound.tick != lastSoundTick || sound.tick == ServerTickClock.UNKNOWN) {
                fail("Calibration trades did not share a synchronized processing tick"); return;
            }
            int[] pitches = new int[batch.size()]; for (int i = 0; i < pitches.length; i++) pitches[i] = batch.get(i).pitch;
            inference.initialize(pitches); phase = paused ? Phase.PAUSED : Phase.RECOVERING;
            reason = "Recovering entity and shared RNG; merchant held";
            say(reason);
        }
        environment = null; lastEnvironmentCheck = 0;
    }

    private void plan(long now) {
        final long anchor = lastSoundTick, version = observationVersion, rosterRevision = roster.revision(), current = generation;
        final long clockRevision = clock.revision();
        // Status refresh uses wall time. This guard stays separate from entity tick inference.
        if (now < lastSample.latest + 250_000_000L) return;
        int minimum = Math.max(3, Math.toIntExact(clock.tick() - anchor) + 2);
        int maximum = Math.min(17, (int)Math.floor((lastSample.earliest + 4_500_000_000L - lastSound) / 50_000_000.0) - 42);
        if (minimum > maximum) return;
        planning = true;
        inference.plan(OfferData.mask(offers), disabledCounts, minimum, maximum, JITTER, result -> enqueue(() -> {
            if (generation != current) return;
            planning = false;
            if (result.delay < 0 || observationVersion != version || paused || phase != Phase.WAITING
                || clock.revision() != clockRevision) return;
            Plan candidate = new Plan(result.delay, anchor, version, rosterRevision, result.revision, clockRevision);
            if (clock.tick() >= candidate.close.first) return;
            scheduled = candidate;
            reason = "Admitted close processing ticks " + candidate.close;
            log("plan", "anchor", anchor, "closeTicks", candidate.close, "modelRevision", result.revision);
        }));
    }
    private void closeForRefresh(long now) {
        if (mc.thePlayer.inventory.getItemStack() != null || mc.thePlayer.openContainer.getSlot(0).getHasStack()
            || mc.thePlayer.openContainer.getSlot(1).getHasStack()) { fail("Payment/cursor must be empty before releasing refresh"); return; }
        beforeRefresh = offers; phase = Phase.CLOSING; closeAt = now; receipt = new CloseReceipt();
        mc.thePlayer.sendQueue.addToSendQueue(new C14PacketTabComplete("rngtrader_tick_receipt_before"));
        internalClose = true; mc.thePlayer.closeScreen(); internalClose = false;
        mc.thePlayer.sendQueue.addToSendQueue(new C14PacketTabComplete("rngtrader_tick_receipt_after"));
        log("close_for_refresh", "delay", scheduled.delay, "disabledCounts", disabledCounts,
            "anchor", scheduled.anchor, "admittedTicks", scheduled.close, "closeTime", closeAt, "sampleEarliest", lastSample.earliest, "sampleLatest", lastSample.latest);
    }
    private void pause() {
        if (inference == null) { say("No running session."); return; }
        paused = true; scheduled = phase == Phase.CLOSING || phase == Phase.REOPENING ? scheduled : null;
        if (phase != Phase.CLOSING && phase != Phase.REOPENING) { resumePhase = phase; phase = Phase.PAUSED; }
        say("Paused; merchant observations continue. A pending click or refresh will finish first."); log("pause");
    }
    private void resume() {
        if (inference == null || phase == Phase.ERROR || phase == Phase.STOPPED) { say("This session cannot resume; stop/release and recalibrate."); return; }
        if (!paused) { say("Session is not paused."); return; }
        Preparation.Report check = report(); if (!check.ready) return;
        paused = false;
        if (phase == Phase.PAUSED) phase = calibrated ? (inference.sharedReady ? Phase.WAITING : Phase.RECOVERING)
            : resumePhase == Phase.CALIBRATING ? Phase.CALIBRATING : Phase.PREPARING;
        say("Resumed."); log("resume");
    }
    private void finish() {
        phase = Phase.COMPLETE; armed = false; trade = null; scheduled = null; disposeWorkers();
        reason = "Complete: 26 trade types, Iron Ingot -> Emerald last"; say(reason); log("complete", "offers", offers, "refreshes", refreshes);
    }
    private void stopNow() {
        generation++; disposeWorkers(); scheduled = null; planning = false;
        if (trade != null) trade.stop();
        phase = Phase.STOPPED; paused = true;
        reason = "Stopped; merchant retained. Use release to close it.";
        say(reason); log("stop", "armed", armed);
    }
    @Override public void fail(String message) {
        reason = message == null ? "Unknown session error" : message;
        if (phase == Phase.ERROR) return;
        boolean released = closingInterval();
        phase = Phase.ERROR; paused = true; scheduled = null; planning = false;
        generation++; disposeWorkers();
        if (trade != null) trade.stop();
        if (released && !forcedClose && target != null && mc.thePlayer != null && !target.isDead) {
            mc.playerController.interactWithEntitySendPacket(mc.thePlayer, target);
        }
        say("Stopped: " + reason + (forcedClose ? ". Merchant is not held." : released ? ". Reopening merchant; verify the current offers." : ". Merchant retained; use stop/release for manual control."));
        log("error", "reason", reason, "armed", armed);
    }
    Preparation.Report report() {
        Preparation.Report result = Preparation.check(mc, target, offers, roster, calibrated, armed, true);
        if (clock.tick() == ServerTickClock.UNKNOWN) result.fail("Synchronized baby cow clock required outside the target AI area");
        else result.lines.add("PASS Tick clock entity " + clock.active().entity + ", tick " + clock.tick() + ", Age " + clock.active().age);
        for (String line : result.lines) say(line);
        log("preparation", "ready", result.ready, "checks", result.lines);
        return result;
    }
    private void status() {
        say(phase + ": " + offers.size() + "/26 types, " + refreshes + " refreshes. " + reason);
        say("Clock tick=" + clock.tick() + ", entity=" + (clock.active() == null ? "none" : clock.active().entity)
            + ", Age=" + (clock.active() == null ? "unknown" : clock.active().age));
        if (inference != null) say("RNG states=" + inference.states + ", timers=" + inference.timersReady
            + ", shared=" + inference.sharedReady + ", probes=" + inference.probes + ", workerBusy=" + inference.busy
            + ", queued=" + inference.pending());
    }
    @Override public void select(int index) {
        if (!(mc.thePlayer.openContainer instanceof ContainerMerchant)) return;
        ((ContainerMerchant)mc.thePlayer.openContainer).setCurrentRecipeIndex(index);
        byte[] bytes = new byte[] {(byte)(index >>> 24), (byte)(index >>> 16), (byte)(index >>> 8), (byte)index};
        mc.thePlayer.sendQueue.addToSendQueue(new C17PacketCustomPayload("MC|TrSel", bytes));
    }
    @Override public long lastSoundTime() { return lastSound; }
    @Override public long pickupRevision() { return pickupRevision; }
    @Override public void log(String event, Object... values) {
        if (log == null) return;
        Object[] data = Arrays.copyOf(values, values.length + 8);
        data[values.length] = "packetSequence"; data[values.length + 1] = packetSequence;
        data[values.length + 2] = "packetReceived"; data[values.length + 3] = packetReceived;
        data[values.length + 4] = "tick"; data[values.length + 5] = clock.tick();
        data[values.length + 6] = "observationVersion"; data[values.length + 7] = observationVersion;
        log.record(event, data);
    }
    void say(String text) {
        messages.add(text); if (messages.size() > 200) messages.remove(0);
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[RNGTrader] " + text));
    }
    List<String> messages() { return messages; }
    boolean holding() { return !forcedClose && (inference != null || armed || phase == Phase.ERROR || phase == Phase.STOPPED); }
    boolean replacingMerchant() { return target != null && (phase == Phase.CHECKING || phase == Phase.REOPENING || holding()); }
    boolean internalClose() { return internalClose; }
    boolean closingInterval() { return phase == Phase.CLOSING || phase == Phase.REOPENING; }
    String screenStatus() { return phase + " | " + offers.size() + "/26 | /rngtrader status"; }
    private static double distance(double x, double y, double z, double a, double b, double c) {
        return (x-a)*(x-a) + (y-b)*(y-b) + (z-c)*(z-c);
    }
}
