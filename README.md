# MultiCall

Experimental Samsung Android audio utility. Needs the Shizuku app running and MultiCall allowed in it.

## What each control does

- **Mute (PLAY_AUDIO)**: silences the app's own sound, e.g. Duolingo.
- **Stop pausing / ducking my music (TAKE_AUDIO_FOCUS)**: the app can no longer pause or lower other audio. It still makes its own sound.
- **Restore**: puts both back to Android's default.

Quick fixes: Mute Duolingo, and stop Teams / Zoom / Meet ducking music (apply before joining the call).

During a live call Android may still lower media because of call mode. That isn't focus ducking: use Samsung Sound Assistant's Multi Sound / Separate app sound for that.

## Download

https://github.com/rickbesada-netizen/MultiCall/releases/download/android-latest/MultiCall.apk
