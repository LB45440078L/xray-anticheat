package io.xrayac.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.domain.ExposureResult;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for the exposure analyser, organised by the legitimate and illegitimate
 * player behaviours from which the classifications must be inferred.
 *
 * <p>Each test states a scenario in the language of gameplay ("a player strip-mining through
 * untouched stone") and asserts the resulting {@link ExposureState}, because these
 * classifications are the facts on which every later statistic is conditioned.
 */
class ExposureAnalyzerTest {

    private static final WorldId WORLD = WorldId.of("survival#minecraft:overworld");
    private static final PlayerRef STEVE = PlayerRef.of(UUID.randomUUID(), "Steve");
    private static final BlockPos ORE = BlockPos.of(0, -59, 0);

    /** A fixed reference instant; all relative times in tests are expressed against it. */
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    private final ExposureAnalyzer analyzer = new ExposureAnalyzer(ExposurePolicy.defaults());

    @Nested
    @DisplayName("genuinely hidden ore")
    class HiddenOre {

        @Test
        @DisplayName("ore fully enclosed by untouched stone is HIDDEN")
        void enclosedInStone() {
            FakeWorldView world = FakeWorldView.untouchedStone();
            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.HIDDEN);
            assertThat(result.openFaces()).isZero();
            assertThat(result.cavityAdjacent()).isFalse();
            assertThat(result.rationale()).isNotBlank();
        }

        @Test
        @DisplayName("a strip-miner's freshly cut approach does not count as pre-existing exposure")
        void freshApproachExcavation() {
            // The player mined straight into the ore from -X over the last few seconds. Those
            // openings are the player's own, inside the approach window, so before the player
            // dug, the ore was enclosed: it is HIDDEN.
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .playerExcavated(ORE.add(-1, 0, 0), NOW.toEpochMilli() - 2_000)
                    .playerExcavated(ORE.add(0, 0, -1), NOW.toEpochMilli() - 2_000);

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.HIDDEN);
            assertThat(result.openFaces()).isEqualTo(2);
            assertThat(result.excavatedAdjacent()).isTrue();
        }
    }

    @Nested
    @DisplayName("naturally visible ore")
    class ExposedOre {

        @Test
        @DisplayName("ore opening onto a natural cave is FULLY_EXPOSED")
        void adjacentToCave() {
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .naturalCavity(ORE.add(1, 0, 0));

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.FULLY_EXPOSED);
            assertThat(result.cavityAdjacent()).isTrue();
            assertThat(result.state().isVisibleByOrdinaryPlay()).isTrue();
        }

        @Test
        @DisplayName("cave visibility is not weakened by also having injected air")
        void cavePlusUnattributed() {
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .naturalCavity(ORE.add(1, 0, 0))
                    .unattributedAir(ORE.add(0, 1, 0));

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.FULLY_EXPOSED);
        }

        @Test
        @DisplayName("a sight-transmitting but solid neighbour (glass) still exposes the ore")
        void throughTransparentSolid() {
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .block(ORE.add(0, 1, 0), BlockKind.TRANSPARENT_SOLID, "minecraft:glass");

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            // Glass transmits sight, so a player could see the ore beneath it.
            assertThat(result.state()).isEqualTo(ExposureState.FULLY_EXPOSED);
        }

        @Test
        @DisplayName("an ore is visible from a diagonal natural cavity when diagonal vision is enabled")
        void diagonalNaturalVantagePoint() {
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .naturalCavity(ORE.add(1, 1, 1));
            ExposureAnalyzer diagonalAware = new ExposureAnalyzer(
                    new ExposurePolicy(true, 90_000L, 1, false));

            ExposureResult result = diagonalAware.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.PARTIALLY_EXPOSED);
            assertThat(result.cavityAdjacent()).isTrue();
        }

        @Test
        @DisplayName("the same diagonal cavity is HIDDEN when diagonal vision is disabled")
        void diagonalIgnoredByDefault() {
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .naturalCavity(ORE.add(1, 1, 1));

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.HIDDEN);
        }
    }

    @Nested
    @DisplayName("multiplayer contamination")
    class MultiplayerContamination {

        @Test
        @DisplayName("ore in another player's tunnel is CONDITIONALLY_EXPOSED, not hidden")
        void otherPlayersTunnel() {
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .otherPlayerExcavated(ORE.add(1, 0, 0), NOW.toEpochMilli() - 600_000);

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.CONDITIONALLY_EXPOSED);
            // The discoverer was walking somebody else's tunnel, so this must not be treated
            // as an unaccountably hidden ore.
            assertThat(result.state().isUnaccountablyHidden()).isFalse();
        }

        @Test
        @DisplayName("ore in the player's own older tunnel is PARTIALLY_EXPOSED")
        void ownOlderTunnel() {
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .playerExcavated(ORE.add(1, 0, 0), NOW.toEpochMilli() - 600_000);

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.PARTIALLY_EXPOSED);
        }
    }

    @Nested
    @DisplayName("uncertainty is modelled, not fabricated")
    class Uncertainty {

        @Test
        @DisplayName("an unloaded neighbour makes the classification UNKNOWN rather than HIDDEN")
        void unloadedNeighbour() {
            FakeWorldView world = FakeWorldView.untouchedStone()
                    .unload(ORE.add(1, 0, 0));

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.UNKNOWN);
            assertThat(result.confidence()).isZero();
        }

        @Test
        @DisplayName("an unloaded ore chunk itself is UNKNOWN")
        void unloadedOreChunk() {
            FakeWorldView world = FakeWorldView.untouchedStone().unload(ORE);

            ExposureResult result = analyzer.analyze(WORLD, ORE, STEVE, NOW, world);

            assertThat(result.state()).isEqualTo(ExposureState.UNKNOWN);
        }

        @Test
        @DisplayName("UNKNOWN carries neutral hiddenness weight so missing data cannot bias the score")
        void unknownIsNeutral() {
            assertThat(ExposureState.UNKNOWN.hiddennessWeight())
                    .isBetween(ExposureState.PARTIALLY_EXPOSED.hiddennessWeight(),
                            ExposureState.HIDDEN.hiddennessWeight());
        }
    }
}
