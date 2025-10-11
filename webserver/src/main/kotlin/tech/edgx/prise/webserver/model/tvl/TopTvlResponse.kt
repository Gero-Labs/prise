package tech.edgx.prise.webserver.model.tvl

import java.time.LocalDateTime

data class TopTvlResponse(
    val date: LocalDateTime,
    val pools: List<PoolTvlResponse>
)