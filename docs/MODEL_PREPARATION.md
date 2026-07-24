# Gemma 4 E2B IT 모델 준비

모델 바이너리는 Git에 넣지 않는다. 정식 파일을 별도 저장소에서 준비한 뒤 다음
세 값을 모두 검증한다.

```text
파일명: gemma-4-E2B-it.litertlm
크기: 2,588,147,712 bytes
SHA-256: 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
```

macOS:

```bash
MODEL="/absolute/path/to/gemma-4-E2B-it.litertlm"
stat -f '%z bytes' "$MODEL"
shasum -a 256 "$MODEL"
```

Linux:

```bash
MODEL="/absolute/path/to/gemma-4-E2B-it.litertlm"
stat -c '%s bytes' "$MODEL"
sha256sum "$MODEL"
```

빌드에는 절대 경로를 환경변수로 전달한다. 경로에 공백이나 한글이 있어도 변수를
항상 따옴표로 감싸면 된다.

```bash
export HJP_GEMMA4_MODEL="$MODEL"
./scripts/build-device-gemma4.sh
```

Gradle property도 지원한다.

```bash
./gradlew :app:assembleDeviceStandalone \
  -PhjpGemma4ModelPath="/absolute/path/to/gemma-4-E2B-it.litertlm"
```

다른 이름, 크기 또는 SHA-256의 파일은 Gemma 3 등 다른 모델로 대체하지 않고
즉시 실패한다. 모델 원본은 APK 외부에 한 부만 유지하고, 빌드 결과에는
`gemma-4-E2B-it.litertlm` 하나만 포함한다.
