package tech.edgx.prise.webserver.validator

import org.springframework.stereotype.Component
import org.springframework.validation.Errors
import org.springframework.validation.Validator
import tech.edgx.prise.webserver.model.tvl.TopTvlRequest

@Component("getTopTvlValidator")
class GetTopTvlValidator : Validator {

    override fun supports(clazz: Class<*>): Boolean {
        return TopTvlRequest::class.java == clazz
    }

    override fun validate(target: Any, errors: Errors) {
        val request = target as TopTvlRequest

        if (request.limit < 1 || request.limit > 100) {
            errors.rejectValue("limit", "limit.invalid", "Limit must be between 1 and 100")
        }
    }
}