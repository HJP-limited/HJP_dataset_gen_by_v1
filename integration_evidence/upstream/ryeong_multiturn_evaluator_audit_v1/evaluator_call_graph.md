# 원본 evaluator 실행 흐름 — BLOCKED

**§7이 요구한 16개 항목 중 확정된 것은 0개다.**

원본 evaluator 파일이 이 기기에 존재하지 않으므로 entry point, argument parser, dataset load,
fixture load, model 생성, turn 실행, session reset, tool simulation, case 채점, aggregate metric,
gate 적용, stdout/stderr, result write, exit code 중 어느 것도 코드에서 읽을 수 없었다.

`eval_multiturn.py`는 `/Users/byeol` 아래 어디에도 없다(§`reference_file_inventory.json`).

## 추측하지 않은 것

파일명이나 과거 결과 요약으로부터 호출 그래프를 역추론하지 않았다. §6이 요구한 대로 **import와
호출 경로를 확인**해야 하는데, 확인할 source가 없다.

특히 다음을 **주장하지 않는다.**

* import 시점 자동 실행 여부
* `if __name__ == "__main__"` 보호 유무
* argument parsing 전 무거운 dependency import 여부
* output을 `CREATE_NEW`로 쓰는지 truncate하는지
* partial result write 여부
* 실패 시에도 exit 0을 반환할 가능성
* random seed 고정 여부
* case 실행 순서의 determinism
* 결과를 본 뒤 gate가 바뀔 수 있는 구조인지

이 항목들은 원본이 공급되면 그때 코드에서 읽어야 한다.

## 로컬에 있는 것 — 원본이 아님

`tools/ryeong_search_benchmark/multiturn-final-20260801.json`은 evaluator가 아니라 **과거 실행의
결과 요약**이다. 이 파일 자체가 자신의 출처를 다음 셋으로 선언한다.

```
search-core/src/test/java/com/hjp/searchlookup/ContactMultiTurnSessionTest.java
agent-core/src/test/kotlin/com/hjp/agent/core/ContactTurnReferenceResolverTest.kt
tool-contact/src/test/kotlin/com/hjp/tool/contact/ContactPluginsTest.kt
```

셋 다 **이 저장소의 Kotlin/Java JUnit 테스트**다. 즉 그 수치를 만든 것은 Python evaluator가 아니다.
이 사실은 "원본 멀티턴 evaluator가 Python이다"라는 전제 자체를 재확인할 필요가 있음을 시사하지만,
그 판단은 원본 저장소가 공급돼야 내릴 수 있다.

또한 `ContactTurnReferenceResolverTest.kt`가 대상으로 삼는 `ContactTurnReferenceResolver`는
`CLAUDE.md` 기준 **deprecated이며 커널에 연결돼 있지 않다.** 따라서 그 결과 요약은 현재 production
경로의 동작을 나타내지 않는다.
