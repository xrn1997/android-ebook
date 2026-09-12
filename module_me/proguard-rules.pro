# 本模块独立态（isModule=true）R8 规则：
# 独立态本模块是 application，R8 在本模块执行，proguardFiles 生效。
# 规则内容全部在 consumer-rules.pro（集成态也走那份文件传播给消费方），
# 此处 -include 引入，避免两份文件重复维护。
-include consumer-rules.pro
