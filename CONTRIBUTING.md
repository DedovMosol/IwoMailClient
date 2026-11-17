# Contributing

Thanks for your interest in iwo Mail Client! This is primarily a personal
project, but issues and pull requests are welcome.

## Getting started

Requirements:

- JDK 17
- Android SDK (min API 26, see `build.gradle.kts` for compile/target SDK)
- Android Studio (latest stable) is recommended

Build and test:

```bash
./gradlew assembleDebug          # build a debug APK
./gradlew testDebugUnitTest      # run unit tests (also runs in CI)
./gradlew koverXmlReportDebug    # coverage report
```

### Live tests against a real Exchange server

`app/src/test/java/com/dedovmosol/iwomail/live/LiveExchangeTest.kt` runs the app's own EAS, EWS, IMAP
and POP3 code against a real server (written for Exchange 2007 SP2 / EAS 12.1). The tests are skipped
unless all of these environment variables are set, so CI and a normal run skip them:
`IWO_LIVE_HOST`, `IWO_LIVE_DOMAIN`, `IWO_LIVE_USER`, `IWO_LIVE_PASS` (optional `IWO_LIVE_LOG=1`
prints the app log). They use a separate device partnership and accept all certificates.
Mail is only read. The calendar test creates a synthetic series 2 weeks ahead and deletes it.

```powershell
$env:IWO_LIVE_HOST='<host>'; $env:IWO_LIVE_DOMAIN='<domain>'; $env:IWO_LIVE_USER='<user>'; $env:IWO_LIVE_PASS='<password>'
./gradlew testDebugUnitTest --tests "*LiveExchangeTest"
Remove-Item Env:IWO_LIVE_PASS
```

Never commit server addresses, account names or passwords: tests, docs and probe outputs use
`192.0.2.x` (RFC 5737), `DOMAIN\user` and `corp.example`.

## Pull requests

1. Create a feature branch from `main`.
2. Keep changes focused; one logical change per PR.
3. Make sure `./gradlew testDebugUnitTest` passes locally — CI runs it on every PR.
4. Update `docs/CHANGELOG_RU.md` / `docs/CHANGELOG_EN.md` when behavior changes.
5. Describe the change and testing you did in the PR description.

## Code style

- Kotlin official style (`kotlin.code.style=official`).
- Jetpack Compose + Material 3 for UI.
- Offline-first: UI reads from Room via Flow; background services update the DB.

## License

The project is licensed under the [GNU General Public License v3.0](LICENSE).
By submitting a contribution you agree that it is licensed under the same terms.

## Reporting bugs

Use the issue templates. For security-sensitive reports, follow
[docs/SECURITY.md](docs/SECURITY.md) ([EN](docs/SECURITY_EN.md), [RU](docs/SECURITY_RU.md)) instead of opening a public issue.
