package io.xrayac.spigot.listener;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.domain.ExposureResult;
import io.xrayac.core.domain.Observation;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.VeinObservation;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.Vector3;
import io.xrayac.core.port.OreCatalog;
import io.xrayac.core.port.WorldView;
import io.xrayac.core.world.ExposureAnalyzer;
import io.xrayac.core.world.VeinAnalyzer;
import io.xrayac.spigot.adapter.ExcavationLedger;
import io.xrayac.spigot.adapter.MaterialClassifier;
import io.xrayac.spigot.analysis.AnalysisService;
import io.xrayac.spigot.config.PluginSettings;
import io.xrayac.spigot.persistence.PendingPersistence;
import io.xrayac.spigot.session.PlayerSession;
import io.xrayac.spigot.session.SessionRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Translates Minecraft events into platform-neutral observations, on the server thread.
 *
 * <h2>Position in the pipeline</h2>
 * This is the adapter layer's edge. Everything it produces — {@link Observation.Mining},
 * {@link TrajectoryAnalysis.PathPoint}, {@link OreDiscovery} — is a value type from the analytical
 * core, which is why the core can be tested without a server and why the statistical code never sees
 * a Bukkit class.
 *
 * <h2>What is done here, and what is deliberately deferred</h2>
 * Only cheap work happens in these handlers: reading a handful of neighbouring blocks, a bounded
 * queue offer, and one map insert. Expensive work — the statistical evaluation — is submitted to a
 * worker. This split is the reason the plugin can observe every block break on a busy server without
 * the tick time moving.
 *
 * <h2>Event priorities</h2>
 * {@link EventPriority#MONITOR} is used throughout, and {@code ignoreCancelled} is set for block
 * breaks. MONITOR means "observe, never change": this plugin must not alter gameplay or interfere with
 * protection plugins, and running last means another plugin's cancellation is already final. Ignoring
 * cancelled breaks is essential — a block a protection plugin refused to let the player break was not
 * mined, and counting it would corrupt both the mined-block denominator and the excavation ledger.
 */
public final class ObservationListener implements Listener {

    private final Supplier<PluginSettings> settings;
    private final SessionRegistry sessions;
    private final ExcavationLedger ledger;
    private final WorldView worldView;
    private final MaterialClassifier classifier;
    private final PendingPersistence pending;
    private final AnalysisService analysis;
    private final Consumer<PlayerRef> playerSeen;

    public ObservationListener(Supplier<PluginSettings> settings,
                               SessionRegistry sessions,
                               ExcavationLedger ledger,
                               WorldView worldView,
                               MaterialClassifier classifier,
                               PendingPersistence pending,
                               AnalysisService analysis,
                               Consumer<PlayerRef> playerSeen) {
        this.settings = settings;
        this.sessions = sessions;
        this.ledger = ledger;
        this.worldView = worldView;
        this.classifier = classifier;
        this.pending = pending;
        this.analysis = analysis;
        this.playerSeen = playerSeen;
    }

    // -------------------------------------------------------------------------------------
    // Block breaking
    // -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        PluginSettings current = settings.get();
        if (!current.analysisEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!current.analysesWorld(world.getName())) {
            return;
        }

        Instant now = Instant.now();
        PlayerRef ref = PlayerRef.of(player.getUniqueId(), player.getName());
        WorldId worldId = WorldId.of(world.getName());
        PlayerSession session = sessions.sessionFor(ref, worldId, now);

        BlockPos pos = BlockPos.of(block.getX(), block.getY(), block.getZ());
        String blockKey = classifier.blockKey(block.getType());

        // Reconstruct the vein BEFORE recording this block's removal. The ore's own removal cannot
        // affect its neighbours' exposure, but doing the analysis first keeps the ordering obvious:
        // everything the analyser reads reflects the world as the player found it.
        maybeRecordDiscovery(current, session, ref, worldId, pos, blockKey, now);

        // Now record the removal, so that a later ore's exposure analysis sees this opening — and,
        // because the timestamp is "now", the analyser will correctly treat it as the player's own
        // approach excavation rather than as pre-existing exposure.
        ledger.record(worldId, pos, io.xrayac.core.domain.MiningOrigin.PLAYER_CREATED, ref.id(), now);

        Observation.Mining miningEvent = new Observation.Mining(ref, worldId,
                world.getFullTime(), now, pos, blockKey,
                io.xrayac.core.domain.MiningOrigin.PLAYER_CREATED);
        session.recordMining(miningEvent);
        pending.enqueueMining(miningEvent);
    }

    /**
     * Reconstructs and classifies a vein when the broken block is a configured ore.
     *
     * <p>The discovery block's own exposure is classified separately from the vein's members. That is
     * the number the rate model keys on — "did the player find this vein by seeing it, or by digging
     * blind?" — so it must be the state of the block actually broken, not an average over the vein.
     */
    private void maybeRecordDiscovery(PluginSettings current, PlayerSession session, PlayerRef ref,
                                      WorldId worldId, BlockPos pos, String blockKey, Instant now) {
        OreCatalog catalog = current.oreCatalog();
        Optional<String> oreId = catalog.oreIdFor(blockKey);
        if (oreId.isEmpty()) {
            return;
        }

        // One vein is one observation. If this block belongs to a vein the player has already been
        // credited or assessed for, breaking another block of it must not create a second discovery:
        // that would both inflate the sample and bias it, because once the player has mined part of a
        // vein their own fresh excavation makes the surviving blocks look enclosed to the analyser.
        if (session.isVeinAccounted(pos)) {
            return;
        }

        ExposureAnalyzer exposureAnalyzer =
                new ExposureAnalyzer(current.exposurePolicy());
        VeinAnalyzer veinAnalyzer = new VeinAnalyzer(catalog, exposureAnalyzer,
                current.performance().maxVeinSize());

        VeinObservation vein;
        try {
            vein = veinAnalyzer.analyze(worldId, pos, blockKey, ref, now, worldView);
        } catch (IllegalArgumentException e) {
            // The catalogue said this was an ore but the analyser disagreed; log and skip rather than
            // letting one anomalous block interrupt observation collection for the whole server.
            return;
        }

        TrajectoryAnalysis.Approach approach = TrajectoryAnalysis.approach(
                session.pathPoints(), pos.center(),
                current.evidenceParameters().lookbackDistanceBlocks());

        // The vein's own verdict, not the broken block's: a player who can see one block of a vein can
        // reasonably mine all of it, so a vein with any visible member is one they could have found by
        // ordinary means. Judging on the first block struck would record visibly-open veins as hidden
        // whenever the player happened to break an enclosed block first (approaching from the side, or
        // mining inward from an adjacent tunnel).
        OreDiscovery discovery = new OreDiscovery(
                vein.oreId(),
                pos,
                now,
                vein.aggregateExposure(),
                vein.size(),
                vein.hiddenCount(),
                vein.exposedCount(),
                session.blocksSinceLastDiscovery(),
                session.distanceSinceLastDiscovery(),
                approach,
                // The origin discount is already expressed by the exposure state: ore found in
                // another player's tunnel is classified CONDITIONALLY_EXPOSED, not HIDDEN, and so
                // never enters the buried-discovery count. Applying a second multiplier here would
                // discount the same fact twice.
                1.0);

        session.recordDiscovery(discovery);
        session.accountVein(vein.blocks());
        pending.enqueueDiscovery(ref.id(), worldId.key(), discovery);
    }

    // -------------------------------------------------------------------------------------
    // Movement
    // -------------------------------------------------------------------------------------

    /**
     * Samples player movement.
     *
     * <p>Movement is the highest-frequency event in the game, so two cheap guards come first: events
     * that only rotated the head are ignored (the player has not moved through the world, and the path
     * geometry is unaffected), and movement in a world that is not analysed is dropped before any
     * allocation.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // Comparing block coordinates ignores pure head rotation, which fires this event constantly
        // and carries no positional information.
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        PluginSettings current = settings.get();
        if (!current.analysisEnabled()) {
            return;
        }
        World world = to.getWorld();
        if (world == null || !current.analysesWorld(world.getName())) {
            return;
        }

        Player player = event.getPlayer();
        PlayerRef ref = PlayerRef.of(player.getUniqueId(), player.getName());
        WorldId worldId = WorldId.of(world.getName());
        Instant now = Instant.now();

        PlayerSession session = sessions.sessionFor(ref, worldId, now);
        session.recordMovement(
                new Vector3(to.getX(), to.getY(), to.getZ()),
                now,
                new Vector3(to.getDirection().getX(), to.getDirection().getY(), to.getDirection().getZ()),
                current.tracking().movementSampleDistance());
    }

    // -------------------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PluginSettings current = settings.get();
        if (!current.analysisEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        playerSeen.accept(PlayerRef.of(player.getUniqueId(), player.getName()));
    }

    /**
     * Finalises a departing player's session.
     *
     * <p>Analysing on quit matters: a cheater who logs off immediately after a suspicious session
     * would otherwise never be assessed, and the evidence they generated would be discarded unread.
     * The session is snapshotted before removal so the worker gets a complete, immutable window.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sessions.remove(player.getUniqueId()).ifPresent(session -> finalise(session, Instant.now()));
    }

    /**
     * Finalises the old world's session when a player changes world.
     *
     * <p>Without this, a player who mines suspiciously in the overworld and then steps into the nether
     * would have their overworld evidence silently replaced by a fresh session — accidentally
     * destroying exactly the evidence a cheater would most want destroyed.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        sessions.remove(player.getUniqueId()).ifPresent(session -> finalise(session, Instant.now()));
    }

    /** Submits a session for analysis if it recorded anything worth assessing. */
    private void finalise(PlayerSession session, Instant now) {
        if (!session.hasAnalysableActivity()) {
            return;
        }
        analysis.submit(session.snapshot(now));
    }
}
