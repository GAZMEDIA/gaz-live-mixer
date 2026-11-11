# Gaz Live Mixer for OBS Studio
*Gaz Media Production · Everything Multimedia*

Gaz Live Mixer lets you control OBS like a hardware video mixer from your phone, a tablet or another PC on the same Wi-Fi. You don't need to set up OBS WebSocket, enter passwords or type IP addresses.

- **Live scene thumbnails** with tally: red = LIVE (program), green = PREVIEW, amber = NEXT.
- **Preview and Program monitors**, like a switcher's multiview.
- **Tap = preview, double-tap = take live.** You can also switch to single-tap takes, Stream Deck style.
- **CUT and AUTO** buttons, a transition picker and a duration box.
- **Tick and untick** which scenes show on each device, and reorder them. Each phone keeps its own layout.
- **Studio mode** toggle, plus ON AIR and REC indicators.
- **Hotkeys** for keypads, Stream Deck and MIDI. Remote PCs get keyboard shortcuts.

Every edition serves the same mixer panel, so they all look and work the same.

| Folder | What it is | Best for |
|---|---|---|
| `obs-script/` | **Script edition**: a Python script loaded in OBS | Using it today on any OBS 28+ |
| `obs-plugin/` | **Native plugin** (C++), with a QR code dock inside OBS | No Python needed; the version to sell or share |
| `android/` | **Android app**: finds your OBS PC automatically | Phones and tablets, with volume-key CUT/AUTO |
| `shared/` | The mixer panel (`panel.html`) and the QR page (`connect.html`) | Used by all editions |

Use **either** the script **or** the native plugin, not both at once, because they share port 4466.

---

## 0. Windows installer (easiest)

Download **GazLiveMixer-Setup-x.y.z.exe** from the [Releases page](https://github.com/GAZMEDIA/gaz-live-mixer/releases/latest) and run it. Close OBS first.

| Install type | What you get |
|---|---|
| **Full** | The OBS plugin and the desktop app. Use this on the PC that runs OBS. |
| **OBS PC only** | Just the plugin. |
| **Remote PC only** | Just the desktop app, for a second PC that controls OBS over the network. |

The installer:
- finds your OBS folder by itself;
- opens Windows Firewall for the mixer, so phones connect without prompts;
- installs Microsoft WebView2 if it's missing;
- adds Start Menu and desktop shortcuts.

You can uninstall it from *Settings → Apps*.

**Gaz Live Mixer desktop app:** it finds the OBS PC on your network and opens the mixer in its own window.

| Key | Action |
|---|---|
| **F11** | Full screen |
| **F5** | Reload |
| **Ctrl+L** | Pick another OBS PC |

## 1. Script edition (quickest start)

1. Install **Python 3.12 (64-bit)** from python.org if you don't have it. On Windows, OBS 30+ works with Python 3.8–3.12.
2. In OBS: **Tools → Scripts → Python Settings**. Point it to your Python folder, for example `C:\Users\<you>\AppData\Local\Programs\Python\Python312`.
3. Copy the `gaz-live-mixer-script` folder somewhere permanent, for example `Documents\OBS Scripts\gaz-live-mixer-script`. It must contain:
   - `gaz_live_mixer.py`
   - `gaz_qrcodegen.py`
   - `panel.html`
   - `connect.html`
4. **Tools → Scripts → +** and pick `gaz_live_mixer.py`.
5. Click **Show phone QR code** and scan it with your phone. The mixer opens in the phone's browser.
   *Tip:* use **Add to Home Screen** so it opens full screen like an app.

The first time, Windows may ask whether OBS can use the network. Tick **Private networks** and allow it.

Script settings:
- **Panel port.** Default is 4466.
- **Live scene thumbnails** on or off.
- **Thumbnail quality.** Use Low on weak Wi-Fi.

## 2. Native plugin (no Python needed)

Requires **OBS Studio 30 or newer**.

**Windows install:** copy the built files into your OBS folder (usually `C:\Program Files\obs-studio\`):
```
obs-plugins\64bit\gaz-live-mixer.dll
data\obs-plugins\gaz-live-mixer\panel.html
data\obs-plugins\gaz-live-mixer\connect.html
data\obs-plugins\gaz-live-mixer\locale\en-US.ini
```
Then restart OBS and open **Docks → Gaz Live Mixer**. The dock shows the QR code and the link. It also has **Open mixer**, **Copy link**, and settings for the port and thumbnail quality.

**Building it:** push this folder to a GitHub repository. The workflow in `.github/workflows/build.yml` builds:
- the Windows plugin
- the Linux plugin
- the Android APK
- the script zip

Download them from the **Actions** tab, under *Artifacts*.

To build on Linux yourself:
```
sudo apt install libobs-dev qt6-base-dev cmake g++
cmake -S obs-plugin -B build && cmake --build build && cmake --install build --prefix ~/.config/obs-studio/plugins
```

## 3. Android app

1. Get `GazLiveMixer.apk` from the GitHub Actions build. You can also open `android/` in Android Studio and click Run.
2. Install it. You'll need to allow installing from unknown sources once.
3. Open the app. It searches the Wi-Fi for OBS and connects by itself, and it remembers the last PC.
4. **Volume Down = AUTO, Volume Up = CUT** while the mixer is open. **Back** returns to the PC list.

If nothing is found, type the address shown in OBS (for example `192.168.1.20:4466`).

---

## Using the mixer

| Action | Phone / tablet | Remote PC keyboard |
|---|---|---|
| Put a scene in preview / NEXT | Tap the tile | `1`–`9`, `0` |
| Take a scene live | Double-tap the tile | `Shift` + number |
| Transition preview → program | **AUTO** | `Space` / `Enter` |
| Hard cut | **CUT** | `C` |
| Studio mode on/off | **STUDIO** | `S` |
| Full screen | ⛶ | `F` |

- **Studio mode on:** tapping changes OBS's real preview, and AUTO/CUT take it to program.
- **Studio mode off:** tapping marks the tile **NEXT** on your panel only, and AUTO/CUT take it live.
- **⚙ Settings → Scenes on my panel:** tick or untick scenes and reorder them with ▲▼. This is saved per device, so the director's phone and the operator's tablet can show different scenes.
- **⚙ Settings → Buttons:** choose double-tap or single-tap to take, and whether a take uses the current transition or a hard cut.

## Keypads, Stream Deck and MIDI (hotkeys)

In **OBS → Settings → Hotkeys**, search **"Gaz Mixer"** to find:
- AUTO, CUT, Next scene, Previous scene, Toggle studio mode
- Take scene 1–12 live, and Preview scene 1–12. The numbers follow your OBS scene list order.

Assign any key to these. A USB numpad becomes a cheap switcher. Stream Deck can trigger hotkeys. A MIDI controller works through a MIDI-to-keystroke tool such as *MIDI Translator* or *Bome*.

## Companion / Stream Deck HTTP buttons (advanced)

Every action is also a simple web link. Use these with **Bitfocus Companion → Generic HTTP**, or any device that can open a URL:
```
http://PC-IP:4466/api/take?scene=Camera%201            take live (current transition)
http://PC-IP:4466/api/take?scene=Camera%201&mode=cut   take live with a hard cut
http://PC-IP:4466/api/preview?scene=Camera%202
http://PC-IP:4466/api/auto        http://PC-IP:4466/api/cut
http://PC-IP:4466/api/next        http://PC-IP:4466/api/prev
http://PC-IP:4466/api/studio?on=toggle
http://PC-IP:4466/api/transition?name=Fade
http://PC-IP:4466/api/duration?ms=500
http://PC-IP:4466/api/state       (JSON: scenes, program, preview, tally…)
```

## Troubleshooting

- **The phone can't connect.** Check that the phone is on the same Wi-Fi as the PC, not mobile data or a guest network. Then check the Windows firewall: allow **OBS** on Private networks.
- **"Port busy" in the OBS log.** Another app is using port 4466. Change the port in the script settings or the plugin dock.
- **Grey tiles with numbers instead of pictures.** Thumbnails are off, or this OBS build doesn't allow them. Switching still works fully.
- **Thumbnails are slow on weak Wi-Fi.** Set Thumbnail quality to Low, or set ⚙ → Thumbnail refresh to Slow.
- **Security.** Anyone on the same network can open the panel. Don't run it on public or hotel Wi-Fi during a show.

## Test status

- The script edition and native plugin were run inside **real OBS 30 (Linux)**. Tested with real scene thumbnails: switching, double-tap, CUT/AUTO, studio mode, next/prev, discovery, clean shutdown and restart.
- **Not yet tested:**
  - The Windows plugin build workflow and the Android APK build. Both run on GitHub Actions and have not been run yet.
  - The script's thumbnail loader on Windows. If it can't load there, the mixer still works and shows numbered tiles.

Third-party code: [cpp-httplib](https://github.com/yhirose/cpp-httplib) (MIT) and [QR Code generator](https://github.com/nayuki/QR-Code-generator) (MIT) by Project Nayuki.
