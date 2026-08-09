package dev.ruri.il2cppmanager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object ManagerIcons {
    val Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "Folder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 6.5f)
                curveTo(3.5f, 5.67f, 4.17f, 5f, 5f, 5f)
                lineTo(9.1f, 5f)
                lineTo(11f, 7.25f)
                lineTo(19f, 7.25f)
                curveTo(19.83f, 7.25f, 20.5f, 7.92f, 20.5f, 8.75f)
                lineTo(20.5f, 18f)
                curveTo(20.5f, 18.83f, 19.83f, 19.5f, 19f, 19.5f)
                lineTo(5f, 19.5f)
                curveTo(4.17f, 19.5f, 3.5f, 18.83f, 3.5f, 18f)
                close()
            }
        }.build()
    }

    val ChevronDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronDown",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 9.5f)
                lineTo(12f, 14.5f)
                lineTo(17f, 9.5f)
            }
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.5f, 7f)
                lineTo(14.5f, 12f)
                lineTo(9.5f, 17f)
            }
        }.build()
    }

    val BrowserWorkspace: ImageVector by lazy {
        ImageVector.Builder(
            name = "BrowserWorkspace",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.5f, 6f)
                lineTo(5.51f, 6f)
                moveTo(9f, 6f)
                lineTo(19f, 6f)
                moveTo(5.5f, 12f)
                lineTo(5.51f, 12f)
                moveTo(9f, 12f)
                lineTo(19f, 12f)
                moveTo(5.5f, 18f)
                lineTo(5.51f, 18f)
                moveTo(9f, 18f)
                lineTo(19f, 18f)
            }
        }.build()
    }

    val CanvasWorkspace: ImageVector by lazy {
        ImageVector.Builder(
            name = "CanvasWorkspace",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 10f)
                lineTo(7f, 7f)
                moveTo(14f, 10f)
                lineTo(17f, 7f)
                moveTo(10f, 14f)
                lineTo(7f, 17f)
                moveTo(14f, 14f)
                lineTo(17f, 17f)
                moveTo(10f, 10f)
                lineTo(14f, 10f)
                lineTo(14f, 14f)
                lineTo(10f, 14f)
                close()
                moveTo(4f, 4f)
                lineTo(7f, 4f)
                lineTo(7f, 7f)
                lineTo(4f, 7f)
                close()
                moveTo(17f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 7f)
                lineTo(17f, 7f)
                close()
                moveTo(4f, 17f)
                lineTo(7f, 17f)
                lineTo(7f, 20f)
                lineTo(4f, 20f)
                close()
                moveTo(17f, 17f)
                lineTo(20f, 17f)
                lineTo(20f, 20f)
                lineTo(17f, 20f)
                close()
            }
        }.build()
    }

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "Search",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10.75f, 4.5f)
                curveTo(7.3f, 4.5f, 4.5f, 7.3f, 4.5f, 10.75f)
                curveTo(4.5f, 14.2f, 7.3f, 17f, 10.75f, 17f)
                curveTo(14.2f, 17f, 17f, 14.2f, 17f, 10.75f)
                curveTo(17f, 7.3f, 14.2f, 4.5f, 10.75f, 4.5f)
                close()
                moveTo(15.25f, 15.25f)
                lineTo(20f, 20f)
            }
        }.build()
    }

    val Copy: ImageVector by lazy {
        ImageVector.Builder(
            name = "Copy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.5f, 8.5f)
                lineTo(19f, 8.5f)
                curveTo(19.83f, 8.5f, 20.5f, 9.17f, 20.5f, 10f)
                lineTo(20.5f, 19f)
                curveTo(20.5f, 19.83f, 19.83f, 20.5f, 19f, 20.5f)
                lineTo(10f, 20.5f)
                curveTo(9.17f, 20.5f, 8.5f, 19.83f, 8.5f, 19f)
                close()
                moveTo(15.5f, 8.5f)
                lineTo(15.5f, 5f)
                curveTo(15.5f, 4.17f, 14.83f, 3.5f, 14f, 3.5f)
                lineTo(5f, 3.5f)
                curveTo(4.17f, 3.5f, 3.5f, 4.17f, 3.5f, 5f)
                lineTo(3.5f, 14f)
                curveTo(3.5f, 14.83f, 4.17f, 15.5f, 5f, 15.5f)
                lineTo(8.5f, 15.5f)
            }
        }.build()
    }

    val ExactMatch: ImageVector by lazy {
        ImageVector.Builder(
            name = "ExactMatch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 5f)
                lineTo(4.5f, 5f)
                lineTo(4.5f, 19f)
                lineTo(7f, 19f)
                moveTo(17f, 5f)
                lineTo(19.5f, 5f)
                lineTo(19.5f, 19f)
                lineTo(17f, 19f)
                moveTo(8f, 10f)
                lineTo(16f, 10f)
                moveTo(8f, 14f)
                lineTo(16f, 14f)
            }
        }.build()
    }

    val MatchCase: ImageVector by lazy {
        ImageVector.Builder(
            name = "MatchCase",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 18.5f)
                lineTo(7.5f, 5.5f)
                lineTo(11.5f, 18.5f)
                moveTo(5.1f, 13.5f)
                lineTo(9.9f, 13.5f)
                moveTo(20.5f, 12.5f)
                curveTo(19.7f, 11.65f, 18.55f, 11.2f, 17.35f, 11.2f)
                curveTo(15.45f, 11.2f, 14.2f, 12.75f, 14.2f, 14.85f)
                curveTo(14.2f, 16.95f, 15.45f, 18.5f, 17.35f, 18.5f)
                curveTo(18.55f, 18.5f, 19.7f, 18.05f, 20.5f, 17.2f)
                moveTo(20.5f, 11.2f)
                lineTo(20.5f, 18.5f)
            }
        }.build()
    }

    val Menu: ImageVector by lazy {
        ImageVector.Builder(
            name = "Menu",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(5f, 7f)
                lineTo(19f, 7f)
                moveTo(5f, 12f)
                lineTo(19f, 12f)
                moveTo(5f, 17f)
                lineTo(19f, 17f)
            }
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder(
            name = "Info",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3.5f)
                curveTo(7.31f, 3.5f, 3.5f, 7.31f, 3.5f, 12f)
                curveTo(3.5f, 16.69f, 7.31f, 20.5f, 12f, 20.5f)
                curveTo(16.69f, 20.5f, 20.5f, 16.69f, 20.5f, 12f)
                curveTo(20.5f, 7.31f, 16.69f, 3.5f, 12f, 3.5f)
                close()
                moveTo(12f, 10.5f)
                lineTo(12f, 16.5f)
                moveTo(12f, 7.5f)
                lineTo(12f, 7.55f)
            }
        }.build()
    }

    val Back: ImageVector by lazy {
        ImageVector.Builder(
            name = "Back",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15.5f, 5.5f)
                lineTo(9f, 12f)
                lineTo(15.5f, 18.5f)
            }
        }.build()
    }

    val Code: ImageVector by lazy {
        ImageVector.Builder(
            name = "Code",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 7f)
                lineTo(4f, 12f)
                lineTo(9f, 17f)
                moveTo(15f, 7f)
                lineTo(20f, 12f)
                lineTo(15f, 17f)
            }
        }.build()
    }

    val Send: ImageVector by lazy {
        ImageVector.Builder(
            name = "Send",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 11.5f)
                lineTo(20.5f, 4f)
                lineTo(15.5f, 20f)
                lineTo(11.5f, 13f)
                close()
                moveTo(11.5f, 13f)
                lineTo(20.5f, 4f)
            }
        }.build()
    }

    val Credits: ImageVector by lazy {
        ImageVector.Builder(
            name = "Credits",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3.5f)
                lineTo(14.65f, 8.85f)
                lineTo(20.5f, 9.7f)
                lineTo(16.25f, 13.8f)
                lineTo(17.25f, 19.5f)
                lineTo(12f, 16.8f)
                lineTo(6.75f, 19.5f)
                lineTo(7.75f, 13.8f)
                lineTo(3.5f, 9.7f)
                lineTo(9.35f, 8.85f)
                close()
            }
        }.build()
    }

    val License: ImageVector by lazy {
        ImageVector.Builder(
            name = "License",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 3.5f)
                lineTo(14.5f, 3.5f)
                lineTo(19f, 8f)
                lineTo(19f, 20.5f)
                lineTo(6f, 20.5f)
                close()
                moveTo(14.5f, 3.5f)
                lineTo(14.5f, 8f)
                lineTo(19f, 8f)
                moveTo(9f, 12f)
                lineTo(16f, 12f)
                moveTo(9f, 16f)
                lineTo(16f, 16f)
            }
        }.build()
    }

    val ExternalLink: ImageVector by lazy {
        ImageVector.Builder(
            name = "ExternalLink",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13f, 5f)
                lineTo(19f, 5f)
                lineTo(19f, 11f)
                moveTo(19f, 5f)
                lineTo(11f, 13f)
                moveTo(10f, 7f)
                lineTo(6.5f, 7f)
                curveTo(5.67f, 7f, 5f, 7.67f, 5f, 8.5f)
                lineTo(5f, 17.5f)
                curveTo(5f, 18.33f, 5.67f, 19f, 6.5f, 19f)
                lineTo(15.5f, 19f)
                curveTo(16.33f, 19f, 17f, 18.33f, 17f, 17.5f)
                lineTo(17f, 14f)
            }
        }.build()
    }

    val Sun: ImageVector by lazy {
        ImageVector.Builder(
            name = "Sun",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 8.25f)
                curveTo(9.93f, 8.25f, 8.25f, 9.93f, 8.25f, 12f)
                curveTo(8.25f, 14.07f, 9.93f, 15.75f, 12f, 15.75f)
                curveTo(14.07f, 15.75f, 15.75f, 14.07f, 15.75f, 12f)
                curveTo(15.75f, 9.93f, 14.07f, 8.25f, 12f, 8.25f)
                close()
                moveTo(12f, 3.5f)
                lineTo(12f, 5.5f)
                moveTo(12f, 18.5f)
                lineTo(12f, 20.5f)
                moveTo(3.5f, 12f)
                lineTo(5.5f, 12f)
                moveTo(18.5f, 12f)
                lineTo(20.5f, 12f)
                moveTo(5.95f, 5.95f)
                lineTo(7.35f, 7.35f)
                moveTo(16.65f, 16.65f)
                lineTo(18.05f, 18.05f)
                moveTo(18.05f, 5.95f)
                lineTo(16.65f, 7.35f)
                moveTo(7.35f, 16.65f)
                lineTo(5.95f, 18.05f)
            }
        }.build()
    }

    val Moon: ImageVector by lazy {
        ImageVector.Builder(
            name = "Moon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 15.5f)
                curveTo(17.7f, 16.2f, 16.2f, 16.5f, 14.7f, 16.2f)
                curveTo(10.9f, 15.4f, 8.5f, 11.7f, 9.3f, 7.9f)
                curveTo(9.6f, 6.5f, 10.3f, 5.2f, 11.3f, 4.2f)
                curveTo(7.2f, 4.5f, 4f, 7.9f, 4f, 12f)
                curveTo(4f, 16.4f, 7.6f, 20f, 12f, 20f)
                curveTo(15f, 20f, 17.6f, 18.3f, 19f, 15.5f)
                close()
            }
        }.build()
    }

    val Undo: ImageVector by lazy {
        ImageVector.Builder(
            name = "Undo",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8f, 7f)
                lineTo(4f, 11f)
                lineTo(8f, 15f)
                moveTo(4.5f, 11f)
                horizontalLineTo(13.5f)
                curveTo(17.09f, 11f, 20f, 13.91f, 20f, 17.5f)
            }
        }.build()
    }

    val Redo: ImageVector by lazy {
        ImageVector.Builder(
            name = "Redo",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(16f, 7f)
                lineTo(20f, 11f)
                lineTo(16f, 15f)
                moveTo(19.5f, 11f)
                horizontalLineTo(10.5f)
                curveTo(6.91f, 11f, 4f, 13.91f, 4f, 17.5f)
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6.5f, 6.5f)
                lineTo(17.5f, 17.5f)
                moveTo(17.5f, 6.5f)
                lineTo(6.5f, 17.5f)
            }
        }.build()
    }
}
