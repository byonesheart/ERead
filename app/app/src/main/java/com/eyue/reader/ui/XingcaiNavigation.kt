package com.eyue.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 未选中导航图标的中性色；彩色只留给选中项。 */
private val NavIdle = Color(0xFF74839C)
private val NavCoral = Color(0xFFFF8B7B)
private val NavAmber = Color(0xFFFFC24B)
private val NavMint = Color(0xFF5FD0A0)
private val NavViolet = Color(0xFFA78BFA)
private val NavSoft = Color(0xFF7FA9F0)

/**
 * 底部主导航：图标在上、标签在下，选中项一块浅蓝圆角底。
 * 三个目的地仍是阅读 / 书架 / 设置，没有新增入口。
 */
@Composable internal fun XingcaiNavigationBar(selected: Int, hasUpdate: Boolean, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding()) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().height(68.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            listOf("阅读", "书架", "设置").withIndex()
                .forEach { (index, label) ->
                    val active = selected == index
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .selectable(active, role = Role.Tab, onClick = { if (!active) onSelect(index) })
                        .testTag("app_nav_$index"), contentAlignment = Alignment.Center) {
                        Column(Modifier.width(76.dp).height(56.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (active) colors.primaryContainer else Color.Transparent),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            BadgedBox(badge = {
                                if (index == 2 && hasUpdate) Badge(Modifier.testTag("settings_update_badge"), containerColor = Color(0xFFD64045))
                            }) { AppNavIcon(index, active, colors.primary, Modifier.size(24.dp)) }
                            Spacer(Modifier.height(4.dp))
                            Text(label, fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
        }
    }
}

@Composable
private fun AppNavIcon(glyph: Int, active: Boolean, accent: Color, modifier: Modifier) {
    val eink = LocalAppEinkMode.current
    val monochrome = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            when (glyph) {
                1 -> drawShelf(active || eink, if (eink) monochrome else null)
                2 -> drawSliders(active || eink, if (eink) monochrome else accent, eink)
                else -> drawRead(active || eink, if (eink) monochrome else accent, eink)
            }
        }
    }
}

/** 打开的双页书：左页主色、右页浅主色、珊瑚书签带。 */
private fun DrawScope.drawRead(active: Boolean, accent: Color, eink: Boolean) {
    val left = Path().apply {
        moveTo(2.4f, 7.8f); quadraticTo(7f, 5f, 11.6f, 7.4f)
        lineTo(11.6f, 20.4f); quadraticTo(7f, 18.4f, 2.4f, 19.8f); close()
    }
    val right = Path().apply {
        moveTo(12.4f, 7.4f); quadraticTo(17f, 5f, 21.6f, 7.8f)
        lineTo(21.6f, 19.8f); quadraticTo(17f, 18.4f, 12.4f, 20.4f); close()
    }
    if (active) {
        drawPath(left, accent)
        drawPath(right, if (eink) accent else NavSoft)
        val ribbon = Path().apply {
            moveTo(8.2f, 3.4f); lineTo(11.2f, 2.6f); lineTo(11.2f, 8.6f)
            lineTo(9.7f, 7.4f); lineTo(8.2f, 8.8f); close()
        }
        drawPath(ribbon, if (eink) accent else NavCoral)
    } else {
        drawPath(left, NavIdle)
        drawPath(right, NavIdle)
    }
}

/** 书架：三本不同高矮的书 + 一块隔板。 */
private fun DrawScope.drawShelf(active: Boolean, ink: Color?) {
    val corner = CornerRadius(1.2f, 1.2f)
    val tall = if (active) ink ?: NavAmber else NavIdle
    val mid = if (active) ink ?: NavMint else NavIdle
    val lean = if (active) ink ?: NavViolet else NavIdle
    val board = if (active) ink ?: NavSoft else NavIdle
    drawRoundRect(tall, Offset(3f, 5.2f), Size(4.8f, 12f), corner)
    drawRoundRect(mid, Offset(8.8f, 8f), Size(4.4f, 9.2f), corner)
    drawRoundRect(lean, Offset(14.4f, 4.4f), Size(4.6f, 12.4f), corner)
    drawRoundRect(board, Offset(2.2f, 17.4f), Size(19.6f, 2.6f), corner)
    if (active) drawLine(Color.White, Offset(3.8f, 9.6f), Offset(6.8f, 9.6f), 1.1f, StrokeCap.Round)
}

/** 设置：三行滑杆，每行一个圆钮。 */
private fun DrawScope.drawSliders(active: Boolean, accent: Color, eink: Boolean) {
    val rows = listOf(5.6f, 12f, 18.4f)
    val knobColors = listOf(NavCoral, NavMint, NavAmber)
    val knobX = listOf(8.4f, 15.6f, 9.8f)
    rows.forEach { y ->
        drawLine(if (active) accent else NavIdle, Offset(3.6f, y), Offset(20.4f, y), 2.4f, StrokeCap.Round)
    }
    rows.forEachIndexed { i, y ->
        val center = Offset(knobX[i], y)
        if (active) {
            drawCircle(Color.White, radius = 3.6f, center = center)
            drawCircle(if (eink) accent else knobColors[i], radius = 3.2f, center = center)
        } else {
            drawCircle(NavIdle, radius = 3.2f, center = center)
        }
    }
}
