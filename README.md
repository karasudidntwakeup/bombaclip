# bombaclip

Copy on one device, paste on the other. PC ⇄ Android, over your LAN.

```
PC:  wl-copy "hello"         ->  paste on any Android app
Phone: copy in any app (or "Share to bombaclip")  ->  Ctrl+V on the PC
```

## PC side

One file, no dependencies:

```sh
python3 bombaclip.py --token "$(cat ~/.bombaclip_token)" &
```

The server runs a live copy of the PC clipboard and answers the phone's polls.
It uses `wl-copy`/`wl-paste` by default (Wayland), or pass `--copy xclip --paste "xclip -o -selection clipboard"` on X11.

Defaults: listens on `0.0.0.0:8080`. A token is needed so anything on your LAN
can't read your clipboard; keep the same token on the phone.

## Android side

Point the app at `http://<pc-ip>:8080`, paste the token, hit "Save and start sync".

### Android 15/16: background sync needs Shizuku

Normal background reads return nothing. bombaclip reads via
[Shizuku](https://github.com/RikkaApps/Shizuku) running in shell (non-root)
mode. Install the app, authorize it in Shizuku, then start sync. With Shizuku
dead or unauthorized, phone→PC only works while the app is on screen.

### Share to PC

Works without Shizuku: share text or a photo via the Android share sheet and
pick bombaclip.

## Notification mirror

Each phone notification pops up on the PC as a desktop notification
(`notify-send`, app icon + app name + title + text; nothing is stored on
either side). Swiping on the phone does not close the PC popup — it times
out on its own.

In the app, flip **"Mirror notifications to PC"** and tap **"Grant
notification access"**, then allow bombaclip in
*Settings → Special app access → Notification access*. Override the desktop
tool with `--notify <cmd>` (default `notify-send`).

The app never requests notification permission, so on Android 13+ its own
sync notification is hidden from the shade automatically (it still shows
under *Settings → Active apps*) — you won't get a persistent banner. The
mirror toggle is the only on/off: off means nothing is read at all — no
history, no backlog.

## Build

```sh
cd android
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release is signed with `keystore/bombaclip.jks` (credentials in
`keystore.properties`, both gitignored).

## Security

This is a no-TLS tool for a trusted LAN. Anyone on the same network can
interact with the server if they know the token. Notifications can contain
secrets (login codes, private messages) and travel plain-text between phone
and PC — keep it on home Wi-Fi.