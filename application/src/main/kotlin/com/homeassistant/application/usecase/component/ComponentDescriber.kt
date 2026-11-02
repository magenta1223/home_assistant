package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.input.component.DescribeComponent
import com.homeassistant.application.port.input.component.ComponentDescriptionResult
import com.homeassistant.application.port.output.episode.read.EpisodeReader
import com.homeassistant.application.port.output.component.ComponentDescriptionGenerator
import com.homeassistant.application.port.output.component.ComponentDescriptionStore
import com.homeassistant.application.port.output.component.ComponentReader
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.component.ComponentDescription
import com.homeassistant.domain.component.ComponentDescriptionRefresh
import com.homeassistant.domain.component.ComponentDescriptionRefreshPolicy
import com.homeassistant.domain.component.ComponentView

/**
 * Describes a Component through the viewer's [ComponentView] on demand. The description is written from that view's
 * episodes only and cached per view, so viewers who read different slices never share a description.
 */
class ComponentDescriber(
    private val components: ComponentReader,
    private val episodes: EpisodeReader,
    private val descriptions: ComponentDescriptionStore,
    private val generator: ComponentDescriptionGenerator,
    private val accessPolicy: UserAccessPolicy,
    private val refreshPolicy: ComponentDescriptionRefreshPolicy = ComponentDescriptionRefreshPolicy(),
) : DescribeComponent {
    override suspend fun describe(userId: UserId, componentId: Int): ComponentDescriptionResult {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val view = components.read(listOf(componentId)).singleOrNull()?.viewBy(userId)
        if (view == null || view.isHidden) return ComponentDescriptionResult.Hidden
        return when (val refresh = refreshPolicy.decide(descriptions.find(view.key), view)) {
            is ComponentDescriptionRefresh.Reuse -> ComponentDescriptionResult.Described(refresh.description.text)
            ComponentDescriptionRefresh.Generate -> generate(userId, view)
        }
    }

    private suspend fun generate(userId: UserId, view: ComponentView): ComponentDescriptionResult {
        // The reader re-checks access, so a slice is never trusted on its own.
        val inView = view.episodeIds.toSet()
        val readable = episodes.getEpisodes(userId).filter { it.id in inView }.sortedByDescending { it.createdAt }
        if (readable.isEmpty()) return ComponentDescriptionResult.Hidden
        val description = ComponentDescription(generator.generate(readable.map { it.content }).trim(), view.versions)
        descriptions.save(view.key, description)
        return ComponentDescriptionResult.Described(description.text)
    }
}
