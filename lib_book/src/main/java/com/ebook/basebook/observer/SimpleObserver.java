
package com.ebook.basebook.observer;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public abstract class SimpleObserver<T> implements Observer<T> {

    @Override
    public void onSubscribe(Disposable d) {

    }

    @Override
    public void onComplete() {

    }
}
