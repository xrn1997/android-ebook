package com.ebook.api.user.entity;

import java.io.Serializable;


public class User implements Serializable {
	private Long id;//ID
	private String username;//用户名（账号）
	private String password;//密码
	private String image;//图片地址
	private String nickname;//昵称

	public User() {
	}

	public User(String username, String password) {
		this.username = username;
		this.password = password;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
}
