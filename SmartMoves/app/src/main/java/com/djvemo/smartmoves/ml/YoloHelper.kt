package com.djvemo.smartmoves.ml

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class DetectionResult(
    val rect: RectF,        // caja en px sobre la imagen ROTADA
    val clsId: Int,
    val clsName: String,
    val score: Float,
    val maskBitmap: Bitmap? // máscara coloreada al tamaño del frame
)

object YoloSegHelper {

    @Volatile private var CONF_TH = 0.35f
    @Volatile private var IOU_TH  = 0.50f
    @Volatile private var MASK_TH = 0.50f

    private lateinit var labels: List<String>

    private var gpuDelegate: GpuDelegate? = null

    fun setThresholds(conf: Float, iou: Float, mask: Float) {
        CONF_TH = conf; IOU_TH = iou; MASK_TH = mask
    }

    fun loadLabels(context: Context, filename: String): List<String> {
        labels = FileUtil.loadLabels(context, filename)
        return labels
    }

    fun createInterpreter(context: Context, modelName: String, useGpu: Boolean = true): Interpreter {
        val model = FileUtil.loadMappedFile(context, modelName)
        val options = Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            if (useGpu) {
                try {
                    val compat = CompatibilityList()
                    if (compat.isDelegateSupportedOnThisDevice) {
                        gpuDelegate = try {
                            GpuDelegate(compat.bestOptionsForThisDevice)
                        } catch (_: Throwable) {
                            GpuDelegate()
                        }
                        addDelegate(gpuDelegate)
                    }
                } catch (t: Throwable) {
                    Log.w("TFLite", "GPU delegate no disponible, uso CPU: ${t.message}")
                }
            }
        }
        return Interpreter(model, options)
    }

    fun close() {
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        gpuDelegate = null
    }

    // ImageProxy (RGBA_8888) → Bitmap rotado
    private fun proxyToBitmapRGBA(image: ImageProxy): Bitmap {
        val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        image.planes[0].buffer.apply {
            rewind()
            bmp.copyPixelsFromBuffer(this)
        }
        val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        image.close()
        return rotated
    }

    private fun preprocess(bitmap: Bitmap, inputW: Int, inputH: Int): Pair<TensorImage, Matrix> {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputH, inputW, ResizeOp.ResizeMethod.BILINEAR)) // stretch
            .add(NormalizeOp(0f, 255f)) // FP32: [0,255] -> [0,1]
            .build()
        val ti = TensorImage.fromBitmap(bitmap)
        val out = imageProcessor.process(ti)

        // mapeo inverso (stretch): escala simple
        val sx = bitmap.width  / inputW.toFloat()
        val sy = bitmap.height / inputH.toFloat()
        val scaleBack = Matrix().apply { postScale(sx, sy) }
        return out to scaleBack
    }

    /**
     * onResults(detections, totalMs, frameW, frameH)
     * - totalMs = pre + infer + post (no incluye CameraX render)
     */
    fun analyzeImageProxy(
        image: ImageProxy,
        interpreter: Interpreter,
        onResults: (List<DetectionResult>, Long, Int, Int) -> Unit
    ) {
        val tTotal0 = SystemClock.elapsedRealtimeNanos()
        val frameBitmap = proxyToBitmapRGBA(image)

        // Pre
        val inputShape = interpreter.getInputTensor(0).shape() // [1,H,W,3]
        val inH = inputShape[1]; val inW = inputShape[2]
        val (tensorImage, scaleBack) = preprocess(frameBitmap, inW, inH)
        val inputBuffer: ByteBuffer = tensorImage.buffer

        val outCount = interpreter.outputTensorCount
        require(outCount in 1..2) { "Modelo inesperado (salidas=$outCount)" }

        // Out0: [1,N,C] o [1,C,N]
        val o0 = interpreter.getOutputTensor(0).shape()
        val isNLast = (o0.size == 3 && o0[1] > o0[2])
        val N = if (isNLast) o0[1] else o0[2]
        val C = if (isNLast) o0[2] else o0[1]

        val out0 = if (isNLast)
            Array(1) { Array(N) { FloatArray(C) } }
        else
            Array(1) { Array(C) { FloatArray(N) } }

        // Infer
        val tInfer0 = SystemClock.elapsedRealtimeNanos()
        if (outCount == 2) {
            val o1 = interpreter.getOutputTensor(1).shape() // [1,M,Mh,Mw]
            val out1 = Array(o1[0]) {
                Array(o1[1]) { Array(o1[2]) { FloatArray(o1[3]) } }
            }
            val outputs = hashMapOf<Int, Any>(0 to out0, 1 to out1)
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

            // Post
            val results = postprocessSeg(out0, isNLast, labels, out1, scaleBack, frameBitmap.width, frameBitmap.height)
            val tTotal = (SystemClock.elapsedRealtimeNanos() - tTotal0) / 1_000_000
            val tInfer = (SystemClock.elapsedRealtimeNanos() - tInfer0) / 1_000_000
            Log.d("YOLO", "lat(ms) total=$tTotal | infer=$tInfer")
            onResults(results, tTotal, frameBitmap.width, frameBitmap.height)
        } else {
            interpreter.run(inputBuffer, out0)
            val results = postprocessDet(out0, isNLast, labels, scaleBack, frameBitmap.width, frameBitmap.height)
            val tTotal = (SystemClock.elapsedRealtimeNanos() - tTotal0) / 1_000_000
            val tInfer = (SystemClock.elapsedRealtimeNanos() - tInfer0) / 1_000_000
            Log.d("YOLO", "lat(ms) total=$tTotal | infer=$tInfer")
            onResults(results, tTotal, frameBitmap.width, frameBitmap.height)
        }
    }

    // ---------- POST: SEG ----------
    private fun postprocessSeg(
        out0: Array<Array<FloatArray>>,
        isNLast: Boolean,
        labels: List<String>,
        proto: Array<Array<Array<FloatArray>>>,
        scaleBack: Matrix,
        outWidth: Int, outHeight: Int
    ): List<DetectionResult> {

        val N = if (isNLast) out0[0].size else out0[0][0].size
        val C = if (isNLast) out0[0][0].size else out0[0].size

        val M  = proto[0].size   // canales de máscara (p.ej., 32)
        val Mh = proto[0][0].size
        val Mw = proto[0][0][0].size

        val numClasses = (C - 4 - M).coerceAtLeast(1)

        fun getVal(row: Int, ch: Int): Float =
            if (isNLast) out0[0][row][ch] else out0[0][ch][row]

        val candidates = ArrayList<RawDet>(N)
        for (i in 0 until N) {
            val cx = getVal(i, 0)
            val cy = getVal(i, 1)
            val w  = getVal(i, 2)
            val h  = getVal(i, 3)

            var bestCls = -1
            var bestScore = -1f
            for (k in 0 until numClasses) {
                val s = getVal(i, 4 + k)
                if (s > bestScore) { bestScore = s; bestCls = k }
            }
            if (bestScore < CONF_TH) continue

            val coeffs = FloatArray(M) { m -> getVal(i, 4 + numClasses + m) }

            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f
            val pts = floatArrayOf(x1, y1, x2, y2)
            scaleBack.mapPoints(pts)
            val rect = RectF(pts[0], pts[1], pts[2], pts[3])

            if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= outWidth || rect.top >= outHeight) continue
            candidates += RawDet(rect, bestCls, bestScore, coeffs)
        }

        if (candidates.isEmpty()) return emptyList()
        val keep = nms(candidates, IOU_TH)

        // Flatten protos: [M][Mh*Mw]
        val protoFlat = Array(M) { FloatArray(Mh * Mw) }
        for (m in 0 until M) {
            var idx = 0
            for (y in 0 until Mh) for (x in 0 until Mw) {
                protoFlat[m][idx++] = proto[0][m][y][x]
            }
        }

        val results = ArrayList<DetectionResult>(keep.size)
        for (d in keep) {
            val maskSmall = FloatArray(Mh * Mw)
            for (m in 0 until M) {
                val cm = d.coeffs[m]
                val pm = protoFlat[m]
                for (i in maskSmall.indices) maskSmall[i] += cm * pm[i]
            }
            for (i in maskSmall.indices) maskSmall[i] = (1f / (1f + exp(-maskSmall[i])))

            val smallBmp = Bitmap.createBitmap(Mw, Mh, Bitmap.Config.ALPHA_8)
            val pixels = ByteArray(Mh * Mw) { i -> if (maskSmall[i] >= MASK_TH) 0xFF.toByte() else 0x00.toByte() }
            smallBmp.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))

            val fullMask = Bitmap.createScaledBitmap(smallBmp, outWidth, outHeight, true)
            val clipped  = applyBoxClip(fullMask, d.rect)

            val color = ColorUtils.colorForClass(d.clsId)
            val colorized = ColorUtils.colorizeAlphaMask(clipped, color, alpha = 120)

            val cls = d.clsId
            val name = labels.getOrNull(cls) ?: "cls$cls"
            results += DetectionResult(d.rect, cls, name, d.score, colorized)
        }
        return results
    }

    // ---------- POST: DET ----------
    private fun postprocessDet(
        out0: Array<Array<FloatArray>>,
        isNLast: Boolean,
        labels: List<String>,
        scaleBack: Matrix,
        outWidth: Int, outHeight: Int
    ): List<DetectionResult> {

        val N = if (isNLast) out0[0].size else out0[0][0].size
        val C = if (isNLast) out0[0][0].size else out0[0].size
        val numClasses = (C - 4).coerceAtLeast(1)

        fun getVal(row: Int, ch: Int): Float =
            if (isNLast) out0[0][row][ch] else out0[0][ch][row]

        val candidates = ArrayList<RawDet>(N)
        for (i in 0 until N) {
            val cx = getVal(i, 0)
            val cy = getVal(i, 1)
            val w  = getVal(i, 2)
            val h  = getVal(i, 3)

            var bestCls = -1
            var bestScore = -1f
            for (k in 0 until numClasses) {
                val s = getVal(i, 4 + k)
                if (s > bestScore) { bestScore = s; bestCls = k }
            }
            if (bestScore < CONF_TH) continue

            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f
            val pts = floatArrayOf(x1, y1, x2, y2)
            scaleBack.mapPoints(pts)
            val rect = RectF(pts[0], pts[1], pts[2], pts[3])
            if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= outWidth || rect.top >= outHeight) continue

            candidates += RawDet(rect, bestCls, bestScore, FloatArray(0))
        }

        if (candidates.isEmpty()) return emptyList()
        val keep = nms(candidates, IOU_TH)
        return keep.map {
            val cls = it.clsId
            val name = labels.getOrNull(cls) ?: "cls$cls"
            DetectionResult(it.rect, cls, name, it.score, null)
        }
    }

    // ---------- Utils ----------
    private data class RawDet(
        val rect: RectF,
        val clsId: Int,
        val score: Float,
        val coeffs: FloatArray // en det simple: tamaño 0
    )

    private fun nms(boxes: List<RawDet>, iouTh: Float): List<RawDet> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<RawDet>()
        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            keep += first
            val it = sorted.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (iou(first.rect, b.rect) >= iouTh) it.remove()
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val ua = a.width() * a.height() + b.width() * b.height() - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    private fun applyBoxClip(mask: Bitmap, rect: RectF): Bitmap {
        // recorta la máscara al rectángulo (limpia bleed fuera de la caja)
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.save()
        c.clipRect(rect)
        c.drawBitmap(mask, 0f, 0f, paint)
        c.restore()
        return out
    }
}
