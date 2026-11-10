package com.doloh.marstekwidget.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets

object SystemBarsInsetHelper {
    fun apply(view: View, leftDp: Int, topDp: Int, rightDp: Int, bottomDp: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val density = view.resources.displayMetrics.density
        val baseLeft = (leftDp * density).toInt()
        val baseTop = (topDp * density).toInt()
        val baseRight = (rightDp * density).toInt()
        val baseBottom = (bottomDp * density).toInt()
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            target.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom,
            )
            insets
        }
        view.requestApplyInsets()
    }
}
