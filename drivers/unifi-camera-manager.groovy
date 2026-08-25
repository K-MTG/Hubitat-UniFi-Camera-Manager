/**
 * UniFi Camera Manager
 *
 * Filename: unifi-camera-manager.groovy
 * Version:  0.5.0
 *
 * Description:
 * - Represents a single UniFi Protect camera (talks directly to the
 *   camera's local HTTPS API, e.g. G4 Doorbell, not the Protect controller)
 * - Exposes a Switch: on() = camera active/recording, off() = privacy mode
 *     - on()  -> mask cleared, status LED on,  volume restored to normalVolume,  single PUT
 *     - off() -> full-frame mask, status LED off, volume lowered to privacyVolume, single PUT
 * - Cookie-based session auth (POST /api/1.1/login) for every command; the
 *   session cookie is NOT cached in device state (see Notes)
 *
 * Notes:
 * - The camera's audio volume field is 0-100 (integer), not 0.0-1.0.
 * - NEVER send volume 0. On at least some camera models,
 *   {"av":{"audio":{"volume":0}}} hard-disables the camera's audio in a way
 *   that persists and is not reversible via the API - it has to be fixed
 *   manually in the Protect app. Volume preferences are therefore bounded
 *   to 1-100, with a runtime clamp as a second line of defense.
 * - The status LED field is {"soundled":{"ledFaceEnabled":0|1}}, confirmed
 *   against a real camera. This is an undocumented field on the camera's
 *   own local API (distinct from, and older than, Ubiquiti's official
 *   Protect controller API) - there's no public documentation for it.
 *   A same-named `soundled.userLedOnNoff` field exists but does NOT
 *   control the visible LED on the tested hardware; don't use it.
 * - Mask + volume + LED are set via a single combined PUT to
 *   /api/1.1/settings, since that endpoint applies partial patches
 *   (confirmed live: setting only some of isp/av/soundled leaves the
 *   rest untouched).
 * - The session cookie is deliberately re-obtained on every command rather
 *   than cached in `state`: Hubitat's device state is persisted and shown
 *   in plaintext in the UI's "State Variables" section (unlike the masked
 *   `password` preference field), so caching a live session token there
 *   would leave a working bearer credential sitting around in the clear.
 *   Commands here are infrequent user-triggered toggles, so the cost of a
 *   fresh login per command is negligible.
 *
 * Changes (0.5.0):
 * - Flip switch semantics: on() is now camera active/recording (normal),
 *   off() is now privacy mode - matches how an on/off camera switch reads
 * - Add status LED control (soundled.ledFaceEnabled), tied to the same
 *   on()/off() toggle and folded into the same single PUT
 *
 * Changes (0.4.0):
 * - Bound volume preferences to 1-100 and add a runtime clamp; volume 0 can
 *   permanently disable the camera's audio
 *
 * Changes (0.3.0):
 * - Stop caching the session cookie in device state; log in fresh per command
 *
 * Changes (0.2.0):
 * - Combine mask + volume into a single PUT instead of two
 *
 * Changes (0.1.0):
 * - Initial Release
 */

metadata {
    definition(
        name: "UniFi Camera Manager",
        namespace: "k-mtg",
        author: "K-MTG",
        importUrl: "https://raw.githubusercontent.com/K-MTG/hubitat-unifi-camera-manager/refs/heads/main/drivers/unifi-camera-manager.groovy"
    ) {
        capability "Switch"
        capability "Actuator"

        command "testConnection"

        attribute "commStatus", "string"
    }

    preferences {
        input name: "cameraIp", type: "string", title: "Camera IP Address", required: true
        input name: "cameraUsername", type: "string", title: "Camera Username", required: true
        input name: "cameraPassword", type: "password", title: "Camera Password", required: true
        input name: "privacyVolume", type: "number", title: "Privacy Mode Volume, switch off (1-100, never 0)", range: "1..100", defaultValue: 1, required: true
        input name: "normalVolume", type: "number", title: "Active Volume, switch on (1-100)", range: "1..100", defaultValue: 100, required: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

/* ================= Lifecycle ================= */

def installed() {
    logInfo "Installed"
    sendEvent(name: "switch", value: "on")
    sendEvent(name: "commStatus", value: "unknown")
}

def updated() {
    logInfo "Updated"

    unschedule("logsOff")
    if (debugLogging) {
        runIn(1800, "logsOff")
    }
}

private void logsOff() {
    logInfo "Debug logging auto-disabled"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

/* ================= Capability: Switch ================= */

def on() {
    logInfo "Activating camera (normal operation)"

    Integer vol = safeVolume(normalVolume, 100)
    if (applyCameraState(true, vol)) {
        sendEvent(name: "switch", value: "on")
        logInfo "Camera active (mask cleared, LED on, volume ${vol})"
    } else {
        logWarn "Camera activate failed; leaving switch state unchanged"
    }
}

def off() {
    logInfo "Enabling privacy mode"

    Integer vol = safeVolume(privacyVolume, 1)
    if (applyCameraState(false, vol)) {
        sendEvent(name: "switch", value: "off")
        logInfo "Privacy mode enabled (mask on, LED off, volume ${vol})"
    } else {
        logWarn "Privacy mode enable failed; leaving switch state unchanged"
    }
}

/**
 * Clamps to 1-100. Volume 0 is refused even if a preference somehow ends
 * up unset/out-of-range - see file header Notes on why 0 is dangerous.
 */
private Integer safeVolume(rawValue, Integer fallback) {
    Integer vol = (rawValue ?: fallback) as Integer
    if (vol < 1) {
        logWarn "Refusing volume ${vol} (0 can permanently disable camera audio); using 1 instead"
        vol = 1
    } else if (vol > 100) {
        vol = 100
    }
    return vol
}

/* ================= Diagnostics ================= */

def testConnection() {
    logInfo "Testing connection / credentials"

    if (login()) {
        sendEvent(name: "commStatus", value: "online")
        logInfo "Connection OK"
    } else {
        sendEvent(name: "commStatus", value: "offline")
        logWarn "Connection failed - check IP/username/password"
    }
}

/* ================= Camera API ================= */

/**
 * Sets mask, volume, and status LED in a single PUT, since
 * /api/1.1/settings applies partial patches (confirmed against a real
 * camera - unrelated isp/av/soundled fields are left untouched).
 *
 * @param active true = normal operation (mask cleared, LED on)
 *               false = privacy mode (full-frame mask, LED off)
 */
private boolean applyCameraState(boolean active, Integer volume0to100) {
    Map masks = active ? [
        "0": null
    ] : [
        "1": [
            coord : [2, 3, 1000, 3, 1000, 1000, 2, 1000],
            update: true
        ],
        color: [0, 128, 128]
    ]

    Map payload = [
        isp     : [masks: masks],
        av      : [audio: [volume: volume0to100]],
        soundled: [ledFaceEnabled: active ? 1 : 0]
    ]
    return apiPut("/api/1.1/settings", payload)
}

/* ================= HTTP / Auth ================= */

private String baseUri() {
    return "https://${cameraIp}"
}

/**
 * Logs in and returns the session cookie (e.g. "authId=..."), or null on
 * failure. Deliberately not cached in `state` - see file header Notes.
 */
private String login() {
    if (!cameraIp || !cameraUsername || !cameraPassword) {
        logWarn "Cannot login: camera IP/username/password not configured"
        return null
    }

    Map params = [
        uri               : baseUri(),
        path              : "/api/1.1/login",
        body              : [username: cameraUsername, password: cameraPassword],
        requestContentType: "application/json",
        contentType       : "application/json",
        ignoreSSLIssues   : true,
        timeout           : 15
    ]

    String cookie = null
    try {
        httpPost(params) { resp ->
            String setCookie = resp.headers?.'Set-Cookie'
            if (setCookie) {
                cookie = setCookie.tokenize(';')[0].trim()
                logDebug "Login OK"
            } else {
                logWarn "Login response had no Set-Cookie header"
            }
        }
    } catch (Exception e) {
        logWarn "Login failed: ${e.message}"
    }

    return cookie
}

/**
 * Logs in fresh, then PUTs to the camera's settings endpoint using that
 * session. Retries once (with a new login) on a 401.
 */
private boolean apiPut(String path, Map body) {
    String cookie = login()
    if (!cookie) {
        return false
    }

    Integer status = rawPut(path, body, cookie)

    if (status == 401) {
        logDebug "Got 401, retrying with a fresh session"
        cookie = login()
        if (cookie) {
            status = rawPut(path, body, cookie)
        }
    }

    if (status == null || status >= 300) {
        logWarn "PUT ${path} failed (status=${status})"
        return false
    }

    return true
}

/**
 * Issues the raw HTTP PUT. Returns the HTTP status code, or null on
 * a transport-level failure (no response at all).
 */
private Integer rawPut(String path, Map body, String cookie) {
    Map params = [
        uri               : baseUri(),
        path              : path,
        headers           : ["Cookie": cookie],
        body              : body,
        requestContentType: "application/json",
        contentType       : "application/json",
        ignoreSSLIssues   : true,
        timeout           : 15
    ]

    Integer status = null
    try {
        httpPut(params) { resp ->
            status = resp.status
            logDebug "PUT ${path} -> ${status}"
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        status = e.response?.status
        logDebug "PUT ${path} -> ${status} (exception)"
    } catch (Exception e) {
        logWarn "PUT ${path} error: ${e.message}"
    }
    return status
}

/* ================= Logging ================= */

private logDebug(msg) { if (debugLogging) log.debug "${device.displayName}: ${msg}" }
private logInfo(msg)  { log.info  "${device.displayName}: ${msg}" }
private logWarn(msg)  { log.warn  "${device.displayName}: ${msg}" }
