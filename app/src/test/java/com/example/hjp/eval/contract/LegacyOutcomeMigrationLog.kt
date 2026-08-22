package com.example.hjp.eval.contract

import com.hjp.agent.contract.TurnOutcomeType
import java.io.File

/**
 * Every place a legacy dataset's typed-outcome expectation was read through the v4 translation.
 *
 * The translation is what lets v1–v3 keep their frozen expectations while the app runs the newer
 * contract. Left implicit it would be a silent allowance, so each use is recorded here with the case
 * it came from and written out as evidence. A reader can therefore see the exact set of turns whose
 * frozen expectation no longer describes correct behaviour, without opening a single frozen file.
 */
object LegacyOutcomeMigrationLog {

    data class Entry(
        val datasetVersion: String,
        val caseId: String,
        val turnIndex: Int,
        val userText: String,
        val category: String,
        val frozenExpected: TurnOutcomeType,
        val projectedExpected: TurnOutcomeType,
    )

    private val entries = linkedSetOf<Entry>()

    @Synchronized
    fun record(
        datasetVersion: String,
        caseId: String,
        turnIndex: Int,
        userText: String,
        category: String,
        frozenExpected: TurnOutcomeType,
        projectedExpected: TurnOutcomeType,
    ) {
        entries += Entry(datasetVersion, caseId, turnIndex, userText, category,
                         frozenExpected, projectedExpected)
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.sortedWith(compareBy({ it.datasetVersion }, { it.caseId }, { it.turnIndex }))

    @Synchronized
    fun clear() = entries.clear()

    /** Writes the migration record for whatever has been observed so far. */
    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val rows = snapshot()
        file.writeText(
            buildString {
                append("{\n")
                append("  ${q("what")}: ${q(
                    "turns whose legacy (v1-v3) expected typed outcome no longer describes correct " +
                        "behaviour under the v4 contract",
                )},\n")
                append("  ${q("original_datasets_modified")}: false,\n")
                append("  ${q("legacy_contract")}: ${q(EvaluationContractVersion.LEGACY_V1_V3.name)},\n")
                append("  ${q("current_contract")}: ${q(EvaluationContractVersion.V4.name)},\n")
                append("  ${q("count")}: ${rows.size},\n")
                append("  ${q("entries")}: [\n")
                rows.forEachIndexed { index, entry ->
                    append("    {\n")
                    append("      ${q("dataset_version")}: ${q(entry.datasetVersion)},\n")
                    append("      ${q("case_id")}: ${q(entry.caseId)},\n")
                    append("      ${q("turn_index")}: ${entry.turnIndex},\n")
                    append("      ${q("user_text")}: ${q(entry.userText)},\n")
                    append("      ${q("category")}: ${q(entry.category)},\n")
                    append("      ${q("legacy_expected_outcome")}: ${q(entry.frozenExpected.name)},\n")
                    append("      ${q("v4_expected_outcome")}: ${q(entry.projectedExpected.name)},\n")
                    append("      ${q("tools_expected_and_run")}: 0,\n")
                    append("      ${q("projection_source")}: ${q(SemanticOutcomeOverlay.VERSION)}\n")
                    append("    }${if (index == rows.lastIndex) "" else ","}\n")
                }
                append("  ]\n}\n")
            },
        )
    }
}
