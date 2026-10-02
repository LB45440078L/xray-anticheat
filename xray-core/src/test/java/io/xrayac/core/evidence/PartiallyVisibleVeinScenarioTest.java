package io.xrayac.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.config.MapOreProfileRegistry;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.VeinObservation;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.evidence.component.ExposureMixComponent;
import io.xrayac.core.evidence.component.HiddenDiscoveryRateComponent;
import io.xrayac.core.evidence.component.InterDiscoveryWaitingComponent;
import io.xrayac.core.evidence.component.OreTargetingComponent;
import io.xrayac.core.evidence.component.TunnelGeometryComponent;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.Vector3;
import io.xrayac.core.port.OreCatalog;
import io.xrayac.core.world.BlockKind;
import io.xrayac.core.world.ExposureAnalyzer;
import io.xrayac.core.world.ExposurePolicy;
import io.xrayac.core.world.FakeWorldView;
import io.xrayac.core.world.MapOreCatalog;
import io.xrayac.core.world.VeinAnalyzer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The regression test for the partially-visible-vein false positive.
 *
 * <p>This is the scenario that was reported from live play: a legitimate player mines veins that are
 * open to a cave at one end, and the anti-cheat accused them of buried-ore mining. The cause was not in
 * the statistics but in the classification feeding them — a discovery was judged by the single block the
 * player happened to break first, so a player who entered a vein from the enclosed side was recorded as
 * having found buried ore while standing next to open cave.
 *
 * <p>Unlike the other scenario tests, this one does not hand the engine a pre-labelled discovery list.
 * It builds a synthetic world, runs the real {@link VeinAnalyzer} over it exactly as the listener does,
 * and feeds the resulting classifications to the real engine. That is what makes it a regression test
 * rather than an assertion about arithmetic: reverting the vein's aggregate verdict to the broken
 * block's verdict turns these discoveries back into hidden ones and the player is flagged again.
 *
 * <p>The control window at the end states the size of the error being guarded against, so that a future
 * reader can see the fix is load-bearing rather than cosmetic.
 */
@DisplayName("partially visible veins")
class PartiallyVisibleVeinScenarioTest {

    private static final WorldId WORLD = WorldId.of("survival#minecraft:overworld");
    private static final PlayerRef PLAYER = PlayerRef.of(UUID.randomUUID(), "Legit");
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final OreProfileRegistry PROFILES =
            MapOreProfileRegistry.of(MapOreProfileRegistry.defaults());
    private static final EvidenceParameters PARAMS = EvidenceParameters.defaults();

    private static final OreCatalog CATALOG = MapOreCatalog.builder()
            .mapAll("diamond", List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"))
            .build();

    private static final EvidenceEngine ENGINE = new EvidenceEngine(List.of(
            new HiddenDiscoveryRateComponent(),
            new InterDiscoveryWaitingComponent(),
            new OreTargetingComponent(),
            new ExposureMixComponent(),
            new TunnelGeometryComponent()));

    /**
     * How many partly-visible veins the player works through.
     *
     * <p>Twenty, chosen deliberately: it is the point at which judging the veins by the block broken
     * first turns this identical mining into a STRONG verdict (measured at 0.997, against 1.6e-11 with
     * the fix). A smaller number would let the test pass for the wrong reason, because the sample-size
     * restraint already refuses to convict on ten observations whatever the classification says.
     */
    private static final int VEIN_COUNT = 20;
    /** Veins are placed this far apart so their flood fills cannot merge. */
    private static final int VEIN_STRIDE = 40;
    private static final int BLOCKS_PER_VEIN = 4;

    /**
     * A world of {@value #VEIN_COUNT} diamond veins, each of {@value #BLOCKS_PER_VEIN} blocks lying in a
     * line, each with a natural cavity opening on to its far end only.
     */
    private static FakeWorldView worldOfPartlyVisibleVeins() {
        return worldOfPartlyVisibleVeins(VEIN_COUNT);
    }

    private static FakeWorldView worldOfPartlyVisibleVeins(int veinCount) {
        FakeWorldView world = FakeWorldView.untouchedStone();
        for (int vein = 0; vein < veinCount; vein++) {
            int base = vein * VEIN_STRIDE;
            for (int block = 0; block < BLOCKS_PER_VEIN; block++) {
                world.block(BlockPos.of(base + block, -59, 0), BlockKind.OPAQUE_SOLID,
                        "minecraft:deepslate_diamond_ore");
            }
            // The opening sits just past the last vein block, so exactly one member is in the open.
            world.naturalCavity(BlockPos.of(base + BLOCKS_PER_VEIN, -59, 0));
        }
        return world;
    }

    /**
     * Analyses every vein from its enclosed end — the case that used to be misreported — and turns the
     * results into discoveries the way the listener does.
     */
    private static List<VeinObservation> analyseVeins(FakeWorldView world) {
        return analyseVeins(world, VEIN_COUNT);
    }

    private static List<VeinObservation> analyseVeins(FakeWorldView world, int veinCount) {
        VeinAnalyzer analyzer = new VeinAnalyzer(CATALOG,
                new ExposureAnalyzer(ExposurePolicy.defaults()), 64);
        List<VeinObservation> veins = new ArrayList<>();
        for (int vein = 0; vein < veinCount; vein++) {
            BlockPos enclosedSeed = BlockPos.of(vein * VEIN_STRIDE, -59, 0);
            veins.add(analyzer.analyze(WORLD, enclosedSeed, "minecraft:deepslate_diamond_ore",
                    PLAYER, NOW, world));
        }
        return veins;
    }

    private static OreDiscovery discoveryFrom(VeinObservation vein, int index,
                                             ExposureState exposure) {
        return new OreDiscovery("diamond", vein.discoveryBlock(), NOW.minusSeconds(600 - index),
                exposure, vein.size(),
                exposure == ExposureState.HIDDEN ? vein.size() : vein.hiddenCount(),
                exposure == ExposureState.HIDDEN ? 0 : vein.exposedCount(),
                75, 40, TrajectoryAnalysis.Approach.noData(), 1.0);
    }

    /** A straight tunnel spanning the mined ground: the shape a strip miner produces. */
    private static List<TrajectoryAnalysis.PathPoint> stripMinePath() {
        List<TrajectoryAnalysis.PathPoint> path = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            path.add(TrajectoryAnalysis.PathPoint.of(new Vector3(i * 10, -59, 0),
                    NOW.minusSeconds(600 - i)));
        }
        return path;
    }

    private static SuspicionSnapshot evaluateScaled(List<OreDiscovery> discoveries,
                                                    double blocksMined) {
        PlayerAnalysisWindow window = PlayerAnalysisWindow.of(PLAYER, WORLD,
                NOW.minusSeconds(3600), NOW, blocksMined, 1200, discoveries, stripMinePath());
        return ENGINE.evaluate(window, PROFILES, PARAMS, NOW);
    }

    @Test
    @DisplayName("a vein open at one end is classified visible even when the enclosed block is struck first")
    void veinsAreClassifiedByTheirMostVisibleMember() {
        for (VeinObservation vein : analyseVeins(worldOfPartlyVisibleVeins())) {
            // The block actually broken was buried, and that is what the old code judged the vein by.
            assertThat(vein.discoveryWasHidden()).isTrue();
            // The vein as a whole was not: one member was open to a cave, so it was findable by ordinary
            // play. This single assertion is the fix.
            assertThat(vein.aggregateExposure().isVisibleByOrdinaryPlay()).isTrue();
        }
    }

    @Test
    @DisplayName("a strip miner working partly-visible veins is not flagged")
    void legitMinerOfPartlyVisibleVeinsIsNotFlagged() {
        List<VeinObservation> veins = analyseVeins(worldOfPartlyVisibleVeins());
        List<OreDiscovery> discoveries = new ArrayList<>();
        for (int i = 0; i < veins.size(); i++) {
            discoveries.add(discoveryFrom(veins.get(i), i, veins.get(i).aggregateExposure()));
        }

        double blocksMined = VEIN_COUNT * 150.0;
        PlayerAnalysisWindow window = PlayerAnalysisWindow.of(PLAYER, WORLD,
                NOW.minusSeconds(3600), NOW, blocksMined, 1200, discoveries, stripMinePath());
        assertThat(window.hiddenCount()).isZero();

        SuspicionSnapshot snapshot = ENGINE.evaluate(window, PROFILES, PARAMS, NOW);

        assertThat(snapshot.evidenceStrength()).isEqualTo(EvidenceStrength.INSUFFICIENT);
        // Not merely under the threshold but effectively zero: every discovery is ore the player could
        // see from open cave, so there is nothing for the engine to count against them. A straight
        // tunnel is a strip mine, which is legitimate, and on its own it must not convict.
        assertThat(snapshot.suspicionScore()).isLessThan(1e-6);
    }

    @Test
    @DisplayName("judging the same mining by the broken block alone is what produced the false positive")
    void judgingByTheBrokenBlockWouldHaveFlaggedThem() {
        List<VeinObservation> veins = analyseVeins(worldOfPartlyVisibleVeins());
        List<OreDiscovery> fixed = new ArrayList<>();
        List<OreDiscovery> byBrokenBlock = new ArrayList<>();
        for (int i = 0; i < veins.size(); i++) {
            fixed.add(discoveryFrom(veins.get(i), i, veins.get(i).aggregateExposure()));
            // The pre-fix classification: every vein recorded as four buried blocks.
            byBrokenBlock.add(discoveryFrom(veins.get(i), i, ExposureState.HIDDEN));
        }

        double blocksMined = VEIN_COUNT * 150.0;
        SuspicionSnapshot fixedSnapshot = evaluateScaled(fixed, blocksMined);
        SuspicionSnapshot brokenSnapshot = evaluateScaled(byBrokenBlock, blocksMined);

        // The same mining, judged by the block the player happened to break instead of by the vein as a
        // whole: the legitimate player goes from nothing at all to a STRONG verdict on twenty veins.
        // That is the size of the false positive reported from live play, and it is why the aggregation
        // in VeinAnalyzer is load-bearing rather than a tidy-up.
        assertThat(fixedSnapshot.suspicionScore()).isLessThan(1e-6);
        assertThat(fixedSnapshot.evidenceStrength()).isEqualTo(EvidenceStrength.INSUFFICIENT);
        assertThat(brokenSnapshot.suspicionScore()).isGreaterThan(0.9);
        assertThat(brokenSnapshot.evidenceStrength())
                .isIn(EvidenceStrength.MODERATE, EvidenceStrength.STRONG, EvidenceStrength.VERY_STRONG);
    }
}
