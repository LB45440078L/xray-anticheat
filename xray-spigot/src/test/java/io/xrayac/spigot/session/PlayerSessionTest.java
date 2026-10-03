package io.xrayac.spigot.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the session's discovery bookkeeping.
 *
 * <p>These run without a server: {@code PlayerSession} holds buffers and counters over core domain
 * types and touches no Minecraft class, which is what makes the one-vein-one-discovery rule testable
 * at all. The listener that consumes these methods is thin by comparison.
 */
@DisplayName("player session")
class PlayerSessionTest {

    private static final PlayerRef STEVE = PlayerRef.of(UUID.randomUUID(), "Steve");
    private static final WorldId WORLD = WorldId.of("world#minecraft:overworld");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final BlockPos ORE = new BlockPos(100, -50, 200);

    private static PlayerSession session(int miningBufferSize, int discoveryBufferSize) {
        return new PlayerSession(STEVE, WORLD, NOW, 64, miningBufferSize, discoveryBufferSize);
    }

    private static OreDiscovery discoveryAt(BlockPos at) {
        return new OreDiscovery("diamond", at, NOW, ExposureState.HIDDEN, 3, 3, 0,
                32, 16, TrajectoryAnalysis.Approach.noData(), 1.0);
    }

    @Test
    @DisplayName("recording a discovery counts once and resets the intervening effort")
    void recordingADiscoveryResetsEffortCounters() {
        PlayerSession session = session(64, 64);
        session.recordDiscovery(discoveryAt(ORE));

        assertThat(session.discoveryCount()).isEqualTo(1);
        assertThat(session.hasAnalysableActivity()).isTrue();
    }

    @Test
    @DisplayName("every block of a recorded vein is accounted for, so re-breaking it adds no discovery")
    void oneVeinYieldsOneDiscovery() {
        PlayerSession session = session(64, 64);
        Set<BlockPos> vein = Set.of(ORE, ORE.add(0, 1, 0), ORE.add(0, 0, 1));

        session.recordDiscovery(discoveryAt(ORE));
        session.accountVein(vein);

        // The listener asks about a block before recording, so any further break of this same vein is
        // skipped. Without this a partly-visible vein produced one discovery per block, and the later
        // ones were systematically biased towards "hidden": by then the player's own earlier breaks are
        // the fresh openings sitting next to them.
        assertThat(vein).allSatisfy(block -> assertThat(session.isVeinAccounted(block)).isTrue());
        assertThat(session.isVeinAccounted(ORE.add(4, 4, 4))).isFalse();
        assertThat(session.discoveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the accounted-vein set stays bounded, evicting the oldest veins first")
    void accountedVeinsStayBounded() {
        PlayerSession session = session(8, 64);
        for (int i = 0; i < 8; i++) {
            session.accountVein(Set.of(ORE.add(i, 0, 0)));
        }
        assertThat(session.isVeinAccounted(ORE)).isTrue();

        // The ninth vein pushes the set past its bound, which must evict rather than grow: a long
        // session digging thousands of veins would otherwise retain every vein position it ever saw.
        session.accountVein(Set.of(ORE.add(8, 0, 0)));

        assertThat(session.isVeinAccounted(ORE)).isFalse();
        assertThat(session.isVeinAccounted(ORE.add(1, 0, 0))).isTrue();
        assertThat(session.isVeinAccounted(ORE.add(8, 0, 0))).isTrue();
    }
}
