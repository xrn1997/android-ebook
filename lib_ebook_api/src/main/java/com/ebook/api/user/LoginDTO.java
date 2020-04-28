package com.ebook.api.user;

import com.ebook.api.user.entity.User;

import androidx.annotation.NonNull;

public class LoginDTO {
    private User user;
    private String token;

    @NonNull
    @Override
    public String toString() {
        return "LoginDTO{" +
                "user=" + user +
                ", token='" + token + '\'' +
                '}';
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
