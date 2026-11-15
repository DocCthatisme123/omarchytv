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
