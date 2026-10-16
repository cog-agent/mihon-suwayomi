package mihon.suwayomi

/** Chapters in reading order (oldest first). The server's source order is newest first. */
fun List<SuwayomiChapter>.inReadingOrder(): List<SuwayomiChapter> = sortedBy { it.sourceOrder }.asReversed()

/** The chapter to open for "continue reading": the first unread one, or null when everything is read. */
fun List<SuwayomiChapter>.nextToRead(): SuwayomiChapter? = inReadingOrder().firstOrNull { !it.isRead }

/** The chapter that follows [current] in reading order, or null if [current] is the last one. */
fun List<SuwayomiChapter>.chapterAfter(current: Int): SuwayomiChapter? {
    val ordered = inReadingOrder()
    val index = ordered.indexOfFirst { it.id == current }
    return if (index < 0) null else ordered.getOrNull(index + 1)
}

/** Ids of chapters to download: skips ones the server already has. */
fun List<SuwayomiChapter>.downloadable(selected: Set<Int>): List<Int> =
    filter { it.id in selected && !it.isDownloaded }.map { it.id }

/**
 * The page to resume at, clamped to the pages that exist. A chapter that was already finished restarts from the
 * first page.
 */
fun resumePage(chapter: SuwayomiChapter, pageCount: Int): Int {
    if (pageCount <= 0 || chapter.isRead) return 0
    return chapter.lastPageRead.coerceIn(0, pageCount - 1)
}
