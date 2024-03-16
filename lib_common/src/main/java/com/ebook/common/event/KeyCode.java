package com.ebook.common.event;


public interface KeyCode {
    interface Main {

    }

    interface Login {
        //存储是否登录
        String SP_IS_LOGIN = "sp_is_login";
        //存储用户名，密码，昵称，头像，id
        String SP_USERNAME = "sp_username";
        String SP_PASSWORD = "sp_password";
        String SP_IMAGE = "sp_image";
        String SP_NICKNAME = "sp_nickname";
        String SP_USER_ID = "sp_user_id";

        String PATH = "path";
        String BASE_PATH = "/ebook/user/";
        //登录
        String LOGIN_PATH = BASE_PATH + "login";
        //注册
        String REGISTER_PATH = BASE_PATH + "register";
        //拦截登录测试
        String TEST_PATH = BASE_PATH + "test";
        //修改密码
        String MODIFY_PATH = BASE_PATH + "modify";
    }

    interface Book {
        String BASE_PATH = "/ebook/book/";
        String COMMENT_PATH = BASE_PATH + "comment";
    }

    interface Find {
    }

    interface Me {
        String BASE_PATH = "/ebook/me/";
        String SETTING_PATH = BASE_PATH + "setting";
        String MODIFY_PATH = BASE_PATH + "modify";
        String COMMENT_PATH = BASE_PATH + "comment";
    }
}
