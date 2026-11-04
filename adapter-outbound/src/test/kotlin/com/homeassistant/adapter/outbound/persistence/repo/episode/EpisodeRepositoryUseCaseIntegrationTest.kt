package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.outbound.memoryextraction.group.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingGenerator
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.memoryextraction.component.ComponentStatementVectors
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.application.usecase.contextprovider.ContextProvider
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeAccess
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
            val east = ComponentVector.normalized(listOf(1.0, 0.0))
            val publicEpisode = stores.saveEpisode(member, "public", EpisodeAccess.PUBLIC)
            val otherRestrictedEpisode = stores.saveEpisode(
                UserId("member-2"),
                "other-restricted",
                EpisodeAccess.restricted(listOf(UserId("member-2"))),
            )
            val hiddenEpisode = stores.saveEpisode(UserId("member-2"), "hidden", EpisodeAccess.restricted(listOf(UserId("member-2"))))
            listOf(publicEpisode, otherRestrictedEpisode, hiddenEpisode).forEach {
                stores.componentMaintenance.saveExtraction(it.id, listOf("fact of ${it.content}"))
            }
            val texts = object : EpisodeEmbeddingGenerator {
                override val space = EmbeddingSpace("test", "passage:v1")
                override fun isAvailable() = true
                override fun generate(content: String) = east
            }
            val search = ComponentSearcher(
                stores.componentMaintenance, stores.groups, { east }, stores.canonicalEpisodes,
                PrepareComponentEmbedding(ComponentStatementVectors(stores.componentMaintenance, texts), stores.componentEmbeddings),
                UserAccessPolicy { it == member },
            )

            val episodeSearch = EpisodeSearcher(
                stores.canonicalEpisodes, { east }, PrepareEpisodeEmbedding(texts, stores.episodeEmbeddings), UserAccessPolicy { it == member },
            )

            val conversationContext = ContextProvider(search, episodeSearch).context(member, "question")

            assertTrue(conversationContext.hasMatches)
            assertTrue(conversationContext.reference.contains(publicEpisode.content))
            assertFalse(conversationContext.reference.contains(otherRestrictedEpisode.content))
            assertFalse(conversationContext.reference.contains(hiddenEpisode.content))
            assertEquals(1, conversationContext.reference.lines().count { it.startsWith("## Component") })
        }
    }

    @Test
    fun `component storage preserves episode content and audience`() {
        withRepositories("component-placement-integration") { stores ->
            val access = EpisodeAccess.restricted(listOf(UserId("other")))
            val episode = stores.saveEpisode(member, "incoming", access)
            val before = stores.canonicalEpisodes.getEpisodes(UserId("other"))
            val (componentId) = stores.componentMaintenance.saveExtraction(episode.id, listOf("fact"))
            assertEquals(access, stores.componentMaintenance.read(listOf(componentId)).single().access)
            assertEquals(before, stores.canonicalEpisodes.getEpisodes(UserId("other")))
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
        access: EpisodeAccess,
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
        return episodeWriter.commit(
            userId,
            listOf(proposal),
            listOf(evidence.id),
        ).single()
    }
}
