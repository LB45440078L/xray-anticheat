package io.xrayac.core.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A player's stored past, rebuilt into the same vocabulary as the live session.
 *
 * <p>This is the counterpart to {@link PlayerAnalysisWindow} for data that has already been written to
 * the database: it exists so that an assessment spans a player's whole recorded time rather than the
 * current login. {@link HistoryHydrator} merges the two.
 *
 * <h2>What is reconstructed exactly, and what is not</h2>
 * Being precise here matters, because the merged window is what the statistical engine sees and a
 * confidently wrong input is worse than a missing one.
 *
 * <ul>
 *   <li><b>Discoveries — exact.</b> Every field of {@link OreDiscovery} the engine reads is persisted,
 *       including the two alignment angles the targeting model consults. It reads no other member of
 *       {@link TrajectoryAnalysis.Approach}, so nothing it needs is missing. The per-discovery effort
 *       fields are stored too, so the waiting-time model is rebuilt rather than approximated.</li>
 *   <li><b>Blocks mined — exact, by the same definition as the live count.</b> Both are counts of block
 *       breaks the plugin recorded, not of blocks that exist.</li>
 *   <li><b>Distance travelled — a lower bound.</b> Only the distance carried on each discovery is
 *       stored, so travel that produced no discovery is invisible. It is therefore never larger than
 *       what the player really moved. No current evidence component reads the window total, so this
 *       understatement cannot inflate suspicion; it is carried for reporting.</li>
 *   <li><b>Trajectory — a mining path, not a movement path.</b> The stored mining events give the
 *       positions of broken blocks. Straightness, efficiency, dominant axis and deviation from it are
 *       meaningful over that path — it is the shape of the excavation, which is what the tunnel-geometry
 *       signal is about — but turn density is coarser than over a movement path, because a player who
 *       walks around a corner without breaking anything leaves no trace. The live movement samples are
 *       concatenated with it, so the most recent behaviour keeps full fidelity.</li>
 *   <li><b>Time span — exact.</b> The earliest stored observation becomes the window start, which is how
 *       the horizon reaches the explanation a moderator reads.</li>
 * </ul>
 *
 * @param discoveries        stored discoveries, oldest first
 * @param blocksMined        block breaks recorded over the history
 * @param distanceTravelled  lower bound on distance moved, summed from per-discovery distances
 * @param miningPath         mined block positions with their times, oldest first
 * @param earliestObservation the oldest instant the history covers, empty when there is none
 * @param truncated          true when a read hit its configured limit, so older data exists that was
 *                           deliberately not loaded; the history is then a floor, not the whole past
 */
public record AnalysisHistory(
        List<OreDiscovery> discoveries,
        double blocksMined,
        double distanceTravelled,
        List<TrajectoryAnalysis.PathPoint> miningPath,
        Optional<Instant> earliestObservation,
        boolean truncated) {

    public AnalysisHistory {
        if (discoveries == null || miningPath == null) {
            throw new IllegalArgumentException("history collections must be present, possibly empty");
        }
        if (blocksMined < 0 || distanceTravelled < 0) {
            throw new IllegalArgumentException("historical totals cannot be negative");
        }
        if (earliestObservation == null) {
            throw new IllegalArgumentException("earliestObservation must be Optional, not null");
        }
        discoveries = List.copyOf(discoveries);
        miningPath = List.copyOf(miningPath);
    }

    /** No stored past: a player seen for the first time, or history switched off. */
    public static AnalysisHistory empty() {
        return new AnalysisHistory(List.of(), 0.0, 0.0, List.of(), Optional.empty(), false);
    }

    /** True when there is nothing stored to contribute. */
    public boolean isEmpty() {
        return discoveries.isEmpty() && miningPath.isEmpty() && blocksMined == 0.0;
    }

    /** How far back the history reaches from {@code now}, or zero when it is empty. */
    public Duration span(Instant now) {
        return earliestObservation
                .map(earliest -> Duration.between(earliest, now))
                .orElse(Duration.ZERO);
    }
}
