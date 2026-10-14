# Text Scheduler

A personal Android app for sending recurring text messages (SMS) from your own phone number.

## What it does
- Schedule a text to one or more people (each gets their own text — not a group chat)
- Repeat: once, daily, weekly (pick days), monthly, or yearly — and "every N" of each
- Optional end date
- Shows the next few send times before you save
- Pause/resume with a switch; "Send now" for a test
- Notification after each send (or if it fails)
- Re-arms schedules after a reboot or time-zone change

## Getting the APK
Every push to `main` builds a signed APK with GitHub Actions. Open the repo's **Releases**
page on your phone and download `TextScheduler.apk` from the latest build.

## First-run setup (Android 15+)
Android blocks SMS permission for apps installed outside the Play Store until you allow it:
1. Install the APK and open it — if the SMS prompt doesn't appear or is greyed out, the app shows these steps.
2. Settings → Apps → Text Scheduler → ⋮ (top right) → **Allow restricted settings**
3. Permissions → SMS → **Allow**. Also allow notifications.

## Notes
- If the phone is off at send time, that send is skipped (not sent late) and the next one is scheduled.
- Force-stopping the app cancels its alarms until you open it again.
- Texts use your default SMS SIM and appear in your Messages app's sent history.
- Monthly on the 29th–31st sends on the last day of shorter months.
- The signing key (`app/release.keystore`) is committed so new builds install as updates. Keep this repo **private**.
