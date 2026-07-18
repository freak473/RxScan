package com.rxscan.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.rxscan.app.R
import com.rxscan.app.data.MockData
import java.io.File

/**
 * Device-to-doctor share (PRD §6): native share sheet with the prescription photo
 * attached + a pre-written question. Goes app → doctor; our servers never see it.
 *
 * UI pass: the "photo" is the mock prescription rendered on-device (paper bg,
 * Kalam ink). When the real camera lands, [prescriptionImage] returns the captured
 * file from local storage instead — the share flow itself doesn't change.
 */
object DoctorShare {

    private fun prescriptionImage(context: Context): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "prescription.png")
        if (file.exists()) return file

        val w = 900
        val bmp = Bitmap.createBitmap(w, 620, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFFBF8F1.toInt()) // paper
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A8266.toInt()
            textSize = 28f
            typeface = ResourcesCompat.getFont(context, R.font.hanken_grotesk)
        }
        canvas.drawText("DR. A. SHARMA · CITY CLINIC", 60f, 90f, header)
        canvas.drawText("11/07/2026", w - 220f, 90f, header)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E3D8F.toInt() // ballpoint blue
            textSize = 42f
            typeface = ResourcesCompat.getFont(context, R.font.kalam_regular)
        }
        MockData.prescription.forEachIndexed { i, med ->
            canvas.drawText(med.ink, 60f, 200f + i * 95f, ink)
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** Open the native share sheet with the prescription photo + [question]. */
    fun askDoctor(context: Context, question: String) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", prescriptionImage(context),
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, question)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Message your doctor"))
    }
}
