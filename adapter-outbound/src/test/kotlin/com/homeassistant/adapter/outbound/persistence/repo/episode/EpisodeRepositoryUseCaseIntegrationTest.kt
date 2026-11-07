package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.embedding.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.application.usecase.embedding.PrepareComponentEmbedding
import com.homeassistant.application.usecase.contextprovider.ContextAssembler
import com.homeassistant.application.usecase.contextprovider.MemoryContextProvider
import com.homeassistant.domain.embedding.EmbeddingVector
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeRepositoryUseCaseIntegrationTest {
    private val member = UserId("member-1")

    @Test
    fun `conversation context reads only the components and episodes the user can see`() {
        withRepositories("component-search-integration") { stores ->
            val east = EmbeddingVector.normalized(listOf(1.0, 0.0))
            val publicEpisode = stores.saveEpisode(member, "public", AccessControl.PUBLIC)
            val otherRestrictedEpisode = stores.saveEpisode(
                UserId("member-2"),
                "other-restricted",
                AccessControl.restricted(listOf(UserId("member-2"))),
            )
            val hiddenEpisode = stores.saveEpisode(UserId("member-2"), "hidden", AccessControl.restricted(listOf(UserId("member-2"))))
            listOf(publicEpisode, otherRestrictedEpisode, hiddenEpisode).forEach {
                stores.components.save(EpisodeComponents(it.id, listOf("fact of ${it.content}")))
            }
            val texts = object : EmbeddingGenerator {
                override val space = EmbeddingSpace("test", "passage:v1")
                override fun isAvailable() = true
                override fun embedDocument(text: String) = east
                override fun embedQuery(text: String) = east
            }
            val search = ComponentSearcher(
                stores.components, stores.groups, texts, stores.episodes,
                PrepareComponentEmbedding(texts, stores.componentEmbeddings),
                UserAccessPolicy { it == member },
            )

            val episodeSearch = EpisodeSearcher(
                stores.episodes, texts, PrepareEpisodeEmbedding(texts, stores.episodeEmbeddings), UserAccessPolicy { it == member },
            )

            val conversationContext = ContextAssembler(listOf(MemoryContextProvider(search, episodeSearch))).context(member, "question")

            assertTrue(conversationContext.hasMatches)
            assertTrue(conversationContext.reference.contains(publicEpisode.content))
            assertFalse(conversationContext.reference.contains(otherRestrictedEpisode.content))
            assertFalse(conversationContext.reference.contains(hiddenEpisode.content))
            assertTrue(conversationContext.reference.lines().contains("- fact of ${publicEpisode.content}"))
        }
    }

    @Test
    fun `component storage preserves episode content and audience`() {
        withRepositories("component-placement-integration") { stores ->
            val access = AccessControl.restricted(listOf(UserId("other")))
            val episode = stores.saveEpisode(member, "incoming", access)
            val before = stores.episodes.findVisibleTo(UserId("other"))
            val (component) = stores.components.save(EpisodeComponents(episode.id, listOf("fact")))
            assertEquals(access, component.access)
            assertEquals(access, stores.components.findVisibleTo(UserId("other")).single().access)
            assertEquals(before, stores.episodes.findVisibleTo(UserId("other")))
        }
    }
    private inline fun <T> withRepositories(prefix: String, block: (RepositoryStores) -> T): T {
        val databasePath = Files.createTempFile(prefix, ".db")
        return try {
            block(RepositoryFactory.create(databasePath.toString()))
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun RepositoryStores.saveEpisode(
        userId: UserId,
        content: String,
        access: AccessControl,
    ): Episode {
        val evidence = sourceRecords.saveAll(
            SourceDescriptor(type = "test", name = content),
            listOf(SourceRecordDraft(content, content)),
            access,
        ).recordsToAnalyze.single()
        val proposal = EpisodeProposal(
                title = "title",
                content = content,
                evidenceIds = listOf(evidence.id),
            )
        return episodes.save(EpisodeBatch(
            userId,
            listOf(proposal),
            listOf(evidence.id),
        )).single()
    }
}
