package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.memory.xmemory.componentextraction.ComponentExtractorImpl
import com.homeassistant.adapter.outbound.memory.xmemory.episodeextraction.EpisodeExtractorImpl
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.inbound.memory.UpdateMemory
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.context.ContextProvider
import com.homeassistant.feature.memory.xmemory.usecase.XMemoryContextProvider
import com.homeassistant.feature.memory.xmemory.usecase.XMemoryUpdateService
import com.homeassistant.feature.memory.xmemory.usecase.component.AssignEpisodeToComponents
import com.homeassistant.feature.memory.xmemory.usecase.component.ComponentSearcher
import com.homeassistant.feature.memory.xmemory.usecase.embedding.PrepareComponentEmbedding
import com.homeassistant.feature.memory.xmemory.usecase.embedding.PrepareEpisodeEmbedding
import com.homeassistant.feature.memory.xmemory.usecase.episode.EpisodeSearcher
import com.homeassistant.feature.memory.xmemory.usecase.episode.ExtractEpisodes
import com.homeassistant.feature.memory.xmemory.usecase.group.MergeSmallGroups
import com.homeassistant.feature.memory.xmemory.usecase.group.OrganizeComponentIntoGroups
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.memory.xmemory.group.GroupMergePolicy
import com.homeassistant.domain.memory.xmemory.group.GroupOrganizationPlanner
import com.homeassistant.domain.memory.xmemory.group.GroupPlacementPolicy
import com.homeassistant.domain.memory.xmemory.group.GroupPolicySettings
import com.homeassistant.domain.memory.xmemory.group.GroupSplitPolicy
import com.homeassistant.llm.CompletionClient

/**
 * Assembles the xMemory method: Episodes extracted from stored source records, Components and Groups built from them,
 * and the chat context searched from both. Only [updateMemory] and [context] leave this module.
 */
internal class XMemoryModule(
    repositories: RepositoryStores,
    embeddings: EmbeddingGenerator,
    completionClient: CompletionClient,
    accessPolicy: UserAccessPolicy,
    groupSettings: GroupPolicySettings,
) {
    private val episodeEmbeddings = PrepareEpisodeEmbedding(embeddings, repositories.episodeEmbeddings)
    private val componentEmbeddings = PrepareComponentEmbedding(embeddings, repositories.componentEmbeddings)

    /** Memorizes stored source records as Episodes, Components and Groups. */
    val updateMemory: UpdateMemory = XMemoryUpdateService(
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

    /** The Components and Episodes a chat answers from. */
    val context: ContextProvider = XMemoryContextProvider(
        ComponentSearcher(
            components = repositories.components,
            groups = repositories.groups,
            queries = embeddings,
            episodes = repositories.episodes,
            componentEmbeddings = componentEmbeddings,
            accessPolicy = accessPolicy,
        ),
        EpisodeSearcher(repositories.episodes, embeddings, episodeEmbeddings, accessPolicy),
    )
}
