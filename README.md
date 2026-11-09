# VoicePilot

Hands-free control of an Android phone (Android 8+) for when the touch screen is unreliable.
An accessibility service listens for spoken commands and taps, swipes and types for you.
The app has no internet permission; speech is recognised by the phone's own speech service (Google app).

## Get the APK
1. Create a GitHub repository and upload this folder (keep the `.github` folder).
2. GitHub builds it automatically (Actions tab, about 3 minutes).
3. Open the repository's **Releases** page on the phone and download `VoicePilot.apk`.

Or open the folder in Android Studio and choose Build > Build APK.

## First-time setup (needs a few taps, or a USB mouse / OTG)
1. Install the APK (allow "install unknown apps").
2. Open VoicePilot, press button 1 and allow the permissions.
3. Press button 2 and switch VoicePilot on under Accessibility.
   On Android 13+, if the switch is greyed out: Settings > Apps > VoicePilot > ⋮ > Allow restricted settings.
4. Press button 3 and allow notification access (needed for "reply" and "read messages").

After that it listens whenever the screen is on, including the lock screen.

## Commands
See the list inside the app. Highlights: `open <app>`, `tap <text on screen>`, `show numbers` then `tap 7`,
`type <text>`, `send`, `call <contact>`, `answer`, `hang up`, `reply saying <text>`,
`message <name> on Messenger saying <text>`, `text <name> saying <text>`, `play music`, `play <song> on YouTube`,
`unlock`, `pin 1 2 3 4`, `scroll down`, `back`, `home`, `stop listening`, `wake up`.

## Known limits
- Right after a reboot, Android does not start accessibility services until the first unlock, so the first PIN must be entered by hand.
- Some phones play a beep each time listening restarts; turn media/notification volume down if it bothers you.
- Speaking a PIN aloud can be overheard. Use it only where that is safe.
