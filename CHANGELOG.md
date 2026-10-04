# Changelog

## v3.60 — 2026-09-24

The "Opened" gate notification uses an open-lock icon instead of the handset
("Answering…" and "Attempted" keep the handset).

## v3.59 — 2026-09-24

Missed-call notifications survive a reboot, as Telecom's did. Each one is
recorded when posted and forgotten when tapped, swiped away, called back or
cleared by opening Recents; BOOT_COMPLETED re-posts whatever is left with its
original time.

## v3.58 — 2026-09-24

Declares a receiver for Telecom's show-missed-calls broadcast, so the system no
longer posts its own missed-call notification alongside ours.

## v3.57 — 2026-09-06

The collapsed incoming-call notification carries its own Decline and Answer
buttons (Samsung's shade hides CallStyle's until expanded, and Do not disturb
suppresses the heads-up that would have shown them). Expanded view and heads-up
banner stay the system's CallStyle.

## v3.56 — 2026-09-06

Call behaviour made consistent. One press of the power button now declines a
ringing call on the incoming screen (Samsung phones use the first press to
silence the ringer and keep the screen on, so the old screen-off hook only saw
the second press; telecom's onSilenceRinger is the first-press signal, ignored
when our own volume-key silence caused it). Answering from the heads-up banner
opens the call screen. A call-waiting ring while the call screen is showing no
longer stacks a heads-up banner over it.

## v3.55 — 2026-09-06

Fixed a crash on "Select calls" in the Recents overflow (the page root id is
replaced by the include's id, so the fade looked up a view that never existed);
the Recents and Contacts headers now sit on AppBarLayout so the header collapses
before the list scrolls (no more double-moving cards at the top), with Contacts
at 25% of the screen to make room for the pinned search field; long Settings
values ("System default (SingTel)") wrap to two lines instead of clipping.

## v3.54 — 2026-09-06

Extended app bar on Recents and Contacts (title centred in a header 36% of the
screen, collapsing to a 56dp toolbar whose small title fades in), single-weight
1.75dp outline icon set for the header, nav and list glyphs, every gap on the
4/8dp grid with 16dp SIM badges, yes/no confirmations (delete logs, block,
unblock, speed-dial assign) as glass dialogs with capsule buttons, call green
back to a vivid #1DA84E (white icons 3.1:1) and the caution amber vivid in dark
mode with a dark glyph, and a fix for the nav bar measuring full-height for a
frame at launch.

## v3.53 — 2026-09-06

Design-system pass from a full UI audit. Colour tokens split into fill vs text
so every control clears WCAG AA in both themes (call green #12873C, avatars,
chips, section titles); three circle tiers (48/56/72dp), one card radius (26dp)
and a nine-step type scale replace the one-offs; 48dp targets everywhere (header
actions, chips, in-call swap/merge, notification buttons); Recents rows gain
contact avatars and a call-type line; Settings and Blocked numbers get a back
button; one overflow menu per tab instead of a gear; cross-fades for tab, search
and in-call state changes; a decline button on the locked incoming screen; the
keypad auto-shrinks long numbers and rows drop to 72dp so a 360×640 screen keeps
its suggestions; speed dial on 2–9; portrait lock on phones; RTL mirroring;
TalkBack selection/expand state.

## v3.52 — 2026-09-03

Unanswered incoming call no longer flashes the in-call layout when it ends.

## v3.51 — 2026-09-03

Swiped Recents rows reset deterministically (helper re-attach ends the recover
animation); the proximity lock only ever engages for calls the UI owns, never
the intercom's; title collapse quantised to ~12 layouts; swipe icons mutated
once; Gate event log read off the main thread; keypad keys have proper TalkBack
names; search-highlight spans clamped to the name.

## v3.50 — 2026-09-03

Refine dialer and incoming SIM identity.

## v3.49 — 2026-09-03

Streamline dialer controls.

## v3.48 — 2026-09-03

Keypad and Gate polish — the empty number field no longer displays an oversized
placeholder (while retaining its TalkBack label), and the Auto-open and Notify
switches have enough drawing room for their rounded edges and are subtly scaled
down to prevent clipping.

## v3.47 — 2026-09-03

Reliability and accessibility release — multi-call teardown, held-call updates
and conference targeting are fixed; fold/unfold preserves navigation, search,
filters and expanded/selected rows; intent-triggered calls are safely confirmed;
emergency callbacks bypass unknown-number blocking; photo/contact work and
Recents construction move off the UI thread; TalkBack can operate the navigation
bar, contacts index and lock-screen answer control; and light-theme contrast and
cross-screen naming/spacing are consistent.

## v3.46 — 2026-09-02

Nav bar bubble is a pill around icon + label again (wider bar, 6dp insets), the
bar sits lower, and the Contacts A–Z column gets its own gutter.

## v3.45 — 2026-09-02

Hotfix — v3.43/3.44 crashed on launch (collapsing-title lookup ran on the list
instead of the activity and got null).

## v3.44 — 2026-09-02

Identity — gradient avatars, a new app icon (gradient background, shadowed
glyph, separate themed-icon layer) and a branded splash screen.

## v3.43 — 2026-09-02

Visual system — ripples masked to card corners, collapsing large titles on
Recents/Contacts, a single radius scale (22 cards / 28 panels / 14 inner, pills
round), hairline depth on cards, springy press feedback on pills and call tiles,
bottom-sheet drag handles, tabular figures for numbers/timers.

## v3.42 — 2026-09-02

Card expand/collapse animates the card's own height in place (no more flicker or
rows sliding from another contact's spot); nav bar bubble gets more air around
it in a slightly taller bar.

## v3.41 — 2026-09-02

"Add to contacts" (keypad and in-call) now asks Create new contact or Update
existing — some contacts apps only offer updating.

## v3.40 — 2026-09-02

Fixed a leaked proximity wake lock that could keep blanking the screen
system-wide (any app, sensor covered) after calls ended — the lock is now owned
by the call service, tied to telecom's call list, non-refcounted, with a forced
fallback release.

## v3.39 — 2026-09-02

Add call fixed — opens a cleared keypad, and a second call always uses the live
call's own SIM (a different SIM would strand the first call on hold with nothing
dialled); the confirm dialog hides its SIM switcher mid-call.

## v3.38 — 2026-09-02

Targets Android 15 (SDK 35) with deliberate edge-to-edge insets on every screen;
MainActivity's dial field, recents swipe, recents item builder and gate setup
checklist moved into their own files (pure moves).

## v3.37 — 2026-09-02

Foldable / wide-screen layouts — on the unfolded display Recents and Contacts
become list + detail panes, the keypad is a centred column, the nav bar and
banners stop spanning edge to edge, and the call screen and Settings sit in a
centred column. The outer display is unchanged.

## v3.36 — 2026-09-02

Polish — Settings rebuilt as grouped list rows with Material switches;
missed-call count badge on the Recents tab; launcher shortcuts (New contact,
Recents, Voicemail) and a themed (monochrome) icon; predictive back; animated
expand/collapse in Recents and Contacts; illustrated empty states; a "Block
unknown numbers" toggle; and a per-SIM filter in Recents.

## v3.35 — 2026-09-02

Contacts gains a New-contact button and an A–Z fast scroller with a letter
bubble; the keypad offers Add to contacts / Send message for an unmatched number
and a Paste chip when the clipboard holds one.

## v3.34 — 2026-09-02

Call screen — round Answer/Decline, 2×3 action tiles (Mute, Keypad, Speaker /
Add call, Hold, Contact), a second-call card with Swap / Merge and "End current
& answer" for call waiting, one-tap Add to contacts for unknown numbers, a
proper DTMF keypad with a digit strip, and haptics on answer/end.

## v3.33 — 2026-09-02

Single contact photo in the call notification (the system was doubling it via
the largeIcon), a restacked expanded layout that no longer truncates the name,
and "Dialling…" spelt properly.

## v3.32 — 2026-09-02

Notification contact photo is circle-cropped (the rounded square read as clipped
under the launcher's badge), and the ongoing-call notification is a custom
layout with round Mute / Speaker / red Hang up buttons visible even collapsed,
plus a live call timer.

## v3.31 — 2026-09-02

R8 code + resource shrinking (obfuscation off, so crash reports stay readable) —
smaller APK, faster cold start.

## v3.30 — 2026-09-02

Polish — app's own status-bar icon on notifications, contact photos in keypad
suggestions, swipe a Recents row right to call / left to message, per-contact
SIM pinning ("Always use for …" in the call dialog), and a Share button on the
crash report.

## v3.29 — 2026-09-02

Performance — contacts/call log reload only when they actually change (content
observers instead of requerying on every resume and tab switch), call-log name
resolution is indexed instead of scanning all contacts per row, and audio/state
ticks no longer re-decode the caller photo on the call screen.

## v3.28 — 2026-09-02

Proximity sensor blanks the screen against the ear during calls; missed calls
post a notification with Call back / Message; the speaker button becomes a route
picker (Phone / Speaker / Bluetooth / Wired) when Bluetooth audio is available.

## v3.27 — 2026-09-02

The notification's contact photo is a rounded square matching the system's
bubble corners, and mute on the call screen shows red with a crossed-out mic
instead of the same blue as speaker.

## v3.26 — 2026-09-02

The call notification is now a proper phone-call foreground service notification
with Hang up, Mute and Speaker, updated live as the call connects or the audio
route changes; outgoing calls no longer pop a banner over the call screen. The
call screen lives in its own task so opening the app mid-call no longer kills
it, and the dialer shows a "return to call" bar while a call is live.

## v3.25 — 2026-09-02

Fixed a crash when a call was hung up within the first second or two — Android
14+ rejected the call notification once telecom no longer saw the call, and that
exception took the whole app down.

## v3.24 — 2026-09-01

Crash reporting. If the app ever closes unexpectedly, Settings gains a red "Last
crash report" row with the full stack trace and a Copy button.

## v3.23 — 2026-09-01

Dual-SIM aware. On a phone with two active SIMs a SIM chip sits beside the dial
button showing which SIM will be used (tap it to switch, ask every time, or
follow the phone's setting); the call confirmation dialog gets a SIM 1 | SIM 2
switcher for one-off overrides; the in-call screen and call notification show
which SIM the call is on; and Recents badges every entry with its SIM. All of it
hides itself on single-SIM phones.

## v3.22 — 2026-09-01

USSD/MMI codes (*100#) no longer match contacts; short numbers require exact
match.

## v3.21 — 2026-08-30

Optional IDD code (default 019) replaces + on numbers opened from other apps.

## v3.20 — 2026-08-30

Cursor-editable keypad number — insert/delete mid-number, paste support.

## v3.19 — 2026-08-29

Handle tel: links from Chrome and other browsers (BROWSABLE category).

## v3.18 — 2026-08-26

Long-press to select and delete call logs; bottom sheets replace all dialogs.

## v3.17 — 2026-08-26

Grouped contact cards; search and filters on Recents.

## v3.16 — 2026-08-24

Dial voicemail as a normal call; prompt to save number when SIM has none.

## v3.15 — 2026-08-24

Keypad refresh — brighter bg, taller keys, lighter font, voicemail on 1.

## v3.14 — 2026-08-23

No gate-opened sound while ringer is on vibrate or mute.

## v3.13 — 2026-08-23

Contact photo and number in call notification.

## v3.12 — 2026-08-23

Show call screen ourselves when locked, heads-up only when device in use.

## v3.11 — 2026-08-23

Let SystemUI launch the incoming-call screen via full-screen intent.

## v3.10 — 2026-08-23

Use a CallStyle notification for calls.

## v3.9 — 2026-08-23

Make call notification silent so ringing follows the phone's ringer mode.

## v3.8 — 2026-08-22

Refine Gate setup controls.

## v3.7 — 2026-08-22

Fix compact Gate setup layout.

## v3.6 — 2026-08-22

Redesign Gate as a fixed dashboard.

## v3.5 — 2026-08-22

Improve independent Gate log scrolling.

## v3.4 — 2026-08-21

Restore 1s DTMF digit spacing.

## v3.3 — 2026-08-19

Space the code's digits 0.7s apart instead of 1s.

## v3.2 — 2026-08-19

Hold a wake lock and run gate timing off the main thread; silence the intercom
call.

## v3.1 — 2026-08-19

Fixed 5s buffer after the final attempt instead of a hangup setting.

## v3.0 — 2026-08-18

Sit the nav bar closer to the bottom edge and tighten the tabs.

## v2.9.9 — 2026-08-18

Grow the nav bar ~5% so the tabs are easier to hit.

## v2.9.8 — 2026-08-18

Make the gate wait tunable, and stop the log understating it.

## v2.9.7 — 2026-08-17

Collapse the Gate status card to what needs attention.

## v2.9.6 — 2026-08-17

Stop the log's scroll fix from eating touches.

## v2.9.5 — 2026-08-17

Reword the start and failure gate notifications.

## v2.9.4 — 2026-08-17

Gate notification opens the log, clearer wording, contained log scroll.

## v2.9.3 — 2026-08-16

Shrink the nav bar's labels and icons a step.

## v2.9.2 — 2026-08-16

Tighten the nav bar.

## v2.9.1 — 2026-08-16

Rebalance the Recents glyph.

## v2.9 — 2026-08-16

Handset-and-arrows glyph for the Recents tab.

## v2.8 — 2026-08-16

Bigger call handset, outlined/solid tab icons, generic intercom default.

## v2.7 — 2026-08-13

Correct the dialog blur against the AOSP window-blurs contract.

## v2.6 — 2026-08-13

Contact photos in the expanded recents card.

## v2.5 — 2026-08-13

Fix nav bar tab-switch stutter.

## v2.4 — 2026-08-13

Fuller gate log detail, contact photos, dialog polish.

## v2.3 — 2026-08-13

The call confirmation dialog is a glass panel matching the floating nav bar —
same translucent surface and hairline edge, 32dp corners, contact avatar, and
full-width Cancel / green Call pill buttons. The rest of the app's dialogs (SIM
chooser, settings pickers, block confirmations) pick up the same rounded glass
treatment via the theme.

## v2.2 — 2026-08-11

Expanding recents, nav bar first-frame fix, bigger keys.

## v2.1 — 2026-08-11

UI polish.

## v2.0 — 2026-08-10

Reliability hardening.

## v1.9 — 2026-08-10

Respect ringer mode for dial pad tones; sound/haptic settings.

## v1.8 — 2026-08-10

Proper circular dial button, icon buttons throughout.

## v1.7 — 2026-08-10

Settings screen, themes, blocked numbers, contact actions.

## v1.6 — 2026-08-09

Auto gate opener + full dialer.

## v1.3

The intercom call is auto-answered with the microphone muted.

## v1.2

Dark theme (black background, dark cards, blue accent), a confirmation dialog
before every outgoing call, and a default-SIM setting (System default / Ask
every time / specific SIM).

## v1.1

Full dialer: four tabs — Keypad (T9 search over contact names plus number
matching, key tones, long-press 0 for +, long-press 1 for voicemail), Recents
(call log with in/out/missed markers and relative times, tap to redial),
Contacts (search, starred first, multi-number chooser) and Gate (automation
settings and event log). In-call screen with avatar, timer, answer/decline,
mute, speaker, DTMF keypad and end call.
