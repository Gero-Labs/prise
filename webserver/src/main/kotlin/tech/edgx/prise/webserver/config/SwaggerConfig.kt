package tech.edgx.prise.webserver.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    companion object {
        private const val SECURITY_SCHEME_NAME = "basicAuth"
    }

    @Bean
    fun publicApi(): GroupedOpenApi {
        return GroupedOpenApi.builder()
            .group("1. public")
            .pathsToMatch("/**")
            .build()
    }

    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Prise API")
                    .description("Cardano Price API - Use HTTP Basic Authentication with username/password")
                    .version("0.1.7")
                    .contact(
                        Contact()
                            .name("edgx.tech")
                            .url("https://edgx.tech")
                            .email(null)
                    )
            )
            .components(
                Components()
                    .addSecuritySchemes(
                        SECURITY_SCHEME_NAME,
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("basic")
                            .description("HTTP Basic Authentication - Enter username and password")
                    )
            )
            .addSecurityItem(SecurityRequirement().addList(SECURITY_SCHEME_NAME))
    }
}