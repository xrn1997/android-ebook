package com.ebook.common.event


interface KeyCode {

    interface Main {
        companion object {
            /** 主界面（三 Tab 宿主容器） */
            const val MAIN_PATH = "/ebook/main/main"
        }
    }

    interface Login {
        companion object {
            //存储是否登录
            const val SP_IS_LOGIN = "sp_is_login"

            //存储用户名、昵称、头像、id（密码不落盘，无密码键）
            const val SP_USERNAME = "sp_username"
            const val SP_IMAGE = "sp_image"
            const val SP_NICKNAME = "sp_nickname"
            const val SP_USER_ID = "sp_user_id"
            const val PATH = "therouter_path" //这个值不能随便改，受制于theRouter
            private const val BASE_PATH = "/ebook/user/"

            //登录
            const val LOGIN_PATH = BASE_PATH + "login"

            //注册
            const val REGISTER_PATH = BASE_PATH + "register"

            //拦截登录测试
            const val TEST_INTERRUPT_PATH = BASE_PATH + "test_interrupt"

            //修改密码（忘记密码流程第一步：邮箱验证码验证身份，由 VerifyUserActivity 持有）
            const val MODIFY_PATH = BASE_PATH + "modify"

            //密码设置页（双模式）：忘记密码第二步（RESET，由验证身份页携 email+验证码跳入）；
            //已登录改密（LOGGED_IN 默认，编辑资料页入口直达）
            const val MODIFY_PWD_PATH = BASE_PATH + "modify_pwd"
        }
    }

    interface Book {
        companion object {
            private const val BASE_PATH = "/ebook/book/"
            const val DETAIL_PATH = BASE_PATH + "detail"
            const val COMMENT_PATH = BASE_PATH + "comment"

            /** 下载管理页（书架下载图标入口，管理队列进度，见 DownloadManageActivity） */
            const val DOWNLOAD_PATH = BASE_PATH + "download"

            /** 修键面板（长按书架书籍 → 编辑匹配信息，spec §9.3） */
            const val EDIT_BOOK_META_PATH = BASE_PATH + "edit_meta"

            const val TEST_LOGIN_PATH = BASE_PATH + "test_login"
        }
    }

    interface Find {
        companion object {
            private const val BASE_PATH = "/ebook/find/"

            /** 分类选书页（extras: url=分类地址, title=分类名/页面标题） */
            const val CHOICE_PATH = BASE_PATH + "choice"

            /** 搜索页 */
            const val SEARCH_PATH = BASE_PATH + "search"

            const val TEST_DETAIL_PATH = BASE_PATH + "test_detail"
        }
    }


    interface Me {
        companion object {
            private const val BASE_PATH = "/ebook/me/"
            const val SETTING_PATH = BASE_PATH + "setting"
            const val MODIFY_PATH = BASE_PATH + "modify"
            const val COMMENT_PATH = BASE_PATH + "comment"
            const val CACHE_PATH = BASE_PATH + "cache"

            /** 修改昵称页（module_me 内部编辑资料入口） */
            const val MODIFY_NICKNAME_PATH = BASE_PATH + "modify_nickname"

            /** 关于页（App 信息 / 用户协议 / 隐私政策 / 开源许可） */
            const val ABOUT_PATH = BASE_PATH + "about"

            /** 协议文本页：doc_type extra 区分用户协议/隐私政策 */
            const val DOC_PATH = BASE_PATH + "doc"

            /** 开源许可页 */
            const val LICENSES_PATH = BASE_PATH + "licenses"

            const val TEST_LOGIN_PATH = BASE_PATH + "test_login"
        }
    }
}
