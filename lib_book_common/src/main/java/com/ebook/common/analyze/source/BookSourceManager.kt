package com.ebook.common.analyze.source

/**
 * 书源四面的聚合接口（见 ADR-0016）。
 *
 * 生产消费者注入**最窄**的那一面：[SourceResolver]、[SourceCatalog]、[SourceAdmin] 或 [SourceCodec]，
 * 只暴露自己真正调用的方法；本接口是四者的组合，只留给需要整张面的测试，生产代码不注入它
 * （也没有它的 Hilt 绑定）。
 *
 * 四面的共同前提：应用不随包携带任何书源，`book_source` 表是清单的唯一事实源，
 * 清单与默认源的真值只有 Room 这一处。业务模块依赖的是这一组面而不是 `BookSourceDao`；
 * 规则对象已带实体列覆盖（`enabled`/`weight`/`group`），调用方拿到的是本机这一行的真值。
 */
interface BookSourceManager : SourceResolver, SourceCatalog, SourceAdmin, SourceCodec
