package top.xjunz.tasker.ktx

import androidx.annotation.FloatRange
import androidx.core.graphics.ColorUtils

/**
 * @author xjunz 2022/12/11
 */
fun Int.modifyAlpha(@FloatRange(from = 0.0, to = 1.0) alpha: Double): Int {
    return ColorUtils.setAlphaComponent(this, (alpha * 0xFF).toInt())
}