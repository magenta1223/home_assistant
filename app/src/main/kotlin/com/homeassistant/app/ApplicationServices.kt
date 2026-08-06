package com.homeassistant.app

import com.homeassistant.adapter.inbound.slack.SlackConfig
import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.adapter.inbound.slack.SlackRuntimeFactory
import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.adapter.outbound.codex.CodexTopicExtractorFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationClientFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationConfig
import com.homeassistant.adapter.outbound.embedding.ollama.OllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.vector.qdrant.QdrantVectorStoreFactory
import com.homeassistant.application.topicanalysis.TopicAnalysisFactory
import com.homeassistant.application.topicanalysis.TopicAnalysisUseCases
import com.homeassistant.adapter.shared.config.AppConfig
import com.homeassistant.adapter.shared.config.Env
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.kakao.KakaoImporterFactory
import com.homeassistant.application.topicanswer.answer.TopicAnswerFactory
import com.homeassistant.application.topicanswer.answer.TopicAnswerUseCase
import com.homeassistant.adapter.outbound.vector.memory.MemorySearchIndexFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import org.slf4j.LoggerFactory

interface ApplicationServices : AutoCloseable {
    val topicAnalysis: TopicAnalysisUseCases
    val topicAnswer: TopicAnswerUseCase
    fun start()
}

private class DefaultApplicationServices(
    override val topicAnalysis: TopicAnalysisUseCases,
    override val topicAnswer: TopicAnswerUseCase,
    private val slackRuntime: SlackRuntime?,
) : ApplicationServices {
    override fun start() {
        slackRuntime?.startAsync()
    }

    override fun close() {
        slackRuntime?.close()
    }
}

object ApplicationServicesFactory {
    fun create(dbPath: String): ApplicationServices {
        val repositories = RepositoryFactory.create(dbPath)
        val embeddingModel = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
            ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
        val embeddingBaseUrl = Env[AppConfig.ENV_VAR_OLLAMA_BASE_URL]
            ?: AppConfig.DEFAULT_OLLAMA_BASE_URL
        log.info("Ollama embedding model={} baseUrl={}", embeddingModel, embeddingBaseUrl)

        val embeddingService = OllamaEmbeddingFactory.create(embeddingBaseUrl, embeddingModel)
        val memorySearchIndex = MemorySearchIndexFactory.create(
            embeddingService,
            QdrantVectorStoreFactory.create(
                baseUrl = Env[AppConfig.ENV_VAR_QDRANT_URL] ?: AppConfig.DEFAULT_QDRANT_URL,
                collection = Env[AppConfig.ENV_VAR_QDRANT_COLLECTION] ?: AppConfig.DEFAULT_QDRANT_COLLECTION,
            ),
        )
        val slackConfig = SlackConfig.fromEnv()
        val accessPolicy = slackConfig?.identityDirectory?.accessPolicy
            ?: HouseholdAccessPolicies.denyAll()
        val topicAnalysis = TopicAnalysisFactory.kakao(
            topicExtractor = CodexTopicExtractorFactory.create(),
            sourceTextParser = KakaoExportParser,
            importer = KakaoImporterFactory.create(repositories.kakaoMessages),
            topicStore = repositories.topicAnalysis,
            previewStore = repositories.kakaoAnalysisPreviews,
            searchIndex = memorySearchIndex,
            indexingOutbox = repositories.indexingOutbox,
            accessPolicy = accessPolicy,
        )
        val topicAnswer = TopicAnswerFactory.create(
            topicStore = repositories.topicAnalysis,
            memorySearchIndex = memorySearchIndex,
            accessPolicy = accessPolicy,
        )
        val conversationClient = CodexConversationConfig.fromEnv()
            ?.let(CodexConversationClientFactory::create)
            ?.takeIf { it.validateVersion() }
        val slackRuntime = slackConfig?.let {
            SlackRuntimeFactory.create(
                it,
                topicAnalysis,
                topicAnswer,
                repositories.slackCodexSessions,
                conversationClient,
            )
        }
        if (slackRuntime == null) {
            log.info("Slack Socket Mode disabled: Slack token, team, or member mapping configuration is missing")
        }
        return DefaultApplicationServices(topicAnalysis, topicAnswer, slackRuntime)
    }
}

private val log = LoggerFactory.getLogger(ApplicationServicesFactory::class.java)
