## What and why

<!-- What does this change, and why? Link related issues. -->

## How it was tested

<!-- Commands run and their results. Device model, Android version and Jellyfin version for any on-device testing. Say plainly what was NOT tested. -->

- [ ] `./gradlew :app:lintDebug :app:verifyRoborazziDebug` passes
- [ ] `./gradlew :app:assembleDebug :app:assembleRelease` passes
- [ ] Tested on a device: <!-- model / Android version / Jellyfin version, or "not tested on device" -->

## Checklist

- [ ] Test fixtures and screenshots use only synthetic data (no real library titles, artwork, server addresses, IDs or tokens)
- [ ] Any performance claim includes raw data and how it was measured (see CONTRIBUTING.md)
- [ ] Re-recorded goldens (`:app:recordRoborazziDebug`) only for intended visual changes, and explained each changed PNG above
- [ ] New source files carry an SPDX header (`SPDX-License-Identifier: GPL-3.0-or-later`)
- [ ] I agree to license my contribution under GPL-3.0-or-later and certify the DCO (`Signed-off-by:` in each commit)
