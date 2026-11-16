# Colin's OmarchyTV

Colin's own build of [piermark/omarchytv](https://github.com/piermark/omarchytv) for his Skyworth SWTV-20AE.
Everything Colin-specific lives in this folder, so Marco's updates merge in cleanly.

- `Dockerfile`: the build environment (JDK 17 + Android SDK 34), so nothing is installed on the build host.
- `omarchytv.json`: Colin's launcher settings (Grok Bot, SpaceX TV and X off, voice key opens Walker).
  Pushed to the TV at /sdcard/Android/data/com.omarchytv.home/files/omarchytv.json after a fresh install.

Code changes outside this folder (kept small, to merge cleanly):
- `ColinKeyLog.java` plus one line each in `KeyMapService` and `MainActivity`: logs every key press,
  for mapping remote buttons. `adb logcat -s OmarchyKey`

Built, signed and installed by Nook on the NUC. The build and deploy scripts are in `~/Nook/projects/omarchytv-ci/`
on the NUC, and the weekly run is Sunday at 03:30. The signing key is kept on the NUC and never committed.

All credit for OmarchyTV to Marco Porro (MIT); see ../LICENSE.

## 4K video wallpaper (ColinVideoWallpaper.java)
Loops a muted clip on a SurfaceView (hardware video plane) behind the home screen. Put clips in
`/sdcard/Android/data/com.omarchytv.home/files/video/` as `<theme>.mp4` (e.g. `cumulus.mp4`) or `default.mp4`.
No clip = stock behaviour. Bad clip = skipped, still wallpaper shows. Log: `adb logcat -s OmarchyVideo`.

## Holmes Haven boot screen (ColinHavenBoot.java)
Replaces the terminal boot when `assets/holmes/` holds boot_bg.webp, emblem.webp and wordmark.webp (art: Slater's
~/Slater/notes/holmes-haven, B2/A1/A2, sized for the 1080p UI). Same triggers as upstream (`"bootScreen"`: boot / wake / off),
any key skips. About 6 s: room fades in with a slow push-in and twinkling stars, the emblem blooms, the wordmark rises, a glowing bar fills.
Hooks: 4 lines in BootScreenView (show, consumeKey, isShowing, onDestroy). Delete assets/holmes/ to get the stock boot back.

## Holmes Haven bookshelf home (ColinHavenHome.java)
A layer over the stock home: GPT's painted library room with six glowing books in the shelf (assets/holmes/home_master.webp)
plus a painted "selected" version of each book (shelf_sel1..6.webp, shelf crops only; the full images were verified
pixel-identical to the master outside the shelf). Up/Down crossfades to that book's shelf (160 ms), OK opens it, nothing is
selected until the first key and it returns to the level books after 30 s idle. Right (or Technology) slides it away to the
stock OmarchyTV home, Home brings it back. Clock and date top right. Art: ~/Slater/notes/holmes-haven/final-pack.
Books: Storybooks=Netflix, Learning=YouTube, Music=YouTube Music, Discovery=Prime Video, Family=Live TV,
Technology=stock home. No night variant yet (the old B3 doesn't match the new painting). Off: `"havenHome": false`.

## 10 workspaces (Colin's layout)
1-5 are the family's: 1 = the Holmes Haven bookshelf, 2-5 = placeholders ("coming soon") on the upscaled landscapes (ws2-5.webp: valley, lake, campfire, waterfall) until Colin and Mikayla design them.
Marco's original five move to 6, 7, 8, 9, 0 (in his bar order). Number keys jump (0 = 10), CH+/CH- step through all ten,
Left/Right step 1-5 (Right on 5 goes to 6), Back anywhere else returns to 1, Home always returns to 1, and the Technology
book opens 8 (the app grid). Handled in ColinHavenHome.workspaceKey, one hook line above Marco's workspace keys.
The Holmes Haven logo (assets/holmes/logo_bar.webp) sits in the sky, top centre, on the family workspaces.

## True 4K on the family workspaces
Android draws app UI at 1080p on this TV; only the video plane is 4K. So each family workspace can have a still 4K clip
(`files/video/haven-1.mp4` .. `haven-5.mp4`, every frame the same picture, made by Nook) played on a SurfaceView behind
ColinHavenHome. Until its first frame is up the 1080p picture shows (no black flash); then the view turns see-through and
Marco's home underneath is hidden. On workspace 1 the selected book's shelf crop is still drawn on top at 1080p.
Paused in onPause, restarted on Home. No clip = 1080p as before.

## Floating Omarchy bar on the family workspaces (Colin's layout)
No background, everything shadowed so it floats. Middle: the Holmes Haven logo with the date and time to its right
(Marco's bar style, CaskaydiaMono). Right: Marco's seven tray icons (Cinemarchy, Arcade, Wi-Fi, Bluetooth, volume,
inputs, PC screen), same actions via HomeV29.barClick(i). On 2-5 the left has our emblem (where Omarchy has its logo)
and 1 2 3 4 5, the current one bold and underlined, the rest faded; workspace 1 has no numbers.
Up from the top book (or from 2-5) goes to the bar, Left/Right along the icons, OK runs it, Down comes back.
The 1080p picture now fades out over 350 ms once the 4K clip is up, instead of switching.

## Holmes Haven phone app, M1 (launcher/companion, package com.holmeshaven.companion)
Plan: ~/Nook/projects/companion/PLAN.md on the NUC (the only plan). Built with the launcher: `./gradlew assembleRelease`
also gives `companion/build/outputs/apk/release/companion-release-unsigned.apk`; Nook signs it with Colin's key.
- **Phone:** finds the TV over mDNS (`_omarchytv._tcp`, or type its address), pairs with the 6-digit code on the TV
  (Omarchy Link SPAKE2; the phone also checks the TV's confirm, so a fake TV can't pair), then signs every request and
  checks every answer's X-OTV-Sig. The token is sealed with an Android Keystore key; backups are off. Home shows the TV's
  status (screen, what's on, TV software, Android, uptime, storage) and checks for an update every time it opens.
  LinkCrypto.java is a copy of the launcher's (only base64 differs); keep the two in step.
- **TV, Phones (ColinPhones.java):** in the floating bar, Left past the first tray icon reaches the Holmes Haven logo; OK
  opens Phones: the paired devices (OK twice forgets one) and "Allow a new phone", which opens pairing for 60 s with a
  big colourful code. Pairing is refused whenever that window is closed.
- **TV, updates (link/ColinCompanion.java):** put the newest phone APK at
  `/sdcard/Android/data/com.omarchytv.home/files/companion/holmes-haven.apk`. Signed routes:
  `GET /v1/companion/update` ({available, package, versionCode, versionName, size, sha256}) and `GET /v1/companion/apk`.
  The phone installs it only if the SHA-256 matches; Android then asks to confirm and refuses another signing key.
  `/v1/status` also carries uptimeS, storageFreeMB, storageTotalMB and androidVersion.

## Holmes Haven phone app, M2: the remote (ColinRemote.java)
Android won't let an app press keys in other apps, and SELinux stops the shell writing to the IR receiver, so the
launcher opens its own ADB (LocalAdb, 127.0.0.1:5555) and keeps Android's `hid -` tool running on one stream: a virtual
"Holmes Haven Remote" (HID consumer control + keyboard). Key reports reach every app as real remote presses
(Netflix and YouTube too), a few ms each. First use: the TV asks "Allow USB debugging?" for the launcher's key; tick
Always allow. Needs adb on 5555 to stay on (Colin, 2026-11-16: option 1; Google's remote service is the fallback).
Routes (signed): `POST /v1/haven/key {key, hold}` (up/down/left/right/select/back/home/menu, media, volume, mute,
channel_up/down, 0-9, power; hold=true on power = the shutdown prompt; "wakeup"/"sleep" press power only if needed),
`POST /v1/haven/workspace {n}` (switches in place in the launcher, like Left/Right; from another app it opens the launcher straight on that workspace), `POST /v1/haven/warm` (the app opened: connect now).
`/v1/status` carries `remote` (off/starting/needs_allow/adb_off/ready) and `wakeMac` (from `dumpsys ethernet`, since
apps can't read the MAC on Android 10); the phone keeps it and sends Wake-on-LAN when the TV doesn't answer.

## Holmes Haven phone app, M3: apps
`GET /v1/haven/apps` (signed) lists every TV app A-Z with its picture: the TV banner (320x180) when the app has one,
else its icon (144x144), as base64 WebP, cached per app version on the TV. The phone's Apps screen shows them two per
row; a tap opens the app (Marco's `POST /v1/apps/launch`) and goes back to the remote.
