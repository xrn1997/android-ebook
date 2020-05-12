package com.ebook.common.event.common;

import com.ebook.common.event.BaseEvent;

/**
 * Description: <BaseFragmentEvent><br>
 */
public class BaseFragmentEvent<T> extends BaseEvent<T> {
    public BaseFragmentEvent(int code) {
        super(code);
    }
}
