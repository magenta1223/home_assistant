package com.homeassistant.adapter.outbound.runtime.distribution.downloader

import java.net.URI
import java.nio.file.Path

internal fun interface AssetDownloader {
    fun download(sourceUri: URI, target: Path)
}