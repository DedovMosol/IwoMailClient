# Contributing

Issues and pull requests are welcome. For a security-sensitive report use the [security policy](docs/SECURITY.md), not a public issue.

## Getting started

Use the Gradle wrapper from the repository root with **JDK 17** and **Android SDK Platform 36**. The minimum device API is 26 (Android 8.0); it is not the SDK used to compile. See [`app/build.gradle.kts`](app/build.gradle.kts) for the build variants and dependencies.

**On a fresh clone:** the easter-egg audio file `app/src/main/res/raw/pashalka_iwo.m4a` is intentionally not distributed. The code references `R.raw.pashalka_iwo`, so the resource must exist for compilation and unit tests. Supply a local audio file you have the right to use, or create the build-only placeholder used by [CI](.github/workflows/ci.yml). The placeholder does not play audio. Do not overwrite an existing file or commit the local resource.

```sh
# Linux/macOS/Git Bash
[ -f app/src/main/res/raw/pashalka_iwo.m4a ] || printf 'placeholder' > app/src/main/res/raw/pashalka_iwo.m4a
```

```powershell
# Windows PowerShell
if (-not (Test-Path app/src/main/res/raw/pashalka_iwo.m4a)) { Set-Content app/src/main/res/raw/pashalka_iwo.m4a -Value placeholder -Encoding ascii }
```

```sh
./gradlew :app:assembleDebug        # debug APKs: app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # JVM tests, including Compose UI under Robolectric
./gradlew :app:koverXmlReportDebug  # app/build/reports/kover/reportDebug.xml
./gradlew :app:lintDebug            # checks against app/lint-baseline.xml
./gradlew :app:assembleRelease      # minified release APKs; does not publish a release
```

On Windows PowerShell, use `./gradlew.bat` instead of `./gradlew`. CI runs the debug unit tests, Kover, lint and the release build on pushes and pull requests targeting `main`. See the [test guide (RU)](app/src/test/README.md) for package-level examples, filtered test commands and report locations.

### Live tests against a real Exchange server

The [`live/` tests](app/src/test/java/com/dedovmosol/iwomail/live) target a legacy Exchange test server (2007, EAS 12.1). They are skipped unless `IWO_LIVE_HOST`, `IWO_LIVE_USER` and `IWO_LIVE_PASS` are non-empty. `IWO_LIVE_DOMAIN` is optional. `IWO_LIVE_LOG` enables extra logging in `LiveExchangeTest`; keep it unset unless diagnosing a test, and redact outputs before sharing. Some tests accept otherwise untrusted certificates for the lab connection; these settings are not a recommended account configuration.

**Use a dedicated test mailbox, not personal or production mail.** The scenarios have different side effects:

- `LiveExchangeTest` reads real mail bodies and attachments, checks EAS/EWS and JavaMail connections, and creates, edits and deletes a synthetic recurring calendar event.
- `LiveServerIdProbeTest` performs full folder synchronizations and sets and clears the flag on an Inbox item to check that flags and bodies survive Exchange 2007 ServerId changes.
- `LiveLockRecoveryTest` checks the account-password verification path when no password is stored locally. It intentionally submits a wrong password as well as the right one; the server's account-lockout policy applies.

Set the variables in a private shell using your usual secret-handling mechanism. Gradle may consider tests up to date after a skipped run; use `--rerun-tasks` to run the selected scenario explicitly, for example:

```powershell
./gradlew.bat :app:testDebugUnitTest --rerun-tasks --tests '*LiveExchangeTest'
```

For a targeted write check on an authorized test mailbox, set `IWO_LIVE_WRITE=1` in addition to the connection variables and select `*LiveDraftRoundTripTest`. It creates a uniquely named draft with no recipients, fetches the original body, edits it by the returned EWS ItemId, fetches it again, and deletes only that draft in `finally`. This check uses normal TLS verification (optional `IWO_LIVE_CA_FILE` for a trusted server certificate), not accept-all. A skipped test is not a successful server check. Do not enable credential or message-body logging.

Select the other live classes by name in the same way. Unset the live variables afterwards so subsequent ordinary test runs stay offline. Never include credentials, internal host names, real mail or unredacted probe output in an issue or PR.

### Settings screenshots for a visual check

`SettingsScreenshotProbe` renders settings components using sample data, different themes, larger text and choice controls. It is skipped unless `IWO_SHOTS=1`; the images go to `app/build/settings-shots/`.

```powershell
$env:IWO_SHOTS = '1'
try { ./gradlew.bat :app:testDebugUnitTest --rerun-tasks --tests '*SettingsScreenshotProbe' } finally { Remove-Item Env:IWO_SHOTS }
```

## Pull requests

1. Branch from `main` and keep the change focused.
2. Follow the existing Kotlin style. For UI changes use Jetpack Compose and Material 3; for mail data, prefer the existing Room/repository flow over direct network access from a screen. See the [architecture](docs/ARCHITECTURE_EN.md).
3. Include a regression test for a bug fix where practical. Describe the checks you actually ran, including the server/protocol or Android version when relevant.
4. When behavior changes, update both [Russian](docs/CHANGELOG_RU.md) and [English](docs/CHANGELOG_EN.md) changelogs. Keep paired README and policy changes in sync.

## License

The project is licensed under the [GNU General Public License v3.0](LICENSE). By submitting a contribution you agree that it is licensed under the same terms.
