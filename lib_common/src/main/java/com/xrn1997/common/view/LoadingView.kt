package com.xrn1997.common.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import com.xrn1997.common.R
import com.xrn1997.common.databinding.ViewComposeBinding
import com.xrn1997.common.ui.LoadingView
import com.xrn1997.common.ui.theme.MyApplicationTheme

/**
 *初始化加载视图（默认不透明背景）
 */
@Suppress("unused")
class LoadingView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {

    /**加载页面状态*/
    private var visibleState = mutableStateOf(false)

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
                    LoadingViewContent()
                }
            }
        }
    }

    fun show(b: Boolean) {
        visibleState.value = b
    }

    @Composable
    fun LoadingViewContent() {
        val visible by remember { visibleState }
        LoadingView(
            visible,
            Modifier
                .wrapContentSize()
        )
    }
}
