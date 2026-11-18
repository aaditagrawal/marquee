# Privacy

Marquee is a client for a Jellyfin server that **you** choose. It has no
accounts, servers or cloud services of its own. It contains no analytics, no
advertising, no crash reporting and no telemetry SDKs.

This page describes what the app stores, what it sends and where it sends it.
It was written by reading the source and the resolved release dependency list.
If you find a difference between this page and the code, please report it (see
[SECURITY.md](SECURITY.md)).

## Who receives data

The app talks only to the Jellyfin server URL you enter on the sign-in screen.
The OkHttp client adds an `Authorization` header only to requests whose
scheme, host, port and base path all match that server address (for example,
with `https://media.example.com/jellyfin`, requests under
`https://media.example.com/jellyfin/`, but not to another host, port, scheme or
path). If a redirect leads to a different host, port or scheme, OkHttp removes
the header before following it.

There are two exceptions, both started by you:

- **Remote trailers.** If the server's metadata lists a remote trailer (usually
  a YouTube link) and you pick it in the Trailers sheet, Marquee hands the URL
  to Android with `ACTION_VIEW`. Another installed app opens it. Marquee does
  not fetch the URL itself.
- **Server-provided media and image URLs.** Artwork, subtitles, trickplay
  thumbnails and streams are requested from your server. If your server is
  configured to redirect to another host, the request follows that redirect,
  without the `Authorization` header.

The release dependency set contains no analytics, crash-reporting,
advertising or Google Play services libraries. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
lists every resolved library. The app's own source has no hard-coded
third-party network endpoints. URLs in the source are documentation links in
comments and licence texts, and the source-code address displayed (as text,
never opened or contacted) in Settings → Open-source licences.

## What is sent to your Jellyfin server

- **Sign-in.** Your username and password (password sign-in), or a Quick
  Connect secret (Quick Connect). The password is sent once to obtain an
  access token. It is never written to storage.
- **The `Authorization` header** on every request: the client name
  ("Marquee"), a device name, a random per-install device ID, the app version
  and, after sign-in, your access token. The device name is the name set in
  Android's device settings (for example "Living Room TV"), or the hardware
  model if none is set, reduced to ASCII. Jellyfin shows the client and device
  in its dashboard and uses them to manage sessions.
- **Normal browsing requests**: library views, item details, search terms you
  type, favourites and watched-state changes.
- **Playback requests**: `PlaybackInfo` negotiation with a device profile that
  describes the codecs your device can play, stream requests, and media-segment
  and trickplay lookups.
- **Playback reports**: start, periodic progress (position, paused state,
  selected audio and subtitle tracks, play method) and stop, sent to
  `/Sessions/Playing*`. Your server uses these for Continue Watching, Next Up
  and its activity dashboard.

What your server does with this data, such as logs, activity history and
plugins, is decided by its administrator, not by Marquee.

## What is stored on the device

Everything is stored in the app's private storage. Other apps cannot read it on
a non-rooted device. `android:allowBackup="false"` excludes it from Android
cloud and adb backups, and the data extraction rules
(`res/xml/data_extraction_rules.xml`) also exclude it from Android 12+
device-to-device transfer.

| Data | Where | Notes |
|---|---|---|
| Server URL, access token, user ID, user name | SharedPreferences `marquee_session` | The token is stored **in plain text** in app-private storage. It is not encrypted with the Android Keystore. Sign-out removes the token, user ID and name; the server URL is kept. |
| Per-install device ID | SharedPreferences `marquee_session` | A random UUID created on first launch. It is not derived from hardware identifiers. Clearing app data creates a new one. |
| Playback and display preferences | SharedPreferences `marquee_prefs` | Bitrate cap, preferred audio and subtitle language, subtitle mode, skip intro/credits, autoplay, audio passthrough, diagnostics overlay and image quality. See [docs/architecture/PREFS.md](docs/architecture/PREFS.md). |
| Recent searches | SharedPreferences `marquee_search` | The search terms you entered recently. |
| Home and catalogue snapshots | `files/` (JSON) | Titles, artwork tags and IDs from your library, kept so Home can draw before the network responds. They are scoped to the server and user, and replaced when the account changes. |
| Artwork cache | `cache/img` (up to 384 MiB), `cache/hero-art-v3`, `cache/startup-art-v1` | Downloaded posters and backdrops. Image URLs contain no tokens. Android may clear `cache/` when storage is low. |

The app does not record your viewing history locally beyond the snapshots
above. Watch progress is stored on your server.

Debug builds (`com.mav.marquee.debug`) also write one development file,
`files/device-profile.json`, when the player starts. It contains the device
profile (codec capabilities) that is sent to the server, and no account data.
Release builds do not write it. No build writes Quick Connect codes to storage.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Talk to your Jellyfin server. |
| `ACCESS_NETWORK_STATE` | Lets libraries check whether a network is available. Coil's network observer, for example, uses it to avoid failing requests while offline. Marquee's own code does not query network state. |
| `WAKE_LOCK` | Media3 holds a network wake lock during playback (`WAKE_MODE_NETWORK`). The player also keeps the screen on while video plays. |

Marquee asks for no location, microphone, camera, contacts, storage or
notification permissions.

## Cleartext HTTP

Many Jellyfin servers run on a home network over plain `http://`. To work with
them, Marquee permits cleartext traffic through its network security config
(`res/xml/network_security_config.xml`, `cleartextTrafficPermitted="true"` for
every host). The manifest does not set `usesCleartextTraffic`.

**Risk:** with an `http://` server address, your password (at sign-in), your
access token (on every request) and your media travel unencrypted. Anyone on the
same network path can read or alter them. On a trusted home LAN this is a
common trade-off. Over the internet it is not safe.

**Recommendation:** use an `https://` address whenever your server offers one,
and always if you reach it over the internet. Marquee uses HTTPS when you enter
an `https://` URL and validates certificates with the Android system trust store.
User-installed CAs are not trusted (the network security config lists only
system trust anchors).

**Addresses typed without `http://` or `https://`.** Marquee tries `https://`
first. It falls back to `http://` only when HTTPS cannot be reached on that
address: the name does not resolve, the connection is refused or times out, or
the port answers in plain HTTP. If a server answers HTTPS with a certificate
that is not trusted (self-signed, expired, unknown CA or wrong host name),
Marquee stops and asks you to fix the certificate or type `http://` yourself; it
does not silently switch to an unencrypted connection. An active attacker who
blocks HTTPS entirely can still cause the fallback, so type `https://` when you
know your server supports it. Whenever the address used for sign-in is
`http://`, the sign-in screen says "Unencrypted connection (http)" under the
server address.

## Signing out and removing data

- **Sign out**: Settings → *Switch user* → *Sign out and choose a user*. This
  asks the server to end the session and deletes the token locally.
- **Clear cached artwork**: Settings → *Clear image cache*.
- **Remove everything**: Android Settings → Apps → Marquee → *Clear storage*
  (or uninstall). This deletes the token, preferences, snapshots, caches and
  the device ID.
- **Revoke on the server**: a Jellyfin administrator can delete the Marquee
  device or session in the dashboard. That invalidates the token even if the
  device is not available.

## Changes

This document is versioned with the source code. Changes are listed in
[CHANGELOG.md](CHANGELOG.md).
