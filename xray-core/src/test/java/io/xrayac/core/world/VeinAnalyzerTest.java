package io.xrayac.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.VeinObservation;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.port.OreCatalog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for vein reconstruction: connectivity, exposure classification of members,
 * shape derivation and the size bound.
 */
class VeinAnalyzerTest {

    private static final WorldId WORLD = WorldId.of("survival#minecraft:overworld");
    private static final PlayerRef STEVE = PlayerRef.of(UUID.randomUUID(), "Steve");
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final BlockPos ORE = BlockPos.of(0, -59, 0);

    private static final OreCatalog CATALOG = MapOreCatalog.builder()
            .mapAll("diamond", java.util.List.of(
                    "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"))
            .build();

    private VeinAnalyzer analyzer(int maxVeinSize) {
        return new VeinAnalyzer(CATALOG,
                new ExposureAnalyzer(ExposurePolicy.defaults()), maxVeinSize);
    }

    @Test
    @DisplayName("reconstructs a face-connected vein and classifies each member's exposure")
    void faceConnectedVein() {
        FakeWorldView world = FakeWorldView.untouchedStone()
                .block(ORE, BlockKind.OPAQUE_SOLID, "minecraft:deepslate_diamond_ore")
                .block(ORE.add(1, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:deepslate_diamond_ore")
                .block(ORE.add(2, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:deepslate_diamond_ore")
                .block(ORE.add(3, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:deepslate_diamond_ore")
                // A natural cavity opens onto the first block only.
                .naturalCavity(ORE.add(0, 0, 1));

        VeinObservation vein = analyzer(64).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        assertThat(vein.oreId()).isEqualTo("diamond");
        assertThat(vein.size()).isEqualTo(4);
        assertThat(vein.exposedCount()).isEqualTo(1);
        assertThat(vein.hiddenCount()).isEqualTo(3);
        assertThat(vein.discoveryBlock()).isEqualTo(ORE);
        assertThat(vein.truncated()).isFalse();
    }

    @Test
    @DisplayName("merges diagonally touching blocks into one vein (26-connectivity)")
    void diagonalConnectivity() {
        FakeWorldView world = FakeWorldView.untouchedStone()
                .block(ORE, BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore")
                .block(ORE.add(1, 1, 1), BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore");

        VeinObservation vein = analyzer(64).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        // Corner-touching blocks belong to one vein; splitting them would inflate discovery counts.
        assertThat(vein.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("treats the two deepslate and stone variants of an ore as the same vein")
    void oreVariantMerging() {
        FakeWorldView world = FakeWorldView.untouchedStone()
                .block(ORE, BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore")
                .block(ORE.add(1, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:deepslate_diamond_ore");

        VeinObservation vein = analyzer(64).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        assertThat(vein.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("does not merge a different ore into the vein")
    void differentOreNotMerged() {
        FakeWorldView world = FakeWorldView.untouchedStone()
                .block(ORE, BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore")
                .block(ORE.add(1, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:emerald_ore");

        VeinObservation vein = analyzer(64).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        assertThat(vein.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("bounds the reconstruction and flags truncation")
    void truncation() {
        FakeWorldView world = FakeWorldView.untouchedStone();
        for (int dx = 0; dx < 6; dx++) {
            world.block(ORE.add(dx, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore");
        }

        VeinObservation vein = analyzer(3).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        assertThat(vein.size()).isEqualTo(3);
        assertThat(vein.truncated()).isTrue();
    }

    @Test
    @DisplayName("derives a shape whose dominant axis follows a linear vein")
    void shapeFollowsLinearVein() {
        FakeWorldView world = FakeWorldView.untouchedStone();
        for (int dx = 0; dx < 5; dx++) {
            world.block(ORE.add(dx, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore");
        }

        VeinObservation vein = analyzer(64).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        // A vein laid out purely along X must have X as its dominant axis (sign is arbitrary).
        assertThat(Math.abs(vein.shape().dominantAxis().x())).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(vein.shape().extent()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(vein.shape().linearity()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("a vein with one block open to a cave is judged VISIBLE, even when the enclosed block is the seed")
    void partlyVisibleVeinIsJudgedVisible() {
        // Three deepslate diamond blocks in a line. The far end opens onto a natural cavity, so a
        // player standing in that cave could see the vein; the near end is solidly enclosed.
        FakeWorldView world = FakeWorldView.untouchedStone()
                .block(ORE, BlockKind.OPAQUE_SOLID, "minecraft:deepslate_diamond_ore")
                .block(ORE.add(1, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:deepslate_diamond_ore")
                .block(ORE.add(2, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:deepslate_diamond_ore")
                .naturalCavity(ORE.add(3, 0, 0));

        // Analysed from the ENCLOSED end, which is the case that used to be reported as a hidden
        // discovery: the player mines the vein from the side, so the first block struck is buried even
        // though the vein itself is in the open. One visible member makes the whole vein findable.
        VeinObservation vein = analyzer(64).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        assertThat(vein.discoveryWasHidden()).isTrue();
        assertThat(vein.aggregateExposure()).isEqualTo(ExposureState.FULLY_EXPOSED);
        assertThat(vein.isVisibleByOrdinaryPlay()).isTrue();
    }

    @Test
    @DisplayName("a fully enclosed vein is still judged hidden")
    void fullyEnclosedVeinStaysHidden() {
        FakeWorldView world = FakeWorldView.untouchedStone()
                .block(ORE, BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore")
                .block(ORE.add(1, 0, 0), BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore");

        VeinObservation vein = analyzer(64).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        // The leniency must not become blanket: a vein with nothing visible anywhere is still buried.
        assertThat(vein.aggregateExposure()).isEqualTo(ExposureState.HIDDEN);
        assertThat(vein.isVisibleByOrdinaryPlay()).isFalse();
    }

    @Test
    @DisplayName("an unreadable vein is UNKNOWN, never resolved into HIDDEN")
    void unreadableVeinIsUnknown() {
        FakeWorldView world = FakeWorldView.untouchedStone()
                .block(ORE, BlockKind.OPAQUE_SOLID, "minecraft:diamond_ore")
                .unload(ORE.add(1, 0, 0));

        VeinObservation vein = analyzer(64).analyze(WORLD, ORE, "minecraft:diamond_ore", STEVE, NOW, world);

        assertThat(vein.aggregateExposure()).isEqualTo(ExposureState.UNKNOWN);
    }

    @Test
    @DisplayName("mostVisible ranks uncertainty above hiddenness")
    void mostVisibleRanking() {
        assertThat(ExposureState.mostVisible(List.of(ExposureState.HIDDEN, ExposureState.UNKNOWN)))
                .isEqualTo(ExposureState.UNKNOWN);
        assertThat(ExposureState.mostVisible(
                List.of(ExposureState.HIDDEN, ExposureState.CONDITIONALLY_EXPOSED)))
                .isEqualTo(ExposureState.CONDITIONALLY_EXPOSED);
        assertThat(ExposureState.mostVisible(List.of(ExposureState.HIDDEN)))
                .isEqualTo(ExposureState.HIDDEN);
        assertThat(ExposureState.mostVisible(List.of())).isEqualTo(ExposureState.UNKNOWN);
    }

    @Test
    @DisplayName("discovers the ore itself and refuses a non-ore seed")
    void rejectsNonOreSeed() {
        FakeWorldView world = FakeWorldView.untouchedStone();
        assertThatThrownBy(() ->
                analyzer(64).analyze(WORLD, ORE, "minecraft:stone", STEVE, NOW, world))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a configured ore");
    }
}
