package com.ebook.api.service.release

import com.ebook.api.entity.ReleaseResponse

/**
 * 发布检查的数据源：按给定端点取一处 latest Release 的 JSON 投影。
 *
 * 接缝刻意只做「取一处的 latest」——**源顺序、failover、APK 过滤都不在这里**
 * （那些属更新策略，归 `module_me` 的 `ReleaseRepository`）。因此 mock 实现
 * （`ReleaseNetworkTest`）可以固定回一份资产，无需模拟两平台与失败降级。
 */
interface ReleaseDataSource {

    /**
     * 拉取指定地址的 latest Release（匿名访问，无需 token）。
     *
     * @param endpoint 完整端点地址（含 host + 路径），两源路径结构不同故由调用方给出
     */
    suspend fun getLatest(endpoint: String): ReleaseResponse
}
