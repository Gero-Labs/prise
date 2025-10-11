package tech.edgx.prise.webserver.model.tokens

import java.time.LocalDateTime

data class TopGainersResponse(
    val date: LocalDateTime,
    val assets: Set<GainerAssetResponse>
)