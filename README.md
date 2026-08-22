# ImageLingo

ImageLingo는 선택한 폴더의 이미지를 순서대로 읽어 Gemini API, OpenAI Responses API 또는 Vertex AI로 번역하고, 원문 위치에 반투명 번역 영역을 합성한 PNG를 저장하는 Android 앱입니다.

> **릴리스 상태 (2026-08-22): `1.0.0` 준비 중**
> 현재 저장소에는 `v1.0.0` 태그와 GitHub Release가 없습니다. 아래 태그/릴리스 단계는 검증이 모두 끝난 뒤 처음 게시할 때 실행합니다.

## 1.0 주요 변경점

- OpenAI `Standard`/`Flex` 서비스 티어 선택
- 기본 모델 갱신: OpenAI `gpt-5.6-luna`, Gemini API/Vertex AI `gemini-3.7-flash`
- 번역 요청 준비, 이미지 처리, HTTP 429 재시도 및 SAF 저장 흐름 개선
- 번역 영역 겹침 완화와 반투명 배경 적용
- 소스/설정 지문을 기록해 오래된 결과를 구분하고, 폴더 번역 버튼을 길게 눌러 강제 재번역 가능
- AGP 9.3.1, Gradle 9.7.1, Android API 36 및 Build-Tools 36.0.0으로 빌드 체인 갱신
- CI 검증, 서명 APK 및 태그 기반 GitHub Release 자동화

## 주요 기능

- 폴더 바로 아래의 이미지 순차 번역 및 이전/다음 페이지 이동
- Gemini API, OpenAI Responses API, Vertex AI 지원
- 번역본 전용 보기, 작업 중지, 429 일시정지 후 같은 페이지부터 계속하기
- 원문을 건드리지 않고 번역 결과를 별도 PNG로 저장
- 폴더별 이름, 용어, 높임말/반말, 호칭 및 직전 페이지 문맥 유지
- HTTP 429 지수 백오프와 `Retry-After`/Google `retryDelay` 반영
- API 응답이 제공한 입력/출력/총 토큰 사용량 표시

## 빠른 사용법

1. Android 9(API 28) 이상 기기에 앱을 설치합니다.
2. **설정**에서 제공자와 자격 증명을 입력합니다.
   - 기본 제공자: Gemini API
   - OpenAI 기본 모델: `gpt-5.6-luna`
   - Gemini API/Vertex AI 기본 모델: `gemini-3.7-flash`
   - Vertex AI `gemini-3.7-flash` 리전: `global` 권장 (`us`, `eu`도 지원)
   - OpenAI 기본 엔드포인트: `https://api.openai.com/v1/responses`
3. **폴더**에서 원본 이미지 폴더를 선택합니다.
4. **폴더 번역**을 누릅니다. 현재 결과가 있는 페이지는 건너뜁니다.
5. 기존 결과까지 다시 API로 보내려면 **폴더 번역을 길게 누릅니다**. 강제 재번역은 API 사용량과 비용이 다시 발생하며 기존 번역 PNG를 갱신합니다.

모델명과 OpenAI 엔드포인트는 설정에서 바꿀 수 있습니다. 선택한 계정에서 실제로 사용할 수 있는 모델명을 입력해야 합니다. `Flex` 지원 여부와 대기 시간은 OpenAI 계정/모델 정책에 따라 다르며, 지원하지 않는 조합에서는 `Standard`를 사용합니다.

Vertex AI의 Gemini 3 모델은 Vertex REST v1 전용 구조화 출력 형식으로 요청합니다. `gemini-3.7-flash`에는 `global`, `us`, `eu`만 입력할 수 있으며, `us`/`eu`는 각각 Google의 멀티 리전 호스트로 연결됩니다. 잘못된 요청에서 Google이 `fieldViolations`를 반환하면 앱 오류 창에 문제 필드와 설명을 함께 표시합니다.

## 출력 위치와 로컬 파일

선택한 원본 폴더 아래에 결과 폴더가 생성됩니다.

```text
원본_폴더/ImageLingo_Translated/
├─ page01_translated.png
├─ page02_translated.png
├─ translation_memory.json
└─ translation_manifest.json
```

- 원본 이미지는 수정하거나 삭제하지 않습니다.
- 일반 출력은 기존 이름 규칙을 유지합니다. `page.jpg`와 `page.png`처럼 기본 이름이 겹칠 때는 `page.jpg.translated.png`, `page.png.translated.png`처럼 원본 확장자를 남깁니다.
- `translation_memory.json`에는 이름, 용어, 높임말/반말, 호칭과 최근 1페이지 번역 문맥이 저장됩니다.
- `translation_manifest.json`에는 결과 최신 여부를 판별하기 위한 소스/설정 지문과 결과 메타데이터가 저장됩니다.
- 두 JSON 파일은 사용자가 선택한 공유 폴더에 저장되는 **평문 파일이며 암호화되지 않습니다**. 민감한 이미지나 번역을 다룬다면 폴더 접근 권한과 백업/동기화 범위를 직접 확인하세요.

### 현재 처리 범위와 제한

- 선택한 폴더의 **바로 아래 파일만** 읽으며 하위 폴더는 재귀 탐색하지 않습니다.
- JPG/JPEG, PNG, WEBP, GIF, BMP, HEIC/HEIF, AVIF 등 Android가 디코딩할 수 있는 이미지 MIME/확장자를 찾습니다. 실제 HEIF/AVIF 지원과 손상 파일 처리 여부는 기기 디코더에 따라 달라질 수 있습니다.
- API 전송본은 최대 변 1440px, JPEG 품질 80으로 줄입니다. 투명 영역은 흰색 배경으로 합성됩니다.
- 저장 합성본은 메모리 부족을 막기 위해 최대 변 4096px 및 1,000만 픽셀 상한을 적용합니다. 이를 넘는 초고해상도 원본은 비율을 유지해 축소됩니다.
- 결과 최신성은 소스 URI·이름·크기·수정 시각과 제공자·모델·엔드포인트·티어·프롬프트 지문으로 판정합니다. 문서 제공자가 크기/수정 시각을 보고하지 않으면 원본 교체 감지 정확도가 낮아질 수 있으므로 **폴더 번역 길게 누르기**로 강제 재번역하세요.

## 데이터 흐름, 개인정보 및 비용

### 번역 요청에 포함되는 데이터

페이지를 번역할 때 앱은 해당 이미지 한 장을 최대 1440px로 축소해 JPEG(품질 80)로 변환한 뒤, 다음 데이터와 함께 선택한 API 제공자에게 HTTPS로 전송합니다.

- 변환된 이미지
- 시스템 프롬프트와 구조화 출력 지시
- 폴더 문맥의 이름, 용어, 높임말/반말, 호칭 및 직전 페이지 번역
- 선택한 모델/서비스 티어 등 요청 설정

응답의 번역문과 위치 좌표는 기기에서 원문 이미지 위에 합성됩니다. 합성 PNG와 문맥/manifest JSON은 선택한 폴더에 저장됩니다. OpenAI 공식 엔드포인트 요청에는 `store: false`가 설정되지만, 각 제공자의 보관·로깅·학습·지역 정책은 해당 계정과 서비스 약관을 따릅니다.

### 자격 증명과 네트워크

- OpenAI/Gemini API 키와 Vertex 서비스 계정 JSON은 Android Keystore 키로 AES-GCM 암호화한 뒤 앱 전용 저장소에 보관합니다.
- 앱 백업은 비활성화되어 있습니다.
- 앱은 평문 HTTP를 허용하지 않습니다. 사용자 지정 OpenAI 엔드포인트도 유효한 `https://` URL만 받습니다.
- Gemini/Vertex 요청은 Google HTTPS 엔드포인트를 사용하고, Vertex `token_uri`는 `https://oauth2.googleapis.com/token`만 허용합니다.
- 사용자 지정 OpenAI 엔드포인트 운영자는 이미지, 프롬프트와 API 인증 헤더를 받을 수 있으므로 신뢰할 수 있는 엔드포인트만 사용하세요.

### 비용

ImageLingo 자체에는 API 구독이나 결제 대행이 포함되어 있지 않습니다. **요청 비용과 할당량은 사용자가 선택한 제공자, 계정, 모델, 서비스 티어에 귀속됩니다.** 화면의 토큰 수는 API 응답의 사용량이며 통화 금액이나 청구 확정값이 아닙니다. 실제 과금은 각 제공자 콘솔에서 확인하세요. 일반 번역은 현재 결과를 건너뛰어 불필요한 호출을 줄이고, 강제 재번역과 실패 후 재시도는 추가 호출/비용을 만들 수 있습니다.

## 데이터 삭제와 폴더 권한

- **API 자격 증명 삭제:** 설정의 **저장된 인증 정보 모두 삭제**를 사용하면 저장된 OpenAI/Gemini 키, Vertex 서비스 계정 JSON과 메모리의 Vertex 액세스 토큰을 지웁니다. 일반 모델/프롬프트 설정, 번역 PNG와 공유 폴더의 JSON은 별도입니다.
- **현재 폴더 문맥 초기화:** 설정의 **현재 폴더 문맥 초기화**는 `translation_memory.json`을 빈 상태로 다시 저장합니다. 번역 PNG는 삭제하지 않습니다.
- **출력 전체 삭제:** 파일 관리자에서 `ImageLingo_Translated` 폴더를 직접 삭제하면 번역 PNG, `translation_memory.json`, `translation_manifest.json`을 함께 제거할 수 있습니다.
- **SAF 폴더 권한:** 다른 폴더를 선택하면 이전 폴더의 지속 SAF 권한을 자동 해제하고 새 폴더 권한만 유지합니다. Android 설정에서 앱 데이터를 삭제하거나 앱을 제거하면 앱 내부 설정/자격 증명과 지속 폴더 권한이 정리됩니다.
- 앱에서 로컬 데이터를 지워도 이미 API 제공자에게 전송된 요청 기록, 사용량 또는 청구 기록은 지워지지 않습니다. 해당 제공자 콘솔과 정책을 확인하세요.

## 개발 환경

- Android Gradle Plugin 9.3.1
- Gradle Wrapper 9.7.1
- JDK 21(CI는 Temurin 21, 앱 Java source/target은 17)
- Android SDK Platform 36 (`compileSdk`/`targetSdk` 36)
- Android SDK Build-Tools 36.0.0
- 최소 지원 기기: Android 9(API 28)

Gradle Wrapper가 저장소에 포함되어 있으므로 시스템 Gradle을 따로 설치할 필요가 없습니다. Wrapper 배포 ZIP은 `distributionSha256Sum`으로 검증됩니다.

## 로컬 검증과 APK 빌드

필요하면 Android SDK Manager로 고정된 구성 요소를 설치합니다.

```powershell
sdkmanager "platforms;android-36" "build-tools;36.0.0"
```

### Windows PowerShell

```powershell
git clone https://github.com/solarless0814-ux/ImageLingo.git
cd ImageLingo
./gradlew.bat --version
./gradlew.bat --no-daemon clean lintDebug testDebugUnitTest assembleDebug assembleRelease
```

### macOS/Linux

```bash
git clone https://github.com/solarless0814-ux/ImageLingo.git
cd ImageLingo
chmod +x gradlew
./gradlew --no-daemon clean lintDebug testDebugUnitTest assembleDebug assembleRelease
```

서명 값을 넣지 않은 빌드 결과:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

`app-release-unsigned.apk`는 릴리스 최적화 검증용이며 설치/배포용이 아닙니다. 기기 시험에는 디버그 APK를 사용합니다.

```powershell
adb install -r ./app/build/outputs/apk/debug/app-debug.apk
```

디버그 앱 ID는 `com.rarisol.imagelingo.debug`라서 정식 앱과 함께 설치할 수 있습니다.

## GitHub Actions CI

`.github/workflows/build-apk.yml`은 `main`/`master` 푸시, Pull Request, 수동 실행과 `v*` 태그에서 동작합니다.

검증 작업은 다음을 수행합니다.

1. Gradle Wrapper 무결성 확인
2. JDK 21과 Android API 36/Build-Tools 36.0.0 설치
3. `lintDebug`, `testDebugUnitTest`, 디버그/unsigned 릴리스 APK 빌드
4. APK SHA-256 생성
5. 14일 보관 CI 아티팩트 업로드

아티팩트 이름은 `ImageLingo-v1.0.0-ci-실행번호`이고 다음 파일을 포함합니다.

```text
ImageLingo-v1.0.0-debug.apk
ImageLingo-v1.0.0-release-unsigned.apk
SHA256SUMS.txt
```

저장소의 **Actions → Android APK → 실행 → Artifacts**에서 내려받을 수 있습니다. GitHub CLI를 사용한다면 실제 실행 ID와 이름을 확인한 뒤 받습니다.

```powershell
gh run list --workflow build-apk.yml
gh run download RUN_ID --name ImageLingo-v1.0.0-ci-RUN_NUMBER --dir ./ImageLingo-artifact
```

Gradle과 GitHub Actions 의존성은 `.github/dependabot.yml`에서 매주 월요일(Asia/Seoul) 확인합니다. 자동 PR은 CI를 통과하고 호환성을 검토한 뒤 병합하세요.

## 1.0.0 서명 릴리스 준비

Android 업데이트 호환성을 유지하려면 첫 릴리스 키를 안전하게 백업하고 이후 버전에도 같은 키를 사용해야 합니다. 키 파일과 비밀번호는 Git에 커밋하지 않습니다.

### 1. JKS 릴리스 키 만들기

```powershell
keytool -genkeypair -v `
  -storetype JKS `
  -keystore ./imagelingo-release.jks `
  -alias imagelingo `
  -keyalg RSA -keysize 4096 -validity 10000
```

오프라인 백업본, 별도 보관한 비밀번호와 인증서 지문을 준비합니다. 릴리스 키를 잃으면 같은 앱 ID의 기존 설치에 업데이트를 제공할 수 없습니다.

### 2. GitHub Actions Secrets 등록

저장소의 **Settings → Secrets and variables → Actions → New repository secret**에 다음 네 값을 등록합니다.

| Secret | 값 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | JKS 파일 전체의 Base64 문자열 |
| `ANDROID_KEYSTORE_PASSWORD` | 키 저장소 비밀번호 |
| `ANDROID_KEY_ALIAS` | 위 예시에서는 `imagelingo` |
| `ANDROID_KEY_PASSWORD` | 키 비밀번호 |

```powershell
$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path ./imagelingo-release.jks))
[Convert]::ToBase64String($bytes) | Set-Clipboard
```

GitHub CLI가 로그인되어 있다면 다음처럼 등록할 수도 있습니다.

```powershell
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path ./imagelingo-release.jks)))
gh secret set ANDROID_KEYSTORE_BASE64 --body $base64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS --body imagelingo
gh secret set ANDROID_KEY_PASSWORD
```

### 3. `main` 검증 후 첫 태그 게시

`app/build.gradle`의 `versionName`과 태그가 정확히 일치해야 합니다. 현재 준비 값은 `versionName 1.0.0`, `versionCode 10000`입니다.

```powershell
git add -A
git commit -m "Prepare ImageLingo 1.0.0"
git push origin main
```

`main` CI 성공과 Secret 등록/백업을 확인한 뒤에만 첫 태그를 게시합니다.

```powershell
git tag -a v1.0.0 -m "ImageLingo 1.0.0"
git push origin v1.0.0
```

태그 워크플로는 태그/`versionName` 일치를 확인하고, 임시 JKS를 복원해 R8 최적화 APK를 빌드하고, `apksigner`와 SHA-256으로 검증한 다음 서명 APK 아티팩트와 GitHub Release를 만듭니다. 작업이 끝나면 임시 JKS를 삭제합니다.

예정된 릴리스 자산:

```text
ImageLingo-v1.0.0-release.apk
SHA256SUMS.txt
```

### 체크섬 확인

릴리스가 실제로 게시된 뒤 내려받은 파일에서 확인합니다.

```powershell
(Get-FileHash ./release/ImageLingo-v1.0.0-release.apk -Algorithm SHA256).Hash.ToLower()
Get-Content ./release/SHA256SUMS.txt
```

## 로컬 서명 릴리스 빌드

현재 PowerShell 세션에만 서명 값을 넣습니다.

```powershell
$env:IMAGELINGO_KEYSTORE_PATH = (Resolve-Path ./imagelingo-release.jks).Path
$env:IMAGELINGO_KEYSTORE_PASSWORD = 'STORE_PASSWORD'
$env:IMAGELINGO_KEY_ALIAS = 'imagelingo'
$env:IMAGELINGO_KEY_PASSWORD = 'KEY_PASSWORD'
./gradlew.bat --no-daemon clean assembleRelease
```

네 값 중 일부만 설정되면 빌드가 종료됩니다. 성공 결과는 `app/build/outputs/apk/release/app-release.apk`이며, 배포 전 다음처럼 확인합니다.

```powershell
apksigner verify --verbose --print-certs ./app/build/outputs/apk/release/app-release.apk
```

## 다음 버전 올리기

1. `app/build.gradle`의 `versionName`과 `versionCode`를 올립니다.
2. `versionCode`는 `major × 10000 + minor × 100 + patch` 규칙을 사용합니다.
3. `CHANGELOG.md`를 갱신합니다.
4. `main` CI를 먼저 통과시킨 뒤 같은 버전의 `v버전` 태그를 푸시합니다.

예: `1.2.3` → `versionCode 10203`, 태그 `v1.2.3`.

## 라이선스

[MIT License](LICENSE)
