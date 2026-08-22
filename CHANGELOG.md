# 변경 기록

이 프로젝트는 [Semantic Versioning](https://semver.org/)을 따릅니다.

## 1.0.0 - 준비 중 (2026-08-22)

이 버전은 아직 태그나 GitHub Release로 게시되지 않았습니다.

### 추가

- 번역 요청의 OpenAI Service tier를 `Standard` 또는 `Flex`로 선택하는 설정 추가
- 폴더 전체 강제 재번역 추가: **폴더 번역** 버튼을 길게 누르면 기존 결과도 다시 요청하고 갱신
- 소스 이미지와 제공자/모델/엔드포인트/티어/프롬프트 설정 지문을 기록하는 `translation_manifest.json` 추가
- 저장된 OpenAI/Gemini API 키, Vertex 서비스 계정 JSON과 메모리 토큰을 한 번에 지우는 설정 추가
- Gradle Wrapper 배포 ZIP SHA-256 검증 추가
- `main`/`master` 푸시와 Pull Request에서 린트, 단위 테스트, 디버그 APK와 최적화 unsigned 릴리스 APK를 확인하는 GitHub Actions 추가
- `v버전` 태그에서 JKS로 서명하고 `apksigner` 검증 후 APK와 `SHA256SUMS.txt`를 게시하는 GitHub Release 작업 추가
- Gradle 및 GitHub Actions 의존성을 매주 확인하는 Dependabot 설정 추가
- 디버그 앱 ID 분리로 정식 앱과 테스트 앱 동시 설치 지원

### 변경

- 기본 모델을 OpenAI `gpt-5.6-luna`, Gemini API/Vertex AI `gemini-3.7-flash`로 갱신
- Android Gradle Plugin 9.3.1, Gradle 9.7.1, Android API 36, Build-Tools 36.0.0, JDK 21 빌드 체인으로 갱신(Java source/target 17 유지)
- `compileSdk`/`targetSdk`를 36으로, `versionName`을 `1.0.0`, `versionCode`를 `10000`으로 갱신
- 다른 폴더를 선택할 때 이전 폴더의 지속 SAF 권한을 해제하고 현재 폴더 권한만 유지하도록 변경
- 앱 백업과 평문 네트워크를 비활성화하고, 사용자 지정 OpenAI 엔드포인트를 HTTPS로 제한
- Vertex 서비스 계정 `token_uri`를 Google OAuth HTTPS 엔드포인트로 제한

### 개선

- 번역 요청 준비와 페이지 처리 흐름을 최적화해 체감 대기 시간 단축
- 표시용 디코드와 저장 합성용 디코드를 분리하고, 저장 경로에 최대 변 4096px/1,000만 픽셀 안전 상한 및 예외 시 bitmap 회수를 적용
- 출력 폴더를 페이지마다 다시 조회하지 않고 한 번의 스냅샷을 재사용하도록 SAF 저장 속도 개선
- 동일 이름의 출력이 있어도 source/settings 지문이 오래되면 다시 번역하도록 건너뛰기 판정 개선
- 같은 기본 이름의 JPG/PNG에만 원본 확장자를 보존해 충돌을 막고 기존 출력명 호환 유지
- 번역 말풍선 배치 시 겹침과 텍스트 가림 완화
- 말풍선 배경을 원본이 보이는 반투명 배경으로 조정
- 설정, 진행 상태와 주요 동작을 빠르게 찾도록 UI 정리
- 릴리스 빌드에 R8 코드 최적화와 리소스 축소 적용

### 보안·데이터

- API 키와 Vertex 서비스 계정 JSON을 Android Keystore 기반 AES-GCM으로 암호화해 앱 전용 저장소에 보관
- 이미지, 프롬프트와 폴더 문맥이 선택한 API 제공자로 전송되는 데이터 흐름 문서화
- 제공자/모델/서비스 티어에 따른 API 비용, 강제 재번역·재시도의 추가 사용량과 토큰 표시 한계 문서화
- 공유 출력 폴더의 `translation_memory.json`과 `translation_manifest.json`이 암호화되지 않은 평문임을 명시
- 자격 증명 삭제, 문맥 초기화, 출력 폴더 수동 삭제, SAF 권한 정리와 앱 데이터 삭제 절차 문서화

### 문서

- 릴리스 상태를 `1.0.0 준비 중`과 기준일 2026-08-22로 명확히 표시
- 로컬 검증 명령을 `lintDebug testDebugUnitTest assembleDebug assembleRelease`로 갱신
- AGP 9.3.1/Gradle 9.7.1/API 36/Build-Tools 36.0.0/JDK 21 요구 사항 추가
- JKS 생성 명령에 `-storetype JKS`를 명시하고 Secret, 태그, 서명, `apksigner`, SHA-256 절차 정리
- 아직 존재하지 않는 버전 태그와 GitHub Release 링크 제거

## 0.9.0

### 추가

- HTTP 429 응답에 지수 백오프 자동 재시도 추가
- `Retry-After` 및 Google `retryDelay` 서버 권장 대기 시간 반영
- 재시도 대기 시간과 남은 횟수를 화면 하단에 표시
- 재시도 한도 도달 시 현재 페이지 일시 정지 및 같은 페이지부터 계속하기 지원

### 개선

- 전체 줄거리/페이지 요약 생성과 저장을 제거하고 직전 페이지 문맥만 사용하도록 단순화
- API 입력 이미지 최대 변을 1600px, JPEG 품질을 82로 조정
- 최대 출력 토큰을 2500으로 조정
- 번역 말풍선의 반투명도와 테두리를 개선하고 배경/텍스트 2단계 렌더링으로 겹침 가독성 향상

## 0.8.0

- 폴더별 번역 메모리 추가
- 등장인물 이름, 말투, 고유명사, 인칭 통일 지원
- 번역 메모리 JSON 직접 편집 및 초기화 지원

## 0.7.0

- 번역 중 페이지 이동 및 번역본 전용 실시간 보기 지원

## 0.6.0

- 상단에 완료 페이지, 백분율, 현재 처리 페이지를 실시간 표시
- 번역된 파일만 보는 모드 추가
