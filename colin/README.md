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
A layer over the stock home: the bear family room (B1; the sleeping family B3 from 22:00 to 06:00) with the apps as glowing
neon books in the shelf. Up/Down pick, OK opens, Right (or the Technology book) slides it away to the stock OmarchyTV home,
Home brings it back. Menu, Back, colour and media keys keep their OmarchyTV meaning. Clock and date top right.
Books (edit BOOKS in the file): Storybooks=Netflix, Learning=YouTube, Music=YouTube Music, Discovery=Prime Video,
Family=Live TV, Technology=stock home. Turn off with `"havenHome": false` in omarchytv.json.
