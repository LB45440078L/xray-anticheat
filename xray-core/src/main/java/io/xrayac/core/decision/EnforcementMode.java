package io.xrayac.core.decision;

/**
 * How the system converts evidence into enforcement.
 *
 * <p>These three modes exist because servers differ enormously in how much they trust automated
 * judgement, and because a server's trust should change over time without a redesign:
 *
 * <ul>
 *   <li>{@link #ALERT_ONLY} — the system never acts on a player. It produces assessments and
 *       alerts for human moderators. This is the mode a server should run in while it calibrates
 *       its ore priors and builds confidence in the plugin.</li>
 *   <li>{@link #IMMEDIATE} — once the configured statistical certainty is reached, the action is
 *       taken at once. Appropriate when a server has high confidence in its configuration.</li>
 *   <li>{@link #BAN_WAVE} — evidence is collected and players are held as candidates; enforcement
 *       is deferred and executed in batches.</li>
 * </ul>
 */
public enum EnforcementMode {

    ALERT_ONLY,

    IMMEDIATE,

    BAN_WAVE
}
