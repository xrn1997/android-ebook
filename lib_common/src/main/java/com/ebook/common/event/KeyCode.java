package com.ebook.common.event;


public interface KeyCode {
    interface Main {

    }

    interface Login {
        //存储是否登录
        String SP_IS_LOGIN = "sp_is_login";
        //存储用户名，密码，昵称，头像
        String SP_USERNAME = "sp_username";
        String SP_PASSWORD = "sp_password";
        String SP_IMAGE="sp_image";
        String SP_NICKNAME="sp_nickname";

        String PATH = "path";
        String BASE_PATH = "/base/path/";
        //登录
        String Login_PATH = BASE_PATH + "login";
        //注册
        String Register_PATH = BASE_PATH + "register";
        //拦截登录测试
        String Test_PATH = BASE_PATH + "test";
        //修改密码
        String Modify_PATH=BASE_PATH+"modify";
    }

    interface Book {

    }

    interface Find {
    }

    interface Me {
        String BASE_PATH = "/me/";
        String Setting_PATH=BASE_PATH+"setting";
        String Modify_PATH=BASE_PATH+"modify";
    }
}
