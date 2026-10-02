package io.xrayac.core.decision;

import java.time.Instant;
import java.util.List;

/**
 * A proposed batch of enforcement actions.
 *
 * <p>Deliberately a <i>data</i> object with no behaviour: the plugin layer decides how to execute it
 * (ban through the server's own ban mechanism, kick, or merely notify staff), because how a player
 * is removed is a platform concern, while which players are removed is a statistical one. Keeping
 * the plan inert also means it can be logged, stored, shown in a GUI and audited before anything
 * happens to anybody.
 *
 * @param plannedAt     when the plan was constructed
 * @param candidates    the players to act on
 * @param automaticBan  whether the server is configured to execute this without human approval
 */
public record BanWavePlan(
        Instant plannedAt,
        List<BanWaveCandidate> candidates,
        boolean automaticBan) {

    public BanWavePlan {
        if (plannedAt == null) {
            throw new IllegalArgumentException("plannedAt is required");
        }
        candidates = List.copyOf(candidates);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("a ban wave plan must contain at least one candidate");
        }
    }

    public int size() {
        return candidates.size();
    }

    /** A compact digest suitable for a console or staff-channel announcement. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Ban wave: %d candidate(s), planned at %s, execution=%s%n",
                candidates.size(), plannedAt, automaticBan ? "automatic" : "awaiting moderator approval"));
        for (BanWaveCandidate candidate : candidates) {
            sb.append(String.format("  %s — %s (confidence %.2f, %d independent signal(s), candidate since %s)%n",
                    candidate.player().display(), candidate.peakStrength().label(),
                    candidate.peakConfidence(), candidate.independentGroups(), candidate.firstDetected()));
        }
        return sb.toString();
    }
}
