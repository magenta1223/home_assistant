package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.application.port.output.component.EpisodeEmbedding
import com.homeassistant.application.port.output.component.EpisodeEmbeddingKey
import com.homeassistant.application.port.output.component.ComponentJoin
import com.homeassistant.application.usecase.component.ComponentSearcher
import com.homeassistant.application.usecase.episode.conversation.EpisodeConversationContextProvider
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.component.ComponentCentroid
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeRepositoryUseCaseIntegrationTest {
    private val member = UserId("member-1")

    @Test
    fun `conversation context reads only the component slices the user can see`() {
        withRepositories("component-search-integration") { stores ->
            val east = ComponentVector.normalized(listOf(1.0, 0.0))
            val publicEpisode = stores.saveEpisode(member, "public", EpisodeAccess.PUBLIC)
            val otherRestrictedEpisode = stores.saveEpisode(
                UserId("member-2"),
                "other-restricted",
                EpisodeAccess.restricted(listOf(UserId("member-2"))),
            )
            val hiddenEpisode = stores.saveEpisode(UserId("member-2"), "hidden", EpisodeAccess.restricted(listOf(UserId("member-2"))))
            val first = ComponentCentroid.of(east)
            val mixed = stores.componentMaintenance.create(publicEpisode.id, first)
            assertTrue(stores.componentMaintenance.join(otherRestrictedEpisode.id, listOf(ComponentJoin(mixed, first, first + east))))
            stores.componentMaintenance.create(hiddenEpisode.id, ComponentCentroid.of(east))
            val space = EmbeddingSpace("test", "passage:v1")
            stores.episodeEmbeddings.save(EpisodeEmbedding(EpisodeEmbeddingKey.of(publicEpisode.id, publicEpisode.content, space), east))
            val search = ComponentSearcher(
                stores.componentMaintenance, stores.groups, { east }, stores.canonicalEpisodes, stores.episodeEmbeddings, space,
                UserAccessPolicy { it == member },
            )

            val conversationContext = EpisodeConversationContextProvider(search).context(member, "question")

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
            val componentId = stores.componentMaintenance.create(episode.id, ComponentCentroid.of(ComponentVector.normalized(listOf(1.0, 0.0))))
            assertEquals(mapOf(access to listOf(episode.id)), stores.componentMaintenance.read(listOf(componentId)).single().slices)
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
                content = content,
                evidenceIds = listOf(evidence.id),
            )
        return canonicalEpisodeBatchWriter.commit(
            userId,
            listOf(IdempotentEpisodeProposal("${userId.value}:$content", proposal)),
            listOf(evidence.id),
        ).single()
    }
}
