# Contributing to ImageLingo

## Local setup

1. Install JDK 17 or newer, Android SDK Platform 36, and Build Tools 36.0.0.
2. Clone the repository. Do not add `local.properties`, keystores, API keys, or
   service-account JSON files to Git.
3. Point `ANDROID_HOME` (or `sdk.dir` in your untracked `local.properties`) to
   the Android SDK.
4. Run the complete local gate:

   ```powershell
   .\gradlew.bat --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleRelease
   ```

On Linux/macOS, use `./gradlew` instead.

## Change guidelines

- Keep provider request/response parsing bounded and fail closed on incomplete
  or malformed structured output.
- Preserve Storage Access Framework access; do not add broad storage permissions.
- Never log keys, bearer tokens, service-account JSON, prompts, or image data.
- Add a regression test for pure parsing, retry, fingerprint, or layout logic.
- Check large images, long Korean text, empty folders, cancellation, 401/429/5xx
  responses, rotation, and a read-only document provider when relevant.
- Keep touch targets at least 48dp and ensure dynamic status text is announced
  by accessibility services.

## Pull requests

Describe the user-visible behavior, risk, and verification performed. CI must
pass unit tests, Android lint, debug assembly, and minified release assembly.
Release tags must exactly match `versionName` (`v1.0.0`, for example) and should
only be created after all signing secrets have been verified.
