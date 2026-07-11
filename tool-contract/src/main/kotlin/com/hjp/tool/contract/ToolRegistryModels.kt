package com.hjp.tool.contract

data class ToolBinding(
    val capabilityId: ToolCapabilityId,
    val modelName: String,
    val contractVersion: ContractVersion,
    val implementationId: ToolImplementationId,
)

data class ToolCatalogSnapshot(
    val revision: String,
    val bindingRevision: String,
    val bindings: List<ToolBinding>,
    val contractsByModelName: Map<String, ToolContract>,
) {
    fun bindingFor(modelName: String): ToolBinding? = bindings.firstOrNull { it.modelName == modelName }
}

interface ToolRegistry {
    suspend fun snapshot(): ToolCatalogSnapshot
    fun resolve(binding: ToolBinding): ToolPlugin?
}
