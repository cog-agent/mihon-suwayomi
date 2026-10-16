package mihon.suwayomi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SuwayomiChaptersTest {

    // Server order: sourceOrder 1 is the newest chapter.
    private fun chapter(id: Int, order: Int, read: Boolean = false, downloaded: Boolean = false, lastPage: Int = 0) =
        SuwayomiChapter(id, 1, "Ch $id", id.toDouble(), null, 0, order, read, false, downloaded, lastPage, 10)

    private val chapters = listOf(chapter(3, 1), chapter(2, 2), chapter(1, 3, read = true))

    @Test
    fun `reading order is oldest first`() {
        chapters.inReadingOrder().map { it.id } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `next to read is the oldest unread chapter`() {
        chapters.nextToRead()?.id shouldBe 2
    }

    @Test
    fun `next to read is null when everything is read`() {
        chapters.map { it.copy(isRead = true) }.nextToRead() shouldBe null
    }

    @Test
    fun `chapter after follows reading order`() {
        chapters.chapterAfter(1)?.id shouldBe 2
        chapters.chapterAfter(3) shouldBe null
        chapters.chapterAfter(99) shouldBe null
    }

    @Test
    fun `downloadable skips downloaded and unselected chapters`() {
        val list = listOf(chapter(1, 3), chapter(2, 2, downloaded = true), chapter(3, 1))

        list.downloadable(setOf(1, 2)) shouldBe listOf(1)
    }

    @Test
    fun `resume page is clamped and restarts finished chapters`() {
        resumePage(chapter(1, 1, lastPage = 4), pageCount = 10) shouldBe 4
        resumePage(chapter(1, 1, lastPage = 40), pageCount = 10) shouldBe 9
        resumePage(chapter(1, 1, read = true, lastPage = 9), pageCount = 10) shouldBe 0
        resumePage(chapter(1, 1, lastPage = 4), pageCount = 0) shouldBe 0
    }
}
