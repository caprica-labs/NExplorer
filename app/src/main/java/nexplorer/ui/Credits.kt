package nexplorer.ui

enum class Licence(val title: String, val file: String) {
    AGPL("GNU Affero General Public License v3", "AGPL-3.0.txt"),
    Apache("Apache License 2.0", "Apache-2.0.txt"),
    MIT("MIT License", "MIT.txt"),
    HarfBuzz("Old MIT License", "HarfBuzz.txt"),
    BSD2("BSD 2-Clause License", "BSD-2-Clause.txt"),
    BSD3("BSD 3-Clause License", "BSD-3-Clause.txt"),
    Zlib("zlib License", "Zlib.txt"),
    IJG("Independent JPEG Group License", "IJG.txt"),
    FreeType("FreeType License", "FTL.txt"),
    OFL("SIL Open Font License 1.1", "OFL-1.1.txt"),
    Bzip2("bzip2 License", "bzip2-1.0.6.txt"),
    ISC("ISC License", "ISC.txt"),
    ZeroBSD("BSD Zero Clause License", "0BSD.txt"),
}

fun reflow(text: String): String =
    text.replace("\r", "").replace(Regex("""(?<=\S)\n(?![\n\s]|Copyright|[-*•]\s|\d+\.\s|\(\w\)\s)"""), " ")

data class Credit(
    val name: String,
    val what: String,
    val copyright: String,
    val licence: Licence,
    val notice: String? = null,
)

object Credits {
    const val SOURCE = "https://github.com/caprica-labs/NExplorer"
    const val EMAIL = "caprica-labs@proton.me"
    const val OWNER = "caprica-labs"
    const val YEAR = "2026"

    val groups: List<Pair<String, List<Credit>>> = listOf(
        "Reading documents" to listOf(
            Credit("MuPDF", "Opens, draws, searches and edits PDF, EPUB, XPS, comics, FB2 and MOBI", "Copyright © Artifex Software, Inc.", Licence.AGPL),
            Credit(
                "FreeType", "Draws the letters of fonts (part of MuPDF)",
                "Copyright © The FreeType Project (David Turner, Robert Wilhelm and Werner Lemberg)", Licence.FreeType,
                notice = "Portions of this software are copyright © The FreeType Project (www.freetype.org). All rights reserved.",
            ),
            Credit("HarfBuzz", "Arranges letters for every writing system (part of MuPDF)", "Copyright © the HarfBuzz authors, listed in the licence", Licence.HarfBuzz),
            Credit("jbig2dec", "Black-and-white scanned pages (part of MuPDF)", "Copyright © Artifex Software, Inc.", Licence.AGPL),
            Credit("Little CMS", "Colour management (part of MuPDF)", "Copyright © Marti Maria Saguer", Licence.MIT),
            Credit("OpenJPEG", "JPEG 2000 pictures (part of MuPDF)", "Copyright © Université catholique de Louvain (UCL), Belgium, and the OpenJPEG contributors", Licence.BSD2),
            Credit(
                "libjpeg", "JPEG pictures (part of MuPDF)", "Copyright © Thomas G. Lane, Guido Vollbeding", Licence.IJG,
                notice = "This software is based in part on the work of the Independent JPEG Group.",
            ),
            Credit("zlib", "Compressed data in documents and archives", "Copyright © Jean-loup Gailly and Mark Adler", Licence.Zlib),
            Credit("Brotli", "Compressed fonts (part of MuPDF)", "Copyright © the Brotli Authors", Licence.MIT),
            Credit("Gumbo", "Reads HTML (part of MuPDF)", "Copyright © Google Inc.", Licence.Apache),
            Credit("MuJS", "JavaScript engine (part of MuPDF)", "Copyright © Artifex Software, Inc.", Licence.ISC),
            Credit("Extract", "Text and layout extraction (part of MuPDF)", "Copyright © Artifex Software, Inc.", Licence.AGPL),
            Credit("URW base 35 fonts", "The standard PDF fonts: Nimbus and Standard Symbols (part of MuPDF)", "Copyright © (URW)++ Design & Development, URW Software and Artifex Software, Inc.", Licence.AGPL),
            Credit("Noto fonts", "Fallback fonts (part of MuPDF)", "Copyright © Google LLC and the Noto Project Authors", Licence.OFL),
            Credit("Droid Sans Fallback", "Chinese, Japanese and Korean fallback font (part of MuPDF)", "Copyright © The Android Open Source Project", Licence.Apache),
            Credit("Charis SIL", "Fallback font (part of MuPDF)", "Copyright © SIL International", Licence.OFL),
        ),
        "Reading RAR comics" to listOf(
            Credit("libarchive for Android", "Opens .cbr comics", "Copyright © Hai Zhang", Licence.Apache),
            Credit("libarchive", "Reads RAR and other archives", "Copyright © Tim Kientzle and the libarchive contributors", Licence.BSD2),
            Credit("bzip2", "Compressed data (part of libarchive for Android)", "Copyright © Julian R Seward", Licence.Bzip2),
            Credit("XZ Utils", "Compressed data (part of libarchive for Android)", "Copyright © the XZ Utils authors", Licence.ZeroBSD),
            Credit("LZ4", "Compressed data (part of libarchive for Android)", "Copyright © Yann Collet", Licence.BSD2),
            Credit("Zstandard", "Compressed data (part of libarchive for Android)", "Copyright © Meta Platforms, Inc. and affiliates", Licence.BSD3),
            Credit("Mbed TLS", "Encrypted archives (part of libarchive for Android)", "Copyright © The Mbed TLS Contributors", Licence.Apache),
        ),
        "The app" to listOf(
            Credit(
                "Android Jetpack (AndroidX)",
                "Screens (Compose), camera scanning (CameraX), settings (DataStore), web pages (WebKit), Media3 and the other Android libraries",
                "Copyright © The Android Open Source Project", Licence.Apache,
            ),
            Credit("Material Icons", "The app's icons", "Copyright © Google LLC", Licence.Apache),
            Credit("Kotlin", "The language and its libraries: coroutines, serialization, atomicfu", "Copyright © JetBrains s.r.o. and Kotlin Programming Language contributors", Licence.Apache),
            Credit("ZXing", "Network Share's QR code", "Copyright © the ZXing authors", Licence.Apache),
            Credit("Guava", "Used by the camera libraries", "Copyright © The Guava Authors", Licence.Apache),
            Credit("Okio", "Used by DataStore", "Copyright © Square, Inc.", Licence.Apache),
            Credit("Dagger, AutoValue, Error Prone and J2ObjC annotations", "Used by the Android libraries", "Copyright © Google LLC", Licence.Apache),
            Credit("JSR-305, javax.inject, Jakarta Inject, JSpecify and JetBrains annotations", "Used by the Android libraries", "Copyright © their respective authors", Licence.Apache),
            Credit("Checker Framework qualifiers", "Used by Guava", "Copyright © the Checker Framework developers", Licence.MIT),
        ),
    )
}
