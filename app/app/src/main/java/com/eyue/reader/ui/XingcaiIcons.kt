package com.eyue.reader.ui

// Glyph paths adapted from the user-provided ebook 2.0.5 UI.
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

private data class XingcaiIconPath(val path: String, val filled: Boolean)

private val paths = mapOf(
    "BOOKMARK_FILLED" to XingcaiIconPath("M6 3 H18 V22 L12 17 L6 22 Z", true),
    "SUN" to XingcaiIconPath("M12 7 A5 5 0 1 1 12 17 A5 5 0 0 1 12 7 M12 1 V4 M12 20 V23 M1 12 H4 M20 12 H23 M4 4 L6 6 M18 18 L20 20", false),
    "GRID" to XingcaiIconPath("M3 3 H9 V9 H3 Z M15 3 H21 V9 H15 Z M3 15 H9 V21 H3 Z M15 15 H21 V21 H15 Z", false),
    "SEARCH" to XingcaiIconPath("M10.5 18 A7.5 7.5 0 1 1 10.5 3 A7.5 7.5 0 0 1 10.5 18 M16 16 L22 22", false),
    "LIST" to XingcaiIconPath("M8 6 H21 M8 12 H21 M8 18 H21 M3 6 H4 M3 12 H4 M3 18 H4", false),
    "CHEVRON" to XingcaiIconPath("M9 5 L16 12 L9 19", false),
    "ARROW" to XingcaiIconPath("M4 12 H20 M13 5 L20 12 L13 19", false),
    "SPARK" to XingcaiIconPath("M12 2 L14 10 L22 12 L14 14 L12 22 L10 14 L2 12 L10 10 Z", false),
    "CLOUD_PENDING" to XingcaiIconPath("M5 17 A4 4 0 0 1 6 9 A6 6 0 0 1 18 8 A4 4 0 0 1 19 16 M14 16 H21 M18 13 L21 16 L18 19 M4 21 H11", false),
    "RENAME" to XingcaiIconPath("M3 5 H12 M7 5 V20 M3 20 H12 M15 18 L22 11 L19 8 L12 15 L11 19 Z", false),
    "SETTINGS" to XingcaiIconPath("M3 5 H21 V6.5 H3 Z M3 17.5 H21 V19 H3 Z M6.5 2.5 H11 V9 H6.5 Z M14 14.5 H18.5 V21.5 H14 Z", true),
    "DOWNLOAD" to XingcaiIconPath("M12 3 V16 M6 10 L12 16 L18 10 M4 18 V21 H20 V18", false),
    "NOTE" to XingcaiIconPath("M5 3 H19 V21 H5 Z M9 8 H15 M9 12 H15 M9 16 H13", false),
    "VOLUME" to XingcaiIconPath("M3 9 H7 L13 4 V20 L7 15 H3 Z M17 8 Q22 12 17 16", false),
    "READ" to XingcaiIconPath("M3 5 Q6.5 4.2 10.5 6.8 V21 Q6.5 18.8 3 19 Z M13 7 Q17 5.2 21 6 V19 Q17 18.6 13 21 Z M14 3.3 L19.8 2.2 L21 4.6 L15.2 5.7 Z", true),
    "NARRATOR" to XingcaiIconPath("M7 4 H14 V20 H7 Z M17 8 Q22 12 17 16 M4 7 H7 M4 17 H7", false),
    "STOP" to XingcaiIconPath("M5 5 H19 V19 H5 Z", false),
    "EXTERNAL" to XingcaiIconPath("M14 3 H21 V10 M21 3 L10 14 M10 5 H3 V21 H19 V14", false),
    "ADD" to XingcaiIconPath("M12 4 V20 M4 12 H20", false),
    "CHECK" to XingcaiIconPath("M5 12 L10 17 L20 6", false),
    "WIFI" to XingcaiIconPath("M3 8 Q12 0 21 8 M6 12 Q12 6 18 12 M10 16 Q12 14 14 16 M12 20 H12.1", false),
    "INFO" to XingcaiIconPath("M12 3 A9 9 0 1 1 12 21 A9 9 0 0 1 12 3 M12 10 V17 M12 6 V7", false),
    "CLOSE" to XingcaiIconPath("M6 6 L18 18 M18 6 L6 18", false),
    "PREV" to XingcaiIconPath("M6 5 V19 M20 5 L8 12 L20 19 Z", false),
    "REFRESH" to XingcaiIconPath("M4 10 A8 8 0 0 1 20 7 M20 2 V8 H14 M20 14 A8 8 0 0 1 4 17 M4 22 V16 H10", false),
    "IMAGE" to XingcaiIconPath("M3 3 H21 V21 H3 Z M3 17 L9 10 L14 16 L18 12 L21 16 M16 6 A2 2 0 1 1 16 10 A2 2 0 0 1 16 6", false),
    "SAVE" to XingcaiIconPath("M4 3 H17 L21 7 V21 H4 Z M8 3 V9 H16 V3 M8 15 H17 V21 H8 Z", false),
    "BACK" to XingcaiIconPath("M20 12 H4 M11 5 L4 12 L11 19", false),
    "LIBRARY" to XingcaiIconPath("M3 4 H7 V19 H3 Z M9.5 7 H13.5 V19 H9.5 Z M16 3.6 L19.6 2.6 L22 17.3 L18.4 18.3 Z M2.5 21 H22 V22.5 H2.5 Z", true),
    "PLAY" to XingcaiIconPath("M8 4 L21 12 L8 20 Z", false),
    "MORE" to XingcaiIconPath("M4 12 H5 M11 12 H12 M19 12 H20", false),
    "TRASH" to XingcaiIconPath("M4 6 H20 M9 3 H15 M6 6 L7 21 H17 L18 6 M10 10 V17 M14 10 V17", false),
    "EDIT" to XingcaiIconPath("M4 20 L5 14 L17 2 L22 7 L10 19 Z M14 5 L19 10", false),
    "NEXT" to XingcaiIconPath("M18 5 V19 M4 5 L16 12 L4 19 Z", false),
    "CLOCK" to XingcaiIconPath("M12 3 A9 9 0 1 1 12 21 A9 9 0 0 1 12 3 M12 7 V12 L16 15", false),
    "EYE" to XingcaiIconPath("M2 12 Q12 -1 22 12 Q12 25 2 12 Z M12 8 A4 4 0 1 1 12 16 A4 4 0 0 1 12 8", false),
    "HEADPHONES" to XingcaiIconPath("M4 15 V11 A8 8 0 0 1 20 11 V15 M4 13 H8 V21 H4 Z M16 13 H20 V21 H16 Z", false),
    "SORT" to XingcaiIconPath("M5 6 H21 M5 12 H16 M5 18 H11", false),
    "UNDO" to XingcaiIconPath("M8 5 L3 10 L8 15 M3 10 H14 A6 6 0 0 1 14 22", false),
    "CLOUD" to XingcaiIconPath("M7 18 H18 A4 4 0 0 0 19 10 A7 7 0 0 0 6 9 A4.5 4.5 0 0 0 7 18", false),
    "CHARACTER" to XingcaiIconPath("M8 3 A4 4 0 1 1 8 11 A4 4 0 0 1 8 3 M2 21 V18 Q2 13 8 13 H10 M15 4 H22 V14 H15 L12 17 V7 Z", false),
    "UPLOAD" to XingcaiIconPath("M12 17 V4 M6 10 L12 4 L18 10 M4 18 V21 H20 V18", false),
    "PAUSE" to XingcaiIconPath("M8 5 V19 M16 5 V19", false),
    "REPLACE" to XingcaiIconPath("M3 7 H18 M14 3 L18 7 L14 11 M21 17 H6 M10 13 L6 17 L10 21", false),
    "BOOKMARK" to XingcaiIconPath("M6 3 H18 V22 L12 17 L6 22 Z", false),
    "BRIGHTNESS" to XingcaiIconPath("M12 7 A5 5 0 1 1 12 17 A5 5 0 0 1 12 7 M12 2 V4 M12 20 V22 M2 12 H4 M20 12 H22 M4 4 L6 6 M18 18 L20 20", false),
    "HISTORY" to XingcaiIconPath("M4 9 A9 9 0 1 1 3 16 M3 3 V9 H9 M12 7 V13 L16 15", false),
    "FOLDER" to XingcaiIconPath("M3 6 H10 L12 9 H21 V21 H3 Z", false),
    "MOON" to XingcaiIconPath("M20 15 A9 9 0 1 1 9 3 A7 7 0 0 0 20 15 Z", false),
    "HEART" to XingcaiIconPath("M12 21 L3 12 A5.5 5.5 0 0 1 12 4 A5.5 5.5 0 0 1 21 12 Z", false),
    "COMIC" to XingcaiIconPath("M3 4 H21 V20 H3 Z M3 11 H21 M11 4 V11 M15 11 V20", false),
)

private val iconCache = ConcurrentHashMap<String, ImageVector>()

/** Parse each glyph once. Theme/navigation recompositions only look up a stable vector. */
private fun xingcaiVector(icon: String, autoMirror: Boolean): ImageVector = iconCache.getOrPut("$icon/$autoMirror") {
    val base = paths.getValue(icon)
    ImageVector.Builder("Xingcai.${icon}", 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply {
        addPath(PathParser().parsePathString(base.path).toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = if (base.filled) SolidColor(Color.Black) else null,
            stroke = if (base.filled) null else SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round)
    }.build()
}

private val xingcaiGlyphNames = mapOf(
    "MenuBook" to "READ",
    "AutoStories" to "COMIC",
    "LibraryBooks" to "LIBRARY",
    "Tune" to "SETTINGS",
    "Settings" to "SETTINGS",
    "Search" to "SEARCH",
    "FormatListBulleted" to "LIST",
    "GridView" to "GRID",
    "ViewList" to "LIST",
    "ChevronRight" to "CHEVRON",
    "ArrowForward" to "ARROW",
    "ArrowBack" to "BACK",
    "Palette" to "SPARK",
    "CloudDownload" to "DOWNLOAD",
    "FileDownload" to "DOWNLOAD",
    "CloudUpload" to "UPLOAD",
    "FileUpload" to "UPLOAD",
    "CloudQueue" to "CLOUD",
    "Cloud" to "CLOUD",
    "CloudSync" to "REFRESH",
    "Restore" to "UNDO",
    "EditNote" to "NOTE",
    "Notes" to "NOTE",
    "Headphones" to "HEADPHONES",
    "RecordVoiceOver" to "NARRATOR",
    "People" to "CHARACTER",
    "Person" to "CHARACTER",
    "VolumeUp" to "VOLUME",
    "Stop" to "STOP",
    "StopCircle" to "STOP",
    "OpenInNew" to "EXTERNAL",
    "Add" to "ADD",
    "Check" to "CHECK",
    "CheckCircle" to "CHECK",
    "Refresh" to "REFRESH",
    "Save" to "SAVE",
    "Close" to "CLOSE",
    "Clear" to "CLOSE",
    "Edit" to "EDIT",
    "DriveFileRenameOutline" to "RENAME",
    "Delete" to "TRASH",
    "DeleteOutline" to "TRASH",
    "DeleteForever" to "TRASH",
    "MoreVert" to "MORE",
    "MoreHoriz" to "MORE",
    "Image" to "IMAGE",
    "SkipPrevious" to "PREV",
    "SkipNext" to "NEXT",
    "FastRewind" to "PREV",
    "FastForward" to "NEXT",
    "Schedule" to "CLOCK",
    "AccessTime" to "CLOCK",
    "Visibility" to "EYE",
    "Sort" to "SORT",
    "Undo" to "UNDO",
    "Folder" to "FOLDER",
    "History" to "HISTORY",
    "FavoriteBorder" to "HEART",
    "Favorite" to "HEART",
    "DarkMode" to "MOON",
    "LightMode" to "SUN",
    "Brightness6" to "BRIGHTNESS",
    "BrightnessAuto" to "BRIGHTNESS",
    "Bookmarks" to "BOOKMARK_FILLED",
    "BookmarkBorder" to "BOOKMARK",
    "Bookmark" to "BOOKMARK_FILLED",
    "FindReplace" to "REPLACE",
    "PlayArrow" to "PLAY",
    "Pause" to "PAUSE",
    "CloudDone" to "CHECK",
    "CloudOff" to "CLOUD_PENDING",
    "Wifi" to "WIFI",
    "Info" to "INFO",
    "ErrorOutline" to "INFO",
    "HelpOutline" to "INFO"
)

internal fun xingcaiIcon(image: ImageVector): ImageVector =
    xingcaiGlyphNames[image.name.substringAfterLast('.')]?.let { xingcaiVector(it, image.autoMirror) } ?: image
