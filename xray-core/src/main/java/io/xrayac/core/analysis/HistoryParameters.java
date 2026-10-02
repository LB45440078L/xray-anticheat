package io.xrayac.core.analysis;

import java.time.Duration;

/**
 * How far back an assessment reaches into stored history.
 *
 * <p>A player's evidence must accumulate over their whole time on the server, not just the current
 * login. Without this, every restart and every session boundary resets the evidence base to zero,
 * which both under-reports a patient cheater and makes ban waves impossible to justify: a wave built
 * on a few hours cannot be defended when the conduct it describes took months.
 *
 * <p>This record is the platform-neutral form of the {@code analysis.history} configuration block. It
 * lives in the core so the hydrator can be tested without a server, exactly like
 * {@link io.xrayac.core.evidence.EvidenceParameters}.
 *
 * @param enabled          whether stored history is merged into the analysis window at all; false
 *                         restores the older session-only behaviour
 * @param lookback         how far back to read; must not exceed the retention that keeps the data
 * @param maxDiscoveries   upper bound on stored discoveries read per player, so one prolific player
 *                         cannot make a single assessment arbitrarily expensive
 * @param maxMiningEvents  upper bound on stored mining events read per player, which is what bounds
 *                         the cost of rebuilding the historical trajectory
 * @param refreshInterval  how long a hydrated history is reused before it is read again; hydration is
 *                         a database round trip, so it must not happen on every evaluation
 */
public record HistoryParameters(
        boolean enabled,
        Duration lookback,
        int maxDiscoveries,
        int maxMiningEvents,
        Duration refreshInterval) {

    public HistoryParameters {
        if (lookback == null || lookback.isNegative() || lookback.isZero()) {
            throw new IllegalArgumentException("history lookback must be a positive duration");
        }
        if (refreshInterval == null || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("history refresh interval cannot be negative");
        }
        if (maxDiscoveries < 1 || maxMiningEvents < 1) {
            throw new IllegalArgumentException("history read limits must be at least 1");
        }
    }

    /**
     * The shipped defaults: ninety days, matching the discovery retention.
     *
     * <p>Ninety days is chosen to span a season of play — long enough that a cheater cannot wash their
     * record by waiting out a restart, short enough that the evidence still describes who the player is
     * now rather than who they were last year.
     */
    public static HistoryParameters defaults() {
        return new HistoryParameters(true, Duration.ofDays(90), 2000, 20000, Duration.ofMinutes(15));
    }

    /** Session-only analysis: no history is read, reproducing the pre-history behaviour. */
    public static HistoryParameters sessionOnly() {
        return new HistoryParameters(false, Duration.ofDays(90), 2000, 20000, Duration.ofMinutes(15));
    }
}
