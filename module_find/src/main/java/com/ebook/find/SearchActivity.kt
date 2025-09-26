package com.ebook.find

import android.animation.Animator
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daimajia.androidanimations.library.Techniques
import com.daimajia.androidanimations.library.YoYo
import com.ebook.common.event.FROM_SEARCH
import com.ebook.common.event.KeyCode
import com.ebook.common.event.RxBusTag
import com.ebook.common.view.flowlayout.TagFlowLayout
import com.ebook.db.entity.BookShelf
import com.ebook.db.entity.SearchBook
import com.ebook.db.entity.SearchHistory
import com.ebook.find.adapter.SearchBookAdapter
import com.ebook.find.adapter.SearchHistoryAdapter
import com.ebook.find.databinding.ActivitySearchBinding
import com.ebook.find.mvvm.viewmodel.SearchViewModel
import com.hwangjr.rxbus.RxBus
import com.hwangjr.rxbus.annotation.Subscribe
import com.hwangjr.rxbus.annotation.Tag
import com.hwangjr.rxbus.thread.EventThread
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.therouter.TheRouter
import com.xrn1997.common.mvvm.view.BaseMvvmRefreshActivity
import dagger.hilt.android.AndroidEntryPoint
import tyrantgit.explosionfield.ExplosionField
import kotlin.math.abs
import kotlin.math.hypot

@AndroidEntryPoint
class SearchActivity : BaseMvvmRefreshActivity<ActivitySearchBinding, SearchViewModel>() {
    override val mViewModel: SearchViewModel by viewModels()
    private lateinit var flSearchContent: FrameLayout
    lateinit var edtContent: EditText

    private lateinit var tvToSearch: TextView
    private lateinit var llSearchHistory: LinearLayout
    private lateinit var tvSearchHistoryClean: TextView
    private lateinit var tflSearchHistory: TagFlowLayout
    private lateinit var searchHistoryAdapter: SearchHistoryAdapter
    private var animHistory: Animator? = null
    private lateinit var explosionField: ExplosionField
    private lateinit var rfRvSearchBooks: RecyclerView
    private lateinit var searchBookAdapter: SearchBookAdapter
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RxBus.get().register(this)
    }

    override fun initView() {
        explosionField = ExplosionField.attach2Window(this)
        searchHistoryAdapter = SearchHistoryAdapter()
        searchBookAdapter = SearchBookAdapter(this)
        flSearchContent = binding.flSearchContent
        edtContent = binding.edtContent
        tvToSearch = binding.tvToSearch
        llSearchHistory = binding.llSearchHistory
        tvSearchHistoryClean = binding.tvSearchHistoryClean
        tflSearchHistory = binding.tflSearchHistory
        tflSearchHistory.setAdapter(searchHistoryAdapter)
        rfRvSearchBooks = binding.rfRvSearchBooks
        rfRvSearchBooks.layoutManager = LinearLayoutManager(this)
        rfRvSearchBooks.adapter = searchBookAdapter
        searchBookAdapter.setItemClickListener(object : SearchBookAdapter.OnItemClickListener {
            override fun clickAddShelf(clickView: View, position: Int, searchBook: SearchBook) {
                mViewModel.addBookToShelf(searchBook)
            }

            override fun clickItem(animView: View, position: Int, searchBook: SearchBook) {
                TheRouter.build(KeyCode.Book.DETAIL_PATH)
                    .withInt("from", FROM_SEARCH)
                    .withObject("data", searchBook)
                    .navigation(this@SearchActivity)
            }
        })

        tvSearchHistoryClean.setOnClickListener {
            for (i in 0 until tflSearchHistory.childCount) {
                explosionField.explode(tflSearchHistory.getChildAt(i))
            }
            mViewModel.cleanSearchHistory(edtContent.text.toString().trim())
        }
        edtContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable) {
                edtContent.setSelection(edtContent.length())
                checkTvToSearch()
                mViewModel.querySearchHistory(edtContent.text.toString().trim())
            }
        })
        edtContent.setOnEditorActionListener { _: TextView, actionId: Int, event: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                toSearch()
                return@setOnEditorActionListener true
            } else return@setOnEditorActionListener false
        }
        tvToSearch.setOnClickListener {
            if (!mViewModel.isInput) {
                finishAfterTransition()
            } else {
                //搜索
                toSearch()
            }
        }
        searchHistoryAdapter.setOnItemClickListener { searchHistory: SearchHistory ->
            edtContent.setText(searchHistory.content)
            toSearch()
        }
        bindKeyBoardEvent()
        mViewModel.querySearchHistory(edtContent.text.toString().trim())
    }

    override fun initData() {
        mViewModel.successEvent.observe(this) { searchHistories ->
            searchHistoryAdapter.replaceAll(searchHistories)
            if (searchHistoryAdapter.dataSize > 0) {
                tvSearchHistoryClean.visibility = View.VISIBLE
            } else {
                tvSearchHistoryClean.visibility = View.INVISIBLE
            }
        }
        mViewModel.mList.observe(this) {
            searchBookAdapter.submitList(it)
        }
    }

    //开始搜索
    private fun toSearch() {
        val key = edtContent.text.toString().trim()
        if (key.isNotEmpty()) {
            mViewModel.setHasSearch(true)
            mViewModel.insertSearchHistory(key)
            closeKeyBoard()
            //执⾏搜索请求
            Handler(Looper.getMainLooper()).postDelayed({
                mViewModel.initPage()
                mViewModel.toSearchBooks(key)
            }, 300)
        } else {
            YoYo.with(Techniques.Shake).playOn(flSearchContent)
        }
    }
    private fun bindKeyBoardEvent() {
        llSearchHistory.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            llSearchHistory.getWindowVisibleDisplayFrame(r)

            val layoutParams = llSearchHistory.layoutParams as FrameLayout.LayoutParams
            val screenHeight = llSearchHistory.context.resources.displayMetrics.heightPixels

            // 获取状态栏的高度
            val statusBarHeight = getStatusBarHeight()

            // Adjust the bottom of the rect for devices with display issues (like notches)
            if (screenHeight < r.bottom) {
                r.bottom = screenHeight
            }

            val diff = screenHeight - r.bottom

            if (diff != 0 && abs(diff) != statusBarHeight) {
                handleKeyboardVisibility(diff, layoutParams, statusBarHeight)
            } else {
                resetLayoutParams(layoutParams)
            }
        }

        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                Handler(Looper.getMainLooper()).postDelayed({ openKeyBoard() }, 100)
                window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    // 获取状态栏高度的方法
    private fun getStatusBarHeight(): Int {
        var statusBarHeight = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 及以上，使用 WindowInsets API
            val insets: WindowInsets? = window.decorView.rootWindowInsets
            if (insets != null) {
                statusBarHeight = insets.getInsets(WindowInsets.Type.systemBars()).top
            }
        } else {
            // Android 4.4 - 10，使用系统资源获取状态栏高度
            val rect = Rect()
            window.decorView.getWindowVisibleDisplayFrame(rect)
            statusBarHeight = rect.top
        }
        return statusBarHeight
    }

    /**
     * 打开键盘后，调整布局
     */
    private fun handleKeyboardVisibility(
        diff: Int,
        layoutParams: FrameLayout.LayoutParams,
        statusBarHeight: Int
    ) {
        val marginBottom = abs(diff)
        if (layoutParams.bottomMargin != marginBottom) {
            if (abs(layoutParams.bottomMargin - marginBottom) != statusBarHeight) {
                layoutParams.setMargins(0, 0, 0, marginBottom)
                llSearchHistory.layoutParams = layoutParams
            }

            if (llSearchHistory.visibility != View.VISIBLE) {
                openOrCloseHistory(true)
            }
        }
    }

    /**
     * 关闭键盘后，重置layout参数
     */
    private fun resetLayoutParams(layoutParams: FrameLayout.LayoutParams) {
        if (layoutParams.bottomMargin != 0) {
            if (!mViewModel.getHasSearch()) {
                finishAfterTransition()
            } else {
                layoutParams.setMargins(0, 0, 0, 0)
                llSearchHistory.layoutParams = layoutParams
                if (llSearchHistory.isVisible) {
                    openOrCloseHistory(false)
                }
            }
        }
    }

    private fun checkTvToSearch() {
        if (llSearchHistory.isVisible) {
            tvToSearch.text = "搜索"
            mViewModel.isInput = true
        } else {
            tvToSearch.text = "返回"
            mViewModel.isInput = false
        }
    }

    override fun enableToolbar(): Boolean {
        return false
    }

    override fun enableLoadMore(): Boolean {
        return true
    }

    override fun enableRefresh(): Boolean {
        return false
    }

    private fun openOrCloseHistory(open: Boolean) {
        animHistory?.cancel()
        if (open) {
            animHistory = ViewAnimationUtils.createCircularReveal(
                llSearchHistory,
                0, 0, 0f,
                hypot(
                    llSearchHistory.width.toDouble(),
                    llSearchHistory.height.toDouble()
                ).toFloat()
            )
            animHistory?.interpolator = AccelerateDecelerateInterpolator()
            animHistory?.duration = 700
            animHistory?.addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    llSearchHistory.visibility = View.VISIBLE
                    edtContent.isCursorVisible = true
                    checkTvToSearch()
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (rfRvSearchBooks.visibility != View.VISIBLE) rfRvSearchBooks.visibility =
                        View.VISIBLE
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
        } else {
            animHistory = ViewAnimationUtils.createCircularReveal(
                llSearchHistory,
                0,
                0,
                hypot(
                    llSearchHistory.height.toDouble(),
                    llSearchHistory.height.toDouble()
                ).toFloat(),
                0f
            )
            animHistory?.interpolator = AccelerateDecelerateInterpolator()
            animHistory?.duration = 300
            animHistory?.addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                }

                override fun onAnimationEnd(animation: Animator) {
                    llSearchHistory.visibility = View.GONE
                    edtContent.isCursorVisible = false
                    checkTvToSearch()
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
        }
        animHistory?.start()
    }

    private fun closeKeyBoard() {
        val imm = this.getSystemService(
            InputMethodManager::class.java
        )
        imm.hideSoftInputFromWindow(edtContent.windowToken, 0)
        /*
        由于关闭软键盘会触发监听事件导致搜索历史关闭，所以这里为了兼容没有软键盘的例外情况，再次核验了一次是否已经关闭搜索历史。
         */
        if (llSearchHistory.isVisible) openOrCloseHistory(false)
    }

    private fun openKeyBoard() {
        val imm = getSystemService(
            InputMethodManager::class.java
        )
        edtContent.requestFocus()
        imm.showSoftInput(edtContent, InputMethodManager.SHOW_IMPLICIT)
        /*
            由于思路是通过软键盘改变“屏幕大小”来控制是否显示搜索历史的，
            因此即便是在打开软键盘失败的情况下，也依旧应该兼容显示搜索历史，
            如PC端的Android模拟器就有可能不会提供软键盘。
             */
        if (llSearchHistory.visibility != View.VISIBLE) openOrCloseHistory(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        explosionField.clear()
        RxBus.get().unregister(this)
    }

    override fun onBindViewBinding(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        attachToParent: Boolean
    ): ActivitySearchBinding {
        return ActivitySearchBinding.inflate(inflater, parent, attachToParent)
    }

    override fun getRefreshLayout(): RefreshLayout {
        return binding.rfRvSmartRefreshLayout
    }

    // 处理添加书籍事件
    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.HAD_ADD_BOOK)])
    fun hadAddBook(bookShelf: BookShelf) {
        mViewModel.bookShelves.add(bookShelf)
        handleBookUpdate(bookShelf, true)  // true 表示添加书籍
    }

    // 处理移除书籍事件
    @Subscribe(thread = EventThread.MAIN_THREAD, tags = [Tag(RxBusTag.HAD_REMOVE_BOOK)])
    fun hadRemoveBook(bookShelf: BookShelf) {
        mViewModel.bookShelves.remove(bookShelf)
        handleBookUpdate(bookShelf, false)  // false 表示移除书籍
    }

    // 公共处理逻辑
    private fun handleBookUpdate(bookShelf: BookShelf, isAdd: Boolean) {
        val currentList = mViewModel.mList.value?.toMutableList() ?: mutableListOf()

        val index = currentList.indexOfFirst { it.noteUrl == bookShelf.noteUrl }
        if (index != -1) {
            // 根据 isAdd 判断是添加还是移除书籍
            val updatedBook = currentList[index].copy(add = isAdd)
            currentList[index] = updatedBook
        }
        searchBookAdapter.submitList(currentList)
    }
}