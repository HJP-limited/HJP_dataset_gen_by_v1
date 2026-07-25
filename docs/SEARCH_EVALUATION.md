# Search evaluation

실제 개인정보를 사용하지 않고 실행 시 생성하는 합성 명함 5,000건과 340 query를
사용한다. category는 exact/partial name, company, title, department, industry,
location, memo, tags, email, phone, semantic natural language, nonexistent,
ambiguous/duplicate, special characters, mixed Korean/English다.

```bash
./gradlew :search-core:searchEvaluation
```

2026-07-25 CPU JVM 측정:

| metric | 0711 legacy | 0725 Ryeong |
|---|---:|---:|
| top1 accuracy | 0.9375 | 1.0000 |
| recall@5 | 0.9375 | 1.0000 |
| MRR | 0.9375 | 1.0000 |
| nDCG@5 | 0.9375 | 1.0000 |
| nonexistent false positive | 1.0000 | 0.0000 |
| ambiguous auto selection | 0 | 0 |
| average latency | 8.22 ms | 11.90 ms |
| p95 latency | 10.65 ms | 36.33 ms |
| index build | 103.48 ms | 291.48 ms |
| measured JVM heap delta | 38,626,360 B | 83,057,176 B |

메모리 값은 강제 GC 전후 JVM heap 사용량 차이이며 native RSS나 Android PSS가
아니다. 실제 기기 PSS, 발열, 배터리는 실기기 절차에서 별도로 측정해야 한다.
0725 결과는 권장 recall/MRR/p95/false-positive 기준을 모두 통과했다.
