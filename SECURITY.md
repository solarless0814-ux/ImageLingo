# Security policy

## Supported code

Security fixes are applied to the `main` branch and to the newest published
release. Older APKs should be upgraded before a report is reproduced.

## Reporting a vulnerability

Please use the repository's **Security → Report a vulnerability** form so API
keys, service-account details, private folder names, and proof-of-concept data
are not posted publicly. Include the affected version/commit, Android version,
provider, reproduction steps, and the smallest redacted log that demonstrates
the issue. Do not attach real credentials or private images.

For a suspected leaked credential, revoke it with the provider immediately and
use ImageLingo's credential deletion action. GitHub issues are appropriate only
after sensitive values and image contents have been removed.

## Security design notes

- Network requests require HTTPS; cleartext traffic is disabled in the manifest.
- API keys and the Vertex service-account JSON are encrypted with an
  Android Keystore-backed AES-GCM key and excluded from backup/device transfer.
- The selected images, system prompt, and folder continuity context are sent to
  the configured third-party API. ImageLingo does not operate a relay server.
- `translation_memory.json` and translated PNGs are ordinary files in the
  user-selected folder. They are intentionally portable and are **not encrypted**.
- Custom OpenAI-compatible endpoints receive the configured bearer token and
  image. Only use an HTTPS host that you control and trust.

## Release integrity

Release-signing credentials are supplied to GitHub Actions as encrypted
secrets and are never committed. Published APKs include `SHA256SUMS.txt`; the
workflow also verifies the APK signature before creating a GitHub Release.
