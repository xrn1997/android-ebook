# lib_ebook_api 混淆规则（一份文件、双声明；本模块恒为 library，规则经
# consumerProguardFiles 传播给消费方的 R8）。
# 当前无需任何手写规则，依据：
# - DTO 反射式 serializer 查找（JsonUtils.parseJson 的 clazz.kotlin.serializer()）
#   由 kotlinx-serialization-core jar 内置的
#   META-INF/proguard/kotlinx-serialization-common.pro 覆盖（已核对该文件内容）；
# - Retrofit 接口反射由 retrofit jar 内置 META-INF/proguard/retrofit2.pro 覆盖；
# - EncodingInterceptor 已去除对 OkHttp 私有字段 contentTypeString 的反射，
#   改为公开 API 等价实现。
# 新增反射面时把 keep 规则写进本文件并注明证据；禁止无证据的 -keep/-dontwarn。
