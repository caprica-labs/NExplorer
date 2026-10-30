package nexplorer.slides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SlideDeckTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
        """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
    private val relNs = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val relType = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun rels(vararg r: Triple<String, String, String>) =
        """<Relationships xmlns="$relNs">""" + r.joinToString("") { (id, type, target) ->
            """<Relationship Id="$id" Type="$relType/$type" Target="$target"/>"""
        } + "</Relationships>"

    private val theme = """<a:theme $ns name="t"><a:themeElements>
        <a:clrScheme name="c"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
        <a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1>
        <a:accent2><a:srgbClr val="ED7D31"/></a:accent2></a:clrScheme>
        <a:fontScheme name="f"><a:majorFont><a:latin typeface="Calibri Light"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme>
        <a:fmtScheme name="s"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>
        <a:lnStyleLst><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>
        </a:themeElements></a:theme>"""

    private val master = """<p:sldMaster $ns><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>
        <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
        <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
          <p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr>
          <p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle/><a:p><a:r><a:t>Master title prompt</a:t></a:r></a:p></p:txBody></p:sp>
        <p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr>
          <p:spPr><a:xfrm><a:off x="838200" y="1825625"/><a:ext cx="10515600" cy="4351338"/></a:xfrm></p:spPr></p:sp>
        <p:sp><p:nvSpPr><p:cNvPr id="4" name="Logo bar"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
          <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="12192000" cy="127000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:schemeClr val="accent2"/></a:solidFill></p:spPr></p:sp>
        </p:spTree></p:cSld>
        <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
        <p:txStyles>
          <p:titleStyle><a:lvl1pPr algn="l"><a:defRPr sz="4400"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mj-lt"/></a:defRPr></a:lvl1pPr></p:titleStyle>
          <p:bodyStyle><a:lvl1pPr marL="228600" indent="-228600"><a:buFont typeface="Arial"/><a:buChar char="•"/><a:defRPr sz="2800"/></a:lvl1pPr>
            <a:lvl2pPr marL="685800" indent="-228600"><a:buFont typeface="Wingdings"/><a:buChar char="§"/><a:defRPr sz="2400"/></a:lvl2pPr></p:bodyStyle>
          <p:otherStyle/>
        </p:txStyles></p:sldMaster>"""

    private val layout = """<p:sldLayout $ns><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
        <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>
        <p:sp><p:nvSpPr><p:cNvPr id="3" name="Content"/><p:cNvSpPr/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>
        </p:spTree></p:cSld></p:sldLayout>"""

    private fun slideXml(tree: String) =
        """<p:sld $ns><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$tree</p:spTree></p:cSld></p:sld>"""

    private fun deck(vararg slides: String, extra: Map<String, String> = emptyMap(), slideRels: String? = null): File {
        val f = tmp.newFile("deck${System.nanoTime()}.pptx")
        val parts = LinkedHashMap<String, String>()
        parts["_rels/.rels"] = rels(Triple("rId1", "officeDocument", "ppt/presentation.xml"))
        parts["ppt/presentation.xml"] = """<p:presentation $ns><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rIdM"/></p:sldMasterIdLst>
            <p:sldIdLst>${slides.indices.joinToString("") { """<p:sldId id="${256 + it}" r:id="rId${it + 1}"/>""" }}</p:sldIdLst>
            <p:sldSz cx="12192000" cy="6858000"/>
            <p:defaultTextStyle><a:lvl1pPr><a:defRPr sz="1800"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill></a:defRPr></a:lvl1pPr></p:defaultTextStyle></p:presentation>"""
        parts["ppt/_rels/presentation.xml.rels"] = rels(
            *slides.indices.map { Triple("rId${it + 1}", "slide", "slides/slide${it + 1}.xml") }.toTypedArray(),
            Triple("rIdM", "slideMaster", "slideMasters/slideMaster1.xml"),
        )
        parts["ppt/slideMasters/slideMaster1.xml"] = master
        parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = rels(Triple("rId1", "theme", "../theme/theme1.xml"))
        parts["ppt/theme/theme1.xml"] = theme
        parts["ppt/slideLayouts/slideLayout1.xml"] = layout
        parts["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = rels(Triple("rId1", "slideMaster", "../slideMasters/slideMaster1.xml"))
        slides.forEachIndexed { i, s ->
            parts["ppt/slides/slide${i + 1}.xml"] = s
            parts["ppt/slides/_rels/slide${i + 1}.xml.rels"] = slideRels ?: rels(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"))
        }
        parts.putAll(extra)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, text) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    private fun sp(ph: String = "", xfrm: String = "", spPr: String = "", body: String = "", style: String = "") =
        """<p:sp><p:nvSpPr><p:cNvPr id="5" name="s"/><p:cNvSpPr/><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr>$xfrm$spPr</p:spPr>$style""" +
            (if (body.isEmpty()) "" else """<p:txBody><a:bodyPr/><a:lstStyle/>$body</p:txBody>""") + "</p:sp>"

    private fun xfrm(x: Long, y: Long, cx: Long, cy: Long) = """<a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>"""

    private fun open(f: File) = SlideDeck.open(f)

    @Test
    fun slideSizeAndCount() {
        open(deck(slideXml(""), slideXml(""))).use { d ->
            assertEquals(960f, d.width, 0.01f)
            assertEquals(540f, d.height, 0.01f)
            assertEquals(2, d.slides.size)
            assertEquals(Solid(0xFFFFFFFF.toInt()), d.slides[0].background)
            val bar = d.slides[0].items.single() as Shape
            assertEquals(Solid(0xFFED7D31.toInt()), bar.fill)
            assertEquals(10f, bar.frame.h, 0.01f)
        }
    }

    @Test
    fun placeholderTakesPlaceAndLookFromMaster() {
        val title = sp("""<p:ph type="title"/>""", body = """<a:p><a:r><a:rPr lang="en-US"/><a:t>Hello</a:t></a:r></a:p>""")
        open(deck(slideXml(title))).use { d ->
            val s = d.slides[0].items.last() as Shape
            assertEquals(838200f / 12700f, s.frame.x, 0.01f)
            val text = s.text!!
            assertEquals('c', text.anchor)
            val run = text.paras.single().runs.single()
            assertEquals("Hello", run.text)
            assertEquals(44f, run.size, 0.01f)
            assertEquals(0xFF000000.toInt(), run.color)
            assertEquals("Calibri Light", run.font)
        }
    }

    @Test
    fun promptTextOfLayoutsIsNeverShown() {
        open(deck(slideXml(""))).use { d ->
            assertTrue(d.slides[0].items.none { it is Shape && it.text != null })
        }
    }

    @Test
    fun bulletsByLevelAndNumbering() {
        val body = sp(
            """<p:ph idx="1"/>""",
            body = """<a:p><a:r><a:t>One</a:t></a:r></a:p><a:p><a:pPr lvl="1"/><a:r><a:t>Sub</a:t></a:r></a:p>
                <a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:t>A</a:t></a:r></a:p>
                <a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:t>B</a:t></a:r></a:p>
                <a:p><a:pPr><a:buAutoNum type="romanUcParenR" startAt="5"/></a:pPr><a:r><a:t>C</a:t></a:r></a:p>""",
        )
        open(deck(slideXml(body))).use { d ->
            val paras = (d.slides[0].items.last() as Shape).text!!.paras
            assertEquals("•", paras[0].bullet!!.text)
            assertEquals(28f, paras[0].runs[0].size, 0.01f)
            assertEquals(18f, paras[0].marginLeft, 0.01f)
            assertEquals(-18f, paras[0].indent, 0.01f)
            assertEquals("▪", paras[1].bullet!!.text)
            assertEquals(24f, paras[1].runs[0].size, 0.01f)
            assertEquals("1.", paras[2].bullet!!.text)
            assertEquals("2.", paras[3].bullet!!.text)
            assertEquals("V)", paras[4].bullet!!.text)
        }
    }

    @Test
    fun shapeStyleGivesFillOutlineAndTextColor() {
        val style = """<p:style><a:lnRef idx="1"><a:schemeClr val="accent1"><a:shade val="50000"/></a:schemeClr></a:lnRef>
            <a:fillRef idx="1"><a:schemeClr val="accent1"/></a:fillRef><a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef>
            <a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></p:style>"""
        val shape = sp(
            xfrm = xfrm(0, 0, 1270000, 635000),
            spPr = """<a:prstGeom prst="roundRect"><a:avLst/></a:prstGeom>""",
            body = """<a:p><a:r><a:t>In a box</a:t></a:r></a:p>""",
            style = style,
        )
        open(deck(slideXml(shape))).use { d ->
            val s = d.slides[0].items.last() as Shape
            assertEquals("roundRect", s.geometry.preset)
            assertEquals(Solid(0xFF4472C4.toInt()), s.fill)
            assertEquals(1f, s.line!!.width, 0.01f)
            assertEquals(0xFF223962.toInt(), s.line!!.color)
            assertEquals(0xFFFFFFFF.toInt(), s.text!!.paras[0].runs[0].color)
            assertEquals(100f, s.frame.w, 0.01f)
        }
    }

    @Test
    fun groupsScaleTheirShapes() {
        val group = """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="9" name="g"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
            <p:grpSpPr><a:xfrm><a:off x="1270000" y="0"/><a:ext cx="2540000" cy="2540000"/><a:chOff x="0" y="0"/><a:chExt cx="1270000" cy="1270000"/></a:xfrm></p:grpSpPr>
            ${sp(xfrm = xfrm(635000, 0, 635000, 635000), spPr = """<a:solidFill><a:srgbClr val="FF0000"/></a:solidFill>""")}</p:grpSp>"""
        open(deck(slideXml(group))).use { d ->
            val s = d.slides[0].items.last() as Shape
            assertEquals(200f, s.frame.x, 0.01f)
            assertEquals(100f, s.frame.w, 0.01f)
        }
    }

    @Test
    fun picturesFromInsideTheFileOnlyAndCrop() {
        val pic = """<p:pic><p:nvPicPr><p:cNvPr id="4" name="p"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
            <p:blipFill><a:blip r:embed="rId2"/><a:srcRect l="10000" r="20000"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
            <p:spPr>${xfrm(0, 0, 127000, 127000)}<a:prstGeom prst="ellipse"><a:avLst/></a:prstGeom></p:spPr></p:pic>
            <p:pic><p:nvPicPr><p:cNvPr id="5" name="web"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
            <p:blipFill><a:blip r:embed="rId3"/></p:blipFill><p:spPr>${xfrm(0, 0, 127000, 127000)}</p:spPr></p:pic>"""
        val rels = """<Relationships xmlns="$relNs"><Relationship Id="rId1" Type="$relType/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
            <Relationship Id="rId2" Type="$relType/image" Target="../media/image1.png"/>
            <Relationship Id="rId3" Type="$relType/image" Target="https://example.com/x.png" TargetMode="External"/></Relationships>"""
        open(deck(slideXml(pic), extra = mapOf("ppt/media/image1.png" to "png bytes"), slideRels = rels)).use { d ->
            val p = d.slides[0].items.filterIsInstance<Picture>().single()
            assertEquals("ppt/media/image1.png", p.image)
            assertEquals(0.1f, p.crop[0], 0.0001f)
            assertEquals(0.2f, p.crop[2], 0.0001f)
            assertEquals("ellipse", p.geometry.preset)
            assertEquals("png bytes", String(d.image(p.image)!!))
        }
    }

    @Test
    fun tablesWithTheDefaultLook() {
        val table = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="4" name="t"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
            <p:xfrm><a:off x="0" y="0"/><a:ext cx="2540000" cy="762000"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">
            <a:tbl><a:tblPr firstRow="1" bandRow="1"><a:tableStyleId>{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}</a:tableStyleId></a:tblPr>
            <a:tblGrid><a:gridCol w="1270000"/><a:gridCol w="1270000"/></a:tblGrid>
            <a:tr h="381000"><a:tc gridSpan="2"><a:txBody><a:bodyPr/><a:p><a:r><a:t>Head</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc><a:tc hMerge="1"><a:txBody><a:bodyPr/><a:p/></a:txBody><a:tcPr/></a:tc></a:tr>
            <a:tr h="381000"><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>a</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc>
            <a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>b</a:t></a:r></a:p></a:txBody><a:tcPr><a:solidFill><a:srgbClr val="00FF00"/></a:solidFill></a:tcPr></a:tc></a:tr>
            </a:tbl></a:graphicData></a:graphic></p:graphicFrame>"""
        open(deck(slideXml(table))).use { d ->
            val t = d.slides[0].items.filterIsInstance<Table>().single()
            assertEquals(listOf(100f, 100f), t.cols)
            assertEquals(3, t.cells.size)
            val head = t.cells[0]
            assertEquals(2, head.colSpan)
            assertEquals(Solid(0xFF4472C4.toInt()), head.fill)
            assertTrue(head.text!!.paras[0].runs[0].bold)
            assertEquals(0xFFFFFFFF.toInt(), head.text!!.paras[0].runs[0].color)
            assertEquals(Solid(0xFF00FF00.toInt()), t.cells[2].fill)
            assertNotNull(t.cells[1].fill)
        }
    }

    @Test
    fun chartsShowAsAMarkedBox() {
        val chart = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="4" name="c"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
            <p:xfrm><a:off x="0" y="0"/><a:ext cx="127000" cy="127000"/></p:xfrm><a:graphic>
            <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rId9"/></a:graphicData></a:graphic></p:graphicFrame>"""
        open(deck(slideXml(chart))).use { d ->
            assertEquals("Chart", d.slides[0].items.filterIsInstance<Missing>().single().label)
        }
    }

    @Test
    fun slideNumberFieldsShowTheRealNumber() {
        val num = sp(xfrm = xfrm(0, 0, 127000, 127000), body = """<a:p><a:fld id="{1}" type="slidenum"><a:t>‹#›</a:t></a:fld></a:p>""")
        open(deck(slideXml(""), slideXml(num))).use { d ->
            assertEquals("2", (d.slides[1].items.last() as Shape).text!!.paras[0].runs[0].text)
        }
    }

    @Test
    fun brokenSlideIsMarkedNotFatal() {
        open(deck(slideXml(""), "<p:sld $ns><broken")).use { d ->
            assertEquals(2, d.slides.size)
            assertTrue(d.slides[1].items.single() is Missing)
        }
    }

    @Test
    fun xmlWithDoctypeIsRefused() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>""" + slideXml("")
        val f = deck(slideXml(""), extra = mapOf("ppt/presentation.xml" to evil))
        try {
            open(f).close()
            fail("should be refused")
        } catch (e: SlideDeck.NotSlides) {
            assertEquals("This file has unsafe content", e.message)
        }
    }

    @Test
    fun notAPowerPointFile() {
        val f = tmp.newFile("x.pptx").apply { writeText("hello") }
        try {
            open(f).close()
            fail("should be refused")
        } catch (e: SlideDeck.NotSlides) {
            assertEquals("This isn't a PowerPoint file", e.message)
        }
    }

    @Test
    fun linkTargetsStayInsideTheFile() {
        assertEquals("ppt/media/a.png", SlidePackage.resolve("ppt/slides", "../media/a.png"))
        assertEquals("ppt/media/a.png", SlidePackage.resolve("ppt/slides", "/ppt/media/a.png"))
        assertEquals("a.png", SlidePackage.resolve("ppt", "../../../a.png"))
    }
}
