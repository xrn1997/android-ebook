package com.ebook.common.event;


public interface EventCode {
    interface MainCode {
        //1000开始
    }

    interface BookCode {
        //2000开始
    }

    interface FindCode {
        //3000开始
    }

    interface MeCode {
        //4000开始
        int NEWS_TYPE_ADD = 4000;
        int NEWS_TYPE_DELETE = 4001;
        int NEWS_TYPE_UPDATE = 4002;
        int NEWS_TYPE_QUERY = 4003;
        int news_detail_add  = 4004;
    }
}
