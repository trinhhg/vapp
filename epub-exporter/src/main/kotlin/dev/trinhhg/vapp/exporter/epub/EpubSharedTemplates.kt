package dev.trinhhg.vapp.exporter.epub

import dev.trinhhg.vapp.exporter.api.ExportFormat
import dev.trinhhg.vapp.exporter.html.ChapterSanitizer

data class EpubChapterEntry(
    val id: String,
    val href: String,
    val title: String,
    val partName: String?,
    val xhtmlContent: String = "",
)

data class EpubAssetEntry(
    val id: String,
    val href: String,
    val mediaType: String,
    val bytes: ByteArray,
    val isCoverImage: Boolean = false,
)

object EpubSharedTemplates {
    const val CONTAINER_XML: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    val DEFAULT_CSS: String =
        """
        body {
            font-family: serif;
            line-height: 1.65;
            margin: 1.2em;
            color: #1f1b16;
        }
        h1, h2 {
            text-align: center;
            margin: 0.8em 0 1em 0;
            line-height: 1.3;
        }
        p {
            text-indent: 1.4em;
            margin: 0.45em 0;
        }
        img {
            max-width: 100%;
            height: auto;
            display: block;
            margin: 0.8em auto;
        }
        .cover-wrap {
            text-align: center;
            margin-top: 2em;
        }
        .meta-author {
            text-align: center;
            font-style: italic;
            margin-bottom: 1.5em;
        }
        """.trimIndent()

    fun buildCoverXhtml(
        title: String,
        coverImageRelativeToTextDir: String,
        format: ExportFormat,
        language: String,
    ): String {
        val safeTitle = ChapterSanitizer.escapeXml(title)
        val safeImg = ChapterSanitizer.escapeXml(coverImageRelativeToTextDir)
        val safeLang = ChapterSanitizer.escapeXml(language.ifBlank { "vi" })
        return if (format == ExportFormat.EPUB_2) {
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$safeLang">
<head>
  <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=utf-8"/>
  <title>$safeTitle</title>
  <link rel="stylesheet" type="text/css" href="../styles/style.css"/>
</head>
<body>
  <div class="cover-wrap"><img src="$safeImg" alt="$safeTitle"/></div>
</body>
</html>"""
        } else {
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$safeLang" lang="$safeLang">
<head>
  <meta charset="utf-8"/>
  <title>$safeTitle</title>
  <link rel="stylesheet" type="text/css" href="../styles/style.css"/>
</head>
<body>
  <section class="cover-wrap"><img src="$safeImg" alt="$safeTitle"/></section>
</body>
</html>"""
        }
    }

    fun buildIntroXhtml(
        title: String,
        author: String,
        synopsis: String,
        format: ExportFormat,
        language: String,
    ): String {
        val safeTitle = ChapterSanitizer.escapeXml(title)
        val safeAuthor = ChapterSanitizer.escapeXml(author.ifBlank { "Không rõ" })
        val cleanSynopsis = ChapterSanitizer.sanitizeBodyFragment(synopsis.ifBlank { "<p></p>" }, includeImages = false)
        val safeLang = ChapterSanitizer.escapeXml(language.ifBlank { "vi" })
        return if (format == ExportFormat.EPUB_2) {
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$safeLang">
<head>
  <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=utf-8"/>
  <title>$safeTitle</title>
  <link rel="stylesheet" type="text/css" href="../styles/style.css"/>
</head>
<body>
  <h1>$safeTitle</h1>
  <p class="meta-author">$safeAuthor</p>
  <div class="synopsis">$cleanSynopsis</div>
</body>
</html>"""
        } else {
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$safeLang" lang="$safeLang">
<head>
  <meta charset="utf-8"/>
  <title>$safeTitle</title>
  <link rel="stylesheet" type="text/css" href="../styles/style.css"/>
</head>
<body>
  <section>
    <h1>$safeTitle</h1>
    <p class="meta-author">$safeAuthor</p>
    <div class="synopsis">$cleanSynopsis</div>
  </section>
</body>
</html>"""
        }
    }
}
