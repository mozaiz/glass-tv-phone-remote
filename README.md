# Phone Remote for Android TV

Turn any phone into a remote for an Android TV box. A small app on the box serves a web page at
`http://<box ip>:8080`: open it on a phone on the same Wi-Fi and you get a D-pad, Back / Home / Menu,
volume and media keys, a box for typing into search fields, and a grid of every app on the TV that
opens with a tap. Nothing to install on the phone, no account, no cloud.

Made as an add-on for [Glass TV](https://github.com/cocombee/glass-tv), but it works with any launcher.

<p>
  <img src="docs/phone-remote.png" width="32%" alt="The phone page's Remote tab: Back, Home, Input, Menu, a round D-pad with OK, volume and media keys, and a box for typing on the TV">
  <img src="docs/phone-inputs.png" width="32%" alt="The phone page's Inputs tab on a streaming box, which reports no inputs, with the TV info panel below">
</p>

<p>
  <img src="docs/tv-screen.png" width="49%" alt="The Phone Remote screen on the TV: the address to open, Connected, and Forget all phones">
  <img src="docs/tv-pairing.png" width="49%" alt="The pairing screen on the TV showing a 4-digit code">
</p>

## What you get

- **Remote tab:** Back, Home, Input, Menu; a round D-pad that repeats while held; OK, where a hold is
  a real long press (Glass TV's Move / App info menu opens from the phone); volume and mute; rewind,
  play/pause, fast-forward; Delete, Enter and Settings. **Input** sends the remote's Input/Source key,
  so the TV opens its own source menu.
- **Type on the TV:** text you type on the phone goes into whatever field has focus on the TV.
- **Apps tab:** every app the TV can open, TV apps and phone-only apps alike, with its icon.
- **Inputs tab:** on a TV (Xiaomi, TCL, Sony…), its HDMI ports, AV and tuner with a dot for what is
  plugged in; one tap switches. Streaming boxes have no inputs and say so.
- **TV info** (under Inputs): what the TV reports about its inputs and which app switches them. If
  switching doesn't work on your TV, a screenshot of this is the most useful thing to put in an issue.
  **Forget this phone** is there too.
- **Fast:** about 40 ms from tap to key press on the TV over Wi-Fi.
- **Starts with the box:** after a reboot the page is back within a minute, with nothing to open.

## How it works

Android lets no ordinary app press keys in other apps. The box's own debugging service can, so on
first start the app connects to it at `127.0.0.1:5555` (the TV asks **Allow USB debugging?** once;
tick **Always allow**) and starts a tiny helper from its own APK with `app_process`, the way
[scrcpy](https://github.com/Genymobile/scrcpy) does. The helper stays running and injects each key
through Android's InputManager, so a press costs milliseconds instead of starting `input keyevent`.
Apps are opened with `am start`.

Inputs come from Android's TV input framework (`TvInputManager`), the same way
[Glass TV](https://github.com/cocombee/glass-tv)'s Inputs menu reads them on Xiaomi, TCL and Sony sets.
Switching is the standard `ACTION_VIEW` on the input's channel URI
(`content://android.media.tv/passthrough/<input id>`, type `vnd.android.cursor.item/channel`), which
the TV's Live TV app handles (on Xiaomi, `com.mitv.livetv`).

- `RemoteService`: foreground service started at boot; owns the web server and the link.
- `HttpServer`: a small HTTP/1.1 server for the page and its JSON API.
- `Adb`: one long-lived connection to the box's debugging service (protocol adapted from Glass TV's
  `CacheCleaner`, MIT).
- `Injector`: the helper that runs as the shell user and presses keys.
- `Pairing`: 4-digit codes and per-phone tokens.
- `TvInputs`: the TV's inputs, their state, and which app switches them.
- `app/src/main/assets/index.html`: the phone page, one file, no dependencies.

## Security

- **Pairing:** the first time a phone connects, the TV shows a 4-digit code; the phone that types it
  gets a random 256-bit token in an `HttpOnly`, `SameSite=Strict` cookie. A code lasts two minutes
  and five wrong guesses throw it away. Every API call except pairing needs the token.
- **Other websites can't press keys:** every POST needs an `X-Remote` header, which a page on another
  site can't send without a CORS preflight that this server never answers.
- **Forget all phones** on the TV screen revokes every token.
- It listens on your local network only, over plain HTTP. Don't forward port 8080 to the internet.
- It needs **network debugging left on** on the box. Anyone you let past the TV's debugging prompt
  can control the box anyway; this app adds no new way in beyond the paired phones.

## Install

Needs Android 8 or newer, and network (ADB) debugging on. Build with JDK 17 and Android SDK platform 34
+ build-tools 34.0.0:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.zmk.tvremote/.MainActivity
```

Accept the debugging prompt on the TV, then open the address it shows on your phone.
With [Glass TV](https://github.com/cocombee/glass-tv)'s tools instead of `adb`:
`tools/venv/bin/python -I tools/b64push.py app-release.apk /data/local/tmp/r.apk` and
`tools/tv.py 'pm install -r /data/local/tmp/r.apk'`.

Uninstall: `adb shell pm uninstall com.zmk.tvremote`.

## Tested on

A generic Allwinner H618 box (model MBOX), Android 10 TV (SDK 29), 1280x720, with Glass TV as Home:
pairing, keys, long press, typing into a TV text field, the app list and launching, and starting again
after a reboot.

Input switching, on the same box (which has no HDMI inputs of its own):
- The Input key reaches Android's input dispatcher (`KEYCODE_TV_INPUT`, 178).
- With no inputs, the Inputs tab says so and an unknown input id is refused.
- With a test app that registers a TV input and takes the switch intent the way a Live TV app does,
  the input is listed with its label and state, and a tap delivers exactly `ACTION_VIEW`,
  `vnd.android.cursor.item/channel`, `content://android.media.tv/passthrough/<id>`, with the id intact.

**Not yet tried on a real TV with HDMI ports.** Only a TV's own system can create HDMI inputs, so no box
or emulator can stand in for that last step. If you have a Xiaomi, TCL or Sony Android TV, please try
the Inputs tab and open an issue with what happened and a screenshot of TV info.

## Licence

MIT, see [LICENSE](LICENSE). The ADB client is adapted from
[Glass TV](https://github.com/cocombee/glass-tv) / [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT).
