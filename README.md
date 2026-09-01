# Call Assist

Android app that auto-opens the condo gate when your building's **intercom**
calls — plus a full replacement dialer (T9 keypad, recents, contacts) since it
runs as the default phone app.

When a call arrives from the configured contact, the app:

1. Auto-answers the call and mutes the mic (v1.3).
2. ~1.2 s after connect, sends DTMF `6` then `#`, 1 s apart (DTMF is
   modem-injected, so muting doesn't affect it).
3. Waits 5 s. If the intercom already hung up (gate opened), it's done.
4. Otherwise re-sends `6` `#` one second apart, then hangs up ~3.5 s later.
5. Failsafe: the call is force-ended after 30 s no matter what.

Everything runs fully in the background (screen can be off/locked) because it is
implemented as an Android `InCallService` — the same mechanism the built-in Phone
app uses. That's also the catch: **the app must be set as the default phone
app** — it's the only way Android lets an app answer calls *and* send DTMF
tones programmatically.

v1.2: dark theme (black bg, dark cards, blue accent),
confirmation dialog before every outgoing call, and a default-SIM setting
(System default / Ask every time / specific SIM) in the Gate tab's Calling card.

v2.3: the call confirmation dialog is a glass panel matching the floating nav
bar — same translucent surface and hairline edge, 32dp corners, contact avatar,
and full-width Cancel / green Call pill buttons. The rest of the app's dialogs
(SIM chooser, settings pickers, block confirmations) pick up the same rounded
glass treatment via the theme.

v3.23: dual-SIM aware. On a phone with two active SIMs a SIM chip sits beside
the dial button showing which SIM will be used (tap it to switch, ask every
time, or follow the phone's setting); the call confirmation dialog gets a
SIM 1 | SIM 2 switcher for one-off overrides; the in-call screen and call
notification show which SIM the call is on; and Recents badges every entry
with its SIM. All of it hides itself on single-SIM phones.

v3.24: crash reporting. If the app ever closes unexpectedly, Settings gains a
red "Last crash report" row with the full stack trace and a Copy button.

v3.25: fixed a crash when a call was hung up within the first second or two —
Android 14+ rejected the call notification once telecom no longer saw the
call, and that exception took the whole app down.

## Dialer features (v1.1)

Four tabs: **Keypad** (T9 search over contact names + number matching, key
tones, long-press 0 for `+`, long-press 1 for voicemail), **Recents** (call log
with in/out/missed markers and relative times, tap to redial), **Contacts**
(search, starred first, multi-number chooser), and **Gate** (automation
settings + event log). In-call screen: avatar, call timer, answer/decline,
mute, speaker, DTMF keypad, end call.

## Install & setup

1. Copy `CallAssist-v1.1.apk` to the phone and install (allow "install unknown
   apps" for your file manager; upgrades install straight over old versions).
2. Open **Call Assist**:
   - Tap **Grant permissions** → allow Contacts, Phone, Notifications.
   - Tap **Set as default phone app** → choose Call Assist.
   - Check the contact name matches your intercom contact exactly
     (default: `Intercom`; a phone number also works). Gate code
     defaults to `6#`.
3. Leave "Gate automation enabled" on. Done — next intercom call opens the gate
   automatically. The **Event log** on the main screen shows what happened on
   each call: one scrollable row per gate run (v2.3), grouped under day headers
   like Recents, stating the outcome up front — *Gate opened* (green) or *Gate
   may not have opened* (red). Tap a row to expand the step-by-step timeline.

To go back to normal: Settings → Apps → Choose default apps → Phone app →
your previous phone app (or flip the automation switch off to keep the app but disable
the gate behavior).

## Build

```bash
# needs JDK 17 + Android SDK (platform 34, build-tools 34.0.0)
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle assembleRelease
```

Release signing reads `keystore.properties` in the project root (gitignored),
with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it the
release build is left unsigned.

## Code map

- `AutomationEngine.kt` — answer + DTMF + retry + hangup state machine (all timings are constants at the top)
- `CallService.kt` — `InCallService`; routes intercom calls to the engine, others to the UI
- `InCallActivity.kt` — in-call screen for normal calls (avatar, timer, controls)
- `MainActivity.kt` — tabbed UI: T9 keypad, recents, contacts, gate settings
- `ContactsRepo.kt` — contact/T9 index + call-log loading
- `RowAdapter.kt` — shared list adapter + avatar colors
