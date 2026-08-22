# CLI·output 계약 — BLOCKED

원본 evaluator가 없으므로 CLI argument 계약도 output write 동작도 확정할 수 없다.

| 항목 | 상태 |
|---|---|
| entry point 명령 | **UNKNOWN** |
| argument parser 종류(argparse 등) | **UNKNOWN** |
| 필수 인자 | **UNKNOWN** |
| 기본값 | **UNKNOWN** |
| output 경로가 고정인지 인자인지 | **UNKNOWN** |
| overwrite / truncate / `CREATE_NEW` | **UNKNOWN** |
| partial result write | **UNKNOWN** |
| exit code 계약 | **UNKNOWN** |

## `--help` smoke

**NOT RUN.**

§11은 정적 감사로 argparse 경로와 조기 종료를 확인한 뒤에만 `--help` 1회를 허용한다. 실행할 파일이
존재하지 않으므로 그 전제 자체가 성립하지 않는다. `NOT SAFE TO RUN`이 아니라 **대상 부재**다.

가짜 module이나 대체 script로 우회하지 않았다(§3 금지사항).

`help_command.txt`, `help_stdout.log`, `help_stderr.log`, `help_result.json`에 모두 `NOT RUN`과
사유를 기록했다.

## overwrite 위험 판정

원본을 읽지 못했으므로 **위험이 없다고도, 있다고도 말하지 않는다.** 다만 이후 작업이 원본을 확보하면
가장 먼저 확인해야 할 것은 다음이다.

1. output 경로가 코드에 고정돼 있어 기존 run을 덮어쓰는가
2. 실패 경로에서도 부분 결과를 남기는가
3. exit code가 gate 판정과 일치하는가

이 셋은 과거 이 저장소의 `RyeongDatasetProductionAdapterTest`에서 실제로 문제가 됐던 항목이며,
당시 `CREATE_NEW` + 실행별 고유 디렉터리로 격리했다. 원본에도 같은 검사를 적용해야 한다.
