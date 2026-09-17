# Restock Worker Android prototype

Galaxy Tab S7에서 네이버 SmartStore 상품 페이지를 실제 WebView로 연 뒤, 같은 페이지 컨텍스트에서 상품 API를 호출해 옵션별 재고를 확인하는 1차 검증 앱입니다.

## 현재 범위

- 기본 테스트 URL: `https://m.smartstore.naver.com/swagkey/products/12348949592`
- 앱 WebView 쿠키/세션 유지
- WebView 원격 디버깅 비활성화
- `__PRELOADED_STATE__` 또는 페이지 네트워크 리소스에서 `channelUid` 탐색
- `/i/v2/channels/{channelUid}/products/{productNo}?withWindow=false`를 페이지 내부 `fetch()`로 호출
- `optionCombinations`의 옵션명, `stockQuantity`, 구매 가능 여부 표시

아직 Discord 제어, 주기 감시, Foreground Service는 연결하지 않습니다. 이 앱에서 상품 API가 `200`으로 조회되는지 먼저 검증한 뒤 추가합니다.

## 빌드

Android Studio에서 이 디렉터리(`android/restock-worker`)를 프로젝트로 열어 빌드할 수 있습니다.

- JDK 17
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- compileSdk 37
- minSdk 26
- targetSdk 35

또는 저장소의 `Android Restock Worker APK` GitHub Actions workflow를 실행하면 debug APK artifact가 생성됩니다.

## 태블릿 테스트

1. APK를 Galaxy Tab S7에 설치합니다.
2. 집 Wi-Fi에 연결합니다.
3. 앱을 실행합니다.
4. 기본 SmartStore 상품이 정상 표시되는지 확인합니다.
5. `옵션 재고 읽기`를 누릅니다.
6. 성공 시 화면 하단에 각 옵션별 `[재고]`/`[품절]` 및 수량이 표시됩니다.

실패 시 화면의 오류 코드만 확인하면 됩니다.

- `CHANNEL_UID_NOT_FOUND`: 상품 페이지는 열렸지만 채널 식별자를 찾지 못함
- `PRODUCT_API_FAILED` + `status: 429`: WebView 세션도 SmartStore에서 제한됨
- `PRODUCT_API_FAILED` + 기타 상태: 응답 상태 확인 필요
- `JS_ERROR`: 페이지 내부 스크립트 실행 오류

로그인 페이지로 이동한다면 우선 로그인하지 말고 그 상태를 기록합니다. 비로그인 WebView 자체가 허용되는지부터 확인하는 것이 1차 목적입니다.
