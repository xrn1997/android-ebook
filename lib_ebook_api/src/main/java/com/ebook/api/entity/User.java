package com.ebook.api.entity;

import android.os.Parcel;
import android.os.Parcelable;


public class User  implements Parcelable {
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

	protected User(Parcel in) {
		if (in.readByte() == 0) {
			id = null;
		} else {
			id = in.readLong();
		}
		username = in.readString();
		password = in.readString();
		image = in.readString();
		nickname = in.readString();
	}

	public static final Creator<User> CREATOR = new Creator<User>() {
		@Override
		public User createFromParcel(Parcel in) {
			return new User(in);
		}

		@Override
		public User[] newArray(int size) {
			return new User[size];
		}
	};

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

	public String getImage() {
		return image;
	}

	public void setImage(String image) {
		this.image = image;
	}

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		if (id == null) {
			dest.writeByte((byte) 0);
		} else {
			dest.writeByte((byte) 1);
			dest.writeLong(id);
		}
		dest.writeString(username);
		dest.writeString(password);
		dest.writeString(image);
		dest.writeString(nickname);
	}
}
