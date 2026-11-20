package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.scheduler.EpisodeAnalysisWorker
import com.homeassistant.adapter.inbound.scheduler.UpdateMemoryWorker
import com.homeassistant.adapter.outbound.memory.xmemory.componentextraction.ComponentExtractorImpl
import com.homeassistant.adapter.outbound.memory.xmemory.episodeextraction.EpisodeExtractorImpl
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.adapter.outbound.reference.ModelSourceReferenceInterpreter
import com.homeassistant.application.usecase.memory.xmemory.XMemoryUpdateService
import com.homeassistant.application.usecase.memory.xmemory.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.memory.xmemory.embedding.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.episode.AnalyzeSubmittedSourcesService
import com.homeassistant.application.usecase.memoryextraction.episode.EpisodeAnalysisService
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

/** Assembles Episode analysis, the worker that analyzes submitted sources, and the worker that builds Components and Groups from the stored Episodes. */
internal class MemoryExtractionModule(
    repositories: RepositoryStores,
    episodeEmbeddings: PrepareEpisodeEmbedding,
    componentEmbeddings: PrepareComponentEmbedding,
    completionClient: CompletionClient,
    accessPolicy: UserAccessPolicy,
    groupSettings: GroupPolicySettings,
) {
    /** Accepts source records for Episode analysis. */
    val episodeAnalysis = EpisodeAnalysisService(
        sourceRecords = repositories.sourceRecords,
        jobs = repositories.episodeAnalysisJobQueue,
        accessPolicy = accessPolicy,
    )

    private val analyzeSubmittedSources = AnalyzeSubmittedSourcesService(
        jobs = repositories.episodeAnalysisJobQueue,
        sourceRecords = repositories.sourceRecords,
        episodes = repositories.episodes,
        episodeExtractor = EpisodeExtractorImpl(completionClient),
        referenceInterpreter = ModelSourceReferenceInterpreter(completionClient),
    )

    private val updateHierarchy = XMemoryUpdateService(
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

    /** The workers that analyze submitted sources and update the memory hierarchy in rounds. */
    val runtimes: List<Runtime> = listOf(EpisodeAnalysisWorker(analyzeSubmittedSources), UpdateMemoryWorker(updateHierarchy))
}
