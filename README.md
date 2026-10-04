# Call Assist

An Android app that opens my building's gate when the intercom rings, without
me touching the phone, and a full replacement dialer built around it.

Many condo intercoms in Singapore call a resident's mobile, and the resident
presses a DTMF code (such as `6#`) to open the gate. Call Assist recognises the
intercom's call, answers it silently, enters the code, confirms the gate
accepted it, and hangs up, with the phone locked in a pocket. Because Android
only lets the default phone app answer calls and send tones, the app is also a
complete dialer: keypad, recents, contacts, in-call screen and call
notifications.

Written in Kotlin against the Android Telecom framework, with no third-party
libraries beyond AndroidX and Material Components. Supports Android 10 and up
(targets Android 15).

## Gate automation

When a call arrives from the configured intercom (matched by number, or by
contact name after a background lookup), `AutomationEngine` runs a small state
machine:

1. **Answer** the call, mute the microphone, route audio off the loudspeaker
   and drop the call volume to its floor, so nothing is heard.
2. **Wait ~1.2 s** for the audio path to settle, then send the gate code as
   DTMF tones, one digit per second.
3. **Listen.** When the gate accepts the code, the intercom hangs up by itself,
   and that counts as success.
4. **Retry** if the call is still connected after the configured wait (5 s by
   default), up to the configured number of attempts (3 by default). The last
   attempt gets an extra 5 s buffer.
5. **Clean up:** restore the previous mute state and call volume, resume any
   call that was put on hold when the intercom rang, and post an *Opened* or
   *Attempted* notification.

A failsafe hangup sized from the current settings guarantees the call never
stays open. Every run is recorded in an on-device **gate log**, grouped by day,
with the outcome first and a step-by-step timeline (answer, mute, send, wait,
retry, result) on tap.

### Reliability details

- **Timing under Doze.** `Handler` delays only count awake time, so a phone
  dozing mid-sequence once stretched a 5 s wait to 17–21 s and split a code's
  digits far enough apart for the gate to time out. The sequence now runs on
  its own `HandlerThread` under a partial wake lock sized to the failsafe, and
  the log reports measured rather than configured timings, so stalls show up.
- **Call waiting.** If the intercom rings during another call, that call is
  held, and it is resumed automatically when the intercom hangs up.
- **Role loss.** If another app takes the default-dialer role, the automation
  silently stops working, so the app checks for this at startup and posts a
  warning.
- **Isolation.** The intercom's call never reaches the UI code paths
  (notifications, call screen, proximity lock), so dialer changes can't
  regress the gate.

## Dialer

- **Keypad** with T9 contact search, highlighted matches, speed dial,
  cursor-editable input, paste, and an optional IDD prefix for international
  numbers opened from other apps.
- **Recents** grouped by day, with merged consecutive calls, search, type/SIM
  filters, swipe to call or message, multi-select delete, and expandable cards
  with full per-number history.
- **Contacts** with favourites, an A–Z fast scroller, inline expanding cards,
  per-contact SIM pinning, and blocking through the system blocked-numbers
  provider.
- **In-call screen** with answer/decline (slide to answer when locked), mute,
  keypad, speaker/Bluetooth route picker, hold, add call, swap/merge for call
  waiting, a proximity screen-off, and a power-button decline.
- **Call notifications** as a `phoneCall` foreground service, with live state,
  timer and actions, plus missed-call notifications that survive a reboot.
- **Dual SIM** support throughout: a SIM chip on the keypad, per-call override,
  and a SIM badge in Recents and in-call.
- **Design system:** light/dark themes, WCAG AA colour tokens, 48 dp touch
  targets, TalkBack labels, RTL mirroring, edge-to-edge insets, two-pane
  layouts on foldables and tablets, and a floating glass navigation bar with
  real cross-window blur on Android 12+.
- **Crash reporting:** uncaught exceptions are saved and shown in Settings,
  with Copy and Share buttons.

## Architecture

| File | Role |
| --- | --- |
| `CallService.kt` | The `InCallService` Telecom binds to. Routes the intercom to the engine and every other call to the UI; owns the call notification and proximity lock. |
| `AutomationEngine.kt` | The answer → DTMF → retry → hangup state machine, with all timings as constants at the top. |
| `OngoingCall.kt` | Observable holder for the foreground call, shared by the call screen and notification. |
| `InCallActivity.kt` | Incoming and in-call screen. |
| `Notifications.kt` | Call, missed-call and gate notifications. |
| `MainActivity.kt` | Tabbed dialer: keypad, recents, contacts and gate setup. |
| `ContactsRepo.kt` | Contact/T9 index and call-log loading, reloaded only when content observers fire. |
| `GateLog.kt` | Parses the automation log into per-run timelines. |
| `SimUtil.kt` | Phone-account and SIM resolution for dual-SIM devices. |
| `Glass*.kt`, `Sheet.kt` | Custom navigation bar, blurred dialogs and bottom sheets. |

## Build

```bash
# Requires JDK 17 and the Android SDK (platform 35, build-tools 34)
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle assembleRelease
```

Release signing reads `keystore.properties` in the project root (gitignored),
with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it the
release build is left unsigned.

## Setup

1. Install the APK and open **Call Assist**.
2. Grant the Contacts, Phone and Notifications permissions, then set Call
   Assist as the default phone app.
3. On the **Gate** tab, enter the intercom's contact name or number and the
   gate code (the default is `6#`). Adjust the wait and attempts if your gate is
   slow to respond.

To stop using the app, pick another default phone app in system settings, or
turn the automation off and keep the dialer.

See [CHANGELOG.md](CHANGELOG.md) for the full version history.
