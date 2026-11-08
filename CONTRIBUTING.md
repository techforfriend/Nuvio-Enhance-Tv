# Contributing

Nuvio AK is a one-person fork. I build it for my own TV first, so the rules here are short and the scope is narrow.

## Bug reports

A good bug report is the most useful thing you can send me. Use the bug report form, one bug per issue, and tell me:

- Your box (model and Android version) and the app version from Settings › About.
- What you were playing when it happened: the title, and the stream's format if you know it (4K remux, Dolby
  Vision, TrueHD Atmos and so on). Leave out addon and debrid URLs, they often carry your account details.
- The steps that make it happen, what you expected, and what you got instead.
- For a crash, a log. Running `adb logcat -d > nuvio-log.txt` right after the crash is enough.

Before you file, check that the bug is actually in AK. If NuvioMedia's app does the same thing, it belongs on
[their tracker](https://github.com/NuvioMedia/NuvioTV/issues). A stream that fails in every player is usually the
addon or the source, not the app.

## Pull requests

I take pull requests for two things:

- **Bug fixes.** Link the issue, say how you reproduced the bug, and say how you tested the fix (which box, which
  steps).
- **Translations.** The strings live in `app/src/main/res/values-*/strings.xml`.

Anything bigger (a feature, a redesign, a refactor, a new dependency) needs an issue first, so we can agree on it
before you put the time in. Open a blank issue and make the case. A pull request that arrives without one will
probably get closed, even if the code is good. AK's screens all follow one design, and I keep the app small on
purpose: the README lists a lot of what I took out.

Keep each pull request to one change. Build it with JDK 21 (`./gradlew assembleFullDebug`) and run
`./gradlew testFullDebugUnitTest` before you open it.

Thanks for helping. Even a short report with the right details saves me hours.
