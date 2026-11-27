package ru.nsu.ccfit.zuev.osu.online

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import ru.nsu.ccfit.zuev.osu.SecurityUtils

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [PostBuilderSigningTest.ShadowSecurityUtils::class])
class PostBuilderSigningTest {
    @Before
    fun setUp() {
        ShadowSecurityUtils.payloads.clear()
    }

    @Test
    fun `one-shot requests retain tournament signing without adding duplicate signatures`() {
        val bodies = mutableListOf<FormBody>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            bodies += chain.request().body as FormBody
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("SUCCESS\nok".toResponseBody("text/plain".toMediaType()))
                .build()
        }.build()
        val post = URLEncodedPostBuilder().apply {
            addParam("username", "Player One")
            addParam("score", "123")
        }

        repeat(2) {
            assertEquals(listOf("SUCCESS", "ok"), post.request(client, "http://localhost/submit"))
        }

        assertEquals(listOf("Player+One_123"), ShadowSecurityUtils.payloads)
        assertEquals(2, bodies.size)
        for (body in bodies) {
            assertEquals(listOf("username", "score", "sign"), (0 until body.size).map(body::name))
            assertEquals("test-signature", body.value(2))
        }
    }

    @Implements(SecurityUtils::class, isInAndroidSdk = false)
    class ShadowSecurityUtils {
        companion object {
            val payloads = mutableListOf<String>()

            @Implementation
            @JvmStatic
            fun signRequest(payload: String): String {
                payloads += payload
                return "test-signature"
            }
        }
    }
}
