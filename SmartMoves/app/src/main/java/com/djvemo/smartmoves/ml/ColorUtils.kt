package com.djvemo.smartmoves.ml

import android.graphics.*

object ColorUtils {
    private val PALETTE = intArrayOf(
        Color.parseColor("#FF3B30"),
        Color.parseColor("#34C759"),
        Color.parseColor("#007AFF"),
        Color.parseColor("#FF9500"),
        Color.parseColor("#AF52DE"),
        Color.parseColor("#5AC8FA"),
        Color.parseColor("#FF2D55"),
        Color.parseColor("#8E8E93")
    )

    fun colorForClass(clsId: Int): Int = PALETTE[kotlin.math.abs(clsId) % PALETTE.size]

    fun colorizeAlphaMask(alphaMask: Bitmap, color: Int, alpha: Int = 120): Bitmap {
        val out = Bitmap.createBitmap(alphaMask.width, alphaMask.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Dibuja el alpha
        val alphaARGB = alphaMask.copy(Bitmap.Config.ARGB_8888, false)
        canvas.drawBitmap(alphaARGB, 0f, 0f, null)

        // Pinta con SRC_ATOP para aplicar color al alpha
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
        return out
    }
}
