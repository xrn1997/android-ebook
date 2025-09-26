package com.ebook.common.event


interface KeyCode {

    interface Login {
        companion object {
            //存储是否登录
            const val SP_IS_LOGIN = "sp_is_login"

            //存储用户名，密码，昵称，头像，id
            const val SP_USERNAME = "sp_username"
            const val SP_PASSWORD = "sp_password"
            const val SP_IMAGE = "sp_image"
            const val SP_NICKNAME = "sp_nickname"
            const val SP_USER_ID = "sp_user_id"

            const val PATH = "path"
            private const val BASE_PATH = "/ebook/user/"

            //登录
            const val LOGIN_PATH = BASE_PATH + "login"

            //注册
            const val REGISTER_PATH = BASE_PATH + "register"

            //拦截登录测试
            const val TEST_INTERRUPT_PATH = BASE_PATH + "test_interrupt"

            //修改密码
            const val MODIFY_PATH = BASE_PATH + "modify"
        }
    }

    interface Book {
        companion object {
            private const val BASE_PATH = "/ebook/book/"
            const val DETAIL_PATH = BASE_PATH + "detail"
            const val COMMENT_PATH = BASE_PATH + "comment"

            const val TEST_LOGIN_PATH = BASE_PATH + "test_login"
        }
    }

    interface Find {
        companion object {
            private const val BASE_PATH = "/ebook/find/"

            const val TEST_DETAIL_PATH = BASE_PATH + "test_detail"
        }
    }


    interface Me {
        companion object {
            private const val BASE_PATH = "/ebook/me/"
            const val SETTING_PATH = BASE_PATH + "setting"
            const val MODIFY_PATH = BASE_PATH + "modify"
            const val COMMENT_PATH = BASE_PATH + "comment"

            const val TEST_LOGIN_PATH = BASE_PATH + "test_login"
        }
    }
}
