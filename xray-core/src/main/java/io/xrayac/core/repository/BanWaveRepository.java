package io.xrayac.core.repository;

import io.xrayac.core.decision.BanWaveCandidate;
import io.xrayac.core.decision.BanWavePlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of ban-wave state: the candidate list and the record of waves that have run.
 *
 * <p>The record of past waves exists so that the inter-wave interval survives a server restart. It
 * is also the audit trail: if a moderator asks "when did this player get removed, and on whose
 * authority?", the answer comes from here.
 *
 * <p><b>Threading contract.</b> Blocking I/O; never call from the server thread.
 */
public interface BanWaveRepository {

    /** Inserts or strengthens a candidate, keeping the earliest first-detection time. */
    void upsertCandidate(BanWaveCandidate candidate);

    /** All current candidates, ordered by most recent detection. */
    List<BanWaveCandidate> candidates();

    /** Removes a candidate, for example after a wave has executed or a moderator has cleared it. */
    void deleteCandidate(UUID playerId);

    /**
     * Removes candidates whose evidence has expired.
     *
     * @return the number of candidates removed
     */
    long deleteCandidatesOlderThan(Instant cutoff);

    /**
     * Records that a wave was planned or executed.
     *
     * @param executedAt when the wave was carried out, or {@code null} if it is still only proposed
     */
    String recordWave(BanWavePlan plan, Instant executedAt);

    /** When the most recent wave executed, if any ever has. */
    Optional<Instant> lastExecutedWaveAt();

    /** Recent waves, newest first, for the administrative record. */
    List<WaveRecord> recentWaves(int limit);

    /**
     * A stored wave.
     */
    record WaveRecord(String id, Instant plannedAt, Instant executedAt, int candidateCount,
                      boolean automaticBan, String summary) {
    }
}
