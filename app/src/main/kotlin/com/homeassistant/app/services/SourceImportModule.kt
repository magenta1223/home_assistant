package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.scheduler.SourceImportWorker
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.adapter.outbound.reference.ModelSourceReferenceInterpreter
import com.homeassistant.feature.sourceimport.usecase.AnalyzeSubmittedSourcesService
import com.homeassistant.feature.sourceimport.usecase.SourceImportService
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.infrastructure.runtime.Runtime
import com.homeassistant.llm.CompletionClient

/** Assembles source import and the worker that completes submitted import jobs. */
internal class SourceImportModule(
    repositories: RepositoryStores,
    completionClient: CompletionClient,
    accessPolicy: UserAccessPolicy,
) {
    /** Accepts submitted source records. */
    val sourceImport = SourceImportService(
        sourceRecords = repositories.sourceRecords,
        jobs = repositories.sourceImportJobQueue,
        accessPolicy = accessPolicy,
    )

    private val analyzeSubmittedSources = AnalyzeSubmittedSourcesService(
        jobs = repositories.sourceImportJobQueue,
        sourceRecords = repositories.sourceRecords,
        referenceInterpreter = ModelSourceReferenceInterpreter(completionClient),
    )

    /** The worker that completes submitted import jobs. */
    val runtimes: List<Runtime> = listOf(SourceImportWorker(analyzeSubmittedSources))
}
