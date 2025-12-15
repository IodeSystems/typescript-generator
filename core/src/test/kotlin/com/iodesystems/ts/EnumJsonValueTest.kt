package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonValue
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.junit.Ignore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RestController
@RequestMapping("/api/auth")
class AuthApi {
    enum class AuthenticatorAttachment(@get:JsonValue val value: String) {
        CROSS_PLATFORM("cross-platform"),
        PLATFORM("platform")
    }

    data class Payload(
        val attachment: AuthenticatorAttachment,
        val attachments: List<AuthenticatorAttachment>
    )

    @GetMapping("/get")
    fun get(): Payload = error("not used in tests")
}

class EnumJsonValueTest {
    @Test
    fun emitsEnumsUsingJsonValueUnionLiterals() {
        // DOING: generate TS from API with @JsonValue enum
        // EXPECT: enum alias is a union of the serialized values ('cross-platform' | 'platform')
        // IF WRONG: Jackson adapter @JsonValue handling failed
        val em = emitter(AuthApi::class) { outputDirectory("./tmp") }
        val out = em.ts().content()

        // The object type should reference the simple enum name
        out.assertContains(
            fragment =
                """
                export type AuthApiPayload = {
                  attachment: AuthApiAuthenticatorAttachment
                  attachments: Array<AuthApiAuthenticatorAttachment>
                }
            """.trimIndent(),
            why = "Payload should reference the simple enum name"
        )

        // And the enum alias should be built from @JsonValue values
        out.assertContains(
            fragment =
                """
                export type AuthApiAuthenticatorAttachment = 'cross-platform' | 'platform'
            """.trimIndent(),
            why = "Enum should serialize using @JsonValue values"
        )
    }
}
