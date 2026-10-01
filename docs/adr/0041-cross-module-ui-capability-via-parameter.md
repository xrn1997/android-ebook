# 跨模块 UI 能力用参数注入：书架空态要一句「去书城找书」

书架空态需要一个去处，而「书城」是宿主 `module_main` 的 NavHost 目的地（`Screen.Bookstore.route`），
书架模块内既没有 `NavController`、也没有那条路由，更没有任何一条 TheRouter 路由能表达「切到某个 Tab」。
做法是把这份能力作为**参数**由知道它的宿主递进页面：`IBookProvider.mainBookPage` 从
`@Composable () -> Unit` 改为 `@Composable (onGoBookstore: (() -> Unit)?) -> Unit`；
传 `null` 表示当前宿主没有书城 Tab（模块独立运行的调试宿主），调用方据此**不渲染**该动作。

## 背景

书架页在本次改动前**没有任何空态**：首启、或删完最后一本书之后，这一屏是一片纯空白——既没说"没有书"，
也没有去处。补空态时，「这一屏下一步该去哪」这个问题的答案落在模块外：

- 书城 Tab 的路由 `"bookstore"` 定义在 `module_main` 的 `MainScreen` 里，是 NavHost 目的地，不是 TheRouter 路由；
- `IBookProvider` 原本只暴露无参的 `@Composable () -> Unit`，页面拿不到宿主的 `NavController`；
- 跨模块导航虽有 TheRouter，但它表达的是「跳到一个页面/服务」，**没有**「把已经在栈里的宿主切到另一个 Tab」这个原语。

## 决策

1. **能力随页面参数向下传，宿主在上层提供**。`mainBookPage` 多一个 `onGoBookstore: (() -> Unit)?` 形参，
   `MainScreen` 组合书架页时把「切到书城」的导航动作传进去。理由：Compose 官方对「子组件需要向上触发的行为」
   的推荐就是回调参数（`CompositionLocal` 留给主题这类**隐式**环境依赖）。宿主是唯一知道
   Tab 路由与切换选项（`popUpTo(start)` + `saveState` + `launchSingleTop` + `restoreState`）的地方。
2. **参数可空，缺省即降级**。`module_book` 独立运行时没有书城 Tab，调试宿主传 `null`，
   空态随之不渲染这个动作——不留一个点不动的按钮。这与仓里「跨模块能力在独立模式下缺失」的既有处置
   同一条口径（如缓存管理页的「回主页」入口在无该路由时退化为不可点）。
3. **Tab 切换收敛成一个入口**。底部导航与「书架空态 → 去书城」共用 `MainScreen` 里的同一个
   `switchTab(route)`。理由：那四个回退栈选项漏掉任何一个（尤其 `saveState`/`restoreState`），
   切回来就是一张全新页面，而这种缺陷不会报错、只在用户来回切时显形；两处各写一遍迟早不一致。

## 权衡

- **`CompositionLocal` 承载 Tab 切换**：不污染 provider 签名，但把「这个页面能切 Tab」变成隐式的环境依赖，
  且默认值只能是个"什么都不做"的实现——正是决策 2 要避免的静默失效形态。调用方也看不出页面依赖宿主提供什么。
- **加一条 TheRouter 路由，由 `MainActivity` 在自己的 `onNewIntent` 里切 Tab**：绕开了 provider 改动，
  但把「切 Tab」表达成一次页面跳转（且要处理 `singleTask` 下的复用与重复导航），
  还叠上路由表异步加载的问题。用跳转去表达进程内状态切换，代价与语义都不划算。
- **动作改成页内已有的入口「导入本地书」**：零接口改动（`ImportBookActivity` 本来就在书架页顶栏可达），
  但把用户的下一步从"去找书"改成"自备文件"，与本次空态要给出去处的意图相悖；且书城那条链路本身是通的
  （无书源时会落到书源引导态，不空转）。

## 下游影响

- `lib_book_common` 的 `IBookProvider`；`module_book` 的 `BookProvider`、`BookShelfPage`；`module_main` 的
  `MainScreen`；`module_book` 独立运行的调试宿主（`src/main/test/debug/MainActivity.kt`）——四处签名同步。
- 新增文案资源在 `module_book`（`shelf_empty_title` / `shelf_empty_hint` / `shelf_empty_action`）。
