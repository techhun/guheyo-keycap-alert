# guheyo-keycap-alert

구해요 키보드 장터의 **판매 → 키캡** 목록을 5분 간격으로 확인하고, 새 매물이 생기면 ntfy로 휴대폰 푸시 알림을 보내는 GitHub Actions 프로젝트입니다.

## 동작 방식

1. GitHub Actions가 5분마다 실행됩니다.
2. Playwright가 `https://guheyo.com/g/keyboard/sell`을 열고 `키캡` 필터를 선택합니다.
3. 현재 매물 목록을 이전 상태(`state.json`)와 비교합니다.
4. 처음 실행은 현재 목록을 기준값으로만 저장하고 알림을 보내지 않습니다.
5. 이후 새 매물만 `ntfy`로 알립니다.

## ntfy 설정

GitHub 저장소의 **Settings → Secrets and variables → Actions → New repository secret**에서 다음 Secret을 추가합니다.

- Name: `NTFY_TOPIC`
- Secret: ntfy 앱에서 구독할 충분히 긴 랜덤 토픽 이름

예: `animal-keycap-<긴 랜덤문자열>`

Android에서 ntfy 앱을 설치한 뒤 같은 토픽을 구독하면 됩니다.

## 수동 테스트

**Actions → Keycap alert → Run workflow**를 실행합니다. 첫 실행은 기준값만 저장하므로 푸시가 오지 않는 것이 정상입니다.

## 참고

GitHub 예약 실행은 정확히 매 5분을 보장하지 않으며 혼잡 시 지연될 수 있습니다.
