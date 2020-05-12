package com.ebook.api.dto;

import android.util.Log;

import java.io.Serializable;

import androidx.annotation.NonNull;

import static android.content.ContentValues.TAG;

public class RespDTO<T> implements Serializable {

    public int code;
    public String error = "";
    public T data;

    public int getCode() {
        return code;
    }

    public String getError() {
        return error;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    @NonNull
    @Override
    public String toString() {
        return "RespDTO{" +
                "code=" + code +
                ", error='" + error + '\'' +
                ", data=" + data +
                '}';
    }

    public void setCode(int code) {
        this.code = code;
    }

    public void setError(String error) {
        this.error = error;
    }
}
