package com.ebook.api.config

/**
 * 服务器地址未配置：应用不内置任何默认地址（ADR-0053），未配置时一切服务端调用
 * （登录/注册/改密/评论）以本异常快速失败——不发请求、不留网络错误的模糊观感。
 *
 * message 即用户所见文案（经 `FailureReport.reportFailure` 直接进 Toast）；
 * 阅读、书源、搜索、下载走第三方书源，不经过 ebook-server，不受本异常影响。
 */
class ServerAddressNotConfiguredException : Exception(
    "未配置服务器地址：请先在「设置 → 服务器地址」填入你的 ebook-server 地址（阅读不受影响）"
)
