package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.componentextraction.ModelComponentKnowledgeExtractor
import com.homeassistant.adapter.outbound.episodeanalysis.EpisodeExtractor
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.adapter.outbound.reference.ModelSourceReferenceInterpreter
import com.homeassistant.application.usecase.memoryextraction.UpdateMemoryHierarchyService
import com.homeassistant.application.usecase.memoryextraction.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.episode.EpisodeAnalysisService
import com.homeassistant.application.usecase.memoryextraction.group.MergeSmallGroups
import com.homeassistant.application.usecase.memoryextraction.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.domain.group.GroupMergePolicy
import com.homeassistant.domain.group.GroupOrganizationPlanner
import com.homeassistant.domain.group.GroupPlacementPolicy
import com.homeassistant.domain.group.GroupPolicySettings
import com.homeassistant.domain.group.GroupSplitPolicy
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.infrastructure.runtime.Runtime
import com.homeassistant.llm.CompletionClient

/** Assembles Episode analysis and the worker that builds Components and Groups from the stored Episodes. */
internal class MemoryExtractionModule(
    repositories: RepositoryStores,
    episodeEmbeddings: PrepareEpisodeEmbedding,
    componentEmbeddings: PrepareComponentEmbedding,
    completionClient: CompletionClient,
    accessPolicy: UserAccessPolicy,
    groupSettings: GroupPolicySettings,
) {
    /** Turns source records into Episodes. */
    val episodeAnalysis = EpisodeAnalysisService(
        episodeExtractor = EpisodeExtractor(completionClient),
        sourceRecords = repositories.sourceRecords,
        episodes = repositories.episodes,
        accessPolicy = accessPolicy,
        referenceInterpreter = ModelSourceReferenceInterpreter(completionClient),
    )

    private val updateHierarchy = UpdateMemoryHierarchyService(
        repositories.componentAssignmentOutbox,
        repositories.pendingComponents,
        AssignEpisodeToComponents(
            repositories.components,
            episodeEmbeddings,
            componentEmbeddings,
            ModelComponentKnowledgeExtractor(completionClient),
        ),
        OrganizeComponentIntoGroups(
            repositories.groups,
            componentEmbeddings,
            GroupOrganizationPlanner(GroupPlacementPolicy(groupSettings), GroupSplitPolicy(groupSettings)),
        ),
        MergeSmallGroups(repositories.groups, GroupMergePolicy(groupSettings)),
    )

    /** The worker that updates the memory hierarchy in rounds. */
    val runtimes: List<Runtime> = listOf(ComponentAssignmentWorker(updateHierarchy))
}
