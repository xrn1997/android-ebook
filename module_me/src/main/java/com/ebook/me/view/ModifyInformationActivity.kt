package com.ebook.me.view

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ebook.common.event.KeyCode
import com.ebook.common.ui.Avatar
import com.ebook.common.ui.CommonCard
import com.ebook.common.ui.CommonListDivider
import com.ebook.common.ui.CommonListItem
import com.ebook.me.R
import com.ebook.me.mvvm.viewmodel.ModifyViewModel
import com.ebook.me.mvvm.viewmodel.ProfileDisplayState
import com.ebook.me.view.profilePhoto.ClipImageActivity
import com.therouter.TheRouter.build
import com.therouter.router.Route
import com.therouter.router.matchRouteMap
import com.xrn1997.common.mvvm.compose.BaseMvvmActivity
import com.xrn1997.common.util.FileUtil
import dagger.hilt.android.AndroidEntryPoint

/**
 * 编辑资料页：列表式条目（修改头像 / 修改昵称 / 修改密码），行内展示当前值。
 *
 * 密码行为条件展示：路由依赖 module_login，未集成时隐藏（见 [ModifyInformationScreen]）。
 *
 * 菜单样式与「我的」主页统一（CommonListItem），资料展示经 [ModifyViewModel.profileState]
 * 单一流驱动，Activity 不再直接注入 ProfileRepository。
 *
 * 更换头像流程（Compose 化，替代原 PhotoCutDialog fragment）：
 * 点击「修改头像」行 → ModalBottomSheet 选「拍照 / 从相册选择」→ 取得图片 Uri →
 * 打开 [ClipImageActivity] 圆形裁剪 → 裁剪结果 Uri 交给 [ModifyViewModel.modifyProfilePhoto] 上传。
 */
@AndroidEntryPoint
@Route(path = KeyCode.Me.MODIFY_PATH, params = ["needLogin", "true"])
class ModifyInformationActivity : BaseMvvmActivity<ModifyViewModel>() {
    override val viewModel: ModifyViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        toolbarTitle.value = getString(R.string.modify_info_title)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun PageContent() {
        val profileState by viewModel.profileState.collectAsState()
        val context = LocalContext.current
        var showPhotoPicker by rememberSaveable { mutableStateOf(false) }

        // 拍照临时文件：页面级唯一，多次拍照复用同一文件避免堆积
        val cameraFile = remember { FileUtil.privateFile(context, "profile_camera.jpg") }
        // 拍照输出 Uri：只算一次（不 remember 的话每次重组都会重跑 FileProvider）
        val cameraUri = remember(cameraFile) { FileUtil.contentUri(context, cameraFile) }

        // 裁剪页返回：拿到裁剪后的图片 Uri，交给 ViewModel 上传并刷新头像
        val cropLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri -> viewModel.modifyProfilePhoto(uri) }
            }
        }

        // 相册选择（PickVisualMedia，Android 13+ 免权限）→ 进入裁剪
        val pickMediaLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            uri?.let { openCropActivity(context, it, cropLauncher) }
        }

        // 拍照 → 临时文件 → 进入裁剪
        val takePhotoLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            if (success) openCropActivity(context, cameraUri, cropLauncher)
        }

        ModifyInformationScreen(
            profileState = profileState,
            // 已登录改密走 LOGGED_IN 模式（旧密码服务端校验）：不跳验证身份/忘记密码流程，
            // 登录态下邮箱验证码身份核验形同虚设，且会绕过旧密码校验。
            // 路由在 module_login 未集成时不可用：独立运行时隐藏此入口。
            showPassword = matchRouteMap(KeyCode.Login.MODIFY_PWD_PATH) != null,
            onModifyPhotoClick = { showPhotoPicker = true },
            onModifyPasswordClick = {
                build(KeyCode.Login.MODIFY_PWD_PATH).navigation()
            },
            onModifyNicknameClick = {
                build(KeyCode.Me.MODIFY_NICKNAME_PATH).navigation()
            }
        )

        // 选图 BottomSheet：拍照 / 从相册选择
        if (showPhotoPicker) {
            ModalBottomSheet(onDismissRequest = { showPhotoPicker = false }) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    CommonListItem(
                        icon = Icons.Outlined.PhotoCamera,
                        title = stringResource(R.string.modify_take_photo),
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = {
                            showPhotoPicker = false
                            takePhotoLauncher.launch(cameraUri)
                        }
                    )
                    CommonListDivider()
                    CommonListItem(
                        icon = Icons.Outlined.PhotoLibrary,
                        title = stringResource(R.string.modify_pick_from_album),
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = {
                            showPhotoPicker = false
                            pickMediaLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }
        }
    }

    companion object {
        /** 启动圆形裁剪页：图片 Uri 经 intent.data 传入，裁剪结果经 onActivityResult 返回。 */
        private fun openCropActivity(
            context: Context,
            uri: Uri,
            launcher: androidx.activity.result.ActivityResultLauncher<Intent>
        ) {
            launcher.launch(Intent(context, ClipImageActivity::class.java).apply { data = uri })
        }
    }
}

/**
 * 编辑资料页内容：列表式条目，每行「标签 + 当前值 + 箭头」，点击进入对应编辑。
 *
 * 列表式替代旧的「顶部大头像/昵称展示区 + 菜单」结构，避免同一信息既展示又有独立入口的重复；
 * 头像缩略图与当前昵称作为行内值展示（trailingContent / trailingText），点击行即进入对应编辑。
 *
 * @param showPassword 修改密码行是否显示：路由依赖 module_login，未集成时隐藏避免误跳模拟登录页。
 */
@Composable
fun ModifyInformationScreen(
    profileState: ProfileDisplayState,
    onModifyPhotoClick: () -> Unit,
    onModifyPasswordClick: () -> Unit,
    onModifyNicknameClick: () -> Unit,
    showPassword: Boolean = true
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            CommonCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 头像行：行内显示当前头像缩略图，点击行进入更换头像流程
                    CommonListItem(
                        icon = Icons.Outlined.PhotoCamera,
                        title = stringResource(R.string.modify_photo),
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        trailingContent = {
                            Avatar(
                                url = profileState.avatarUrl,
                                modifier = Modifier.size(36.dp),
                            )
                        },
                        onClick = onModifyPhotoClick
                    )
                    CommonListDivider()
                    // 昵称行：行内显示当前昵称（未设置时占位），点击进入修改昵称
                    CommonListItem(
                        icon = Icons.Outlined.DriveFileRenameOutline,
                        title = stringResource(R.string.modify_nickname),
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        trailingText = profileState.nickname.ifEmpty { stringResource(R.string.common_not_set) },
                        onClick = onModifyNicknameClick
                    )
                    // 密码行：依赖 module_login，路由不可用（未集成）时隐藏；分割线随行一起，避免末尾孤立分隔线
                    if (showPassword) {
                        CommonListDivider()
                        CommonListItem(
                            icon = Icons.Outlined.Password,
                            title = stringResource(R.string.modify_password),
                            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            onClick = onModifyPasswordClick
                        )
                    }
                }
            }
        }
    }
}
