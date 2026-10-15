package mihon.suwayomi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SuwayomiServerUrlTest {

    @Test
    fun `trims whitespace and trailing slash`() {
        SuwayomiServerUrl.normalize("  http://192.168.1.10:4567/  ") shouldBe "http://192.168.1.10:4567"
    }

    @Test
    fun `keeps path prefix`() {
        SuwayomiServerUrl.normalize("https://example.test/suwayomi/") shouldBe "https://example.test/suwayomi"
    }

    @Test
    fun `rejects non http schemes, blanks and garbage`() {
        SuwayomiServerUrl.normalize("") shouldBe null
        SuwayomiServerUrl.normalize("ftp://example.test") shouldBe null
        SuwayomiServerUrl.normalize("example.test:4567") shouldBe null
        SuwayomiServerUrl.normalize("not a url") shouldBe null
    }

    @Test
    fun `rejects embedded credentials, query and fragment`() {
        SuwayomiServerUrl.normalize("http://user:pass@example.test") shouldBe null
        SuwayomiServerUrl.normalize("http://example.test?token=abc") shouldBe null
        SuwayomiServerUrl.normalize("http://example.test#frag") shouldBe null
    }

    @Test
    fun `builds graphql endpoint`() {
        SuwayomiServerUrl.graphQlEndpoint("http://example.test:4567/") shouldBe "http://example.test:4567/api/graphql"
    }
}
