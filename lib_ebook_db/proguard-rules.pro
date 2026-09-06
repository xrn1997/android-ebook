# lib_ebook_db 混淆规则（一份文件、双声明；本模块恒为 library，规则经
# consumerProguardFiles 传播给消费方的 R8，本模块自己的 proguardFiles 不生效）。
# 当前无需任何手写 keep 规则：Room3（androidx.room3）运行时 AAR 自带 consumer
# 规则覆盖实体/DAO/生成代码；实体与 DAO 由 Room 编译期生成代码直接引用，
# 无运行时反射。原模板遗留的死规则（**$Properties、net.sqlcipher、rx.**）
# 已删除——本仓库无对应类与依赖。
# 新增反射面（Class.forName / getDeclaredField / kotlin-reflect 等）时，
# 把对应 keep 规则写进本文件并注明证据；禁止无证据的 -keep/-dontwarn。
