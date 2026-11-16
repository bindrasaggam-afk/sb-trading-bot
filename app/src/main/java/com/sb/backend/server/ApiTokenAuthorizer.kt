package com.sb.backend.server

import com.sb.backend.security.ApiTokenPolicy
import com.sb.backend.security.LocalApiTokens

enum class ApiAuthorizationDecision {
    ALLOWED,
    UNAUTHORIZED,
    FORBIDDEN
}

object ApiTokenAuthorizer {

    fun decide(
        access: ApiAccess,
        primaryToken: String?,
        adminHeaderToken: String?,
        tokens: LocalApiTokens,
        optionsRequest: Boolean = false
    ): ApiAuthorizationDecision {
        if (optionsRequest) return ApiAuthorizationDecision.ALLOWED

        val primaryIsAdmin = ApiTokenPolicy.matches(
            primaryToken,
            tokens.admin
        )
        val primaryIsRead = ApiTokenPolicy.matches(
            primaryToken,
            tokens.readOnly
        )
        if (!primaryIsAdmin && !primaryIsRead) {
            return ApiAuthorizationDecision.UNAUTHORIZED
        }

        if (access != ApiAccess.ADMIN) {
            return ApiAuthorizationDecision.ALLOWED
        }

        val adminHeaderIsAdmin = ApiTokenPolicy.matches(
            adminHeaderToken,
            tokens.admin
        )
        return if (primaryIsAdmin || adminHeaderIsAdmin) {
            ApiAuthorizationDecision.ALLOWED
        } else {
            ApiAuthorizationDecision.FORBIDDEN
        }
    }
}
