# Android Studio 가상 디바이스 테스트 가이드

이 문서는 HJP 앱을 Android Studio의 Android Virtual Device(AVD)에서 직접 실행하고, 수동 기능 테스트와 자동 계측 테스트를 수행하는 전체 절차를 설명한다.

## 1. HJP Emulator 테스트의 범위

HJP는 실행 환경에 따라 모델 경로가 다르다.

| 환경 | 모델 경로 | `.litertlm` 필요 여부 |
|---|---|---|
| 실제 Android 단말 | `LiteRtAgentModelGateway` | 필요 |
| Android Emulator | `LocalToolRoutingModelGateway` | 필요 없음 |

Emulator에서는 ARM CPU feature 오탐으로 인한 LiteRT-LM native crash를 피하기 위해 규칙 기반 호환 라우터를 사용한다. 호환 라우터는 LLM이 아니지만 다음 production 구성은 실제 단말과 동일하게 사용한다.

- `AgentKernel` ReAct loop
- tool registry와 contract
- 입력 codec validation
- 사용자 confirmation 정책
- tool별 timeout과 중복 실행 방지
- Room 명함 DB와 로컬 검색 backend
- Android 캘린더·메일·문자 Intent
- tool observation과 Compose UI event 처리

따라서 Emulator는 앱 UI, tool chain, DB, 정책, Intent 연동을 확인하는 데 적합하다. 실제 모델의 자연어 이해, 추론 정확도, 속도, 메모리 사용량은 실제 단말에서 별도로 검증해야 한다.

## 2. 사전 준비

필요한 환경:

- Android Studio
- JDK 21
- Android SDK 36.1
- Android Emulator
- API 36 이상의 Android system image
- 최소 5GB 이상의 여유 저장 공간

프로젝트 루트:

```text
sojung/
```

Android Studio에서 이 디렉터리를 프로젝트로 연다. 하위 `app` 디렉터리만 별도 프로젝트로 열면 multi-module Gradle 구성을 읽지 못한다.

### Gradle JDK 설정

Android Studio에서 다음 메뉴를 연다.

```text
Settings
  > Build, Execution, Deployment
  > Build Tools
  > Gradle
```

`Gradle JDK`를 JDK 21로 설정한다. 프로젝트는 Gradle toolchain 21을 사용한다.

## 3. AVD 생성

1. Android Studio에서 `View > Tool Windows > Device Manager`를 연다.
2. `+` 버튼을 누르고 `Create Virtual Device`를 선택한다.
3. `Pixel 8`과 같은 Phone hardware profile을 선택한다.
4. API 36 이상의 system image를 선택한다.
5. 가능하면 `Google Play` 표시가 있는 이미지를 선택한다.
6. Apple Silicon Mac에서는 ARM64 image를 선택한다.
7. AVD 이름을 확인하고 생성한다.

Google Play image를 권장하는 이유는 Play Store를 통해 Google Calendar, Gmail, Messages 등 외부 작성 앱을 설치할 수 있기 때문이다. HJP의 명함과 현재 시각 기능만 시험할 때는 외부 앱이 없어도 되지만, 일정·이메일·문자 tool을 시험하려면 해당 Intent를 처리할 앱이 필요하다.

공식 문서: [Create and manage virtual devices](https://developer.android.com/studio/run/managing-avds)

## 4. Emulator 시작과 상태 확인

Device Manager에서 생성한 AVD의 실행 버튼을 누른다. Android 홈 화면이 완전히 나타날 때까지 기다린다.

터미널에서 연결 상태를 확인한다.

```bash
/opt/homebrew/share/android-commandlinetools/platform-tools/adb devices -l
```

정상 예시:

```text
List of devices attached
emulator-5556    device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64
```

상태가 반드시 `device`여야 한다. `offline`, `unauthorized` 또는 목록에 나타나지 않는 상태에서는 Android Studio 설치와 계측 테스트가 정상 동작하지 않는다.

## 5. `offline` 상태 복구

현재 AVD가 `offline`이면 다음 순서로 처리한다.

1. Device Manager에서 AVD 메뉴를 열고 `Stop`을 선택한다.
2. 다시 메뉴를 열고 `Cold Boot`를 실행한다.
3. 홈 화면이 나타난 뒤 `adb devices -l`을 다시 확인한다.
4. 계속 offline이면 Device Manager에서 `Wipe Data`를 실행한다.
5. AVD를 다시 시작한다.

ADB server 문제라면 다음을 실행한다.

```bash
/opt/homebrew/share/android-commandlinetools/platform-tools/adb kill-server
/opt/homebrew/share/android-commandlinetools/platform-tools/adb start-server
/opt/homebrew/share/android-commandlinetools/platform-tools/adb devices -l
```

동일한 AVD가 중복 실행 중이면 모든 emulator 창을 종료한 뒤 하나만 다시 시작한다.

`Wipe Data`는 AVD에 설치한 앱과 데이터를 모두 삭제한다. HJP의 Room DB, 외부 앱 설치 상태, 로그인 상태도 사라진다.

## 6. Android Studio에서 앱 실행

Android Studio 상단 실행 영역을 다음과 같이 설정한다.

- Run configuration: `app`
- Target device: 생성한 AVD
- Build variant: `debug`

그다음 Run 버튼을 누른다. Android Studio가 debug APK를 빌드하고 AVD에 설치한 후 `MainActivity`를 실행한다.

공식 문서: [Build and run your app](https://developer.android.com/studio/run)

### 정상 실행 기준

화면에 다음이 표시되어야 한다.

- `HJP Agent` 제목
- `온디바이스 Single ReAct · 검색/조회/외부 작성 연동` 설명
- 활성화된 요청 입력창
- 비활성화된 보내기 버튼
- 예시 명함 검색 문구

Emulator에서는 `.litertlm` 파일이 없어도 입력창이 활성화된다. 모델 파일 필요 안내가 표시되거나 입력창이 계속 비활성화되면 AVD가 emulator로 인식되지 않았거나 앱이 정상적으로 다시 설치되지 않은 상태인지 확인한다.

## 7. 수동 기능 테스트

각 테스트는 이전 결과가 다음 테스트에 영향을 주지 않도록 필요할 때 `새 대화`를 누르고 시작한다.

### 7.1 명함 검색

입력:

```text
판교 AI 개발자 찾아줘
```

확인 사항:

- `오성령`이 검색되는가
- 회사 `코어AI`, 직함 `AI 엔지니어`, 지역 `판교`가 표시되는가
- 검색 결과에 전화번호나 이메일이 직접 노출되지 않는가

### 7.2 존재하지 않는 이름

입력:

```text
김민수 명함 찾아줘
```

예상 결과:

```text
‘김민수’에 해당하는 명함을 찾지 못했습니다.
```

이 테스트는 feature-hashing 검색이 모르는 짧은 한글 이름에 그럴듯한 false positive를 반환하지 않는지 확인한다.

### 7.3 현재 날짜·시각

입력:

```text
현재 날짜와 시간을 알려줘
```

확인 사항:

- 날짜가 `yyyy-MM-dd` 형식인가
- 시각이 `HH:mm:ss` 형식인가
- AVD timezone과 결과 timezone이 일치하는가

AVD timezone은 Android 설정의 `System > Date & time > Time zone`에서 확인하거나 변경할 수 있다.

### 7.4 명함 수정과 confirmation

입력:

```text
김지원 명함 메모를 VIP로 변경해 줘
```

예상 순서:

1. `search_contacts`로 김지원 검색
2. `get_contact`로 단건 상세 조회
3. 앱 안에 수정 confirmation 표시
4. `실행` 또는 `취소` 선택
5. 실행한 경우 `update_business_card`로 Room DB 수정

두 경로를 모두 확인한다.

- `취소`: 수정이 실행되지 않고 취소 메시지가 나타나야 한다.
- `실행`: 명함 수정 완료 메시지가 나타나야 한다.

수정 후 앱을 종료하고 다시 실행해도 Room DB에 수정 내용이 남아 있어야 한다. 다만 현재 검색 결과 UI에는 메모가 표시되지 않으므로 메모 persistence는 Android Studio의 Database Inspector로 확인한다.

1. HJP 앱을 debug 상태로 실행한다.
2. `View > Tool Windows > App Inspection`을 연다.
3. 실행 중인 `com.example.hjp` process를 선택한다.
4. `Database Inspector`에서 `hjp-agent.db`를 연다.
5. `business_cards` table에서 `C001` row의 `memo`가 `VIP`인지 확인한다.
6. 앱을 종료했다가 다시 실행한 후에도 같은 값을 확인한다.

Database Inspector는 API 26 이상에서 Room/SQLite DB를 실행 중에 조회할 수 있다. 공식 문서: [Debug your database with the Database Inspector](https://developer.android.com/studio/inspect/database)

검색 backend는 수정 직후 cache를 무효화하므로 수정 후 명함 검색도 오류 없이 정상 동작해야 한다.

### 7.5 상대 날짜 일정

입력:

```text
내일 오후 2시 일정 만들어 줘
```

예상 순서:

1. `get_current_datetime` 호출
2. 현재 날짜를 기준으로 내일 오후 2시 계산
3. `create_calendar_event` 호출
4. 외부 캘린더 일정 작성 화면 표시

확인 사항:

- 날짜가 실제 내일인가
- 시작 시각이 오후 2시인가
- 종료 시각이 생략된 경우 오후 3시인가
- HJP가 저장 완료라고 말하지 않고 저장 전 확인을 안내하는가

캘린더 작성 화면이 열리지 않으면 Play Store에서 Google Calendar를 설치하고 다시 시도한다.

### 7.6 이메일 작성

입력:

```text
ai@example.com에게 제목: 테스트 본문: 안녕하세요 이메일 작성해 줘
```

확인 사항:

- 이메일 작성 앱이 열리는가
- 수신자가 `ai@example.com`인가
- 제목이 `테스트`인가
- 본문이 `안녕하세요`인가
- HJP가 전송 완료라고 말하지 않는가

이메일 앱이 없으면 Google Play image의 Play Store에서 Gmail을 설치한다.

### 7.7 문자 작성

입력:

```text
010-1234-5678에게 메시지: 테스트입니다 문자 작성해 줘
```

확인 사항:

- SMS 작성 앱이 열리는가
- 수신자 전화번호가 입력되어 있는가
- 본문이 입력되어 있는가
- 실제 전송은 사용자가 눌러야 하는가

SMS 앱이 없으면 Google Messages를 설치한다.

### 7.8 명함 기반 이메일 chain

입력:

```text
오성령에게 본문: 안녕하세요 이메일 작성해 줘
```

예상 순서:

1. 이름으로 명함 검색
2. 검색 결과가 한 명이면 상세 조회
3. 상세정보에서 이메일 선택
4. 이메일 작성 화면 열기

이 테스트는 `search_contacts -> get_contact -> open_compose` 다단계 tool chain을 확인한다.

## 8. 자동 계측 테스트

AVD가 `device` 상태이고 캘린더·이메일·문자 앱이 모두 설치되어 있으면 다음 명령을 실행한다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :app:connectedDebugAndroidTest
```

공식 문서: [Test from the command line](https://developer.android.com/studio/test/command-line)

Android Studio에서는 다음 파일을 연 뒤 class 또는 개별 test 왼쪽의 Run 버튼을 눌러도 된다.

```text
app/src/androidTest/java/com/example/hjp/AgentScreenInstrumentedTest.kt
```

계측 테스트는 다음 6개 항목을 검사한다.

1. 앱 제목과 설명 표시
2. model readiness에 따른 입력 활성화와 보내기 버튼 상태
3. Activity recreation 이후 작성 중 draft 보존
4. 새 대화 실행 시 draft와 UI session 초기화
5. 캘린더·이메일·SMS Intent resolver 존재
6. 존재하지 않는 명함 검색의 end-to-end 처리

계측 테스트의 `ReadableModelFileRule`은 앱 시작 전에 읽을 수 있는 sentinel model file을 만든다. Emulator에서는 native LiteRT engine을 열지 않으므로 실제 모델 binary가 필요하지 않다.

### 계측 테스트 실패 해석

`calendarEmailAndSmsIntentsResolveForAppUid`가 실패하면 HJP core 문제가 아니라 AVD에 Intent를 처리할 외부 앱이 없는 경우가 많다. Calendar, Gmail, Messages를 설치한 뒤 다시 실행한다.

여러 device가 연결되어 있으면 Gradle이 모든 연결 device를 대상으로 test할 수 있다. 테스트할 AVD 하나만 남기고 다른 emulator 또는 실제 단말 연결을 종료하는 것이 명확하다.

## 9. 앱 데이터 초기화

HJP 앱 데이터만 삭제하려면 다음을 실행한다.

```bash
/opt/homebrew/share/android-commandlinetools/platform-tools/adb shell pm clear com.example.hjp
```

삭제되는 데이터:

- Room DB `hjp-agent.db`
- 수정된 명함 정보
- 앱 내부 상태

다음 앱 실행 시 `business_cards.json`의 초기 명함 2건이 다시 seed된다.

AVD 전체를 초기화할 필요가 없으면 Device Manager의 `Wipe Data` 대신 위 명령을 사용한다. `pm clear`는 외부 Calendar, Gmail, Messages의 데이터는 삭제하지 않는다.

## 10. Logcat 확인

Android Studio에서 `View > Tool Windows > Logcat`을 열고 다음 filter를 사용한다.

```text
package:com.example.hjp
```

확인할 내용:

- 앱 process crash 여부
- Room 초기화 또는 asset parsing exception
- 외부 Activity 실행 실패
- ViewModel에서 처리되지 않은 exception

실제 단말의 LiteRT 초기화 로그를 확인할 때는 다음 tag도 사용할 수 있다.

```text
tag:HjpLiteRt
```

Emulator는 `LocalToolRoutingModelGateway`를 사용하므로 정상적인 emulator 실행에서는 LiteRT engine 초기화 로그가 발생하지 않는다.

## 11. 자주 발생하는 문제

| 증상 | 원인 후보 | 해결 방법 |
|---|---|---|
| AVD가 `offline` | snapshot 또는 ADB server 문제 | Stop, Cold Boot, Wipe Data, ADB restart 순서로 처리 |
| 입력창 비활성화 | emulator 감지 실패 또는 앱 재설치 문제 | Google API/Play phone AVD 사용, 앱 삭제 후 재실행 |
| 캘린더 화면이 안 열림 | 캘린더 resolver 없음 | Google Calendar 설치 |
| 이메일 화면이 안 열림 | 이메일 resolver 없음 | Gmail 설치 |
| 문자 화면이 안 열림 | SMS resolver 없음 | Google Messages 설치 |
| 수정 내용이 예상과 다름 | 기존 Room DB 상태가 남음 | `adb shell pm clear com.example.hjp` 후 재실행 |
| 자연스러운 표현을 이해하지 못함 | Emulator router는 규칙 기반 | 이 문서의 검증 문장을 사용하거나 실제 단말 모델에서 확인 |
| 계측 테스트가 device를 찾지 못함 | AVD가 아직 boot 중이거나 offline | 홈 화면까지 기다리고 `adb devices -l` 확인 |

## 12. 테스트 완료 기준

다음 조건을 모두 만족하면 Emulator 기준 검증이 완료된 것으로 볼 수 있다.

- 앱이 crash 없이 실행된다.
- 입력과 새 대화가 정상 동작한다.
- 명함 검색 결과가 정확하다.
- 존재하지 않는 짧은 한글 이름에 false positive가 없다.
- 현재 시각과 상대 날짜 일정 변환이 맞다.
- 명함 수정 전에 confirmation이 표시된다.
- 수정 결과가 Room DB에 유지된다.
- 캘린더·이메일·문자 작성 화면이 올바른 초안으로 열린다.
- 앱이 외부 저장 또는 전송 완료를 주장하지 않는다.
- `connectedDebugAndroidTest`의 계측 테스트 6개가 통과한다.

이 검증은 Emulator 호환 라우터와 Android app integration에 대한 검증이다. 실제 `.litertlm` 모델의 native function calling은 실제 Android 단말에서 별도로 검증해야 한다.
