# Installing on a TV in another home

The PC and the TV are on different networks. Tailscale links the two privately: nothing is opened to the internet.

## On the PC first
- Build the launcher: `launcher\app\build\outputs\apk\release\app-release.apk` (Sapphire Arctic fork).
- Theme: `themes\sapphire-arctic\config.json`. Its `latitude` and `longitude` are empty here; pass the TV's city to
  the install script with `-Latitude` and `-Longitude` so the weather and sunrise/sunset work.
- Install script: `tools\install-wireless.ps1` (pair, install, theme, Home, screensaver, live background; `-Undo` reverts).
- Tailscale installed on the PC and signed in (see step 1 if not).

## At the TV
1. **PC on Tailscale**: if not signed in yet, run `tailscale login`, which prints a link; open it on a phone and sign in.
   The PC then shows up in the Tailscale admin console.
2. **TV on Tailscale**: on the TV, Google Play Store → install **Tailscale** → open → **Log in**. It shows a code/QR:
   scan it with a phone and sign in with the **same** account. Turn the connection on in the TV app.
3. **Check the link**: ping the TV's `100.x.y.z` Tailscale address from the PC.
4. **Wireless debugging**: on the TV, Settings → System → Developer options → Wireless debugging → **Enabled**.
   Note the **port** on that screen (use the Tailscale IP, not the one shown).
5. **Pair**: choose **Pair device with pairing code** and leave that screen open. Note the **6-digit code** and the
   **port** after the colon, then pair from the PC.
6. **Install**: run the script, for example
   `tools\install-wireless.ps1 -Pair <tailscale-ip>:<pair-port> -Code <code> -Connect <tailscale-ip>:<port> -Latitude <lat> -Longitude <lon>`.
   Press **Home** on the remote when asked.
7. **If Google TV still opens its own home**, re-run with `-DisableStockLauncher` (reversible with `-Undo`).
8. **Live background**: play any video in a video app on the main row for a few seconds, then press Home and rest on that app:
   the background should fade into that video's thumbnail.

## If something goes wrong
- `tools\install-wireless.ps1 -Connect <tailscale-ip>:<port> -Undo` puts the stock home and screensaver back and
  removes the launcher.
