package dev.trinhhg.vapp.exporter

import dev.trinhhg.vapp.exporter.api.BookExportMetadata
import dev.trinhhg.vapp.exporter.api.ChapterExportInput
import dev.trinhhg.vapp.exporter.api.CleanupRuleSpec
import dev.trinhhg.vapp.exporter.api.ExportFormat
import dev.trinhhg.vapp.exporter.api.ExportRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EpubExporterTest {
    @TempDir
    lateinit var tempDir: File

    private val sampleJpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    @Test
    fun `EPUB 2_0 writes STORED mimetype first, OPF 2_0, toc_ncx, Vietnamese UTF-8, and valid XML`() =
        runTest {
            val outFile = File(tempDir, "pham_nhan_v2.epub")
            val exporter = BookExporterImpl()
            val req =
                ExportRequest(
                    book =
                        BookExportMetadata(
                            id = "book-vn-1",
                            title = "Phàm Nhân Tu Tiên",
                            author = "Vong Ngữ",
                            synopsis = "<p>Hàn Lập &amp; bước đường tu tiên.</p>",
                            language = "vi",
                            coverBytes = sampleJpegHeader,
                        ),
                    chapters =
                        listOf(
                            ChapterExportInput(
                                0,
                                "Chương 1: Khởi đầu",
                                htmlContent = "<p>Thiếu niên bình phàm.</p>",
                            ),
                            ChapterExportInput(
                                1,
                                "Chương 2: Nhập môn",
                                htmlContent = "<p>Thất Huyền Môn ở trước mắt.</p>",
                            ),
                        ),
                    format = ExportFormat.EPUB_2,
                    includeCover = true,
                    outputFilePath = outFile.absolutePath,
                )

            val result = exporter.export(req)
            assertTrue(result.isSuccess)
            val output = result.getOrThrow()
            assertEquals(1, output.totalVolumes)
            assertEquals(2, output.totalChaptersExported)
            assertTrue(outFile.exists() && outFile.length() > 0L)

            assertMimetypeIsFirstStoredEntry(outFile)

            ZipFile(outFile).use { zip ->
                val container = zip.readUtf8("META-INF/container.xml")
                assertTrue(container.contains("OEBPS/content.opf"))

                val opf = zip.readUtf8("OEBPS/content.opf")
                assertTrue(opf.contains("version=\"2.0\""))
                assertTrue(opf.contains("<dc:title>Phàm Nhân Tu Tiên</dc:title>"))
                assertTrue(opf.contains("<dc:creator opf:role=\"aut\">Vong Ngữ</dc:creator>"))
                assertTrue(opf.contains("<meta name=\"cover\" content=\"cover-image\"/>"))
                assertTrue(opf.contains("application/x-dtbncx+xml"))
                assertWellFormedXml(opf)

                val ncx = zip.readUtf8("OEBPS/toc.ncx")
                assertTrue(ncx.contains("Chương 1: Khởi đầu"))
                assertTrue(ncx.contains("Chương 2: Nhập môn"))
                assertWellFormedXml(ncx)

                val chap1 = zip.readUtf8("OEBPS/text/chap_0001.xhtml")
                assertTrue(chap1.contains("Thiếu niên bình phàm."))
                assertWellFormedXml(chap1)
            }
        }

    @Test
    fun `EPUB 3_0 writes OPF 3_0 with dcterms modified, cover-image property, and nav_xhtml landmarks`() =
        runTest {
            val outFile = File(tempDir, "pham_nhan_v3.epub")
            val exporter = BookExporterImpl()
            val req =
                ExportRequest(
                    book =
                        BookExportMetadata(
                            id = "book-vn-3",
                            title = "Đấu Phá Thương Khung",
                            author = "Thiên Tàm Thổ Đậu",
                            language = "vi",
                            coverBytes = sampleJpegHeader,
                        ),
                    chapters =
                        listOf(
                            ChapterExportInput(0, "Chương 1: Đấu khí", htmlContent = "<p>Ba đoạn đấu khí!</p>"),
                        ),
                    format = ExportFormat.EPUB_3,
                    includeCover = true,
                    outputFilePath = outFile.absolutePath,
                )

            val output = exporter.export(req).getOrThrow()
            assertEquals(1, output.totalChaptersExported)
            assertMimetypeIsFirstStoredEntry(outFile)

            ZipFile(outFile).use { zip ->
                val opf = zip.readUtf8("OEBPS/content.opf")
                assertTrue(opf.contains("version=\"3.0\""))
                assertTrue(opf.contains("property=\"dcterms:modified\""))
                assertTrue(opf.contains("properties=\"nav\""))
                assertTrue(opf.contains("properties=\"cover-image\""))
                assertWellFormedXml(opf)

                val nav = zip.readUtf8("OEBPS/nav.xhtml")
                assertTrue(nav.contains("epub:type=\"toc\""))
                assertTrue(nav.contains("epub:type=\"landmarks\""))
                assertTrue(nav.contains("Chương 1: Đấu khí"))
                assertWellFormedXml(nav)
            }
        }

    @Test
    fun `Sanitizer strips scripts, iframes, event handlers, images when disabled, and applies cleanup rules`() =
        runTest {
            val outFile = File(tempDir, "sanitized.epub")
            val exporter = BookExporterImpl()
            val dirtyHtml =
                """
                <div onclick="steal()">
                  <script>alert(1)</script>
                  <iframe src="https://ads.example.com"></iframe>
                  <p>Đoạn văn sạch. [QC: Xem tại abc.xyz]</p>
                  <a href="javascript:void(0)">Link bẫy</a>
                  <img src="banner.jpg" />
                </div>
                """.trimIndent()

            val req =
                ExportRequest(
                    book = BookExportMetadata(id = "clean1", title = "Clean Test"),
                    chapters = listOf(ChapterExportInput(0, "Ch 1", htmlContent = dirtyHtml)),
                    format = ExportFormat.EPUB_3,
                    includeCover = false,
                    includeImages = false,
                    cleanupRules =
                        listOf(
                            CleanupRuleSpec(pattern = "\\[QC:[^\\]]+\\]", replacement = "", isRegex = true),
                        ),
                    outputFilePath = outFile.absolutePath,
                )

            assertTrue(exporter.export(req).isSuccess)
            ZipFile(outFile).use { zip ->
                val chap = zip.readUtf8("OEBPS/text/chap_0001.xhtml")
                assertFalse(chap.contains("<script"))
                assertFalse(chap.contains("<iframe"))
                assertFalse(chap.contains("onclick"))
                assertFalse(chap.contains("javascript:"))
                assertFalse(chap.contains("<img"))
                assertFalse(chap.contains("[QC:"))
                assertTrue(chap.contains("Đoạn văn sạch."))
                assertWellFormedXml(chap)
            }
        }

    @Test
    fun `Custom chapter range filter, volume splitting, and TXT export work accurately`() =
        runTest {
            val exporter = BookExporterImpl()
            val chapters =
                (1..5).map { idx ->
                    ChapterExportInput(
                        orderIndex = idx - 1,
                        title = "Chương $idx",
                        htmlContent = "<p>Nội dung chương số $idx.</p>",
                    )
                }

            // 1. Range + volume split on EPUB 3
            val splitBase = File(tempDir, "multi.epub")
            val splitOut =
                exporter
                    .export(
                        ExportRequest(
                            book = BookExportMetadata(id = "s1", title = "Bộ Truyện Dài", author = "Tác Giả"),
                            chapters = chapters,
                            format = ExportFormat.EPUB_3,
                            includeCover = false,
                            autoSplitVolumes = true,
                            maxChaptersPerVolume = 2,
                            chapterRangeSpec = "2-5",
                            outputFilePath = splitBase.absolutePath,
                        ),
                    ).getOrThrow()

            assertEquals(2, splitOut.totalVolumes)
            assertEquals(4, splitOut.totalChaptersExported)
            assertEquals(2, splitOut.outputFiles.size)
            assertTrue(File(splitOut.outputFiles[0]).name.endsWith("_vol1.epub"))
            assertTrue(File(splitOut.outputFiles[1]).name.endsWith("_vol2.epub"))

            // 2. TXT export
            val txtFile = File(tempDir, "book.txt")
            val txtOut =
                exporter
                    .export(
                        ExportRequest(
                            book = BookExportMetadata(id = "t1", title = "Truyện Chữ", author = "Tác Giả Việt"),
                            chapters = chapters,
                            format = ExportFormat.TXT,
                            chapterRangeSpec = "1,3",
                            outputFilePath = txtFile.absolutePath,
                        ),
                    ).getOrThrow()

            assertEquals(2, txtOut.totalChaptersExported)
            val txt = txtFile.readText(Charsets.UTF_8)
            assertTrue(txt.contains("Truyện Chữ"))
            assertTrue(txt.contains("Tác giả: Tác Giả Việt"))
            assertTrue(txt.contains("Nội dung chương số 1."))
            assertFalse(txt.contains("Nội dung chương số 2."))
            assertTrue(txt.contains("Nội dung chương số 3."))
        }

    @Test
    fun `Failed export leaves zero output files and zero tmp files`() =
        runTest {
            val outFile = File(tempDir, "should_not_exist.epub")
            val exporter = BookExporterImpl()

            // ReDoS regex pattern should fail validation before writing any final artifact
            val badResult =
                exporter.export(
                    ExportRequest(
                        book = BookExportMetadata(id = "fail1", title = "Fail Book"),
                        chapters = listOf(ChapterExportInput(0, "Ch 1", htmlContent = "<p>Test</p>")),
                        format = ExportFormat.EPUB_3,
                        cleanupRules = listOf(CleanupRuleSpec(pattern = "(a+)+$", isRegex = true)),
                        outputFilePath = outFile.absolutePath,
                    ),
                )

            assertTrue(badResult.isFailure)
            assertFalse(outFile.exists())
            val leftovers = tempDir.listFiles()?.filter { it.name.contains(".tmp") || it.name.endsWith(".epub") }.orEmpty()
            assertTrue(leftovers.isEmpty())
        }

    private fun assertMimetypeIsFirstStoredEntry(epubFile: File) {
        ZipInputStream(ByteArrayInputStream(epubFile.readBytes())).use { zis ->
            val firstEntry = zis.nextEntry
            assertNotNull(firstEntry)
            assertEquals("mimetype", firstEntry.name)
            assertEquals(ZipEntry.STORED, firstEntry.method)
            assertEquals("application/epub+zip", String(zis.readBytes(), Charsets.US_ASCII))
        }
    }

    private fun ZipFile.readUtf8(entryName: String): String {
        val entry = getEntry(entryName)
        assertNotNull(entry, "Expected ZIP entry $entryName")
        return getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun assertWellFormedXml(xmlContent: String) {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Skip external DTD fetch in offline unit tests
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlContent.encodeToByteArray()))
    }
}
