# lib_book_common 混淆规则（一份文件、双声明；本模块恒为 library，规则经
# consumerProguardFiles 传播给消费方的 R8）。
# 当前无需任何 keep 规则，依据：Jsoup / juniversalchardet / PermissionX 无反射
# 需求；BookSourceManagerImpl 的 serializer 调用为编译期静态引用。
# 新增反射面时把 keep 规则写进本文件并注明证据；禁止无证据的 -keep/-dontwarn。
