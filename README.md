# Hubitat UniFi Camera Manager

A Hubitat driver that exposes a UniFi Protect camera (e.g. G4 Doorbell) as an on/off **Switch** device —
switch on for normal, active/recording operation; switch off for privacy mode — no Protect controller,
cloud, or bridge service required. The driver talks directly to the camera's local HTTPS API.

```text
┌─────────────────────────┐
│      Hubitat Hub        │
│                          │
│  UniFi Camera Manager    │
└────────────▲─────────────┘
             │
             │ HTTPS (LAN)
             │
┌────────────┴─────────────┐
│    UniFi Protect Camera   │
│  (e.g. G4 Doorbell)       │
└───────────────────────────┘
```

Turning the switch **on** activates normal operation:
- Clears the privacy mask
- Turns the status LED on
- Restores speaker volume (default `100`)

Turning the switch **off** enables privacy mode:
- Applies a full-frame privacy mask (blacks out the video feed)
- Turns the status LED off
- Lowers speaker volume to a near-mute level (default `1`)

---

## What you get

### Features

- ✅ One-click privacy mode (mask + status LED + mute) from Hubitat, Dashboards, or Rule Machine, via a
  single API call
- ✅ Cookie-based session auth against the camera's local API; logs in fresh per command rather than caching
  a session token in device state (see Security Notes)
- ✅ Configurable active/privacy volume levels
- ✅ `testConnection` command + `commStatus` attribute for verifying credentials without touching the mask

### Limitations / Notes

- The camera's audio volume field is **0-100** (integer), not 0.0-1.0.
- **Never set volume to `0`.** On at least some camera models, `{"av":{"audio":{"volume":0}}}` doesn't just
  turn the gain down — it hard-disables the camera's audio, in a way that persists and is not reversible via
  the same API call (you have to go fix it manually in the Protect app). The driver's volume preferences are
  bounded to `1-100` for this reason; the lowest safe "near-mute" value is `1`.
- The status LED is controlled via `{"soundled":{"ledFaceEnabled":0|1}}`, confirmed against real hardware.
  This is an undocumented field on the camera's own local API (older than, and separate from, Ubiquiti's
  official Protect controller API), so there's no public reference for it. A similarly-named
  `soundled.userLedOnNoff` field exists but did **not** control the visible LED in testing.
- The camera uses a self-signed certificate; the driver ignores SSL validation errors (`ignoreSSLIssues`)
  when talking to it. This is only safe because the connection stays on your LAN.
- This is LAN-local. Do not expose the camera's HTTPS port to the public internet.
- Only one privacy mask slot is used (index `1`); if you already use custom privacy masks on this camera for
  something else, `on()`/`off()` will overwrite/clear that slot.

---

## Prerequisites

### Hardware
- UniFi Protect camera with a local HTTPS management API (tested against G4 Doorbell)
- Hubitat Hub

### Network
- Hubitat makes outbound HTTPS requests to the camera on port **443**
- **Static IP or DHCP reservation for the camera is required.** The driver stores the camera's IP in its
  preferences; if the camera's address changes, the driver will stop working until you update it.
  - In UniFi Network, set a fixed IP for the camera under **Client Devices → (camera) → Config → Network →
    Use Fixed IP Address**, or configure a DHCP reservation on your router/DHCP server.

### Credentials
- The camera's **local management username and password** (this is the local device credential set on the
  camera itself — typically `ubnt` plus a device-local password — not your Ubiquiti cloud/SSO account).
  - If you don't already have this, it's usually set/reset via the UniFi Protect app's camera device
    settings, or during camera adoption.

---

### Security Notes

- Treat the camera's local credentials like any other password — the driver stores the password using
  Hubitat's `password` preference type, which is masked in the UI.
- The driver deliberately does **not** cache the camera's session cookie in device state. Hubitat's device
  state is persisted and shown in plaintext in the device page's "State Variables" section (unlike the
  masked `password` field), so caching a live session token there would leave a working bearer credential
  sitting around in the clear. Instead, the driver logs in fresh for every command — commands are infrequent
  user-triggered toggles, so the extra login round-trip is negligible.
- Keep the camera and Hubitat hub on a trusted LAN; do not port-forward the camera's HTTPS port.

---

## Getting Started

### Setup Hubitat Driver

1. In Hubitat, go to **Drivers Code**
2. Click **New Driver**, then either:
   - Paste the contents of [`drivers/unifi-camera-manager.groovy`](drivers/unifi-camera-manager.groovy), **or**
   - Use **Import** with:
     `https://raw.githubusercontent.com/K-MTG/hubitat-unifi-camera-manager/refs/heads/main/drivers/unifi-camera-manager.groovy`
3. Click **Save**
4. Go to **Devices → Add Device → Virtual**, give it a name, and set **Type** to **UniFi Camera Manager**
5. Open the new device and fill in **Preferences**:
   - **Camera IP Address** — the camera's static IP / DHCP reservation
   - **Camera Username** / **Camera Password** — the camera's local management credentials
   - **Active Volume, switch on** — volume (1-100) to use during normal operation (default `100`)
   - **Privacy Mode Volume, switch off** — volume (1-100, never `0` — see Limitations) to use while privacy
     mode is on (default `1`)
6. Click **Save Preferences**
7. Click **testConnection** and check the **commStatus** attribute / **Logs** to confirm the credentials work
8. Repeat steps 4-7 for each additional camera — one device per camera

Once configured, use the device's **on**/**off** commands (or add it to a Dashboard tile / Rule Machine rule)
to toggle between normal operation (on) and privacy mode (off).

---

## Components

### Driver: "UniFi Camera Manager"
- Implements Hubitat's `Switch` capability:
  - `on()` → single PUT that clears the privacy mask, turns the status LED on, and sets volume to
    **Active Volume**
  - `off()` → single PUT that sets a full-frame privacy mask, turns the status LED off, and sets volume to
    **Privacy Mode Volume**
- `testConnection` command re-authenticates and reports via the `commStatus` attribute (`online`/`offline`)
- Logs in fresh before every command (not cached in device state); retries once with a new session on a 401
