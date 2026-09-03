# RxBus → SharedFlow：事件总线替换

移除 `RxBusTag`，书架事件改用 `BookRepository` 的 `MutableSharedFlow<BookShelfEvent>`（extraBufferCapacity = 64）发布。**消费方统一在 ViewModel 内用穷尽 `when` 消费**（ChoiceBookViewModel、SearchViewModel、BookListViewModel、BookDetailViewModel）——sealed class 穷尽分支编译期强制覆盖全部子类型，新增事件子类型会在所有消费方产生编译错误，避免 `filterIsInstance` 运行时过滤的静默漏收。书架事件不再于 Activity 侧收集，消费与页面生命周期解耦，旋转重建不重复累积。

## 动机

- 与 RxJava→Coroutines 迁移主线一致，事件机制不再依赖 RxJava
- SharedFlow 提供结构化并发（viewModelScope 内自动取消）与类型安全的事件模型（sealed class）

## 权衡

- **背压语义**：SharedFlow 默认无 replay，先订阅后发布的事件会丢失；extraBufferCapacity = 64 缓冲瞬时并发。RxBus 的粘性事件（粘性订阅重放）语义未保留——当前无消费方依赖粘性
- 事件模型从 String tag + Any payload 改为 sealed class，编译期类型安全
