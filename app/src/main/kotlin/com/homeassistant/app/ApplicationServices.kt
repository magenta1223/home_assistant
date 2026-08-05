package com.homeassistant.app

import com.homeassistant.app.slack.SlackConfig
import com.homeassistant.app.slack.SlackRuntime
import com.homeassistant.app.slack.SlackRuntimeFactory
import com.homeassistant.adapter.outbound.codex.CodexTopicExtractorFactory
import com.homeassistant.application.topicanalysis.TopicAnalysisFactory
import com.homeassistant.application.topicanalysis.TopicAnalysisUseCase
import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.core.identity.HouseholdAccessPolicies
import com.homeassistant.domain.kakao.KakaoImporterFactory
import com.homeassistant.domain.memory.PayloadVectorStoreFactory
import com.homeassistant.domain.topicanswer.TopicAnswerFactory
import com.homeassistant.domain.topicanswer.TopicAnswerUseCase
import com.homeassistant.domain.topicanswer.TopicClaimSearchIndexFactory
import com.homeassistant.nlp.embedding.EmbeddingServiceFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import org.slf4j.LoggerFactory

interface ApplicationServices : AutoCloseable {
    val topicAnalysis: TopicAnalysisUseCase
    val topicAnswer: TopicAnswerUseCase
    fun start()
}

private class DefaultApplicationServices(
    override val topicAnalysis: TopicAnalysisUseCase,
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

        val embeddingService = EmbeddingServiceFactory.ollama(embeddingBaseUrl, embeddingModel)
        val topicClaimSearchIndex = TopicClaimSearchIndexFactory.create(
            embeddingService,
            PayloadVectorStoreFactory.qdrant(
                baseUrl = Env[AppConfig.ENV_VAR_QDRANT_URL] ?: AppConfig.DEFAULT_QDRANT_URL,
                collection = Env[AppConfig.ENV_VAR_QDRANT_COLLECTION] ?: AppConfig.DEFAULT_QDRANT_COLLECTION,
            ),
        )
        val slackConfig = SlackConfig.fromEnv()
        val accessPolicy = slackConfig?.identityDirectory?.accessPolicy
            ?: HouseholdAccessPolicies.denyAll()
        val topicAnalysis = TopicAnalysisFactory.kakao(
            topicExtractor = CodexTopicExtractorFactory.create(),
            importer = KakaoImporterFactory.create(repositories.kakaoMessages),
            topicStore = repositories.topicAnalysis,
            previewStore = repositories.kakaoAnalysisPreviews,
            searchIndex = topicClaimSearchIndex,
            indexingOutbox = repositories.indexingOutbox,
            accessPolicy = accessPolicy,
        )
        val topicAnswer = TopicAnswerFactory.create(
            topicStore = repositories.topicAnalysis,
            topicClaimSearchIndex = topicClaimSearchIndex,
            accessPolicy = accessPolicy,
        )
        val slackRuntime = slackConfig?.let {
            SlackRuntimeFactory.create(it, topicAnalysis, topicAnswer, repositories.slackCodexSessions)
        }
        if (slackRuntime == null) {
            log.info("Slack Socket Mode disabled: Slack token, team, or member mapping configuration is missing")
        }
        return DefaultApplicationServices(topicAnalysis, topicAnswer, slackRuntime)
    }
}

private val log = LoggerFactory.getLogger(ApplicationServicesFactory::class.java)
