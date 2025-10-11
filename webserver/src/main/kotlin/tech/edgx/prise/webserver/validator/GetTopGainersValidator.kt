package tech.edgx.prise.webserver.validator

import org.springframework.stereotype.Component
import org.springframework.validation.Errors
import org.springframework.validation.Validator
import tech.edgx.prise.webserver.model.tokens.TopGainersRequest

@Component("getTopGainersValidator")
class GetTopGainersValidator : Validator {

    override fun supports(clazz: Class<*>): Boolean {
        return TopGainersRequest::class.java == clazz
    }

    override fun validate(target: Any, errors: Errors) {
        val request = target as TopGainersRequest

        if (request.limit < 1 || request.limit > 100) {
            errors.rejectValue("limit", "limit.invalid", "Limit must be between 1 and 100")
        }

        if (request.hours < 1 || request.hours > 168) {
            errors.rejectValue("hours", "hours.invalid", "Hours must be between 1 and 168 (1 week)")
        }
    }
}