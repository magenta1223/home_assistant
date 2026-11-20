package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.scheduler.SourceImportWorker
import com.homeassistant.adapter.inbound.scheduler.UpdateMemoryWorker
import com.homeassistant.adapter.outbound.memory.xmemory.componentextraction.ComponentExtractorImpl
import com.homeassistant.adapter.outbound.memory.xmemory.episodeextraction.EpisodeExtractorImpl
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.adapter.outbound.reference.ModelSourceReferenceInterpreter
import com.homeassistant.application.usecase.memory.xmemory.XMemoryUpdateService
import com.homeassistant.application.usecase.memory.xmemory.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.memory.xmemory.embedding.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memory.xmemory.episode.ExtractEpisodes
import com.homeassistant.application.usecase.sourceimport.AnalyzeSubmittedSourcesService
import com.homeassistant.application.usecase.sourceimport.SourceImportService
import com.homeassistant.application.usecase.memory.xmemory.group.MergeSmallGroups
import com.homeassistant.application.usecase.memory.xmemory.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.memory.xmemory.embedding.PrepareComponentEmbedding
import com.homeassistant.domain.memory.xmemory.group.GroupMergePolicy
import com.homeassistant.domain.memory.xmemory.group.GroupOrganizationPlanner
import com.homeassistant.domain.memory.xmemory.group.GroupPlacementPolicy
import com.homeassistant.domain.memory.xmemory.group.GroupPolicySettings
import com.homeassistant.domain.memory.xmemory.group.GroupSplitPolicy
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.infrastructure.runtime.Runtime
import com.homeassistant.llm.CompletionClient

/** Assembles source import, the worker that completes submitted import jobs, and the worker that builds Episodes, Components and Groups from the stored source records. */
internal class SourceImportModule(
    repositories: RepositoryStores,
    episodeEmbeddings: PrepareEpisodeEmbedding,
    componentEmbeddings: PrepareComponentEmbedding,
    completionClient: CompletionClient,
    accessPolicy: UserAccessPolicy,
    groupSettings: GroupPolicySettings,
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

    private val updateHierarchy = XMemoryUpdateService(
        repositories.episodeExtractionQueue,
        ExtractEpisodes(EpisodeExtractorImpl(completionClient), repositories.episodes),
        repositories.componentAssignmentOutbox,
        repositories.components,
        AssignEpisodeToComponents(
            repositories.components,
            episodeEmbeddings,
            componentEmbeddings,
            ComponentExtractorImpl(completionClient),
        ),
        OrganizeComponentIntoGroups(
            repositories.groups,
            componentEmbeddings,
            GroupOrganizationPlanner(GroupPlacementPolicy(groupSettings), GroupSplitPolicy(groupSettings)),
        ),
        MergeSmallGroups(repositories.groups, GroupMergePolicy(groupSettings)),
    )

    /** The workers that complete submitted import jobs and update the memory hierarchy in rounds. */
    val runtimes: List<Runtime> = listOf(SourceImportWorker(analyzeSubmittedSources), UpdateMemoryWorker(updateHierarchy))
}
