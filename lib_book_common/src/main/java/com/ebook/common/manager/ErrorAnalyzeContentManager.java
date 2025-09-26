package com.ebook.common.manager;

import android.content.Context;
import android.util.Log;

import com.xrn1997.common.event.SimpleObserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableOnSubscribe;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ErrorAnalyzeContentManager {
    private static final String TAG = "ErrorAnalyzeContentManager";
    private static volatile ErrorAnalyzeContentManager instance;

    private ErrorAnalyzeContentManager() {
    }

    public static ErrorAnalyzeContentManager getInstance() {
        if (instance == null) {
            synchronized (ErrorAnalyzeContentManager.class) {
                if (instance == null) {
                    instance = new ErrorAnalyzeContentManager();
                }
            }
        }
        return instance;
    }

    public void writeNewErrorUrl(Context context, final String url) {
        Observable.create((ObservableOnSubscribe<Boolean>) emitter -> {
                    try {
                        File dir = getExternalFilesDir(context);
                        if (dir == null) {
                            Log.e(TAG, "getExternalFilesDir is null");
                            emitter.onError(new IOException("External files directory is null"));
                            return;
                        }

                        File errorDetailFile = new File(dir, "ErrorAnalyzeUrlsDetail.txt");
                        appendToFile(errorDetailFile, url + "    \r\n");

                        File errorFile = new File(dir, "ErrorAnalyzeUrls.txt");
                        String content = readFileContent(errorFile);
                        String baseUrl = url.substring(0, url.indexOf('/', 8));
                        if (!content.contains(baseUrl)) {
                            appendToFile(errorFile, baseUrl + "    \r\n");
                        }

                        emitter.onNext(true);
                        emitter.onComplete();
                    } catch (Exception ex) {
                        Log.e(TAG, "Error in writeNewErrorUrl", ex);
                        emitter.onError(ex);
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(Boolean value) {
                        // Handle success
                    }

                    @Override
                    public void onError(Throwable e) {
                        // Handle error
                    }
                });
    }

    public void writeMayByNetError(Context context, final String url) {
        Observable.create((ObservableOnSubscribe<Boolean>) emitter -> {
                    try {
                        File dir = getExternalFilesDir(context);
                        if (dir == null) {
                            Log.e(TAG, "getExternalFilesDir is null");
                            emitter.onError(new IOException("External files directory is null"));
                            return;
                        }

                        File errorNetFile = new File(dir, "ErrorNetUrl.txt");
                        appendToFile(errorNetFile, url + "    \r\n");

                        emitter.onNext(true);
                        emitter.onComplete();
                    } catch (Exception ex) {
                        Log.e(TAG, "Error in writeMayByNetError", ex);
                        emitter.onError(ex);
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<>() {
                    @Override
                    public void onNext(Boolean value) {
                        // Handle success
                    }

                    @Override
                    public void onError(Throwable e) {
                        // Handle error
                    }
                });
    }

    private File getExternalFilesDir(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir != null && !dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: " + dir.getPath());
                return null;
            }
        }
        return dir;
    }

    private void appendToFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(content.getBytes());
            fos.flush();
        }
    }

    private String readFileContent(File file) throws IOException {
        if (!file.exists()) {
            return "";
        }
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
            return outputStream.toString();
        }
    }
}
