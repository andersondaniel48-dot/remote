# TV Hub — setup

TV Hub turns an old Android phone into a remote and control panel for your TVs. From one screen you can:

- **Switch between TVs.** Each TV gets a card showing whether it's on and what it's playing (app, title, and whether it's playing or paused).
- **Use the remote.** D‑pad, OK, Back, Home, Menu, play/pause, rewind/fast-forward, volume, sleep/wake, and typing into the TV's search boxes.
- **Open apps.** Launch any app installed on the selected TV.
- **Open links.** Send a YouTube link or web page to the selected TV.
- **Cast from the phone.** Send a photo or video stored on the phone to the selected TV.

The phone talks to the TVs over Wi‑Fi using Android's developer connection (ADB). You don't need a computer or any account for that part.

---

## 1. Turn on ADB debugging on each TV (one time)

**Fire TV (both of yours)**
1. Settings → **My Fire TV** → **About**. Select the device name **7 times** until it says you're a developer.
2. Back → **Developer options** → turn **ADB debugging** ON.
3. Settings → My Fire TV → About → **Network**. Write down the **IP address** (e.g. `192.168.1.50`).
4. To make Volume and Wake work: Settings → Display & Sounds → **HDMI CEC Device Control** → ON.

**Google TV**
1. Settings → System → About → select **Android TV OS build** **7 times**.
2. Settings → System → **Developer options** → turn **USB debugging** ON (if you also see **Network debugging**, turn that on too).
3. Settings → Network & Internet → your Wi‑Fi network. Write down the **IP address**.
4. Some newer Google TVs only offer **Wireless debugging**, which uses a pairing code. TV Hub can't do that pairing itself. If yours is one of these, run these commands once from a computer that has `adb` installed:
   ```
   adb pair <ip>:<pairing-port>     # enter the code shown on the TV
   adb connect <ip>:<port>
   adb tcpip 5555
   ```
   After a TV restart you have to run them again.

**Tip:** In your router's settings, give each TV a reserved IP address (sometimes called a DHCP reservation). That way the addresses saved in TV Hub keep working.

## 2. Build the app

Pick one of these:

- **Android Studio:** File → Open → choose this folder. Wait for the Gradle sync to finish, plug the phone in over USB with USB debugging enabled, then press **Run ▶**.
- **GitHub, no setup on your computer:** push this folder to a new GitHub repo. Every push runs the **Build APK** workflow. Open Actions → the latest run → download **TVHub-apk**, unzip it, copy `app-debug.apk` to the phone and open it to install.

## 3. First run on the phone

1. Factory-reset the old phone and connect it to the same Wi‑Fi as the TVs.
2. Open TV Hub. Settings opens automatically the first time. Tap **Add a TV** for each TV, or tap **Scan Wi‑Fi** to find TVs that have ADB on. Name them (e.g. Living Room, Bedroom, Kids) and pick Fire TV or Google TV.
3. **Go to each TV.** A dialog asks **"Allow USB debugging?"**. Tick **Always allow from this computer**, then choose **OK**. The TV's card should turn green.
4. **The first cast to each TV** installs TV Hub's small player app on that TV, which takes about 30 seconds. You can also start it ahead of time with **Set up** in the Cast tab.

Settings is hidden behind the gear icon: **press and hold** it. Tapping alone does nothing, which keeps kids out.

## 4. Make it the only app on the phone

**Easy mode (no computer):** Settings → **Use TV Hub as the home screen**. Choose TV Hub when Android asks for a default home app. Home and Back now always come back to TV Hub, and the status and navigation bars are hidden. You can also turn on **Pin TV Hub** (Android's screen pinning).

**Full lock (one-time, uses a computer):** the phone has to be freshly reset with **no Google account added**. Then run:
```
adb install app-debug.apk
adb shell dpm set-device-owner dev.daniel.tvhub/.AdminReceiver
```
Open TV Hub → Settings → turn on **Full lock**. TV Hub now starts locked every time and blocks notifications, recent apps and other apps. To undo it: Settings → turn off **Full lock** → **Remove device-owner mode**.

**Battery:** leave the phone on a charger. If the phone has a battery-protection or 80% charge-limit setting, turn it on. "Keep screen on while charging" is on by default and can be switched off in TV Hub's settings.

---

## Limits to know about

- **What's playing:** TV Hub reads it from the TV's media info. Netflix, YouTube and most video apps report titles. Some apps only report the app name.
- **Links:** YouTube links open in the YouTube app on every TV. Other web links open in Silk on Fire TV. Google TV has no browser built in, so you'd need to install one.
- **Videos:** the TV plays the file the phone streams. Very high-bitrate 4K/HEVC phone videos can fail on older Fire TV Sticks. Photos work everywhere.
- **Volume, sleep and wake on Fire TV** go through HDMI‑CEC to the television, so they depend on the TV supporting CEC.

## Changing the app

The code is in `app/src/main/java/dev/daniel/tvhub/`:

| File | What it does |
|---|---|
| `core/TvHub.kt` | Everything the app does to TVs: status polling, keys, apps, links, casting |
| `core/TvConnection.kt` | One Wi‑Fi ADB connection per TV (uses the `dadb` library) |
| `core/TvParsers.kt` | Reads `dumpsys` output to work out what's on screen |
| `core/MediaServer.kt` | Small HTTP server that streams phone media to the TVs |
| `ViewerActivity.kt` | The player that runs on the TV |
| `ui/` | The phone screens (Jetpack Compose) |

If you change the app, raise `versionCode` in `app/build.gradle.kts`. TV Hub will then offer to update its player on each TV.
