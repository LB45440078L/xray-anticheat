package io.xrayac.core.decision;

import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.evidence.EvidenceStrength;
import io.xrayac.core.evidence.SuspicionSnapshot;
import java.time.Instant;

/**
 * A player held for deferred enforcement, together with the evidence that put them there.
 *
 * <p>A candidate is not a verdict. It records that on at least one occasion the player's behaviour
 * reached the configured evidence threshold, and it accumulates the <b>peak</b> assessment seen so
 * far rather than the latest one: evidence that a player was once assessed at "very strong" is not
 * erased by a later quiet session, and a cheater cannot clear their record by behaving for a day.
 * Only the time-to-live expiry removes a candidate.
 *
 * <p>The evidence summary is stored with the candidate so that a wave can be planned — and audited
 * afterwards — without re-deriving anything, while the final enforcement still recomputes the
 * assessment from persisted observations.
 *
 * @param player              the candidate
 * @param world               the world in which the evidence arose
 * @param firstDetected       when the player first reached the threshold
 * @param lastDetected        when they most recently did
 * @param peakSuspicionScore  highest suspicion score observed
 * @param peakConfidence      highest statistical confidence observed
 * @param peakStrength        strongest evidence band observed
 * @param sampleSize          observation count at the peak assessment
 * @param independentGroups   independent signal families at the peak assessment
 * @param evidenceSummary     a human-readable digest of the peak assessment
 */
public record BanWaveCandidate(
        PlayerRef player,
        WorldId world,
        Instant firstDetected,
        Instant lastDetected,
        double peakSuspicionScore,
        double peakConfidence,
        EvidenceStrength peakStrength,
        int sampleSize,
        int independentGroups,
        String evidenceSummary) {

    public BanWaveCandidate {
        if (player == null || world == null || firstDetected == null || lastDetected == null) {
            throw new IllegalArgumentException("a candidate requires a player, a world and both timestamps");
        }
        if (lastDetected.isBefore(firstDetected)) {
            throw new IllegalArgumentException("lastDetected cannot precede firstDetected");
        }
        if (peakStrength == null) {
            throw new IllegalArgumentException("peakStrength is required");
        }
        if (evidenceSummary == null) {
            evidenceSummary = "";
        }
    }

    /** Creates a candidate from a first qualifying assessment. */
    public static BanWaveCandidate from(PlayerRef player, WorldId world, SuspicionSnapshot snapshot,
                                        Instant now) {
        return new BanWaveCandidate(player, world, now, now,
                snapshot.suspicionScore(), snapshot.statisticalConfidence(),
                snapshot.evidenceStrength(), snapshot.sampleSize(), snapshot.independentGroups(),
                snapshot.explain());
    }

    /**
     * Folds a new assessment into this candidate, keeping the stronger of the two on every
     * dimension. The first-detection time is preserved so the record shows how long the player has
     * been a candidate.
     */
    public BanWaveCandidate mergedWith(SuspicionSnapshot snapshot, Instant now) {
        boolean stronger = snapshot.evidenceStrength().ordinal() > peakStrength.ordinal()
                || (snapshot.evidenceStrength() == peakStrength
                        && snapshot.suspicionScore() > peakSuspicionScore);
        return new BanWaveCandidate(
                player,
                world,
                firstDetected,
                now,
                Math.max(peakSuspicionScore, snapshot.suspicionScore()),
                Math.max(peakConfidence, snapshot.statisticalConfidence()),
                stronger ? snapshot.evidenceStrength() : peakStrength,
                Math.max(sampleSize, snapshot.sampleSize()),
                Math.max(independentGroups, snapshot.independentGroups()),
                stronger ? snapshot.explain() : evidenceSummary);
    }

    /**
     * Folds another candidate's evidence into this one, keeping the stronger value on every
     * dimension and the earliest first-detection time.
     *
     * <p>This exists because the alternative — expressing "keep the stronger" in SQL — means
     * comparing {@link EvidenceStrength} values as strings, and the enum's names do not sort in
     * strength order ({@code WEAK} sorts alphabetically after {@code VERY_STRONG} while being far
     * weaker). Doing the comparison in Java, on the enum's declaration order, makes the ordering
     * explicit and unit-testable rather than an accident of spelling.
     */
    public BanWaveCandidate mergedWith(BanWaveCandidate other) {
        boolean otherStronger = other.peakStrength.ordinal() > peakStrength.ordinal()
                || (other.peakStrength == peakStrength && other.peakSuspicionScore > peakSuspicionScore);
        Instant earliest = other.firstDetected.isBefore(firstDetected) ? other.firstDetected : firstDetected;
        Instant latest = other.lastDetected.isAfter(lastDetected) ? other.lastDetected : lastDetected;
        return new BanWaveCandidate(
                player,
                world,
                earliest,
                latest,
                Math.max(peakSuspicionScore, other.peakSuspicionScore),
                Math.max(peakConfidence, other.peakConfidence),
                otherStronger ? other.peakStrength : peakStrength,
                Math.max(sampleSize, other.sampleSize),
                Math.max(independentGroups, other.independentGroups),
                otherStronger ? other.evidenceSummary : evidenceSummary);
    }

    /** Age of the candidate relative to the given instant, in milliseconds. */
    public long ageMillis(Instant now) {
        return now.toEpochMilli() - firstDetected.toEpochMilli();
    }
}
