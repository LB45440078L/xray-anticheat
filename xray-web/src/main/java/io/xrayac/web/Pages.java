package io.xrayac.web;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.decision.BanWaveCandidate;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.SuspicionSnapshot;
import io.xrayac.core.repository.BanWaveRepository;
import io.xrayac.core.repository.ModeratorActionRepository;
import io.xrayac.core.repository.OreDiscoveryRepository;
import io.xrayac.core.repository.PlayerRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The pages, as HTML.
 *
 * <p>Server-rendered rather than a single-page application. That is a security decision as much as a
 * simplicity one: every page is produced after the session has been checked on the server, so there is
 * no privileged data sitting in the browser to be leaked by a client-side bug, no second
 * authentication path to keep in step with the first, and no JSON of player data cached in a tab.
 * JavaScript is used for progressive enhancement only - the action forms work without it.
 *
 * <p>Every interpolated value goes through {@link Html#esc(String)}. The page bodies are built here
 * rather than in a template engine because a template engine is a dependency, and this module exists to
 * have none.
 */
final class Pages {

    private Pages() {
    }

    /** Navigational entries: path, label. */
    private static final String[][] NAV = {
            {"/", "Dashboard"},
            {"/players", "Players"},
            {"/banwave", "Ban waves"},
            {"/audit", "Audit"},
    };

    static String layout(String title, boolean authed, String active, String body, String csrf) {
        StringBuilder out = new StringBuilder(8192);
        out.append("<!DOCTYPE html>\n<html lang=\"en\" data-theme=\"dark\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                // The console has no business being framed, indexed or phone-home-referring.
                .append("<meta name=\"robots\" content=\"noindex, nofollow\">\n")
                .append("<title>").append(Html.esc(title)).append(" &middot; XRay AntiCheat</title>\n")
                .append("<link rel=\"stylesheet\" href=\"/assets/app.css\">\n")
                .append("</head>\n<body>\n");

        if (authed) {
            out.append("<header class=\"topbar\">\n")
                    .append("  <div class=\"brand\"><span class=\"brand__mark\"></span>")
                    .append("<span class=\"brand__name\">XRay <b>AntiCheat</b></span>")
                    .append("<span class=\"brand__sub\">admin panel</span></div>\n")
                    .append("  <nav class=\"nav\">\n");
            for (String[] entry : NAV) {
                boolean isActive = entry[0].equals(active);
                out.append("    <a class=\"nav__link").append(isActive ? " is-active" : "")
                        .append("\" href=\"").append(entry[0]).append("\">")
                        .append(Html.esc(entry[1])).append("</a>\n");
            }
            out.append("  </nav>\n")
                    .append("  <form class=\"logout\" method=\"post\" action=\"/logout\">\n")
                    .append("    <input type=\"hidden\" name=\"csrf\" value=\"").append(Html.esc(csrf)).append("\">\n")
                    .append("    <button class=\"btn btn--ghost\" type=\"submit\">Sign out</button>\n")
                    .append("  </form>\n")
                    .append("</header>\n");
        }

        out.append("<main class=\"page\">\n").append(body).append("</main>\n")
                .append("<div id=\"toast\" class=\"toast\" role=\"status\" aria-live=\"polite\"></div>\n")
                .append("<script src=\"/assets/app.js\" defer></script>\n")
                .append("</body>\n</html>\n");
        return out.toString();
    }

    static String login(String error, String notice) {
        StringBuilder body = new StringBuilder();
        body.append("<div class=\"login\">\n")
                .append("  <div class=\"login__card card\">\n")
                .append("    <div class=\"login__mark\"></div>\n")
                .append("    <h1 class=\"login__title\">XRay <b>AntiCheat</b></h1>\n")
                .append("    <p class=\"muted\">Administration panel</p>\n");
        if (notice != null) {
            body.append("    <div class=\"alert alert--info\">").append(Html.esc(notice)).append("</div>\n");
        }
        if (error != null) {
            body.append("    <div class=\"alert alert--error\">").append(Html.esc(error)).append("</div>\n");
        }
        body.append("    <form method=\"post\" action=\"/login\" class=\"stack\">\n")
                .append("      <label class=\"field\"><span>Username</span>")
                .append("<input type=\"text\" name=\"username\" autocomplete=\"username\" autofocus required></label>\n")
                .append("      <label class=\"field\"><span>Password</span>")
                .append("<input type=\"password\" name=\"password\" autocomplete=\"current-password\" required></label>\n")
                .append("      <button class=\"btn btn--primary btn--block\" type=\"submit\">Sign in</button>\n")
                .append("    </form>\n")
                .append("    <p class=\"login__foot muted\">This panel is bound to a local address. "
                        + "Failed attempts are rate-limited.</p>\n")
                .append("  </div>\n</div>\n");
        return layout("Sign in", false, null, body.toString(), "");
    }

    static String error(int status, String message) {
        String body = "<div class=\"empty card\">\n"
                + "  <div class=\"empty__code\">" + status + "</div>\n"
                + "  <p>" + Html.esc(message) + "</p>\n"
                + "  <a class=\"btn btn--ghost\" href=\"/\">Back to the dashboard</a>\n"
                + "</div>\n";
        return layout("Error " + status, true, null, body, "");
    }

    static String dashboard(WebData.Overview overview,
                            List<BanWaveCandidate> candidates,
                            List<ModeratorActionRepository.StoredAction> recentActions,
                            int activeSessions,
                            String csrf) {
        StringBuilder body = new StringBuilder();
        body.append("<section class=\"hero\">\n")
                .append("  <h1>Overview</h1>\n")
                .append("  <p class=\"muted\">Storage is <b>").append(Html.esc(overview.dialect()))
                .append("</b> at schema version <b>").append(overview.schemaVersion()).append("</b>.</p>\n")
                .append("</section>\n");

        body.append("<section class=\"grid grid--stats\">\n");
        stat(body, "Players tracked", String.valueOf(overview.trackedPlayers()),
                "most recent " + overview.trackedLimit() + " by last seen");
        stat(body, "Ban-wave candidates", String.valueOf(overview.candidates()),
                overview.candidates() == 0 ? "nothing awaiting review" : "awaiting review");
        stat(body, "World removals", String.valueOf(overview.worldRemovals()),
                "blocks recorded as player-mined");
        stat(body, "Last wave", overview.lastWaveAt() == null ? "never" : Html.ago(overview.lastWaveAt()),
                overview.recentWaves() + " wave(s) recorded");
        stat(body, "Active sessions", String.valueOf(activeSessions), "signed in to this panel");
        body.append("</section>\n");

        if (!candidates.isEmpty()) {
            body.append("<section class=\"card\">\n")
                    .append("  <div class=\"card__head\"><h2>Highest-scoring candidates</h2>")
                    .append("<a class=\"btn btn--ghost\" href=\"/banwave\">Review all</a></div>\n")
                    .append("  <table class=\"table\">\n")
                    .append("    <thead><tr><th>Player</th><th>Strength</th><th>Peak score</th>")
                    .append("<th>Confidence</th><th>Evidence</th><th></th></tr></thead>\n<tbody>\n");
            int shown = 0;
            for (BanWaveCandidate candidate : candidates) {
                if (shown++ >= 8) {
                    break;
                }
                body.append("      <tr>\n")
                        .append("        <td><a href=\"/players/").append(candidate.player().id()).append("\">")
                        .append(Html.esc(candidate.player().name())).append("</a></td>\n")
                        .append("        <td>").append(strengthBadge(candidate.peakStrength().name())).append("</td>\n")
                        .append("        <td>").append(scoreBar(candidate.peakSuspicionScore())).append("</td>\n")
                        .append("        <td>").append(Html.percent(candidate.peakConfidence())).append("</td>\n")
                        .append("        <td class=\"muted\">").append(Html.esc(truncate(candidate.evidenceSummary(), 90)))
                        .append("</td>\n")
                        .append("        <td class=\"right\"><button class=\"btn btn--ghost btn--sm\" ")
                        .append("data-copy=\"").append(Html.esc(candidate.player().id().toString())).append("\">")
                        .append("Copy UUID</button></td>\n")
                        .append("      </tr>\n");
            }
            body.append("    </tbody>\n  </table>\n</section>\n");
        }

        body.append("<section class=\"card\">\n")
                .append("  <div class=\"card__head\"><h2>Recent moderation activity</h2>")
                .append("<a class=\"btn btn--ghost\" href=\"/audit\">Full audit trail</a></div>\n");
        if (recentActions.isEmpty()) {
            body.append("  <p class=\"empty\">Nothing has been moderated yet.</p>\n");
        } else {
            body.append("  <ul class=\"timeline\">\n");
            for (ModeratorActionRepository.StoredAction action : recentActions) {
                body.append("    <li class=\"timeline__item\">\n")
                        .append("      <span class=\"timeline__dot\"></span>\n")
                        .append("      <div><b>").append(Html.esc(action.action())).append("</b> ")
                        .append("<a class=\"muted\" href=\"/players/").append(action.playerId()).append("\">")
                        .append(action.playerId().toString(), 0, 8).append("&hellip;</a> ")
                        .append("<span class=\"muted\">").append(Html.ago(action.performedAt())).append("</span>");
                if (action.note() != null && !action.note().isBlank()) {
                    body.append("<div class=\"muted\">").append(Html.esc(action.note())).append("</div>");
                }
                body.append("</div></li>\n");
            }
            body.append("  </ul>\n");
        }
        body.append("</section>\n");

        return layout("Dashboard", true, "/", body.toString(), csrf);
    }

    static String players(List<WebData.PlayerRow> rows,
                          int page,
                          int pageSize,
                          boolean hasNext,
                          String filter,
                          String csrf) {
        StringBuilder body = new StringBuilder();
        body.append("<section class=\"hero\">\n  <h1>Players</h1>\n")
                .append("  <p class=\"muted\">Ordered by most recently seen. ")
                .append("Scores come from the latest stored assessment; a dash means the engine has not produced one.</p>\n")
                .append("</section>\n");

        body.append("<form class=\"toolbar\" method=\"get\" action=\"/players\">\n")
                .append("  <input class=\"input\" type=\"search\" name=\"q\" placeholder=\"Filter by name or UUID\" ")
                .append("value=\"").append(Html.esc(filter)).append("\">\n")
                .append("  <button class=\"btn btn--primary\" type=\"submit\">Filter</button>\n")
                .append("</form>\n");

        if (rows.isEmpty()) {
            body.append("<div class=\"empty card\"><p>No players match.</p></div>\n");
        } else {
            body.append("<section class=\"card\">\n  <table class=\"table table--players\">\n")
                    .append("    <thead><tr><th>Player</th><th>Strength</th><th>Score</th>")
                    .append("<th>Confidence</th><th>Samples</th><th>Last evaluated</th><th>Last seen</th></tr></thead>\n")
                    .append("    <tbody>\n");
            for (WebData.PlayerRow row : rows) {
                body.append("      <tr>\n")
                        .append("        <td><a href=\"/players/").append(row.id()).append("\" class=\"player\">")
                        .append("<span class=\"avatar\">").append(initials(row.name())).append("</span>")
                        .append("<span class=\"player__name\">").append(Html.esc(row.name() == null ? "unknown" : row.name()))
                        .append("</span></a></td>\n")
                        .append("        <td>").append(row.strength() == null ? minorDash() : strengthBadge(row.strength())).append("</td>\n")
                        .append("        <td>").append(scoreBar(row.score())).append("</td>\n")
                        .append("        <td>").append(row.latest() == null ? minorDash() : Html.percent(row.latest().statisticalConfidence())).append("</td>\n")
                        .append("        <td>").append(row.sampleSize() == 0 ? minorDash() : String.valueOf(row.sampleSize())).append("</td>\n")
                        .append("        <td>").append(row.latest() == null ? minorDash() : Html.ago(row.latest().evaluatedAt())).append("</td>\n")
                        .append("        <td>").append(Html.ago(row.lastSeen())).append("</td>\n")
                        .append("      </tr>\n");
            }
            body.append("    </tbody>\n  </table>\n")
                    .append("  <div class=\"pager\">\n");
            if (page > 1) {
                body.append("    <a class=\"btn btn--ghost\" href=\"/players?page=").append(page - 1)
                        .append(filterQuery(filter)).append("\">Previous</a>\n");
            } else {
                body.append("    <span class=\"btn btn--ghost is-disabled\">Previous</span>\n");
            }
            body.append("    <span class=\"pager__label\">page ").append(page).append("</span>\n");
            if (hasNext) {
                body.append("    <a class=\"btn btn--ghost\" href=\"/players?page=").append(page + 1)
                        .append(filterQuery(filter)).append("\">Next</a>\n");
            } else {
                body.append("    <span class=\"btn btn--ghost is-disabled\">Next</span>\n");
            }
            body.append("  </div>\n</section>\n");
        }
        return layout("Players", true, "/players", body.toString(), csrf);
    }

    static String player(WebData.PlayerRow row,
                         List<SuspicionSnapshot> history,
                         List<OreDiscoveryRepository.StoredDiscovery> discoveries,
                         List<ModeratorActionRepository.StoredAction> actions,
                         List<Map.Entry<String, Integer>> tally,
                         String windowLabel,
                         boolean online,
                         boolean readOnly,
                         List<String> actionErrors,
                         String csrf) {
        StringBuilder body = new StringBuilder();
        body.append("<section class=\"hero hero--player\">\n")
                .append("  <span class=\"avatar avatar--lg\">").append(initials(row.name())).append("</span>\n")
                .append("  <div>\n    <h1>").append(Html.esc(row.name() == null ? "unknown" : row.name()))
                .append(" <span class=\"pill ").append(online ? "pill--on" : "pill--off").append("\">")
                .append(online ? "online" : "offline").append("</span></h1>\n")
                .append("    <p class=\"muted mono\">").append(row.id()).append("</p>\n")
                .append("    <p class=\"muted\">First seen ").append(Html.time(row.firstSeen()))
                .append(" &middot; last seen ").append(Html.time(row.lastSeen())).append("</p>\n")
                .append("  </div>\n</section>\n");

        for (String error : actionErrors) {
            body.append("<div class=\"alert alert--error\">").append(Html.esc(error)).append("</div>\n");
        }

        body.append("<section class=\"grid grid--stats\">\n");
        if (row.latest() == null) {
            stat(body, "Suspicion", "&mdash;", "no stored assessment");
            stat(body, "Strength", "&mdash;", "not yet evaluated");
            stat(body, "Samples", "0", "nothing to conclude from");
        } else {
            SuspicionSnapshot latest = row.latest();
            stat(body, "Suspicion", Html.percent(latest.suspicionScore()), "posterior probability");
            stat(body, "Strength", latest.evidenceStrength().name(), "band from the evidence engine");
            stat(body, "Confidence", Html.percent(latest.statisticalConfidence()), "statistical confidence");
            stat(body, "Samples", String.valueOf(latest.sampleSize()),
                    latest.independentGroups() + " independent signal group(s)");
        }
        stat(body, "Ore discoveries", String.valueOf(discoveries.size()), "within the last " + windowLabel);
        body.append("</section>\n");

        if (row.latest() != null) {
            body.append("<section class=\"card\">\n")
                    .append("  <div class=\"card__head\"><h2>Why this assessment</h2></div>\n")
                    .append("  <p class=\"muted\">Each component contributes a log-likelihood ratio. ")
                    .append("The engine requires both a minimum sample size and a minimum number of independent ")
                    .append("groups before any verdict, so a single strong signal cannot decide on its own.</p>\n")
                    .append("  <table class=\"table\">\n")
                    .append("    <thead><tr><th>Component</th><th>Group</th><th>Log LR</th><th>Samples</th>")
                    .append("<th>Reliability</th><th>Explanation</th></tr></thead>\n    <tbody>\n");
            for (EvidenceContribution contribution : row.latest().contributions()) {
                body.append("      <tr>\n")
                        .append("        <td class=\"mono\">").append(Html.esc(contribution.componentId())).append("</td>\n")
                        .append("        <td class=\"mono\">").append(Html.esc(contribution.independentGroup())).append("</td>\n")
                        .append("        <td class=\"num\">").append(Html.decimal(contribution.logLikelihoodRatio(), 3)).append("</td>\n")
                        .append("        <td class=\"num\">").append(contribution.sampleSize()).append("</td>\n")
                        .append("        <td class=\"num\">").append(Html.decimal(contribution.reliability(), 3)).append("</td>\n")
                        .append("        <td class=\"muted\">").append(Html.esc(contribution.explanation())).append("</td>\n")
                        .append("      </tr>\n");
            }
            body.append("    </tbody>\n  </table>\n</section>\n");
        }

        if (!tally.isEmpty()) {
            body.append("<section class=\"card\">\n")
                    .append("  <div class=\"card__head\"><h2>Discovered by ore</h2></div>\n")
                    .append("  <div class=\"chips\">\n");
            int max = tally.get(0).getValue();
            for (Map.Entry<String, Integer> entry : tally) {
                int width = max == 0 ? 0 : (int) Math.round(entry.getValue() * 100.0 / max);
                body.append("    <div class=\"chip\" style=\"--fill:").append(width).append("%\">")
                        .append("<span class=\"chip__ore\">").append(Html.esc(entry.getKey())).append("</span>")
                        .append("<span class=\"chip__bar\"><i></i></span>")
                        .append("<span class=\"chip__n\">").append(entry.getValue()).append("</span></div>\n");
            }
            body.append("  </div>\n</section>\n");
        }

        body.append("<section class=\"card\">\n")
                .append("  <div class=\"card__head\"><h2>Recent discoveries</h2></div>\n");
        if (discoveries.isEmpty()) {
            body.append("  <p class=\"empty\">No discoveries in the display window.</p>\n");
        } else {
            body.append("  <table class=\"table\">\n")
                    .append("    <thead><tr><th>Ore</th><th>Block</th><th>Exposure</th><th>Vein</th>")
                    .append("<th>Hidden</th><th>When</th></tr></thead>\n    <tbody>\n");
            for (OreDiscoveryRepository.StoredDiscovery stored : discoveries) {
                OreDiscovery discovery = stored.discovery();
                body.append("      <tr>\n")
                        .append("        <td>").append(Html.esc(discovery.oreId())).append("</td>\n")
                        .append("        <td class=\"mono\">").append(discovery.discoveryBlock().x()).append(", ")
                        .append(discovery.discoveryBlock().y()).append(", ")
                        .append(discovery.discoveryBlock().z()).append("</td>\n")
                        .append("        <td>").append(exposureBadge(discovery.discoveryExposure().name())).append("</td>\n")
                        .append("        <td class=\"num\">").append(discovery.veinSize()).append("</td>\n")
                        .append("        <td class=\"num\">").append(discovery.hiddenVeinBlocks()).append(" / ")
                        .append(discovery.hiddenVeinBlocks() + discovery.exposedVeinBlocks()).append("</td>\n")
                        .append("        <td>").append(Html.ago(discovery.time())).append("</td>\n")
                        .append("      </tr>\n");
            }
            body.append("    </tbody>\n  </table>\n");
        }
        body.append("</section>\n");

        body.append("<section class=\"card\">\n")
                .append("  <div class=\"card__head\"><h2>Assessment history</h2></div>\n");
        if (history.isEmpty()) {
            body.append("  <p class=\"empty\">No stored assessments.</p>\n");
        } else {
            body.append("  <table class=\"table\">\n")
                    .append("    <thead><tr><th>When</th><th>World</th><th>Strength</th><th>Score</th>")
                    .append("<th>Confidence</th><th>Samples</th><th>Decay</th></tr></thead>\n    <tbody>\n");
            for (SuspicionSnapshot snapshot : history) {
                body.append("      <tr>\n")
                        .append("        <td>").append(Html.time(snapshot.evaluatedAt())).append("</td>\n")
                        .append("        <td class=\"mono\">").append(Html.esc(snapshot.world().key())).append("</td>\n")
                        .append("        <td>").append(strengthBadge(snapshot.evidenceStrength().name())).append("</td>\n")
                        .append("        <td>").append(scoreBar(snapshot.suspicionScore())).append("</td>\n")
                        .append("        <td class=\"num\">").append(Html.percent(snapshot.statisticalConfidence())).append("</td>\n")
                        .append("        <td class=\"num\">").append(snapshot.sampleSize()).append("</td>\n")
                        .append("        <td class=\"num\">").append(Html.decimal(snapshot.decayFactor(), 3)).append("</td>\n")
                        .append("      </tr>\n");
            }
            body.append("    </tbody>\n  </table>\n");
        }
        body.append("</section>\n");

        body.append("<section class=\"card\">\n")
                .append("  <div class=\"card__head\"><h2>Moderation</h2>");
        if (readOnly) {
            body.append("<span class=\"pill pill--warn\">read-only mode</span>");
        }
        body.append("</div>\n");

        if (readOnly) {
            body.append("  <p class=\"empty\">Moderation is disabled because <code>read-only</code> is set. ")
                    .append("The audit trail below is still recorded for inspection.</p>\n");
        } else {
            body.append("  <div class=\"actions\">\n")
                    .append("    <form class=\"action\" method=\"post\" action=\"/players/").append(row.id()).append("/action\">\n")
                    .append("      <input type=\"hidden\" name=\"csrf\" value=\"").append(Html.esc(csrf)).append("\">\n")
                    .append("      <input type=\"hidden\" name=\"action\" value=\"message\">\n")
                    .append("      <label class=\"field field--inline\"><span>Message</span>")
                    .append("<input type=\"text\" name=\"reason\" maxlength=\"220\" placeholder=\"Sent to the player in game\"></label>\n")
                    .append("      <button class=\"btn btn--ghost\" type=\"submit\">Send</button>\n")
                    .append("    </form>\n")
                    .append("    <form class=\"action\" method=\"post\" action=\"/players/").append(row.id()).append("/action\">\n")
                    .append("      <input type=\"hidden\" name=\"csrf\" value=\"").append(Html.esc(csrf)).append("\">\n")
                    .append("      <input type=\"hidden\" name=\"action\" value=\"note\">\n")
                    .append("      <label class=\"field field--inline\"><span>Note</span>")
                    .append("<input type=\"text\" name=\"reason\" maxlength=\"220\" placeholder=\"Recorded in the audit trail\"></label>\n")
                    .append("      <button class=\"btn btn--ghost\" type=\"submit\">Save note</button>\n")
                    .append("    </form>\n")
                    .append("    <form class=\"action action--danger\" method=\"post\" action=\"/players/").append(row.id()).append("/action\">\n")
                    .append("      <input type=\"hidden\" name=\"csrf\" value=\"").append(Html.esc(csrf)).append("\">\n")
                    .append("      <input type=\"hidden\" name=\"action\" value=\"kick\">\n")
                    .append("      <label class=\"field field--inline\"><span>Kick reason</span>")
                    .append("<input type=\"text\" name=\"reason\" maxlength=\"220\" placeholder=\"Shown to the player\"></label>\n")
                    .append("      <button class=\"btn btn--danger\" type=\"submit\" data-confirm=\"Kick this player now?\">Kick</button>\n")
                    .append("    </form>\n")
                    .append("    <form class=\"action action--danger\" method=\"post\" action=\"/players/").append(row.id()).append("/action\">\n")
                    .append("      <input type=\"hidden\" name=\"csrf\" value=\"").append(Html.esc(csrf)).append("\">\n")
                    .append("      <input type=\"hidden\" name=\"action\" value=\"ban\">\n")
                    .append("      <label class=\"field field--inline\"><span>Ban reason</span>")
                    .append("<input type=\"text\" name=\"reason\" maxlength=\"220\" placeholder=\"Shown to the player\"></label>\n")
                    .append("      <button class=\"btn btn--danger\" type=\"submit\" data-confirm=\"Ban this player? This is recorded in the audit trail.\">Ban</button>\n")
                    .append("    </form>\n")
                    .append("  </div>\n");
        }

        if (actions.isEmpty()) {
            body.append("  <p class=\"empty\">No moderation actions recorded for this player.</p>\n");
        } else {
            body.append("  <ul class=\"timeline\">\n");
            for (ModeratorActionRepository.StoredAction action : actions) {
                body.append("    <li class=\"timeline__item\"><span class=\"timeline__dot\"></span><div>")
                        .append("<b>").append(Html.esc(action.action())).append("</b> ")
                        .append("<span class=\"muted\">").append(Html.time(action.performedAt())).append("</span>");
                if (action.note() != null && !action.note().isBlank()) {
                    body.append("<div class=\"muted\">").append(Html.esc(action.note())).append("</div>");
                }
                body.append("</div></li>\n");
            }
            body.append("  </ul>\n");
        }
        body.append("</section>\n");

        return layout("Player " + (row.name() == null ? row.id().toString() : row.name()),
                true, "/players", body.toString(), csrf);
    }

    static String banWave(List<BanWaveCandidate> candidates,
                          List<BanWaveRepository.WaveRecord> waves,
                          String notice,
                          String csrf) {
        StringBuilder body = new StringBuilder();
        body.append("<section class=\"hero\">\n  <h1>Ban waves</h1>\n")
                .append("  <p class=\"muted\">Candidates accumulate as assessments cross the configured strength ")
                .append("threshold. Execution stays a separate, explicit decision.</p>\n</section>\n");
        if (notice != null) {
            body.append("<div class=\"alert alert--info\">").append(Html.esc(notice)).append("</div>\n");
        }

        body.append("<section class=\"card\">\n  <div class=\"card__head\"><h2>Candidates (")
                .append(candidates.size()).append(")</h2></div>\n");
        if (candidates.isEmpty()) {
            body.append("  <p class=\"empty\">No candidates are being held.</p>\n");
        } else {
            body.append("  <table class=\"table\">\n")
                    .append("    <thead><tr><th>Player</th><th>World</th><th>Strength</th><th>Peak score</th>")
                    .append("<th>Confidence</th><th>Samples</th><th>First detected</th><th>Last detected</th><th></th></tr></thead>\n")
                    .append("    <tbody>\n");
            for (BanWaveCandidate candidate : candidates) {
                body.append("      <tr>\n")
                        .append("        <td><a href=\"/players/").append(candidate.player().id()).append("\">")
                        .append(Html.esc(candidate.player().name())).append("</a></td>\n")
                        .append("        <td class=\"mono\">").append(Html.esc(candidate.world().key())).append("</td>\n")
                        .append("        <td>").append(strengthBadge(candidate.peakStrength().name())).append("</td>\n")
                        .append("        <td>").append(scoreBar(candidate.peakSuspicionScore())).append("</td>\n")
                        .append("        <td class=\"num\">").append(Html.percent(candidate.peakConfidence())).append("</td>\n")
                        .append("        <td class=\"num\">").append(candidate.sampleSize()).append("</td>\n")
                        .append("        <td>").append(Html.time(candidate.firstDetected())).append("</td>\n")
                        .append("        <td>").append(Html.time(candidate.lastDetected())).append("</td>\n")
                        .append("        <td class=\"right\">\n")
                        .append("          <form method=\"post\" action=\"/banwave/dismiss\">\n")
                        .append("            <input type=\"hidden\" name=\"csrf\" value=\"").append(Html.esc(csrf)).append("\">\n")
                        .append("            <input type=\"hidden\" name=\"player\" value=\"").append(candidate.player().id()).append("\">\n")
                        .append("            <button class=\"btn btn--ghost btn--sm\" type=\"submit\" ")
                        .append("data-confirm=\"Remove this candidate from the wave? The evidence stays in the database.\">")
                        .append("Dismiss</button>\n")
                        .append("          </form>\n")
                        .append("        </td>\n")
                        .append("      </tr>\n");
            }
            body.append("    </tbody>\n  </table>\n");
        }
        body.append("</section>\n");

        body.append("<section class=\"card\">\n  <div class=\"card__head\"><h2>Past waves</h2></div>\n");
        if (waves.isEmpty()) {
            body.append("  <p class=\"empty\">No waves have been executed.</p>\n");
        } else {
            body.append("  <table class=\"table\">\n")
                    .append("    <thead><tr><th>Planned</th><th>Executed</th><th>Candidates</th>")
                    .append("<th>Automatic</th><th>Summary</th></tr></thead>\n    <tbody>\n");
            for (BanWaveRepository.WaveRecord wave : waves) {
                body.append("      <tr>\n")
                        .append("        <td>").append(Html.time(wave.plannedAt())).append("</td>\n")
                        .append("        <td>").append(Html.time(wave.executedAt())).append("</td>\n")
                        .append("        <td class=\"num\">").append(wave.candidateCount()).append("</td>\n")
                        .append("        <td>").append(wave.automaticBan() ? "yes" : "no").append("</td>\n")
                        .append("        <td class=\"muted\">").append(Html.esc(truncate(wave.summary(), 120))).append("</td>\n")
                        .append("      </tr>\n");
            }
            body.append("    </tbody>\n  </table>\n");
        }
        body.append("</section>\n");

        return layout("Ban waves", true, "/banwave", body.toString(), csrf);
    }

    static String audit(List<ModeratorActionRepository.StoredAction> actions, String csrf) {
        StringBuilder body = new StringBuilder();
        body.append("<section class=\"hero\">\n  <h1>Audit trail</h1>\n")
                .append("  <p class=\"muted\">Every moderation action and panel note, newest first. ")
                .append("Entries recorded through this panel are attributed to the panel, not to a player account.</p>\n</section>\n");
        body.append("<section class=\"card\">\n");
        if (actions.isEmpty()) {
            body.append("  <p class=\"empty\">Nothing recorded yet.</p>\n");
        } else {
            body.append("  <table class=\"table\">\n")
                    .append("    <thead><tr><th>When</th><th>Action</th><th>Player</th><th>Moderator</th><th>Note</th></tr></thead>\n")
                    .append("    <tbody>\n");
            for (ModeratorActionRepository.StoredAction action : actions) {
                body.append("      <tr>\n")
                        .append("        <td>").append(Html.time(action.performedAt())).append("</td>\n")
                        .append("        <td><b>").append(Html.esc(action.action())).append("</b></td>\n")
                        .append("        <td><a class=\"mono\" href=\"/players/").append(action.playerId()).append("\">")
                        .append(Html.esc(shortId(action.playerId()))).append("</a></td>\n")
                        .append("        <td class=\"mono\">").append(action.moderatorId() == null
                                ? "<span class=\"muted\">automatic</span>"
                                : Html.esc(shortId(action.moderatorId()))).append("</td>\n")
                        .append("        <td class=\"muted\">").append(Html.esc(action.note())).append("</td>\n")
                        .append("      </tr>\n");
            }
            body.append("    </tbody>\n  </table>\n");
        }
        body.append("</section>\n");
        return layout("Audit", true, "/audit", body.toString(), csrf);
    }

    // ---------------------------------------------------------------------------------------------
    // Fragments
    // ---------------------------------------------------------------------------------------------

    private static void stat(StringBuilder out, String label, String value, String sub) {
        out.append("  <div class=\"stat card\">\n")
                .append("    <div class=\"stat__label\">").append(Html.esc(label)).append("</div>\n")
                .append("    <div class=\"stat__value\">").append(value).append("</div>\n")
                .append("    <div class=\"stat__sub muted\">").append(Html.esc(sub)).append("</div>\n")
                .append("  </div>\n");
    }

    /**
     * A strength badge.
     *
     * <p>The class is derived from a fixed mapping rather than from the value, so a value the stylesheet
     * does not know cannot inject anything into the class attribute.
     */
    static String strengthBadge(String strength) {
        String modifier = switch (strength == null ? "" : strength) {
            case "VERY_STRONG" -> "badge--very-strong";
            case "STRONG" -> "badge--strong";
            case "MODERATE" -> "badge--moderate";
            case "WEAK" -> "badge--weak";
            case "INSUFFICIENT" -> "badge--insufficient";
            default -> "badge--unknown";
        };
        return "<span class=\"badge " + modifier + "\">"
                + Html.esc(strength == null ? "none" : strength.replace('_', ' ')) + "</span>";
    }

    static String exposureBadge(String exposure) {
        String modifier = switch (exposure == null ? "" : exposure) {
            case "HIDDEN" -> "badge--very-strong";
            case "CONDITIONALLY_EXPOSED" -> "badge--moderate";
            case "PARTIALLY_EXPOSED" -> "badge--weak";
            case "EXPOSED" -> "badge--insufficient";
            case "UNKNOWN" -> "badge--unknown";
            default -> "badge--unknown";
        };
        return "<span class=\"badge " + modifier + "\">"
                + Html.esc(exposure == null ? "unknown" : exposure.replace('_', ' ').toLowerCase())
                + "</span>";
    }

    /** A score rendered as a small bar plus its percentage. */
    static String scoreBar(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return minorDash();
        }
        int width = (int) Math.round(Math.max(0, Math.min(1, score)) * 100);
        String tone = score >= 0.9 ? "is-high" : score >= 0.6 ? "is-mid" : "is-low";
        return "<span class=\"score " + tone + "\"><span class=\"score__bar\"><i style=\"width:"
                + width + "%\"></i></span><span class=\"score__n\">" + Html.percent(score)
                + "</span></span>";
    }

    private static String minorDash() {
        return "<span class=\"muted\">&mdash;</span>";
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        return Html.esc(name.substring(0, 1).toUpperCase());
    }

    private static String shortId(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8) + "\u2026";
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
    }

    private static String filterQuery(String filter) {
        return filter == null || filter.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(filter, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The display window used by the player page, exposed so the page can state it. */
    static String windowLabel(Duration window) {
        long days = window.toDays();
        return days > 0 ? days + " days" : window.toHours() + " hours";
    }
}
