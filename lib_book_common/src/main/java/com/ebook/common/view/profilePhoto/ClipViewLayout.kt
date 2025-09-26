package com.ebook.common.view.profilePhoto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.exifinterface.media.ExifInterface
import com.ebook.common.R
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 头像上传原图裁剪容器
 */
class ClipViewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) :
    RelativeLayout(context, attrs, defStyleAttr) {
    //图片缩放、移动操作矩阵
    private val matrix = Matrix()

    //图片原来已经缩放、移动过的操作矩阵
    private val savedMatrix = Matrix()

    //用于存放矩阵的9个值
    private val matrixValues = FloatArray(9)

    //记录起始坐标
    private val start = PointF()

    //记录缩放时两指中间点坐标
    private val mid = PointF()

    //最大缩放比例
    private val maxScale = 4f

    //裁剪原图
    private lateinit var imageView: ImageView

    //裁剪框
    private var clipView: ClipView = ClipView(context)

    //裁剪框水平方向间距，xml布局文件中指定
    private var mHorizontalPadding = 0f

    //裁剪框垂直方向间距，计算得出
    private var mVerticalPadding = 0f

    //初始化动作标志
    private var mode = NONE
    private var oldDist = 1f

    //最小缩放比例
    private var minScale = 0f

    init {
        init(context, attrs)
    }

    //初始化控件自定义的属性
    fun init(context: Context, attrs: AttributeSet?) {
        val array = context.obtainStyledAttributes(attrs, R.styleable.ClipViewLayout)

        //获取剪切框距离左右的边距, 默认为50dp
        mHorizontalPadding = array.getDimensionPixelSize(
            R.styleable.ClipViewLayout_mHorizontalPadding,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, resources.displayMetrics)
                .toInt()
        ).toFloat()
        //获取裁剪框边框宽度，默认1dp
        val clipBorderWidth = array.getDimensionPixelSize(
            R.styleable.ClipViewLayout_clipBorderWidth,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
                .toInt()
        )
        //裁剪框类型(圆或者矩形)
        val clipType = array.getInt(R.styleable.ClipViewLayout_clipType, 1)

        //回收
        array.recycle()
        //设置裁剪框类型
        setClipType(clipType)
        //设置剪切框边框
        clipView.setClipBorderWidth(clipBorderWidth)
        //设置剪切框水平间距
        clipView.setHorizontalPadding(mHorizontalPadding)
        imageView = ImageView(context)
        //相对布局布局参数
        val lp: ViewGroup.LayoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        this.addView(imageView, lp)
        this.addView(clipView, lp)
    }

    /**
     * 初始化图片
     */
    fun setImageSrc(uri: Uri) {
        //需要等到imageView绘制完毕再初始化原图
        val observer = imageView.viewTreeObserver
        observer.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                initSrcPic(uri)
                imageView.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    fun setClipType(clipType: Int) {
        clipView.setClipType(if (clipType == 1) ClipView.ClipType.CIRCLE else ClipView.ClipType.RECTANGLE)
    }

    /**
     * 初始化图片
     * step 1: decode 出 720*1280 左右的照片  因为原图可能比较大 直接加载出来会OOM
     * step 2: 将图片缩放 移动到imageView 中间
     */
    fun initSrcPic(uri: Uri) {
        Log.e("evan", "**********clip_view uri*******  $uri")

        //这里decode出720*1280 左右的照片,防止OOM
        var bitmap = decodeSampledBitmap(context, uri, 720, 1280) ?: return
        //竖屏拍照的照片，直接使用的话，会旋转90度，下面代码把角度旋转过来
        val rotation = getExifOrientation(context, uri) //查询旋转角度
        val m = Matrix()
        m.setRotate(rotation.toFloat())
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)

        //图片的缩放比
        var scale: Float
        if (bitmap.width >= bitmap.height) { //宽图
            scale = imageView.width.toFloat() / bitmap.width
            //如果高缩放后小于裁剪区域 则将裁剪区域与高的缩放比作为最终的缩放比
            val rect = clipView.clipRect
            //高的最小缩放比
            minScale = rect.height() / bitmap.height.toFloat()
        } else { //高图
            //高的缩放比
            scale = imageView.height.toFloat() / bitmap.height
            //如果宽缩放后小于裁剪区域 则将裁剪区域与宽的缩放比作为最终的缩放比
            val rect = clipView.clipRect
            //宽的最小缩放比
            minScale = rect.width() / bitmap.width.toFloat()
        }
        if (scale < minScale) {
            scale = minScale
        }
        // 缩放
        matrix.postScale(scale, scale)
        // 平移,将缩放后的图片平移到imageview的中心
        //imageView的中心x
        val midX = imageView.width / 2
        //imageView的中心y
        val midY = imageView.height / 2
        //bitmap的中心x
        val imageMidX = (bitmap.width * scale / 2).toInt()
        //bitmap的中心y
        val imageMidY = (bitmap.height * scale / 2).toInt()
        matrix.postTranslate((midX - imageMidX).toFloat(), (midY - imageMidY).toFloat())
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.imageMatrix = matrix
        imageView.setImageBitmap(bitmap)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                //设置开始点位置
                start[event.x] = event.y
                mode = DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                //开始放下时候两手指间的距离
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(mid, event)
                    mode = ZOOM
                }
            }

            MotionEvent.ACTION_UP -> {}
            MotionEvent.ACTION_POINTER_UP -> mode = NONE
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) { //拖动
                    matrix.set(savedMatrix)
                    val dx = event.x - start.x
                    val dy = event.y - start.y
                    mVerticalPadding = clipView.clipRect.top.toFloat()
                    matrix.postTranslate(dx, dy)
                    //检查边界
                    checkBorder()
                } else if (mode == ZOOM) { //缩放
                    //缩放后两手指间的距离
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        //手势缩放比例
                        var scale = newDist / oldDist
                        if (scale < 1) { //缩小
                            if (this.scale > minScale) {
                                matrix.set(savedMatrix)
                                mVerticalPadding = clipView.clipRect.top.toFloat()
                                matrix.postScale(scale, scale, mid.x, mid.y)
                                //缩放到最小范围下面去了，则返回到最小范围大小
                                while (this.scale < minScale) {
                                    //返回到最小范围的放大比例
                                    scale = 1 + 0.01f
                                    matrix.postScale(scale, scale, mid.x, mid.y)
                                }
                            }
                            //边界检查
                            checkBorder()
                        } else { //放大
                            if (this.scale <= maxScale) {
                                matrix.set(savedMatrix)
                                mVerticalPadding = clipView.clipRect.top.toFloat()
                                matrix.postScale(scale, scale, mid.x, mid.y)
                            }
                        }
                    }
                }
                imageView.imageMatrix = matrix
            }
        }
        return true
    }

    /**
     * 根据当前图片的Matrix获得图片的范围
     */
    private fun getMatrixRectF(matrix: Matrix): RectF {
        val rect = RectF()
        val d = imageView.drawable
        if (null != d) {
            rect[0f, 0f, d.intrinsicWidth.toFloat()] = d.intrinsicHeight.toFloat()
            matrix.mapRect(rect)
        }
        return rect
    }

    /**
     * 边界检测
     */
    private fun checkBorder() {
        val rect = getMatrixRectF(matrix)
        var deltaX = 0f
        var deltaY = 0f
        val width = imageView.width
        val height = imageView.height
        // 如果宽或高大于屏幕，则控制范围 ; 这里的0.001是因为精度丢失会产生问题，但是误差一般很小，所以我们直接加了一个0.01
        if (rect.width() >= width - 2 * mHorizontalPadding) {
            if (rect.left > mHorizontalPadding) {
                deltaX = -rect.left + mHorizontalPadding
            }
            if (rect.right < width - mHorizontalPadding) {
                deltaX = width - mHorizontalPadding - rect.right
            }
        }
        if (rect.height() >= height - 2 * mVerticalPadding) {
            if (rect.top > mVerticalPadding) {
                deltaY = -rect.top + mVerticalPadding
            }
            if (rect.bottom < height - mVerticalPadding) {
                deltaY = height - mVerticalPadding - rect.bottom
            }
        }
        matrix.postTranslate(deltaX, deltaY)
    }

    val scale: Float
        /**
         * 获得当前的缩放比例
         */
        get() {
            matrix.getValues(matrixValues)
            return matrixValues[Matrix.MSCALE_X]
        }

    /**
     * 多点触控时，计算最先放下的两指距离
     */
    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt((x * x + y * y).toDouble()).toFloat()
    }

    /**
     * 多点触控时，计算最先放下的两指中心坐标
     */
    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point[x / 2] = y / 2
    }

    /**
     * 获取剪切图
     */
    fun clip(): Bitmap {
        val rect = clipView.clipRect
        // 创建与目标区域相同大小的 Bitmap
        val bitmap = Bitmap.createBitmap(imageView.width, imageView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        imageView.draw(canvas)
        val cropBitmap =
            Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        return zoomBitmap(cropBitmap, 200, 200).apply {
            bitmap.recycle()
            cropBitmap.recycle()
        }
    }


    companion object {
        private val TAG: String = ClipViewLayout::class.java.simpleName

        //动作标志：无
        private const val NONE = 0

        //动作标志：拖动
        private const val DRAG = 1

        //动作标志：缩放
        private const val ZOOM = 2

        /**
         * 查询图片旋转角度
         */
        fun getExifOrientation(context: Context, uri: Uri): Int {
            var degree = 0
            try {
                // 通过ContentResolver获取Uri对应的InputStream
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    ExifInterface(inputStream).let { exif ->
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_UNDEFINED
                        )
                        degree = when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    }

                }
            } catch (ex: IOException) {
                Log.e(TAG, "getExifOrientation: ", ex)
            }
            return degree
        }


        /**
         * 图片等比例压缩
         *
         * @param uri  文件路径
         * @param reqWidth  期望的宽
         * @param reqHeight 期望的高
         * @return bitmap
         */
        fun decodeSampledBitmap(
            context: Context,
            uri: Uri,
            reqWidth: Int,
            reqHeight: Int
        ): Bitmap? {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                // 1. 设置inJustDecodeBounds=true，获取图像尺寸，并进行第一次解码
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, options)

                // 2. 计算合适的缩放比例，并重新设置解码参数
                options.apply {
                    inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
                    inJustDecodeBounds = false // 现在解码为真正的Bitmap
                    inPreferredConfig = Bitmap.Config.RGB_565 // 使用较低的内存配置
                }

                // 3. 使用缩放后的配置重新解码图像并返回
                return BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, options)
            }
            return null // 如果无法打开文件描述符，返回null
        }

        /**
         * 计算InSampleSize
         * 宽的压缩比和高的压缩比的较小值  取接近的2的次幂的值
         * 比如宽的压缩比是3 高的压缩比是5 取较小值3  而InSampleSize必须是2的次幂，取接近的2的次幂4
         */
        private fun calculateInSampleSize(
            options: BitmapFactory.Options,
            reqWidth: Int, reqHeight: Int
        ): Int {
            // Raw height and width of image
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                // Calculate ratios of height and width to requested height and
                // width

                val heightRatio = Math.round(height.toFloat() / reqHeight.toFloat())
                val widthRatio = Math.round(width.toFloat() / reqWidth.toFloat())

                // Choose the smallest ratio as inSampleSize value, this will
                // guarantee
                // a final image with both dimensions larger than or equal to the
                // requested height and width.
                val ratio =
                    min(heightRatio.toDouble(), widthRatio.toDouble()).toInt()
                // inSampleSize只能是2的次幂  将ratio就近取2的次幂的值
                inSampleSize = if (ratio < 3) ratio
                else if (ratio < 6.5) 4
                else max(ratio.toDouble(), 8.0).toInt()
            }

            return inSampleSize
        }

        /**
         * 图片缩放到指定宽高
         *
         *
         * 非等比例压缩，图片会被拉伸
         *
         * @param bitmap 源位图对象
         * @param w      要缩放的宽度
         * @param h      要缩放的高度
         * @return 新Bitmap对象
         */
        fun zoomBitmap(bitmap: Bitmap, w: Int, h: Int): Bitmap {
            val width = bitmap.width
            val height = bitmap.height
            val matrix = Matrix()
            val scaleWidth = (w.toFloat() / width)
            val scaleHeight = (h.toFloat() / height)
            matrix.postScale(scaleWidth, scaleHeight)
            return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
        }
    }
}
