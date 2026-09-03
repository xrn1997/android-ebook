package com.ebook.api.config

import com.ebook.api.BuildConfig

/**
 * ebook-server 基址配置。
 *
 * 主机地址经 BuildConfig 注入（来源：机器私有 local.properties 的 `ebook.server.host`，
 * 缺省 10.0.2.2 = 模拟器视角的宿主机；真机局域网联调在本机 local.properties 覆盖），
 * 不再硬编码进版本库。端口保持常量：9090 与 ebook-server 的 `server.port`（config.yaml
 * 与代码默认值一致）对齐；客户端历史配置 5000 为笔误，已修正。需要时再提属性。
 */
object API {
    /** 用户/认证服务主机（开发期与评论服务同机部署） */
    val URL_HOST_USER: String = BuildConfig.EBOOK_SERVER_HOST

    /** 用户/认证服务端口（对齐 ebook-server server.port=9090） */
    const val URL_PORT_USER = 9090

    /** 评论服务主机（开发期与用户服务同机部署） */
    val URL_HOST_COMMENT: String = BuildConfig.EBOOK_SERVER_HOST

    /** 评论服务端口（对齐 ebook-server server.port=9090） */
    const val URL_PORT_COMMENT = 9090
}

