# Android Emulator 테스트

## 빌드와 설치

```bash
./scripts/build-emulator-apk.sh
adb install -r dist/HJP-Agent-Emulator-debug.apk
adb shell am start -W -n com.example.hjp/.MainActivity
```

화면 상단에 다음 문구가 보여야 한다.

```text
에뮬레이터 모드: 온디바이스 LLM 미사용
```

이 variant에는 `.litertlm`이 없고 app runtime graph에도 `llm-litert`와
`litertlm-android`가 없다. deterministic router, `AgentKernel`,
`DefaultToolRegistry`, Room seed/CRUD, validation과 grounding은 device와 같은
코드를 사용한다.

## 기능 점검

```text
김지원 명함을 찾아줘.
김지원 명함 보여줘.
김지원 회사 정보를 수정해줘.
현재 시간 알려줘.
내일 오후 3시에 회의 일정 만들어줘.
test@example.com에게 내용은 안녕하세요라고 메일 작성해줘.
김지원에게 안녕하세요라고 문자 작성해줘.
존재하지 않는 사람 명함을 찾아줘.
```

Calendar/Email/SMS는 Android `Intent` 작성 화면만 연다. 저장 또는 전송은 외부
앱에서 사용자가 최종 확인한다. 해당 앱이 없는 에뮬레이터에서는 명확한 실행 실패가
반환되는 것이 정상이다.

## 검증 명령

```bash
./gradlew :app:testEmulatorDebugUnitTest
./gradlew :app:assembleEmulatorDebug
./gradlew :app:verifyEmulatorMergedAssets
unzip -l dist/HJP-Agent-Emulator-debug.apk
```

APK 목록에 `.litertlm`, `.xnnpack_cache_` 또는 LiteRT-LM native library가 없어야
한다. `assets/cards/business_cards.json`은 있어야 한다.
