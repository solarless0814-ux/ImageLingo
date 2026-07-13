# ImageLingo 0.3

이미지 폴더를 만화·비주얼노벨 리더처럼 넘겨 보다가 **이미지 번역** 버튼으로 현재 이미지를 Gemini, OpenAI 또는 Vertex AI에 보내 한국어 번역을 받는 개인용 Android 앱입니다.

## 0.3 업데이트

- 번역 결과를 하단 검은 패널에 나열하지 않고 **각 원문 위치 위에 직접 표시**
- AI가 말풍선·문단별 번역문과 0~1000 정규화 좌표를 JSON으로 반환
- 원문 영역을 기준으로 둥근 반투명 번역 창의 크기를 자동 계산
- 번역문 길이에 맞춰 글자 크기와 줄바꿈을 자동 조절
- 핀치 확대·드래그 이동 시 번역 창도 이미지와 함께 정확히 이동
- 왼쪽 아래 **번역 숨기기/보기** 버튼 추가
- 번역 숨기기/보기 버튼을 길게 눌러 전체 번역문 복사
- 모델이 좌표 JSON 형식을 지키지 못할 경우에도 반투명 중앙 오버레이로 표시하는 예비 처리
- 기존 시스템 프롬프트와 우측 하단 실제 토큰 사용량 표시 유지

## 포함된 기능

- Android 폴더 선택기로 이미지 폴더 열기
- 숫자가 포함된 파일명을 자연 순서로 정렬 (`1, 2, 10`)
- 좌우 스와이프 및 이전/다음 버튼
- 핀치 확대, 드래그 이동, 두 번 탭 확대/초기화
- 현재 이미지만 압축해 API로 전송
- 번역 결과를 원문 위치별 반투명 오버레이로 표시
- 번역문 복사 및 세션 내 결과·토큰 사용량 캐시
- Gemini API 키 지원
- OpenAI Responses API 지원: 모델명과 엔드포인트 수정 가능
- Vertex AI 서비스 계정 JSON 지원: JWT로 OAuth 액세스 토큰을 발급한 뒤 Vertex `generateContent` 호출
- API 키와 Vertex JSON을 Android Keystore의 AES-GCM 키로 암호화해 앱 전용 저장소에 보관

## 시스템 프롬프트

설정 화면의 **시스템 프롬프트** 칸에서 말투, 고유명사 처리 등 번역 방식을 직접 지정할 수 있습니다. 비워 두면 앱의 기본 만화·게임 번역 프롬프트가 사용됩니다. **기본 시스템 프롬프트 채우기** 버튼으로 기본 문구를 불러와 수정할 수도 있습니다.

앱은 원문 위치 오버레이를 만들기 위해 시스템 프롬프트 뒤에 좌표 JSON 출력 규칙을 자동으로 덧붙입니다. 따라서 사용자 프롬프트에는 번역 스타일만 적는 것이 좋습니다.

예시:

```text
이미지 속 일본어를 자연스러운 한국어로 번역한다.
캐릭터 이름은 원어 발음을 살려 한글로 표기하고, 존댓말과 반말을 문맥에 맞게 유지한다.
화자별 대사를 줄바꿈으로 구분하며 번역문만 출력한다.
```

## 토큰 표시

번역이 성공하면 오른쪽 아래에 다음과 같은 배지가 나타납니다.

```text
총 1,240 · 입력 980 / 출력 260
```

표시값은 추정치가 아니라 각 API 응답에 포함된 사용량 메타데이터입니다. 제공자나 호환 엔드포인트가 사용량 정보를 반환하지 않으면 `토큰 정보 없음`으로 표시됩니다. 캐시된 번역을 다시 열면 `캐시`라고 함께 표시되며 API를 재호출하지 않습니다.

## 중요한 보안 안내

Vertex 서비스 계정 JSON에는 **개인키**가 들어 있습니다. 이 구현은 본인 기기에서 사용하는 개인용 APK를 전제로 합니다.

- JSON이나 API 키는 APK 파일에 포함되지 않고 앱 실행 후 직접 입력합니다.
- 그래도 루팅된 기기, 악성 앱, 디버그 백업 등에서는 절대적인 보호를 보장할 수 없습니다.
- 다른 사람에게 배포할 앱이라면 서비스 계정 JSON을 모바일에 넣지 말고, 본인이 관리하는 백엔드에서 Vertex AI를 호출하세요.
- 서비스 계정 권한은 필요한 프로젝트의 최소 권한으로 제한하고 사용량·결제 한도를 설정하세요.

## Android Studio에서 APK 만들기

필요 환경:

- JDK 17
- Android SDK 35
- 인터넷 연결이 가능한 Android Studio

절차:

1. 이 폴더를 Android Studio에서 엽니다.
2. Gradle 동기화를 마칩니다.
3. **Build → Build APK(s)**를 누릅니다.
4. 생성 파일은 `app/build/outputs/apk/debug/app-debug.apk`에 있습니다.

릴리스 서명 APK가 필요하면 **Build → Generate Signed Bundle / APK**를 사용하세요.

## GitHub Actions로 자동 빌드

프로젝트를 GitHub 저장소에 올리면 `.github/workflows/build-apk.yml`이 자동으로 디버그 APK를 빌드합니다.

1. 저장소의 **Actions → Build APK → Run workflow**
2. 완료된 실행 요약 화면의 **Artifacts**에서 `ImageLingo-debug-apk` 다운로드
3. ZIP을 풀고 `app-debug.apk` 설치

0.2부터 GitHub Actions의 디버그 서명 키를 캐시합니다. **기존 0.1 APK와 최초 0.2 APK의 서명이 다르면 한 번은 기존 앱을 삭제해야 설치될 수 있습니다.** 최초 0.2 빌드 이후에는 같은 저장소에서 만든 후속 APK를 일반 업데이트처럼 덮어 설치할 수 있습니다.

## 기존 GitHub 저장소 업데이트

업데이트 ZIP을 기존 `ImageLingo` 폴더에 덮어쓴 뒤 Termux에서 실행합니다.

```bash
git add .
git commit -m "Update ImageLingo to 0.3"
git push
```

Push가 끝나면 GitHub Actions가 새 APK를 자동으로 빌드합니다.

## 앱 사용법

1. 앱 실행 후 상단 **폴더**를 눌러 이미지 폴더를 선택합니다.
2. 상단 **설정**에서 사용할 API를 고릅니다.
3. 선택한 제공자에 맞게 키·모델을 입력합니다.
4. 필요한 경우 시스템 프롬프트를 입력합니다.
5. Vertex AI는 **서비스 계정 JSON 불러오기**를 누르고 JSON 파일을 선택합니다.
6. 이미지를 넘기다가 하단 **이미지 번역**을 누릅니다.
7. 번역 창을 잠시 감추려면 왼쪽 아래 **번역 숨기기**를 누릅니다. 같은 버튼을 길게 누르면 전체 번역문이 복사됩니다.

### 권장 초기 모델값

- OpenAI: `gpt-4.1-mini`
- Gemini API: `gemini-2.5-flash`
- Vertex AI: `gemini-2.5-flash`

계정에서 사용할 수 있는 모델명이 다르거나 모델이 폐기된 경우 설정에서 현재 모델명으로 바꾸면 됩니다.

## 현재 한계

- 번역 창은 원문 위에 반투명하게 덮이지만 원본 픽셀을 지우거나 이미지 파일 자체를 편집하지는 않습니다.
- 좌표는 비전 모델이 추정하므로 복잡한 세로쓰기, 휘어진 효과음, 매우 작은 글자는 위치가 조금 어긋날 수 있습니다.
- 선택한 폴더 바로 아래의 이미지만 읽으며 하위 폴더는 재귀 탐색하지 않습니다.
- PDF, ZIP/CBZ/CBR 리더 기능은 아직 없습니다.
- GIF·애니메이션 이미지는 정지 프레임으로 표시될 수 있습니다.
- 기기 밖의 API로 이미지가 전송되므로 민감한 사진에는 사용하지 않는 편이 좋습니다.

## 공식 API 참고 문서

- OpenAI Responses API 및 instructions: https://developers.openai.com/api/docs/guides/text
- OpenAI 이미지·비전 입력: https://developers.openai.com/api/docs/guides/images-vision
- Gemini GenerateContent, system instruction, usage metadata: https://ai.google.dev/api/generate-content
- Vertex AI Gemini 추론: https://cloud.google.com/vertex-ai/generative-ai/docs/model-reference/inference
- Google 서비스 계정 OAuth: https://developers.google.com/identity/protocols/oauth2/service-account

## 라이선스

이 프로젝트의 자체 소스는 MIT License로 제공됩니다. API 사용료와 각 서비스의 이용 약관은 사용자가 별도로 확인해야 합니다.
