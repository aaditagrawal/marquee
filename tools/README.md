# Tools

Host-side helpers. Each one needs explicit arguments: none contains
addresses, ports, credentials or device names, and none guesses which device
to act on.

| Tool | What it does | Touches a device? |
|---|---|---|
| [`install-apk.sh`](install-apk.sh) `--serial S --apk FILE [--compile-speed] [--launch]` | Installs one APK on one ADB device. It checks the APK's native ABIs against the device, verifies the installed bytes by SHA-256, and warns about debuggable builds. If asked, it AOT-compiles (`speed`) and launches. | Yes: install only. It changes no settings and uninstalls nothing. |
| [`device-capabilities.sh`](device-capabilities.sh) `--serial S [--out FILE]` | Read-only report of decoders, display/HDR modes, ABIs, page size and memory limits, for bug reports and capability work. | Yes: read-only `getprop`, `dumpsys` and `cat` of the codec XML. |
| [`check-motion.sh`](check-motion.sh) `[dir…]` | Fails if app sources use Compose animation APIs (see [docs/architecture/MOTION_ALLOWLIST.md](../docs/architecture/MOTION_ALLOWLIST.md)). Runs in CI. | No |
| [`third_party_notices.py`](third_party_notices.py) `REPORT [--write]` | Regenerates the dependency table in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) from Gradle's resolved release classpath and the POMs in the Gradle cache. It also copies the file into the APK assets. | No |
| [`third_party_notices.py`](third_party_notices.py) `--sync-asset` / `--check-asset` | Copies the hand-edited prose of THIRD_PARTY_NOTICES.md into the APK assets, or checks that the asset copy is byte-identical. | No |
| [`licences_closeup.py`](licences_closeup.py) `--write` / `--check` | Rebuilds the AFTER panel of `docs/media/settings-licences-closeup.png` from the `settings-end.png` golden, or checks both panels against their sources (see [docs/RELEASE_CHECKLIST.md](../docs/RELEASE_CHECKLIST.md#changing-the-in-app-source-url)). Needs Pillow: `uv run --no-project --with pillow python tools/licences_closeup.py`. | No |
| [`publish_manifest.py`](publish_manifest.py) `--write` / `--check` | Regenerates or checks [docs/PUBLISH_MANIFEST.md](../docs/PUBLISH_MANIFEST.md), the list of files safe to publish (everything `.gitignore` does not exclude) with sizes, SHA-256 hashes and an aggregate hash. Uses `git ls-files` against a throwaway repository in a temporary directory, so it creates no `.git` in the project. Run `--write` after any change to a published file. | No |
| [`fixtures/`](fixtures/) | Synthetic test-fixture generator (see [docs/dev/TESTING.md](../docs/dev/TESTING.md)). | No |

Regenerate the notices after a dependency change:

```sh
./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath > /tmp/deps.txt
uv run --no-project python tools/third_party_notices.py /tmp/deps.txt --write
```

After editing the prose of THIRD_PARTY_NOTICES.md by hand (it is the source of truth outside the
generated table), copy it into the APK and check the copy:

```sh
uv run --no-project python tools/third_party_notices.py --sync-asset
uv run --no-project python tools/third_party_notices.py --check-asset
```

Before publishing, and after changing any published file, refresh and check the publish manifest:

```sh
uv run --no-project python tools/publish_manifest.py --write
uv run --no-project python tools/publish_manifest.py --check
```

The icon and banner generator is in [`design/icon/`](../design/icon/CREDITS.txt).
Benchmark capture and analysis scripts are in [`bench/`](../bench/README.md).
