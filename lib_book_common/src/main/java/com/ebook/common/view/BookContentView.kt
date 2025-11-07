package com.ebook.common.view

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ebook.common.R
import com.ebook.common.view.ContentSwitchView.LoadDataListener

/**
 * BookContentView：显示书籍章节内容的自定义控件
 *
 * 功能：
 * 1. 显示章节标题、内容、页码
 * 2. 支持加载状态与错误状态
 * 3. 支持设置字体、背景和行间距
 * 4. 支持异步数据加载回调
 */
class BookContentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 请求标识，用于防止异步回调过期 */
    var qTag: Long = System.currentTimeMillis()

    /** 背景 ImageView */
    private lateinit var ivBg: ImageView

    /** 标题 TextView */
    private lateinit var tvTitle: TextView

    /** 内容容器 */
    private lateinit var llContent: LinearLayout

    /** 主要内容 TextView */
    private lateinit var tvContent: MTextView

    /** 页码 TextView */
    private lateinit var tvPage: TextView

    /** 加载中提示 */
    private lateinit var tvLoading: TextView

    /** 错误提示容器 */
    private lateinit var llError: LinearLayout
    private lateinit var tvErrorInfo: TextView

    /** 章节标题与内容 */
    private var title: String? = null
    private var content: String? = null

    /** 当前章节索引、总章节数、当前页索引、总页数 */
    @JvmField
    var durChapterIndex: Int = 0
    @JvmField
    var chapterAll: Int = 0
    @JvmField
    var durPageIndex: Int = 0 // -1: 从头开始，-2: 从尾开始
    @JvmField
    var pageAll: Int = 0

    /** 数据加载回调 */
    var loadDataListener: LoadDataListener? = null
    var setDataListener: SetDataListener? = null

    init {
        initView()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 强制让系统重新分发 WindowInsets
        ViewCompat.requestApplyInsets(this)
    }

    /** 初始化视图与事件 */
    private fun initView() {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.adapter_content_switch_item, this, false)
        addView(view)

        // 绑定视图
        ivBg = view.findViewById(R.id.iv_bg)
        tvTitle = view.findViewById(R.id.tv_title)
        llContent = view.findViewById(R.id.ll_content)
        val mainContent = view.findViewById<LinearLayout>(R.id.main_content)
        tvContent = view.findViewById(R.id.tv_content)
        tvPage = view.findViewById(R.id.tv_page)

        tvLoading = view.findViewById(R.id.tv_loading)
        llError = view.findViewById(R.id.ll_error)
        tvErrorInfo = view.findViewById(R.id.tv_error_info)
        val tvLoadAgain = view.findViewById<TextView>(R.id.tv_load_again)

        // 点击重新加载
        tvLoadAgain.setOnClickListener {
            if (loadDataListener != null) loading()
        }

        // 处理状态栏内边距
        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { v, insets ->
            val stateBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(stateBars.left, stateBars.top, stateBars.right, stateBars.bottom)
            insets
        }
    }

    /** 显示加载中状态 */
    fun loading() {
        llError.visibility = GONE
        tvLoading.visibility = VISIBLE
        llContent.visibility = INVISIBLE
        qTag = System.currentTimeMillis()

        loadDataListener?.loadData(this, qTag, durChapterIndex, durPageIndex)
    }

    /** 显示内容 */
    fun finishLoading() {
        llError.visibility = GONE
        llContent.visibility = VISIBLE
        tvLoading.visibility = GONE
    }

    /** 设置无数据状态 */
    fun setNoData(contentLines: String?) {
        this.content = contentLines
        tvPage.text = context.getString(
            R.string.page_indicator_format,
            durPageIndex + 1,
            pageAll
        )
        finishLoading()
    }

    /** 更新内容数据并刷新视图 */
    fun updateData(
        tag: Long,
        title: String?,
        contentLines: MutableList<String>,
        durChapterIndex: Int,
        chapterAll: Int,
        durPageIndex: Int,
        durPageAll: Int
    ) {
        if (tag != qTag) return

        this.title = title
        this.content = contentLines?.joinToString("") ?: ""
        this.durChapterIndex = durChapterIndex
        this.chapterAll = chapterAll
        this.durPageIndex = durPageIndex
        this.pageAll = durPageAll

        // 回调外部
        setDataListener?.setDataFinish(
            this,
            durChapterIndex,
            chapterAll,
            durPageIndex,
            durPageAll,
            this.durPageIndex
        )

        tvTitle.text = this.title
        tvContent.text = this.content
        tvPage.text = context.getString(R.string.page_indicator_format, durPageIndex + 1, pageAll)

        finishLoading()
    }

    /** 设置章节基本信息并加载 */
    fun loadData(title: String?, durChapterIndex: Int, chapterAll: Int, durPageIndex: Int) {
        this.title = title
        this.durChapterIndex = durChapterIndex
        this.chapterAll = chapterAll
        this.durPageIndex = durPageIndex

        tvTitle.text = title
        tvPage.text = ""
        loading()
    }

    /** 设置加载回调 */
    fun setLoadDataListener(
        loadDataListener: LoadDataListener?,
        setDataListener: SetDataListener?
    ) {
        this.loadDataListener = loadDataListener
        this.setDataListener = setDataListener
    }

    /** 显示加载失败状态 */
    fun loadError() {
        llError.visibility = VISIBLE
        tvLoading.visibility = GONE
        llContent.visibility = INVISIBLE
    }

    /** 获取内容 TextView */
    fun getTvContent(): TextView = tvContent

    /** 计算每页显示行数 */
    fun getLineCount(height: Int): Int {
        val ascent = tvContent.paint.ascent()
        val descent = tvContent.paint.descent()
        val textHeight = descent - ascent
        return ((height.toFloat() - tvContent.lineSpacingExtra) / (textHeight + tvContent.lineSpacingExtra)).toInt()
    }

    /** 设置阅读样式控制器 */
    fun setReadBookControl(readBookControl: ReadBookControl) {
        setTextKind(readBookControl)
        setBg(readBookControl)
    }

    /** 设置背景与文字颜色 */
    fun setBg(readBookControl: ReadBookControl) {
        ivBg.setImageDrawable(ColorDrawable(readBookControl.textBackground))
        tvTitle.setTextColor(readBookControl.textColor)
        tvContent.setTextColor(readBookControl.textColor)
        tvPage.setTextColor(readBookControl.textColor)
        tvLoading.setTextColor(readBookControl.textColor)
        tvErrorInfo.setTextColor(readBookControl.textColor)
    }

    /** 设置文字大小与行间距 */
    fun setTextKind(readBookControl: ReadBookControl) {
        tvContent.textSize = readBookControl.textSize.toFloat()
        tvContent.setLineSpacing(readBookControl.textExtra.toFloat(), 1f)
    }

    /** 外部回调接口，用于设置数据完成 */
    interface SetDataListener {
        fun setDataFinish(
            bookContentView: BookContentView?,
            durChapterIndex: Int,
            chapterAll: Int,
            durPageIndex: Int,
            pageAll: Int,
            fromPageIndex: Int
        )
    }
}
