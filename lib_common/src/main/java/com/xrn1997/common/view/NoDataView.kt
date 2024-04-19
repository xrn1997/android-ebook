package com.xrn1997.common.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import com.xrn1997.common.R
import com.xrn1997.common.databinding.ViewComposeBinding
import com.xrn1997.common.ui.NoDataView
import com.xrn1997.common.ui.theme.MyApplicationTheme


/**
 * 无数据视图
 * @author xrn1997
 * @date 2022/3/3 16:18
 */
class NoDataView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {
    /**没有数据页面状态*/
    private var visibleState = mutableStateOf(false)

    /** 显示的图片资源ID*/
    private var imageState = mutableIntStateOf(R.drawable.no_data)
    init {
        val root = View.inflate(context, R.layout.view_compose, this)
        val binding = ViewComposeBinding.bind(root)
        val composeView = binding.composeView
        composeView.apply {
            // Dispose of the Composition when the view's LifecycleOwner
            // is destroyed
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MyApplicationTheme {
                    NoDataViewContent()
                }
            }
        }
    }

    fun show(b: Boolean) {
        visibleState.value = b
    }

    @Composable
    private fun NoDataViewContent() {
        val visible by remember { visibleState }
        val image by remember { imageState }
        NoDataView(
            visible,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            res = image
        )
    }


    fun setNoDataView(@DrawableRes imgResId: Int) {
        imageState.intValue = imgResId
    }
}