package tech.edgx.prise.webserver.model.tvl

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class TopTvlRequest(
    @Min(1, message = "Limit must be at least 1")
    @Max(100, message = "Limit cannot exceed 100")
    val limit: Int = 10
)