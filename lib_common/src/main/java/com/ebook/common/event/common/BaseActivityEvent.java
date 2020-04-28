package com.ebook.common.event.common;

import com.ebook.common.event.BaseEvent;


public class BaseActivityEvent<T> extends BaseEvent<T> {
    public BaseActivityEvent(int code) {
        super(code);
    }
}
