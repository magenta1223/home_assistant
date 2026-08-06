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
import com.homeassistant.application.memory.answer.AnswerFromMemory
import com.homeassistant.application.memory.answer.MemoryAnswerUseCase
import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.analyze.AnalyzeSourceUseCase
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopics
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import com.homeassistant.adapter.shared.config.AppConfig
import com.homeassistant.adapter.shared.config.Env
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.kakao.KakaoImporterFactory
import com.homeassistant.adapter.outbound.vector.memory.MemorySearchIndexFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import org.slf4j.LoggerFactory

interface ApplicationServices : AutoCloseable {
    val analyzeSource: AnalyzeSourceUseCase
    val saveAnalyzedTopics: SaveAnalyzedTopicsUseCase
    val memoryAnswer: MemoryAnswerUseCase
    fun start()
}

private class DefaultApplicationServices(
    override val analyzeSource: AnalyzeSourceUseCase,
    override val saveAnalyzedTopics: SaveAnalyzedTopicsUseCase,
    override val memoryAnswer: MemoryAnswerUseCase,
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
        val kakaoImporter = KakaoImporterFactory.create(repositories.kakaoMessages)
        val analyzeSource = AnalyzeSource(
            topicExtractor = CodexTopicExtractorFactory.create(),
            sourceTextParser = KakaoExportParser,
            importService = kakaoImporter,
            previewRepository = repositories.kakaoAnalysisPreviews,
            accessPolicy = accessPolicy,
        )
        val saveAnalyzedTopics = SaveAnalyzedTopics(
            importService = kakaoImporter,
            sourceTextParser = KakaoExportParser,
            topicRepository = repositories.topicAnalysis,
            previewRepository = repositories.kakaoAnalysisPreviews,
            memorySearchIndex = memorySearchIndex,
            indexingOutbox = repositories.indexingOutbox,
            accessPolicy = accessPolicy,
        )
        val memoryAnswer = AnswerFromMemory(
            topicStore = repositories.topicAnalysis,
            accessPolicy = accessPolicy,
            memorySearchIndex = memorySearchIndex,
        )
        val conversationClient = CodexConversationConfig.fromEnv()
            ?.let(CodexConversationClientFactory::create)
            ?.takeIf { it.validateVersion() }
        val slackRuntime = slackConfig?.let {
            SlackRuntimeFactory.create(
                it,
                analyzeSource,
                saveAnalyzedTopics,
                memoryAnswer,
                repositories.slackCodexSessions,
                conversationClient,
            )
        }
        if (slackRuntime == null) {
            log.info("Slack Socket Mode disabled: Slack token, team, or member mapping configuration is missing")
        }
        return DefaultApplicationServices(analyzeSource, saveAnalyzedTopics, memoryAnswer, slackRuntime)
    }
}

private val log = LoggerFactory.getLogger(ApplicationServicesFactory::class.java)
