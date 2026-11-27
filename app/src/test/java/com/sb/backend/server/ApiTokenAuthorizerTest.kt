package com.sb.backend.server

import com.sb.backend.security.LocalApiTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiTokenAuthorizerTest {

    private val tokens = LocalApiTokens(
        readOnly = "read-token",
        admin = "admin-token"
    )

    @Test
    fun readEndpointRejectsMissingOrInvalidToken() {
        assertEquals(
            ApiAuthorizationDecision.UNAUTHORIZED,
            ApiTokenAuthorizer.decide(
                ApiAccess.READ,
                null,
                null,
                tokens
            )
        )
        assertEquals(
            ApiAuthorizationDecision.UNAUTHORIZED,
            ApiTokenAuthorizer.decide(
                ApiAccess.READ,
                "wrong",
                null,
                tokens
            )
        )
    }

    @Test
    fun readTokenCanReadButCannotAdminister() {
        assertEquals(
            ApiAuthorizationDecision.ALLOWED,
            ApiTokenAuthorizer.decide(
                ApiAccess.READ,
                "read-token",
                null,
                tokens
            )
        )
        assertEquals(
            ApiAuthorizationDecision.FORBIDDEN,
            ApiTokenAuthorizer.decide(
                ApiAccess.ADMIN,
                "read-token",
                null,
                tokens
            )
        )
    }

    @Test
    fun adminTokenRemainsCompatibleInPrimaryHeader() {
        assertEquals(
            ApiAuthorizationDecision.ALLOWED,
            ApiTokenAuthorizer.decide(
                ApiAccess.READ,
                "admin-token",
                null,
                tokens
            )
        )
        assertEquals(
            ApiAuthorizationDecision.ALLOWED,
            ApiTokenAuthorizer.decide(
                ApiAccess.ADMIN,
                "admin-token",
                null,
                tokens
            )
        )
    }

    @Test
    fun adminHeaderAuthorizesOnlyWhenPrimaryTokenIsValid() {
        assertEquals(
            ApiAuthorizationDecision.ALLOWED,
            ApiTokenAuthorizer.decide(
                ApiAccess.ADMIN,
                "read-token",
                "admin-token",
                tokens
            )
        )
        assertEquals(
            ApiAuthorizationDecision.UNAUTHORIZED,
            ApiTokenAuthorizer.decide(
                ApiAccess.ADMIN,
                null,
                "admin-token",
                tokens
            )
        )
        assertEquals(
            ApiAuthorizationDecision.FORBIDDEN,
            ApiTokenAuthorizer.decide(
                ApiAccess.ADMIN,
                "read-token",
                "wrong",
                tokens
            )
        )
    }

    @Test
    fun rotatedTokensImmediatelyRevokeOldTokens() {
        val rotated = LocalApiTokens(
            readOnly = "new-read",
            admin = "new-admin"
        )

        assertEquals(
            ApiAuthorizationDecision.UNAUTHORIZED,
            ApiTokenAuthorizer.decide(
                ApiAccess.READ,
                "read-token",
                null,
                rotated
            )
        )
        assertEquals(
            ApiAuthorizationDecision.UNAUTHORIZED,
            ApiTokenAuthorizer.decide(
                ApiAccess.ADMIN,
                "admin-token",
                null,
                rotated
            )
        )
        assertEquals(
            ApiAuthorizationDecision.ALLOWED,
            ApiTokenAuthorizer.decide(
                ApiAccess.ADMIN,
                "new-read",
                "new-admin",
                rotated
            )
        )
    }

    @Test
    fun optionsRequestIsPublic() {
        assertEquals(
            ApiAuthorizationDecision.ALLOWED,
            ApiTokenAuthorizer.decide(
                ApiAccess.READ,
                null,
                null,
                tokens,
                optionsRequest = true
            )
        )
    }
}
