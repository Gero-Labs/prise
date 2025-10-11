package tech.edgx.prise.webserver.model.tokens

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class TopGainersRequest(
    @Min(1, message = "Limit must be at least 1")
    @Max(100, message = "Limit cannot exceed 100")
    val limit: Int = 10,

    @Min(1, message = "Hours must be at least 1")
    @Max(168, message = "Hours cannot exceed 168 (1 week)")
    val hours: Int = 24
)