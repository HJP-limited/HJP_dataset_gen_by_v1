package com.example.hjp.v9

// GENERATED FILE — do not edit.
//
// Written by tools/ryeong_official_v9/generate_kotlin_identity_v9.py from
// tools/ryeong_official_v9/contracts/run_identity_registry.json, whose version is
// ryeong-v9-run-identity-registry-1. Its --verify mode re-derives these bytes and compares them,
// so "the runner's constants are the registry's values" is checked rather than claimed.
//
// v8 held run identity as literals in the runner and as different literals in the
// cross-run contract, each produced by its own substitution list and each verified only
// against itself. When one list moved the result schema and the other did not, both
// artefacts stayed internally correct and stopped agreeing. There was no third artefact
// that owned both facts, so nothing noticed until the reader refused the record.
//
// The runner no longer holds literals. It compiles against these constants, and these
// constants come from the registry. A value the registry does not declare cannot appear
// in the runner, because there is nowhere for it to come from.

/** Run identity, generated from the registry that owns it. */
object V9RunIdentity {

    const val REGISTRY_VERSION = "ryeong-v9-run-identity-registry-1"
    const val REGISTRY_SCHEMA = "ryeong_v9_run_identity_registry/v1"

    /** D9. declared, because this run has not happened */
    object D9 {
        const val deviceRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V9_RUN_D9_DEVICE_ACTUAL_MODEL_BASELINE"
        const val deviceResultSchema = "ryeong_v9_device_run/v1"
        const val rawTurnSchema = "ryeong_v6_raw_turn/v1"
        const val deviceOfficialNamespace = "ryeong_device_eval_v9_official"
        const val deviceInvocationMarker = "ryeong_device_eval_v9_official/invocation.marker"
        const val runtimeMode = "device_actual_model"
    }

    /** K3. the result schema, the run id and the status of this run are read out of the record it wrote, not copied from a contract that describes it */
    object K3 {
        const val hostRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V3_RUN_K3_JVM_KEYWORD_BASELINE"
        const val hostResultSchema = "ryeong_v3_official_result/v1"
        const val hostOutputNamespace = "integration_evidence/evaluation/ryeong_official_v3/result/jvm_keyword"
        const val metricSourcePath = "\$"
        const val runtimeMode = "jvm_keyword_host"
    }

    /** K4. the result schema, the run id and the status of this run are read out of the record it wrote, not copied from a contract that describes it */
    object K4 {
        const val hostRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V4_RUN_K4_JVM_KEYWORD_HOST_BASELINE"
        const val hostResultSchema = "ryeong_v4_official_result/v1"
        const val hostOutputNamespace = "integration_evidence/evaluation/ryeong_official_v4/result/jvm_keyword"
        const val metricSourcePath = "\$"
        const val runtimeMode = "jvm_keyword_host"
    }

    /** K5. the result schema, the run id and the status of this run are read out of the record it wrote, not copied from a contract that describes it */
    object K5 {
        const val hostRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V5_RUN_K5_JVM_KEYWORD_HOST_BASELINE"
        const val hostResultSchema = "ryeong_v5_official_result/v1"
        const val hostOutputNamespace = "integration_evidence/evaluation/ryeong_official_v5/result/jvm_keyword"
        const val metricSourcePath = "\$"
        const val runtimeMode = "jvm_keyword_host"
    }

    /** K6. the result schema, the run id and the status of this run are read out of the record it wrote, not copied from a contract that describes it */
    object K6 {
        const val hostRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V6_RUN_K6_JVM_KEYWORD_HOST_BASELINE"
        const val hostResultSchema = "ryeong_v6_official_result/v1"
        const val rawTurnSchema = "ryeong_v6_raw_turn/v1"
        const val hostOutputNamespace = "integration_evidence/evaluation/ryeong_official_v6/result/jvm_keyword"
        const val metricSourcePath = "\$.kotlin_official_evaluator.metrics"
        const val runtimeMode = "jvm_keyword_host"
    }

    /** K7. v7 ended at HOLD BEFORE V7 DEVICE PREFLIGHT with one failing characterization test */
    object K7 {
        const val hostRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V7_RUN_K7_JVM_KEYWORD_HOST_BASELINE"
        const val hostOutputNamespace = "integration_evidence/evaluation/ryeong_official_v7/result/jvm_keyword"
        const val hostInvocationMarker = "integration_evidence/evaluation/ryeong_official_v7/execution/jvm_keyword/invocation.marker"
        const val runtimeMode = "jvm_keyword_host"
    }

    /** K8. the result schema, the run id and the status of this run are read out of the record it wrote, not copied from a contract that describes it */
    object K8 {
        const val hostRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V8_RUN_K8_JVM_KEYWORD_HOST_BASELINE"
        const val hostResultSchema = "ryeong_v8_official_result/v1"
        const val rawTurnSchema = "ryeong_v6_raw_turn/v1"
        const val hostOutputNamespace = "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword"
        const val hostInvocationMarker = "integration_evidence/evaluation/ryeong_official_v8/execution/jvm_keyword/invocation.marker"
        const val metricSourcePath = "\$.kotlin_official_evaluator.metrics"
        const val runtimeMode = "jvm_keyword_host"
    }

    /** K9. declared, because this run has not happened */
    object K9 {
        const val hostRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V9_RUN_K9_JVM_KEYWORD_HOST_BASELINE"
        const val hostResultSchema = "ryeong_v9_official_result/v1"
        const val hostStatusSchema = "ryeong_v9_run_status/v1"
        const val rawTurnSchema = "ryeong_v6_raw_turn/v1"
        const val hostOutputNamespace = "integration_evidence/evaluation/ryeong_official_v9/result/jvm_keyword"
        const val hostInvocationMarker = "integration_evidence/evaluation/ryeong_official_v9/execution/jvm_keyword/invocation.marker"
        const val metricSourcePath = "\$.kotlin_official_evaluator.metrics"
        const val censusSourcePath = "per_metric"
        const val runtimeCounterSourcePath = "\$.kotlin_official_evaluator.runtime_counter_totals"
        const val runtimeMode = "jvm_keyword_host"
    }

    /** V9_SMOKE. declared, because this run has not happened */
    object SMOKE {
        const val smokeRunId = "RYEONG_PRODUCTION_COMPATIBILITY_V9_SMOKE_DEVICE"
        const val deviceResultSchema = "ryeong_v9_device_run/v1"
        const val deviceSmokeNamespace = "ryeong_device_eval_v9_smoke"
        const val smokeInvocationMarker = "ryeong_device_eval_v9_smoke/invocation.marker"
        const val runtimeMode = "device_smoke"
    }

    /** Every run this object carries, so a test can iterate rather than remember. */
    val DECLARED_RUNS: List<String> = listOf(
        "D9",
        "K3",
        "K4",
        "K5",
        "K6",
        "K7",
        "K8",
        "K9",
        "V9_SMOKE",
    )
}
