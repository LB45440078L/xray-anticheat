# Discord notifications

This page covers two related settings: sending alerts to a Discord channel, and replacing the
plugin's built-in kick and ban with commands of your own.

The Discord feature exists for one reason. The plugin's alerts are in-game chat messages, so they
only help somebody who is online. On a server where moderators sleep, an X-ray user has the whole
night to work. A webhook is a way to reach a phone.

---

## 1. Create the webhook in Discord

You need the **Manage Webhooks** permission on the channel, which most administrators have.

1. Open Discord and find the channel you want the alerts in. A dedicated channel such as
   `#xray-alerts` is worth creating, because these messages are technical and a busy general
   channel will bury them.
2. Right-click the channel name and choose **Edit Channel**.
3. Open the **Integrations** tab, then click **Webhooks**.
4. Click **New Webhook**. Discord creates one with a default name and, importantly, a default
   channel: the one you opened. If you opened the wrong channel, change **Channel** in the
   dropdown.
5. Give it a recognisable name. This is only used if you leave `username` empty in the config.
6. Click **Copy Webhook URL**. The result looks like

   ```
   https://discord.com/api/webhooks/1234567890/AbCdEfGh...
   ```

   If you have never done this and see no **Webhooks** option, you may be in a DM or in a channel
   category rather than a text channel. Webhooks belong to text channels.

## 2. Put the URL in `config.yml`

Find the `alerts` section. It is not far below `ban-wave`.

```yaml
alerts:
  throttle-minutes: 5

  discord:
    enabled: true
    webhook-url: "https://discord.com/api/webhooks/1234567890/AbCdEfGh..."
    username: "XRay AntiCheat"
    mention-role-id: ""
    events:
      - ALERT
      - KICK
      - BAN
      - BAN_WAVE
    minimum-strength: MODERATE
    minimum-confidence: 0.5
    timeout-seconds: 5
```

Then either restart the server or run `/xray reload`. The console will tell you if the
configuration was rejected.

The URL **must** start with `https://`. A plain `http://` address is refused rather than warned
about, because the webhook is a credential and would otherwise be sent in clear text.

## 3. Choose what gets posted

`events` decides which happenings reach the channel:

| Value | Posted when |
| --- | --- |
| `ALERT` | Evidence crossed the alert threshold. Nothing has happened to the player yet. |
| `KICK` | A player was disconnected, automatically or by a moderator. |
| `BAN` | A single player was banned. |
| `BAN_WAVE` | A ban wave ran, with one message per player and a summary at the end. |

Removing a value silences that event without touching anything else. Listing no events at all is
treated as listing all of them, since the alternative reading — enable the feature, select
nothing, receive nothing — is a configuration that looks right and does nothing.

The two thresholds work exactly as their in-game counterparts do, and both must be satisfied:

- `minimum-strength` is the weakest evidence band worth sending. Bands, weakest to strongest:
  `INSUFFICIENT`, `WEAK`, `MODERATE`, `STRONG`, `VERY_STRONG`.
- `minimum-confidence` is how far the sample behind that band must support it, from `0` to `1`.

The defaults send moderate evidence with at least half the possible confidence. Raising
`minimum-strength` to `STRONG` is the usual choice once you trust the model, and it keeps the
channel to cases worth a moderator's attention.

## 4. Optional: ping a role

A muted channel still shows a role mention, which is how you reach somebody who is not watching.

1. In Discord, open **User Settings**, then **Advanced**, and turn on **Developer Mode**.
2. Right-click the role you want pinged and choose **Copy Role ID**. This gives you a long
   number, not the role's name.
3. Put that number in `mention-role-id`, keeping the quotes:

   ```yaml
   mention-role-id: "1122334455667788990"
   ```

Leave it empty for no ping. The role is mentioned once per message, which is why `throttle-minutes`
matters as much as it does below.

## 5. Check that it works

There is no separate test command, and that is deliberate: a "send a test message" button in a
plugin is usually how a channel gets accidentally spammed during setup. The quickest real check is
to lower the thresholds for one session:

```yaml
    minimum-strength: WEAK
    minimum-confidence: 0.0
```

Then `/xray reload` and play normally. Any alert that reaches staff chat is also posted. Put the
thresholds back afterwards.

If nothing arrives, see **Troubleshooting** below.

---

## The URL is a credential

Anyone holding that URL can post to your channel, with any name and any message. Treat it the way
you would treat a password:

- Do not paste it into a public place, a screenshot, or a support thread. If you need help with
  it, describe the problem and redact the URL.
- **Never commit it.** If you keep your server configuration in a Git repository, this line is the
  one that leaks. Use a private repository, or keep the URL out of the committed file.
- If it leaks, go back to **Integrations → Webhooks**, delete that webhook and create a new one.
  Deleting it invalidates the URL immediately; there is no grace period.
- The plugin will not follow an HTTP redirect from the webhook, so a hijacked or mistyped address
  cannot quietly forward your credential to a third party.

## What the messages look like

Alerts are sent as plain messages, not rich embeds, so that they render the same on every Discord
client and in notifications. They use Discord's own `**bold**` markdown, and the colour codes from
`messages.yml` are stripped before sending, because a raw section sign in a channel reads as a
rendering fault.

The wording comes from the `alerts.discord` section of `messages.yml`, so you can change it
without touching any code:

```yaml
alerts:
  discord:
    alert: "**%player%** — %strength% evidence (%confidence% confidence, %samples% observations, %signals% independent signals) in %world%. Score %score%, %decibans% dB. Evidence id %snapshot-id%."
```

A message longer than 2000 characters is truncated with a ` [...]` marker rather than rejected.
Discord enforces that ceiling with an error, so truncating preserves the notification; a long
evidence explanation accompanies the strongest cases, which is exactly when losing it would hurt.

## Throttling

`throttle-minutes` suppresses further alerts about the **same player** once one has been delivered.
Without it, a persistently flagged player produces a message on every analysis pass and buries the
channel in minutes.

Suppressed alerts are not discarded silently: the count is carried on the next alert, so a
moderator learns that forty assessments were made rather than one. Setting the value to `0`
disables throttling and sends everything.

---

## Troubleshooting

**Nothing appears in the channel.** Check the console log first. The plugin logs a warning for each
of the following:

- *The webhook URL is not usable* — the address is malformed, or `enabled` is true with an empty
  `webhook-url`.
- *Discord rejected the notification with HTTP 401 or 404* — the webhook was deleted or regenerated
  in Discord. Create a new one and update the URL.
- *Discord rejected the notification with HTTP 400* — the request was malformed, which usually means
  someone has broken the JSON by editing `messages.yml` with unbalanced quotes.
- *Could not reach Discord* — the server has no outbound network access, or a firewall blocks
  `discord.com`. See the note below.
- *Discord rate-limited the notification (HTTP 429)* — you are sending more than the channel
  accepts. Raise `throttle-minutes`, raise `minimum-strength`, or use a quieter channel.

**A warning about the URL not looking like a Discord webhook.** The post will still be attempted;
the warning exists because a URL pointing somewhere else sends your evidence somewhere you may not
have intended.

**The messages arrive, but no ping.** `mention-role-id` expects the numeric role ID, not the role
name. `@Moderators` does nothing; `1122334455667788990` works. Verify with Developer Mode as
described above.

**The server has no internet access.** The Discord feature cannot work through an air gap, and it
is off by default precisely so this costs you nothing. Everything else in the plugin continues to
work, including the ban wave and the administration panel.

---

## Related: replacing the kick and ban commands

Servers that already run a punishment system often do not want this plugin writing to the vanilla
ban list behind that system's back. The `enforcement.commands` section runs your own command
instead:

```yaml
enforcement:
  commands:
    kick: "kick %player% %reason%"
    ban: "networkban %player% 30d %reason%"
    ban-wave: ""
    alert: ""
  log-commands: false
```

Commands run as the console, so do not prefix them with a slash. The available placeholders are
`%player%`, `%uuid%`, `%world%`, `%reason%`, `%strength%`, `%confidence%`, `%score%`, `%samples%`,
`%signals%`, `%decibans%`, `%snapshot-id%` and `%moderator%`.

A misspelled placeholder is reported at startup rather than silently passed through, because the
failure is otherwise invisible: the command runs, a punishment is applied, and only later does
anybody notice the reason text came out wrong.

Two things happen whatever you configure:

- **The player is still disconnected.** A configured command might be a temporary ban, a
  network-wide ban issued to a proxy, or a command that failed quietly because a plugin changed
  its syntax. Leaving the player connected would mean the evidence threshold was crossed and
  nothing observable happened.
- **The plugin still writes its own audit row.** The reason a player was removed has to remain
  answerable even when the punishment itself is recorded somewhere else.

Set `log-commands: true` while trying a new template. It is the only way to see exactly what the
plugin dispatched.
