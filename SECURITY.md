# Security policy

## Supported versions

Marquee is pre-1.0 software maintained on a best-effort basis. Only the
**latest published release** and the current `main` branch receive security
fixes. Older releases will not be patched. Please update first.

| Version | Supported |
|---|---|
| Latest release | Yes |
| `main` | Yes (fixes land here first) |
| Anything older | No |

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.

Report it privately with GitHub's **private vulnerability reporting**: go to the
repository's *Security* tab and choose *Report a vulnerability*. Your report is
visible only to the maintainers until an advisory is published.

Please include:

- the Marquee version (Settings → About Marquee) and how you installed it,
- the device model and Android version,
- the Jellyfin server version, if relevant,
- steps to reproduce, and what an attacker could achieve.

**Do not include real access tokens, passwords, API keys or your server's
public address.** Redact them (for example `Token="REDACTED"`). If a log is
needed, remove URLs and `Authorization` headers first.

You should receive an acknowledgement within 14 days. This is a volunteer
project, so timelines are goals, not guarantees. We will agree on a disclosure
date with you, and credit you in the advisory unless you prefer otherwise.

## Scope

In scope:

- Marquee's own source code in this repository,
- the build and release workflows under `.github/`,
- how Marquee stores and transmits credentials.

Out of scope (please report these upstream):

- Jellyfin server vulnerabilities → the
  [Jellyfin project](https://github.com/jellyfin/jellyfin/security),
- FFmpeg, AndroidX Media3, OkHttp, Coil and other dependencies → their
  respective projects. Please tell us too if Marquee needs to update a pinned
  version.
- Device or firmware issues → the vendor.

## Design decisions that are not vulnerabilities

These are documented behaviours. Reports that only restate them will be closed,
but suggestions to improve them are welcome as normal issues.

- **Cleartext HTTP is allowed.** Most self-hosted Jellyfin servers on a home LAN
  use `http://`, so Marquee permits cleartext traffic to any host. With an
  `http://` server, credentials and media travel unencrypted, and the sign-in
  screen labels the connection "Unencrypted connection (http)". For an address
  typed without a scheme, Marquee tries `https://` first and falls back to
  `http://` only when HTTPS is unreachable on that address; an untrusted
  certificate stops the sign-in instead of falling back. An attacker who can
  block HTTPS completely can still force that fallback for scheme-less input, so
  type `https://` whenever you can. See [PRIVACY.md](PRIVACY.md#cleartext-http).
- **Token storage.** The Jellyfin access token is stored in app-private
  SharedPreferences, not encrypted with the Android Keystore. Other apps cannot
  read it on a non-rooted device, and app backup is disabled. Someone with root
  access or physical debugging access to an unlocked device can read it. Signing
  out, clearing app storage or revoking the device on the server invalidates it.
- **Debug builds** (`com.mav.marquee.debug`) export `PlayerActivity`, accept
  test-only intent extras and write `files/device-profile.json` for testing. Do
  not use debug builds as daily drivers.
- **No certificate pinning.** HTTPS uses the Android system trust store.

## Release integrity

Release APKs are built by the `release` workflow from a tagged commit. Each is
published with a SHA-256 checksum file and, when available, a GitHub build
provenance attestation. You can check one with:

```bash
sha256sum -c marquee-<version>.apk.sha256
gh attestation verify marquee-<version>.apk --repo aaditagrawal/marquee
```

Signed releases use the project's release key. Compare its certificate digest
with the one published in the release notes (`apksigner verify
--print-certs`). If the workflow ran without signing secrets, the file is named
`marquee-<version>-unsigned.apk` and Android will not install it until it is
signed.
