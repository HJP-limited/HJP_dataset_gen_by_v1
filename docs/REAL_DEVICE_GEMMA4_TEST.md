# Gemma 4 E2B 실기기 빌드·테스트

## 빌드

[모델 준비 가이드](MODEL_PREPARATION.md)의 이름·크기·SHA-256 검증을 먼저 한다.

```bash
export HJP_GEMMA4_MODEL="/absolute/path/to/gemma-4-E2B-it.litertlm"
./scripts/build-device-gemma4.sh
./scripts/install-device-apk.sh
```

APK는 debug key로 서명되고 `arm64-v8a`만 포함한다. 모델은 APK asset에서 앱 전용
외부 files directory로 최초 한 번 복사되며 앱이 크기와 SHA-256을 다시 검증한다.

## 필수 요청

아래 요청을 순서대로 실행하고 tool sequence, 최종 응답, fallback 여부를 기록한다.

```text
김지원 명함을 찾아줘.
비전글로벌 대표의 명함을 확인해 줘.
코어AI에서 인공지능 개발을 담당하는 사람을 찾아줘.
판교에서 AI 개발을 담당하는 사람을 찾아줘.
김지원 연락처 보여줘.
현재 시간 알려줘.
내일 오후 3시에 회의 일정 만들어줘.
test@example.com한테 지난번 미팅에 대한 감사 메일 작성해줘.
김지원에게 지난 상담에 감사하고 다음 주에 연락하겠다는 문자 작성해줘.
존재하지 않는 사람 명함을 찾아줘.
오늘 기분이 어때?
```

확인 기준:

- 앱 시작만으로 Engine이 로드되지 않는다.
- 첫 deterministic 요청 뒤에도 모델 초기화 로그가 없다.
- 첫 비정형 요청에서 Gemma 4가 정확히 한 번 초기화된다.
- compose draft도 두 번째 Engine을 만들지 않는다.
- 명함에 없는 recipient나 개인정보를 생성하지 않는다.
- 검색 0건은 `찾지 못했습니다`로 끝난다.
- 검색 로그가 Ryeong `keyword → semantic → RRF` 단일 경로를 표시한다.
- `search_contacts` 결과에는 전화번호·이메일·상세 주소가 없고 `get_contact`에서만
  실제 Room 값이 나온다.
- 동명이인은 한 사람을 임의 선택하지 않고 사용자 확인을 요구한다.
- 명함 생성·수정·삭제 직후 검색 결과와 local embedding snapshot이 갱신된다.
- Calendar/Email/SMS는 실제 Intent 작성 화면을 열고 자동 저장·전송하지 않는다.
- Room 수정 후 앱 재시작에도 변경이 유지된다.

## 시간·메모리·안정성

개발 PC의 `adb`로 측정한다. 첫 요청과 warm 요청을 구분한다.

```bash
adb shell dumpsys meminfo com.example.hjp
adb shell top -b -n 1 -p "$(adb shell pidof -s com.example.hjp)"
adb logcat -c
adb logcat -v threadtime
```

다음을 실제 기기별로 기록한다.

1. Gemma 4 초기화 성공/실패와 초기화 시간
2. 첫 요청 latency와 전체 응답 시간
3. warm 요청 latency
4. 앱 PSS(초기, 첫 추론 후, 10회 후)
5. 연속 10회 요청 중 tool sequence와 fallback
6. 백그라운드 전환 후 복귀 및 session 상태
7. 10회 전후 배터리와 표면 발열
8. OOM, `SIGILL`, native crash, ANR 유무
9. 5,000장 상당 데이터에서 검색 평균/p95 latency와 index rebuild 시간

PSS는 Android가 보고한 프로세스 메모리이며 GPU 메모리나 시스템 전체 메모리로
표현하지 않는다. 발열과 배터리는 기기·주변 온도에 따라 달라 로컬 빌드만으로
판정할 수 없다.
