package nexplorer.slides

class Frame(val x: Float, val y: Float, val w: Float, val h: Float, val rot: Float = 0f, val flipH: Boolean = false, val flipV: Boolean = false)

sealed interface Fill
data object NoFill : Fill
data class Solid(val color: Int) : Fill
data class Gradient(val stops: List<Pair<Float, Int>>, val angle: Float, val radial: Boolean) : Fill
data class ImageFill(val image: String) : Fill

data class Line(
    val color: Int,
    val width: Float,
    val dash: String? = null,
    val head: String? = null,
    val tail: String? = null,
)

class Geometry(val preset: String, val adjust: Map<String, Float> = emptyMap(), val paths: List<GeoPath> = emptyList()) {
    companion object { val RECT = Geometry("rect") }
}

class GeoPath(val w: Float, val h: Float, val fill: Boolean, val stroke: Boolean, val cmds: List<PathCmd>)

sealed interface PathCmd {
    data class Move(val x: Float, val y: Float) : PathCmd
    data class LineTo(val x: Float, val y: Float) : PathCmd
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : PathCmd
    data class Quad(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : PathCmd
    data class Arc(val wR: Float, val hR: Float, val start: Float, val sweep: Float) : PathCmd
    data object Close : PathCmd
}

class Run(
    val text: String,
    val size: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val color: Int,
    val font: String,
    val baseline: Float,
)

data class Spacing(val percent: Float? = null, val points: Float? = null)

class Bullet(val text: String, val color: Int?, val sizePercent: Float)

class Para(
    val align: Char,
    val marginLeft: Float,
    val indent: Float,
    val bullet: Bullet?,
    val lineSpacing: Spacing?,
    val before: Spacing?,
    val after: Spacing?,
    val runs: List<Run>,
    /** The size an empty paragraph takes. */
    val endSize: Float,
)

class TextBody(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val anchor: Char,
    val wrap: Boolean,
    val shrink: Boolean,
    val fontScale: Float,
    val spacingReduction: Float,
    val vertical: Int,
    val paras: List<Para>,
)

sealed interface Item
class Shape(val frame: Frame, val geometry: Geometry, val fill: Fill?, val line: Line?, val text: TextBody?, val textFrame: Frame? = null) : Item
class Picture(val frame: Frame, val image: String, val crop: FloatArray, val geometry: Geometry, val line: Line?) : Item
class TableCell(
    val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int,
    val fill: Fill?, val left: Line?, val top: Line?, val right: Line?, val bottom: Line?,
    val text: TextBody?,
)
class Table(val frame: Frame, val cols: List<Float>, val rows: List<Float>, val cells: List<TableCell>) : Item
class Missing(val frame: Frame, val label: String) : Item

class Slide(val background: Fill?, val items: List<Item>)
