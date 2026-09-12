package com.ebook.source.sandbox

/**
 * 沙箱里唯一的全局装配点：脚本看见的 `java.*`、`cookie`、`source`/`book`/`chapter` 三个对象面、顶层摘要别名、
 * 以及每次执行的绑定，全由这段文本决定。**能力清单的正面在这里，负面是「别处也不存在」**——
 * 没有 `cache`/`Java`/`org`/`okhttp3` 这些名字，脚本碰到的就是 `ReferenceError`。
 *
 * 三个对象面里只有数据过边界（绑定里的 JSON 文本），方法一律在 [BOOTSTRAP] 每次执行时重装——
 * 对象本身是每次执行新建的，方法挂在已丢弃的旧对象上等于没挂。
 *
 * 三段职责：
 * 1. [SOURCE] 在 runtime 创建时求值一次，装好 `java` 与纯计算别名。
 * 2. [BOOTSTRAP] 每次执行时前置在用户脚本之前，把 `__bindings` 落成全局量。
 *    分开是因为绑定是**每次执行**的量、垫片是 **runtime 级**的量；合在一起会让用户脚本的完成值
 *    变成绑定赋值的返回值（QuickJS 的完成值是最后一条语句的值）。
 * 3. `__UNSUPPORTED__:` 前缀是 `JsStatus.UNSUPPORTED_API` 的唯一来源——
 *    桥接层不需要懂白名单，只搬运一个前缀约定。
 *
 * 文本里刻意不出现美元符（Kotlin 原始字符串的模板起点）与三引号，最后一条有单测锁。
 */
internal object QuickJsPrelude {

    /** 每次执行前置的一行；`__bindings` 由桥接层在求值前挂到 globalThis */
    const val BOOTSTRAP: String = "__bind(__bindings);"

    val SOURCE: String = """
        // ==== 沙箱垫片：QuickJS 内唯一被信任的 JS，随包冻结、不接受书源覆盖 ====

        var __call = function (api, argList) {
          var reply = __host_call(api, JSON.stringify(argList));
          if (!reply || reply.ok !== true) {
            throw new Error(reply && reply.error ? reply.error : (api + ' 调用失败'));
          }
          return reply.data;
        };

        // 绑定：每次执行由 BOOTSTRAP 调一次。缺失与 null 都落成 undefined，
        // 让脚本里的 `typeof result` 判空真的生效（落成字符串 'null' 会让净化逻辑把 'null' 当正文）。
        var __bind = function (b) {
          var names = ['result', 'baseUrl', 'src', 'key', 'page', 'title'];
          var obj = b || {};
          for (var i = 0; i < names.length; i++) {
            var v = obj[names[i]];
            globalThis[names[i]] = (v === undefined || v === null) ? undefined : String(v);
          }
          var bk = obj.bookJson;
          var ch = obj.chapterJson;
          globalThis.book = (bk === undefined || bk === null) ? undefined : JSON.parse(bk);
          globalThis.chapter = (ch === undefined || ch === null) ? undefined : JSON.parse(ch);
          var sj = obj.sourceJson;
          globalThis.source = (sj === undefined || sj === null) ? undefined : JSON.parse(sj);
          // 行为面每次执行重装：三个对象都是刚刚才建出来的，方法挂在上一次的对象上等于没挂
          __installFaces(globalThis.source, globalThis.book, globalThis.chapter);
        };

        var java = {};

        // 白名单登记：名字必须出现在 JsHostApi 表里（JsHostApiTest 逐条比对）。
        // arity 校验在此侧做，理由见 JsHostApi 的 KDoc。
        // 挂在谁身上是另一件事：`def` 只管 `java.*`，`source` 与 `book`/`chapter` 的方法走 defOn，
        // 元数检查同一条——名字对上表里的 api、实参个数对上表里的元数，都由 JsHostApiTest 锁。
        var defOn = function (target, name, minArgs, maxArgs, fn) {
          // 目标缺席就什么都不装：`book`/`chapter`/`source` 在没有对应绑定的场景里是 `undefined`，
          // 脚本读到「没有这个东西」比在一个凭空造出来的空对象上取到一堆 undefined 诚实。
          if (!target) return;
          target[name] = function () {
            var n = arguments.length;
            if (n < minArgs || (maxArgs > 0 && n > maxArgs)) {
              throw new Error(name + ' 参数个数不符（需要 ' + minArgs + ' 到 ' + (maxArgs || minArgs) + ' 个）');
            }
            var a = [];
            for (var i = 0; i < n; i++) a.push(arguments[i]);
            return fn.apply(null, a);
          };
        };

        var def = function (name, minArgs, maxArgs, fn) { defOn(java, name, minArgs, maxArgs, fn); };

        // ==== 字节材料：JS 侧的「字节数组」就是一个带前缀标记的普通对象 ====
        // 边界（帧）上只有文本，所以「这是一串 base64 / 十六进制 / UTF-8 文本」必须由前缀表达，
        // 与 HostCompute.materialToBytes 是同一套约定。裸字符串一律原样过界——
        // 'https://x' 这种含冒号的文本前缀不认得，整串按 UTF-8 处理，不会被误当标记。
        var B = function (enc, text) { return { __bytes: enc, text: String(text) }; };
        var mat = function (v) {
          if (v === null || v === undefined) return '';
          if (typeof v === 'object' && v.__bytes) return v.__bytes + ':' + v.text;
          return String(v);
        };
        // 宿主把「产物是字节」的调用统一回 "base64:…"，就地重新包成令牌，好让它接着喂给下一次调用
        var matOut = function (s) {
          var text = String(s);
          var i = text.indexOf(':');
          var head = i > 0 ? text.substring(0, i) : '';
          return (head === 'base64' || head === 'hex') ? B(head, text.substring(i + 1)) : text;
        };

        // —— 纯计算：顶层别名与 java. 两个入口并存（语料两种写法都有），指向同一实现 ——
        var computeNames = ['md5', 'sha1', 'sha256', 'base64Encode', 'base64Decode',
          'base64EncodeUrl', 'base64DecodeUrl', 'hexEncode', 'hexDecode',
          'aesEncode', 'aesDecode', 'desEncode', 'desDecode', 'timestamp', 'FormatDate',
          'uriEncode', 'randomUUID', 'digestHex', 'hmacBase64', 'symmetricCrypto', 'bytesToStr'];
        computeNames.forEach(function (n) {
          def(n, 0, 0, function () {
            return __call(n, Array.prototype.slice.call(arguments).map(mat));
          });
          globalThis[n] = java[n];
        });

        // ==== 上游 `java.*` 的改名族：脚本侧沿用游客体的名字，表里只有一个 api ====
        // 一律经数组装配而不是 def('字面量'：JsHostApiTest 的字面量元数比对只认「纯转发到 HOST」那一族，
        // 而这里的名字与表里的 api 不同名（md5Encode ↔ md5），按字面量登记会让每条改名报「表里查无此名」。
        // 第三项是缺席时的默认实参（只有 FormatDate 用得上：上游 timeFormat(millis) 自带格式）。
        // longToast → toast：长短的差别只在弹多久的 UI，而本仓这一族一律映射成日志。
        // 语料实测（build/survey-longtoast-arity.js + survey-longtoast-per-source2.js）：两池全量 248 次调用、
        // 按 bookSourceUrl 去重 34 条源（去重后 144 次），但**按承载字段拆开才是真收益**：196 次写在
        // loginUrl(164) / jsLib(20) / loginCheckJs(6) / ruleContent.payAction(6) 这四类本仓根本不执行的字段里，
        // 落在会求值的规则上的是 52 次 / 16 条源（bookSourceComment 里 0 次——那字段是自定义 UI 脚本，本仓不执行）。
        // 一律 1 参，故表里 TOAST 的元数不必放宽；裸 `longToast(` 零命中，只需挂 java 面。
        // 缺这个名字的症状不是崩，是「这条源解不开」——TypeError 落在 @js: 段里等于整段求值失败。
        var javaAliases = [
          ['md5Encode', 'md5'], ['hexDecodeToString', 'hexDecode'], ['HMacBase64', 'hmacBase64'],
          ['encodeURI', 'uriEncode'], ['getString', 'queryString'], ['longToast', 'toast'],
          ['timeFormat', 'FormatDate', 'yyyy-MM-dd HH:mm:ss']];
        javaAliases.forEach(function (p) {
          defOn(java, p[0], 0, 0, function () {
            var a = Array.prototype.slice.call(arguments).map(mat);
            if (p[2] !== undefined && a.length < 2) a[1] = p[2];
            return __call(p[1], a);
          });
        });
        // encodeURI 只挂 java 面：顶层 encodeURI 是 ECMA 内建（空格成 %20），
        // computeNames 那一族的 globalThis 落点会把它静默换成 URLEncoder（空格成 +）。

        // ==== 门面族：脚本侧名字在表里没有对应项，它把一次 api 调用包成对象或一步调用 ====
        var symmetricApi = {
          decryptStr: function (d) { return callSym('decryptStr', this, d); },
          decrypt: function (d) { return matOut(callSym('decrypt', this, d)); },
          encrypt: function (d) { return matOut(callSym('encrypt', this, d)); },
          encryptBase64: function (d) { return callSym('encryptBase64', this, d); },
          encryptHex: function (d) { return callSym('encryptHex', this, d); }
        };
        var callSym = function (op, c, d) {
          return __call('symmetricCrypto', [op, String(c.transformation), mat(c.key), mat(c.iv), mat(d)]);
        };
        var faceApi = {
          createSymmetricCrypto: function (trans, key, iv) {
            var c = { transformation: trans, key: key, iv: iv === undefined ? '' : iv };
            for (var m in symmetricApi) c[m] = symmetricApi[m];
            return c;
          },
          aesBase64DecodeToString: function (data, key, trans, iv) {
            return faceApi.createSymmetricCrypto(trans, key, iv).decryptStr(B('base64', data));
          },
          // 这三条在上游返回真字节数组，在这里只打标记、零跨进程往返：
          // 解码动作发生在真正要用它的那次计算里（HostCompute 按前缀解）
          base64DecodeToByteArray: function (t) { return B('base64', t); },
          hexDecodeToByteArray: function (t) { return B('hex', t); },
          strToBytes: function (t) { return B('utf8', t); }
        };
        Object.keys(faceApi).forEach(function (n) { java[n] = faceApi[n]; });

        // —— 网络：全部经主进程的受限代理（scheme / 私网 / 白名单 / 大小 / 限流都在那一侧判）——
        def('ajax', 1, 2, function (u, c) { return __call('ajax', c === undefined ? [u] : [u, c]); });
        def('load', 1, 2, function (u, c) { return __call('load', c === undefined ? [u] : [u, c]); });
        def('post', 2, 3, function (u, body, h) {
          return __call('post', h === undefined ? [u, body] : [u, body, h]);
        });
        def('responseCode', 1, 1, function (u) { return __call('responseCode', [u]); });

        // —— 变量（规格 §5.2：JS 内用 java.put / java.get；`@get:` 在 JS 里不可用）——
        // 注意 java.get 只有「读变量」一义，不重载成网络取页（偏差 2）。
        def('put', 2, 2, function (k, v) { __call('putVar', [String(k), String(v)]); });
        def('get', 1, 1, function (k) { return __call('getVar', [String(k)]); });
        def('rmKey', 1, 1, function (k) { __call('rmVar', [String(k)]); });

        // —— 递归规则求值：脚本把规则串交回解释器，输入是当前页面 ——
        def('getElements', 1, 1, function (r) { return __call('getElements', [r]); });
        def('getElement', 1, 1, function (r) { return __call('getElement', [r]); });
        def('queryString', 1, 1, function (r) { return __call('queryString', [r]); });
        def('putToPage', 2, 2, function (k, v) { __call('putToPage', [String(k), String(v)]); });
        // 改写后续规则求值的基准页（语料 31 处全是「先 ajax 取一页、再让 getElements 定位那一页」）
        def('setContent', 1, 2, function (c, u) {
          return __call('setContent', u === undefined ? [String(c)] : [String(c), String(u)]);
        });

        // —— 日志：toast 一族映射成日志，不弹 UI ——
        def('toast', 1, 1, function (m) { __call('toast', [String(m)]); });
        def('log', 1, 1, function (m) { __call('log', [String(m)]); });

        // —— 明确不支持的能力：给一句可诊断的话，而不是让它撞进 ReferenceError ——
        var reject = function (name) {
          return function () { throw new Error('__UNSUPPORTED__:' + name + ' 属本项目不支持的能力（登录/浏览器/持久层/批量外呼一族）'); };
        };
        ['connect', 'getConnect', 'response', 'req', 'assets', 'files', 'Files', 'bookSource', 'cookieManager', 'ajaxAll']
          .forEach(function (n) { def(n, 0, 0, reject(n)); });

        // ==== 书源对象 `source`：数据在绑定里，行为挂在这里（每次执行装一次） ====
        // `key` 与 `getKey()` 在上游都是 `bookSourceUrl` 的别名（语料三者合计 482 次），
        // 所以这里不另存一份值，只把名字指回同一个字段。
        var sourceApi = { getKey: function () { return this.bookSourceUrl; } };

        // 变量族转发器：实参一律按文本送过边界。不 String() 的话 `source.put('k', 2)` 会把数字
        // 原样带进 `Map<String, String>` 的那张表，`@get:k` 再取回时就多了一种「看着像 2 的形态」。
        var fwdVar = function (api) {
          return function () {
            var a = [];
            for (var i = 0; i < arguments.length; i++) a.push(String(arguments[i]));
            return __call(api, a);
          };
        };

        // ==== 会话 cookie：脚本侧的 `cookie` 对象面（一源一份，落在主进程的 SourceCookieJar） ====
        // 装配刻意排在 fwdVar **之后**：`var fwdVar = function ...` 提升的只是变量名，
        // 放前面的话这几行会在垫片求值期就撞上「fwdVar 不是函数」，整个垫片一起废掉。
        // 名字与表里的 api 不同名（getCookie ↔ cookieGet），所以走 defOn 转发行：
        // JsHostApiTest 按行比对「转发的 api 在表里、元数与表同值」。
        // 三条都不发外呼，脚本可以在取页配额用满之后照样清会话重来（语料里真有这条自救路径）。
        var cookie = {};
        defOn(cookie, 'getCookie', 1, 1, fwdVar('cookieGet'));
        defOn(cookie, 'setCookie', 2, 2, fwdVar('cookieSet'));
        defOn(cookie, 'removeCookie', 1, 1, fwdVar('cookieRemove'));
        // 上游同一族能力另有 `java.getCookie/setCookie/removeCookie` 的写法（语料里以作者自述可用
        // 清单的形式出现过），装在这里是为了让抄来的脚本拿到值而不是 TypeError。
        defOn(java, 'getCookie', 1, 1, fwdVar('cookieGet'));
        defOn(java, 'setCookie', 2, 2, fwdVar('cookieSet'));
        defOn(java, 'removeCookie', 1, 1, fwdVar('cookieRemove'));

        // 拒绝桩名单：登录态与「刷新/批量」一族宿主回调（语料 238 次调用，`source` 一项被 248/1168
        // 条源引用；本仓按 ADR-0028 决策 6 不做）。给一句话而不是让它们撞进 TypeError，是为了让
        // 「这条源要登录」在错误里就看得见，而不是表现成正文莫名缺失。
        var sourceReject = ['getLoginInfoMap', 'getLoginInfo', 'putLoginInfo', 'putLoginInfoMap', 'removeLoginInfo',
          'getLoginHeader', 'getLoginHeaderMap', 'putLoginHeader', 'removeLoginHeader',
          'refreshExplore', 'refreshJSLib', 'putConcurrent'];

        // 变量面装配。**一条一行，形状不许改**：JsHostApiTest 按行比对「转发的 api 在能力表里、
        // 元数与表同值、目标就是 source / book / chapter 这三个名字」。
        // 上游的三套持久变量（书源 / book / chapter）在这里全部落在主进程同一张任务变量表上，
        // 唯一例外是 `source.getVariable`：它要的是整串（零参），走 sourceGetVariable。
        var __installFaces = function (source, book, chapter) {
          if (source) {
            source.key = source.bookSourceUrl;
            for (var an in sourceApi) source[an] = sourceApi[an];
          }
          defOn(source, 'put', 2, 2, fwdVar('putVar'));
          defOn(source, 'get', 1, 1, fwdVar('getVar'));
          defOn(source, 'getVariable', 0, 0, fwdVar('sourceGetVariable'));
          defOn(source, 'setVariable', 1, 1, fwdVar('sourceSetVariable'));
          defOn(source, 'putVariable', 1, 1, fwdVar('sourceSetVariable'));
          defOn(book, 'getVariable', 1, 1, fwdVar('getVar'));
          defOn(book, 'putVariable', 2, 2, fwdVar('putVar'));
          defOn(chapter, 'getVariable', 1, 1, fwdVar('getVar'));
          defOn(chapter, 'putVariable', 2, 2, fwdVar('putVar'));
          for (var i = 0; i < sourceReject.length; i++) defOn(source, sourceReject[i], 0, 0, reject(sourceReject[i]));
        };
    """.trimIndent()
}
