# Bionic 动态链接器：命名空间隔离与符号解析

> 学习资料（文章模式沉淀，证据等级：二手）。机制按 AAOS13（Android 13）本地源码核对并逐题标注，不在本地树的组件按源材料（Android 17 锚点）转写并标注版本差异。主线：Android 动态链接器为什么需要命名空间隔离、命名空间的可达性判定为何有"路径"与"符号"两套语义、`dlopen` 的三段查找流程，以及 `ld.config` 运行时配置如何把 Treble 的隔离策略落地。本册结论未逐条核对本地 AOSP 源码，字段与枚举以官方文档与 AOSP 源码为准；版本差异已标注。已有 01 册的 Binder 与 JNI 册覆盖的是框架层调用与 JNI 桥接，本册是其加载底座，不重复。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Android 动态链接器为什么需要"命名空间"这种机制，glibc 的同名机制解决了什么不同的问题？**

glibc 的命名空间解决的是**同一进程内不同环境的库版本隔离**（典型如 Firefox 内嵌自己的 libc），Android 的命名空间解决的是**系统与供应商两侧同名库的符号冲突**，以及**构建期不可见的运行时依赖**这两个具体难题。

官方文档明确列出要解决的两点。其一是 SP-HAL 共享库及其依赖项（包括 VNDK-SP 库）会加载进框架进程，需要机制防止符号冲突；其二是 `dlopen()` 与 `android_dlopen_ext()` 可能引入构建时不可见、静态分析很难检测的运行时依赖。命名空间机制通过**隔离不同命名空间中的共享库**，让"库名相同但符号不同"的库互不干扰。

```text
Android 8.0 起：框架进程的 VNDK Lite 配置
default   search.paths = /system/${LIB} /odm/${LIB} /vendor/${LIB} /product/${LIB}
          isolated = false
sphal     search.paths = /odm/${LIB} /vendor/${LIB}
          permitted.paths = /odm/${LIB} /vendor/${LIB}
          isolated = true, visible = true
          links = default,vndk,rs
          link.default.shared_libs = LL-NDK
          link.vndk.shared_libs  = VNDK-SP
          link.rs.shared_libs    = libRS_internal.so
vndk      search.paths = /odm/${LIB}/vndk-sp /vendor/${LIB}/vndk-sp /system/${LIB}/vndk-sp-${VER}
          isolated = true, visible = true
          links = default
          link.default.shared_libs = LL-NDK
rs        links = default,vndk
          link.default.shared_libs = LL-NDK, libmediandk.so, libft2.so
```

`default` 不隔离、`sphal`/`vndk`/`rs` 隔离并各自声明 `links` 与 `shared_libs`，这套声明就是 Treble 隔离策略的表达形式。判断规则：看到"库明明在设备上，dlopen 却报找不到"时，第一反应不该是文件不存在，而应确认它是否在调用方命名空间的 `search.paths` 或 `permitted.paths` 覆盖范围内——命名空间隔离下"存在"与"可达"是两件事。

**Q2: [learning] 命名空间的四种类型与两个附加标志分别是什么？**

四种类型是 `REGULAR`、`ISOLATED`、`SHARED`、`SHARED_ISOLATED`，两个附加标志是 `ANDROID_NAMESPACE_TYPE_EXEMPT_LIST_ENABLED` 与 `ANDROID_NAMESPACE_TYPE_ALSO_USED_AS_ANONYMOUS`。

```text
// bionic/linker/linker.h
enum {
    ANDROID_NAMESPACE_TYPE_REGULAR = 0,
    ANDROID_NAMESPACE_TYPE_ISOLATED = 1,
    ANDROID_NAMESPACE_TYPE_SHARED = 2,
    ANDROID_NAMESPACE_TYPE_EXEMPT_LIST_ENABLED = 0x08000000,
    ANDROID_NAMESPACE_TYPE_ALSO_USED_AS_ANONYMOUS = 0x10000000,
    ANDROID_NAMESPACE_TYPE_SHARED_ISOLATED = ANDROID_NAMESPACE_TYPE_SHARED
                                          | ANDROID_NAMESPACE_TYPE_ISOLATED,
};
```

四类语义：

- **REGULAR**：自定义搜索路径，不对原生库位置施加任何限制。
- **ISOLATED**：所有库必须在搜索路径上或 `permitted_when_isolated_path` 之下；搜索路径是 `ld_library_path` 与 `default_library_path` 的并集。
- **SHARED**：创建时克隆调用方命名空间的库列表，命名空间间**共用同一份库副本**——注意只共享创建**之前**已加载的库，之后加载的不共享。它不继承调用方的搜索路径与 `permitted_path`。
- **SHARED_ISOLATED**：上面两者的组合。

两个标志里，`ALSO_USED_AS_ANONYMOUS` 值得注意：**一个进程内只能有一个匿名命名空间**，已有匿名命名空间时再带此标志创建会直接失败。判断规则：看到 `android_create_namespace` 返回失败且错误信息与 anonymous 相关，先确认进程内是否已存在匿名命名空间，而不是反复重试。

**Q3: [learning] soinfo 是什么，加载一个 so 时它记录了哪些关键字段？**

`soinfo` 是链接器为每个已加载 so 维护的运行时描述结构，包含加载基址、动态段、依赖关系、命名空间归属与加载标志，是链接器所有决策的数据源。

```cpp
// bionic/linker/linker.cpp，load_library 阶段解析的关键字段
for (const ElfW(Dyn)* d = elf_reader.dynamic(); d->d_tag != DT_NULL; ++d) {
    if (d->d_tag == DT_RUNPATH) {
        si->set_dt_runpath(elf_reader.get_string(d->d_un.d_val));
    }
    if (d->d_tag == DT_SONAME) {
        si->set_soname(elf_reader.get_string(d->d_un.d_val));
    }
    /* 需要尽早识别 DF_1_GLOBAL 库，才能把它链进命名空间 */
    if (d->d_tag == DT_FLAGS_1) {
        si->set_dt_flags_1(d->d_un.d_val);
    }
}
```

其中 `DT_FLAGS_1` 的识别时机会影响 `global_group` 的构成——`get_global_group()` 是按 `DF_1_GLOBAL` 过滤的，所以这个字段必须在建立命名空间归属之前填好。`soinfo` 还持有 `primary_namespace_` 与 `secondary_namespaces` 两个字段，`get_caller_namespace()` 正是用调用者 so 的 `primary_namespace_` 反查命名空间。判断规则：分析"符号为什么没解析到"时，顺序应是先看目标 so 的 `soname` 是否与请求名一致（`DT_NEEDED` 记录的是 soname 而非文件名），再看它的 `DF_1_GLOBAL` 与 `primary_namespace_` 是否符合预期——很多"链接进去了但符号不可见"就是 soname 不匹配导致 `find_loaded_library_by_soname` 找不到已有副本，重复加载了同名不同内容的第二份。

**Q4: [learning] is_accessible 为什么有两个重载，路径可达与符号可见为什么必须分开判定？**

因为它们回答的是两个不同问题：路径重载回答"这个**文件**允许被本命名空间加载吗"，符号重载回答"这个**已加载库**的符号允许被本命名空间的库查找吗"。混用会导致两种错误——本该拒绝的被放行，或本该可用的被误拒。

```cpp
// bionic/linker/linker_namespaces.cpp，路径版本
bool android_namespace_t::is_accessible(const std::string& file) {
    if (!is_isolated_) {
        return true;
    }
    // 隔离命名空间下继续按 permitted_when_isolated_path 判定
}
```

```cpp
// bionic/linker/linker_namespaces.cpp，符号版本（要点）
bool android_namespace_t::is_accessible(soinfo* s) {
    auto is_accessible_ftor = [this](soinfo* si, bool allow_secondary) {
        if (!si->is_lp64_or_has_min_version(3)) {
            DL_WARN("Warning: invalid soinfo version for \"%s\" "
                    "(assuming inaccessible)", si->get_soname());
            return false;
        }
        if (si->get_primary_namespace() == this) {
            return true;
        }
        if (allow_secondary) {
            const android_namespace_list_t& secondary = si->get_secondary_namespaces();
            if (secondary.contains(this)) {
                return true;
            }
        }
        return false;
    };
    if (is_accessible_ftor(s, true)) {
        return true;
    }
    return !s->get_parents().visit([&](soinfo* si) {
        return !is_accessible_ftor(si, false);
    });
}
```

符号版本的语义要点是：**本命名空间内的库，其符号可见；但只可见"直接依赖"的库，不递归传递**。官方注释写得很直白——假设 `libapp.so -> libandroid.so` 跨了命名空间边界，则应搜索 `libandroid.so`，但不搜索 `libandroid.so` 自己的依赖。`allow_secondary` 参数控制的正是"次要命名空间成员"这一例外：主可执行文件、`LD_PRELOAD` 库与 `DF_1_GLOBAL` 库会作为次要成员进入命名空间，它们的符号可见，但其依赖不可见。判断规则：库文件可加载 ≠ 它的依赖都可解析——链接期通过但运行期 `undefined symbol` 的问题，根因通常是这条"依赖不传递"的规则被忽略。

**Q5: [learning] dlopen 加载一个库的查找流程分哪几步，为什么"用绝对路径"能过第一道却过不了第二道？**

分三步：①在调用方命名空间及其链接命名空间里查"是否已加载"；②尝试在调用方命名空间内真正加载；③遍历调用方命名空间链接的所有命名空间再找。绝对路径只是在第①步被直接跳过——因为绝对路径的库必然"未加载"，没有可查的 soinfo——但第②步的 `is_accessible` 权限检查仍然会拦住它。

```text
find_loaded_library_by_soname(ns, name, ...)
    全路径 → 直接 false（"未加载"判断对绝对路径无意义）
    否则在 caller 命名空间的 soinfo 列表找 → 找到即返回
    未找到则遍历所有 linked 命名空间，先 is_accessible 权限检查再找

load_library(ns, name, ...)
    extinfo != NULL（来自 Java System.loadLibrary）→ 该 so 已加载，走重载路径
    extinfo == NULL（native 层直接 dlopen）→ open_library
        绝对路径 → 直接 open 文件
        否则 → 依次试 ns->get_ld_library_paths()、ns->dt_runpath()
    open_library 之后统一做 is_accessible(path) 权限检查

find_library_in_linked_namespace(ns_link, name, ...)
    绝对路径 → 跳过"已加载"查找，但仍做 is_accessible 权限检查
    注意：不递归 ns_link 自己的 linked 命名空间（链接关系不具传递性）
```

第三步"链接关系不传递"是最容易踩的一条：A 链接 B、B 链接 C，C 里的库对 A 不可见。官方注释在描述 namespace 链接时用的是"链接的链接器命名空间"这一措辞，而查找实现里 `find_library_in_linked_namespace` 只在传入的那一个 `ns_link` 上查找。判断规则：`dlopen` 失败日志里若同时出现"已找到文件"和"权限不足"，那就是第②步的 `is_accessible` 拦住了——此时把绝对路径换成相对路径、改搜索路径、加 `permitted.paths` 都无效，唯一的解法是把该库声明为本命名空间的 `shared_libs` 或修正 `links` 关系。

**Q6: [learning] dlopen_ext 与 dlopen 在实现上差在哪，为什么这个差别会导致"库已加载"与"需要加载"两种路径？**

`dlopen` 是 `dlopen_ext` 的特化：前者的 `extinfo` 恒为 `nullptr`，后者可携带 `android_dlextinfo`。这个差别决定了加载流程走"已加载重载"还是"先 open 再加载"。

```cpp
// bionic/linker/dlfcn.cpp
void* __loader_dlopen(const char* filename, int flags, const void* caller_addr) {
    return dlopen_ext(filename, flags, nullptr, caller_addr);
}

void* __loader_android_dlopen_ext(const char* filename, int flags,
        const android_dlextinfo* extinfo, const void* caller_addr) {
    return dlopen_ext(filename, flags, extinfo, caller_addr);
}
```

差别体现在 `load_library` 的第一个分支上：只有 Java 层 `System.loadLibrary` 触发的加载才携带 `extinfo`，此时 so 文件已经由 ClassLoader 加载进内存，所以走重载路径直接复用；native 层直接调 `dlopen`/`android_dlopen_ext` 时 `extinfo` 为空，必须先 `open_library` 真正打开文件。`do_dlopen` 在 `extinfo != NULL` 时同样直接尝试 `load_library` 重载。判断规则：从 Java 侧 `System.loadLibrary` 成功但随后 `dlopen` 同一库失败，说明两者落在了不同的命名空间——前者是 ClassLoader 映射的命名空间，后者是调用方 so 所属的命名空间；这类"能加载但找不到"的典型根因就是调用方 so 与 Java 代码不在同一命名空间。

**Q7: [learning] 命名空间之间是怎么建立"共享库"关系的，shared_libs 与 allow_all_shared_libs 有何区别？**

命名空间之间通过 `links` 建立单向回退关系、通过 `link.<ns>.shared_libs` 声明哪些库允许跨边界被找到。`allow_all_shared_libs` 是更宽松的开关，一旦为真则该命名空间的所有库都可被链接方访问，不再逐个比对共享库清单。

```cpp
// bionic/linker/linker_namespaces.cpp，全局组与共享组
soinfo_list_t android_namespace_t::get_global_group() {
    soinfo_list_t global_group;
    soinfo_list().for_each([&](soinfo* si) {
        if ((si->get_dt_flags_1() & DF_1_GLOBAL) != 0) {
            global_group.push_back(si);
        }
    });
    return global_group;
}

soinfo_list_t android_namespace_t::get_shared_group() {
    if (this == &g_default_namespace) {
        return get_global_group();
    }
    soinfo_list_t shared_group;
    soinfo_list().for_each([&](soinfo* si) {
        if ((si->get_rtld_flags() & RTLD_GLOBAL) != 0) {
            shared_group.push_back(si);
        }
    });
    return shared_group;
}
```

全局组由 `DF_1_GLOBAL` 标记构成（主可执行文件与 `DT_NEEDED` 库天然带此标记），共享组由 `RTLD_GLOBAL` 加载标志构成。命名空间对以 `SHARED` 类型创建时会**克隆**调用方的库列表，因此创建时刻之前已加载的库在两个命名空间里是**同一份副本**，这正是 `SHARED` 的用途。边界：克隆只发生在创建时刻，之后新加载的库不会自动出现在另一个命名空间。判断规则：`SHARED` 命名空间省内存但只对"创建前已加载"的库有效，期望后续新增的库也自动共享是错误的预期——这种需求应该用 `links` + `shared_libs` 声明式表达，而不是靠克隆。

**Q8: [learning] dlsym 的查找有两条路径吗，RTLD_DEFAULT 与 RTLD_NEXT 的差别是什么？**

有两条：`dlsym_linear_lookup` 沿命名空间的 `soinfo` 全局列表线性查找，用于 `RTLD_DEFAULT`；`dlsym_handle_lookup_impl` 只在指定 so 及其依赖里广度优先查找，用于按 handle 查找与 `RTLD_NEXT`。

```cpp
// bionic/linker/linker.cpp
static const ElfW(Sym)* dlsym_linear_lookup(android_namespace_t* ns,
        const char* name, const version_info* vi,
        soinfo** found, soinfo* caller, void* handle) {
    SymbolName symbol_name(name);
    auto& soinfo_list = ns->soinfo_list();
    auto start = soinfo_list.begin();
    if (handle == RTLD_NEXT) {
        if (caller == nullptr) {
            // 找不到调用者，直接失败
            return nullptr;
        } else {
            auto it = soinfo_list.find(caller);
            CHECK(it != soinfo_list.end());
            start = ++it;          // 从调用者之后开始
        }
    }
    // RTLD_DEFAULT 从列表头开始
}
```

线性查找里有两条跳过规则值得记住：非 `RTLD_GLOBAL` 的库会被跳过，但注释明确说明不跳过 `RTLD_LOCAL` 库——`RTLD_LOCAL` 库也要参与 `dlsym(RTLD_DEFAULT, ...)`，这是为兼容 target SDK 低于 23 的应用（代码里以 `get_target_sdk_version() >= 23` 判定）。另外线性查找未命中时会回落到 `dlsym_handle_lookup_impl` 去查调用者的 `local_group_root`。按 handle 查找时，若 handle 是主可执行文件，会直接转成对 `RTLD_DEFAULT` 的线性查找——因为主可执行文件与所有 `DT_NEEDED` 库都带 `RTLD_GLOBAL` 且按广度优先顺序加载，一次线性查找就够。判断规则：`dlsym` 返回 null 但符号确实存在于某个已加载库里时，先确认查找起点——`RTLD_NEXT` 从调用者**之后**开始，所以它看不到调用者自己定义的同名符号，这是设计而非缺陷。

**Q9: [learning] ld.config 是怎么产生的，设备上的哪个文件是真正生效的那份？**

Android 11 起链接器配置在**启动时按运行时环境生成**，落到 `/linkerconfig` 下，而不是使用源码树里的纯文本文件。生成逻辑是解析命名空间之间的依赖关系——例如某个 APEX 模块上出现依赖项更新，配置就会随之重新生成。

```text
配置文件名对应不同隔离等级
ld.config.legacy.txt          Android 7.x 及更早设备
ld.config.txt                 有运行时命名空间隔离的设备
ld.config.vndk_lite.txt       有 VNDK-SP 命名空间隔离的设备
```

VNDK 配置与 VNDK Lite 配置的区别在于隔离彻底程度：VNDK 配置创建 `default`、`vndk`、`sphal`、`rs` 四个命名空间且全部隔离，确保 `system` 分区的模块不依赖 `vendor` 分区的库，反之亦然；供应商进程侧则是 `default`（隔离，装供应商库）、`vndk`（装 VNDK 与 VNDK-SP）、`system`（装 LL-NDK 及其依赖）三个命名空间。Android 8.1 起 VNDK 配置是默认配置，官方建议把 `BOARD_VNDK_VERSION` 设为 `current` 以启用完整隔离。Android 9 的变化是给供应商进程也加入了 `vndk` 命名空间、把 VNDK 库与默认命名空间隔离，并把 `PRODUCT_FULL_TREBLE` 换成更具体的 `PRODUCT_TREBLE_LINKER_NAMESPACES`，同时新增 `product` 与 `odm` 分区。判断规则：排查命名空间问题时，第一步是读设备上实际的 `ld.config`（在 `/system/etc/linkerconfig/` 一带），而不是源码树里的模板——两者可能因设备配置而不同，源码树里的那份不代表设备实际加载的隔离策略。

**Q10: [learning] 动态链接器自己是怎么被加载并完成自举的，AT_BASE 起到了什么作用？**

内核把链接器自身当作一个"由内核加载的可执行文件"映射进来，链接器必须先把自己链接好才能执行任何代码。`AT_BASE` 是内核在 `auxv` 里给出的链接器自身基址，链接器据此算出自己的 load bias 并完成 `prelink_image` 与 `link_image`。

```cpp
// bionic/linker/linker_main.cpp，load bias 的算法
static ElfW(Addr) get_elf_exec_load_bias(const ElfW(Ehdr)* elf) {
    const ElfW(Phdr)* phdr_table =
        reinterpret_cast<const ElfW(Phdr)>(
                reinterpret_cast<uintptr_t>(elf) + elf->e_phoff);
    for (const ElfW(Phdr)* phdr = phdr_table; phdr < phdr_table + elf->e_phnum; ++phdr) {
        if (phdr->p_type == PT_LOAD) {
            return reinterpret_cast<uintptr_t>(elf) + phdr->p_offset - phdr->p_v_addr;
        }
    }
    return 0;
}
```

```cpp
// bionic/linker/linker_main.cpp，linker 自链接的关键调用
soinfo linker_so(nullptr, nullptr, nullptr, 0, 0);
linker_so.base  = linker_addr;
linker_so.size  = phdr_table_get_load_size(elf_hdr, elf_hdr->e_phnum);
linker_so.load_bias = get_elf_exec_load_bias(elf_hdr);
linker_so.set_linker_flag();
linker_so.prelink_image();
/* 用 g_empty_list 而非 local_group：linker 以 DT_SYMBOLIC 构建，
   符号相对自身解析，不需查 local_group；且此时分配器还没初始化，
   不能调用 linked_list.push_* 之类需要分配内存的函数 */
linker_so.link_image(g_empty_list, g_empty_list, nullptr);
```

自举的顺序是：读 `auxv` 拿到 `AT_PHDR`/`AT_BASE` → 算 load bias → `prelink_image` 解析自己的动态段 → `link_image` 完成自重定位 → 才把 `libdl_info` 这个"每个进程都免费获得"的 `soinfo` 挂到全局链表。`get_libdl_info()` 里有一句硬校验 `CHECK((linker_si.flags_ & FLAG_GNU_HASH) != 0)`——链接器自身必须带 GNU hash。判断规则：链接器自举失败的表现是进程在 `linker_main` 阶段就 `abort`，`logcat` 里通常只有一句 "CANNOT LINK"，没有任何 Java 层栈；这类问题几乎总是 so 损坏或 ABI 槽位不匹配，而不是业务代码问题。

**Q11: [learning] 库搜索的优先级顺序是怎样的，LD_LIBRARY_PATH 排第几？**

`LD_LIBRARY_PATH` 优先级最高，且在命名空间路径列表里搜到的库**不再做可达性检查**——因为能出现在命名空间路径列表上的东西本来就应当可达。之后依次是 `DT_RUNPATH` 与默认库路径。

```cpp
// bionic/linker/linker.cpp，open_library()
static int open_library(android_namespace_t* ns, const char* name, ...) {
    /* LD_LIBRARY_PATH 优先级最高；搜命名空间路径列表时无需再检查可达性，
       因为出现在该列表上的库本来就应当是允许的 */
    int fd = open_library_on_paths(zip_archive_cache, name, file_offset,
                                    ns->get_ld_library_paths(), realpath);
    // 随后尝试 DT_RUNPATH 对应的路径
}
```

```cpp
// bionic/linker/linker_main.cpp，环境变量只在非 setuid/setgid 时生效
parse_LD_LIBRARY_PATH(ldpath_env);
parse_LD_PRELOAD(ldpreload_env);
```

`LD_PRELOAD` 的解析同时兼容空格与冒号两种分隔符（`Split(path, " :")`），这是历史上的兼容行为。判断规则：设置 `LD_LIBRARY_PATH` 绕过隔离在部分场景下确实有效，但代价是该路径下的库不再受命名空间可达性约束——用调试环境变量"修好"的加载问题，量产后会变成难查的符号冲突，正确做法是把库声明进对应命名空间。

**Q12: [learning] public.libraries.txt 与命名空间的 shared_libs 是什么关系，APK 能加载的库由什么决定？**

APK 能 `dlopen` 哪些库由它所在命名空间的 `shared_libs` 清单决定，而该清单的来源是平台的 `public.libraries.txt` 等声明。`libc.so` 之所以任何 app 都能加载，是因为它在 `public.libraries.txt` 中被声明为共享库，会被填入各命名空间的 `shared_libs`；而 `libart.so` 这类内部库不在其中，因此即便文件存在于 `/apex/...`，app 也无权加载。

```text
dlopen("libc.so")  → 成功
   第①步 find_loaded_library_by_soname：libc.so 在 public.libraries.txt 中声明为共享，
             权限检查通过，在 caller 命名空间链接的 default 命名空间里找到已加载的 soinfo

dlopen("/apex/com.android.runtime/lib/libart.so") → 失败
   第①步 绝对路径直接跳过（无意义）
   第②步 open_library 因绝对路径直接打开文件，随后 is_accessible 未通过、
             也不在 greylist 中 → 无权限
   第③步 链接命名空间的 is_accessible 同样未通过
             （即使 libart.so 在 runtime 命名空间的已加载列表里也不行，
              因为 caller → runtime 的 links 里没把 libart.so 声明为 shared_lib）
```

最后那句括号里的说明是整套机制的核心：**"文件在某个命名空间里已加载"不等于"本命名空间有权加载它"**，还得看链接方向上的 `shared_libs` 声明。判断规则：把平台内部库从绝对路径改成 soname 调用不会改变结果——`find_loaded_library_by_soname` 对绝对路径直接返回 false，正是为了让"用绝对路径绕过 soname 匹配"这条路也必须过 `is_accessible`；真正的解法只有两条：走系统公开接口，或在自己进程里创建 `REGULAR` 类型的自定义命名空间（需满足 `visible` 与链接关系）。

**Q13: [learning] greylist 在命名空间可达性判定中处于什么位置，它现在还有效吗？**

`greylist` 曾是 `is_accessible` 未通过时的一条例外通道：不在允许范围内但在灰名单里的库仍可加载。**在当前 AOSP 中这条路径已经失效**——中文社区的源码分析明确记录"权限检查失败后判断是否在 greylist 灰名单中，**（现在已经失效）**"。

```text
is_accessible 失败
  └─ is_greylisted 命中 → soinfo_alloc 正常分配（历史行为，现已失效）
  └─ 未命中           → 打印错误日志，无权限
```

这意味着"某库历史上是灰名单库所以能加载"这类经验在今天的 AOSP 上不再成立。判断规则：遇到"这个库以前能用现在不能用"的加载失败，不要去找灰名单配置（该机制已移除），应改为核对当前命名空间的 `shared_libs` 与 `permitted.paths` 声明——把历史经验当现状是这类问题最常见的误判来源。

**Q14: [learning] 链接器层面能观测到什么，排查"库加载失败"时应该按什么顺序收集信息？**

按"失败发生在哪一步"收集：文件是否存在、命名空间是否覆盖、共享库是否声明、是否命中 greylist（已失效）、是否为 SELinux 拒绝。每一步的日志特征不同。

```text
第①步 soname 已加载查找失败  → 日志无明显输出，属正常路径
第②步 open_library 失败      → "cannot locate library" 类信息，文件不在搜索路径
第②步 is_accessible 失败     → 明确的无权限日志，文件找到了但命名空间不覆盖
第③步 链接命名空间也失败     → 说明库在别的命名空间里，但未在本命名空间声明为 shared_lib
更底层                   → SELinux avc 拒绝，或路径本身不可访问
```

`soinfo` 版本校验失败也会产生独立日志：

```text
DL_WARN("Warning: invalid soinfo version for \"%s\" (assuming inaccessible)",
        si->get_soname());
```

这条日志来自 `is_accessible(soinfo*)` 的前置检查——`!si->is_lp64_or_has_min_version(3)` 时直接判为不可达，这是防止某些应用篡改 `soinfo` 链表注入自有条目的防护（代码注释里引用了对应的 bug 编号）。判断规则：先确认"文件在不在"，再确认"命名空间允不允许"，最后才查 SELinux——把 SELinux 排第一位是最常见的浪费，因为前两步的失败不产生任何 avc 记录，反过来 avc 出现时通常已经排除了前两步。

**Q15: [learning] Product 分区的接口强制执行与链接器命名空间是什么关系，为什么 Android 11 要把它独立出来？**

`product` 分区在 Android 11 之前与 `system`、`vendor` 捆绑，Android 11 解除了这种捆绑，因此可以像 `vendor` 一样独立控制 `product` 对原生与 Java 接口的访问权限。启用方式是 `PRODUCT_PRODUCT_VNDK_VERSION := current`（目标 Shipping API 大于 29 时自动设置），Java 侧另有 `PRODUCT_ENFORCE_PRODUCT_PARTITION_INTERFACE`。

启用后的效果是给 `product` 分区的原生模块打上 `native:product` 链接类型，它只能链接同为 `native:product` 或 `native:vndk` 的模块，链接到其他类型会触发构建系统的链接类型检查错误。

```text
Android.bp 属性                          强制执行前          强制执行后
默认（无）                                core               core（不含 product）
product_specific: true                    core               产品
vendor_available: true                    core、vendor       core、vendor
product_available: true                   不适用             core、产品
vendor_available + product_available      不适用             core、产品、vendor
product_specific + vendor_available       core、vendor       产品、vendor
```

`hidl_interface` 有个隐含行为：它同时隐含 `product_available: true` 与 `vendor_available: true`，但未在 `Android.bp` 中显式写出，因此无论是否带 `system_ext_specific: true` 都对所有分区可用。迁移时最常见的构建错误是"任何带 `product_specific: true` 的 `hidl_interface` 模块都不适用于系统模块"，官方给的修法是把它改成 `system_ext_specific: true`。判断规则：接口强制执行带来的构建错误分两类——链接类型错误说明 Java 模块的 `sdk_version` 范围不匹配（修法是扩展应用的 `sdk_version` 或收紧库的），运行时链接失败说明原生侧跨分区依赖（修法是改模块属性而不是加 `PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS` 之类的绕过开关）。

**Q16: [learning] 这几层机制的版本差异会让结论失效吗，跨版本迁移时必须重新核对什么？**

会。命名空间配置文件名、供应商进程命名空间集合、`PRODUCT_TREBLE_LINKER_NAMESPACES` 取代 `PRODUCT_FULL_TREBLE`、`/linkerconfig` 改为运行时生成——这些都是随版本变化的实现细节，不是稳定契约。

按版本记住三组差异：Android 8.0 引入运行时命名空间隔离（`ld.config.txt`），Android 8.0 的 VNDK Lite 与完整 VNDK 配置并存、8.1 起完整 VNDK 成为默认，Android 9 给供应商进程也加了 `vndk` 命名空间并新增 `product`、`odm` 分区，Android 11 起配置改为启动时在 `/linkerconfig` 下运行时生成。判断规则：分析"这个库为什么加载不了"时，先确认结论依据的配置文件名在该设备上是否存在（`ld.config.txt` 还是 `ld.config.vndk_lite.txt` 还是 `ld.config.legacy.txt`），文件选错则整份 `links`/`shared_libs` 声明都对不上；这也是为什么必须读设备上的实际配置而不是照抄某一版的文档。

**Q17: [learning] 用 dlopen 加载一个不在白名单里的库，有哪些"看起来可行"实际上不行的绕法？**

常见的三种绕法都不可行：改用绝对路径、改 `LD_LIBRARY_PATH`、换一个已加载的等价库。三种都绕不开 `is_accessible` 这一关。

绝对路径只跳过"是否已加载"的查找（`find_loaded_library_by_soname` 对全路径直接返回 false），第②步的 `open_library` 之后仍要过 `is_accessible`。`LD_LIBRARY_PATH` 虽然优先级最高，但在命名空间路径列表上搜到的库同样要走可达性判定——代码注释说得很明确：搜 `ld_library_paths` 时"无需检查可达性"，但这只意味着**不再额外拒绝**，不意味着放宽命名空间的整体约束；而命名空间的 `default_library_path` 之外的文件能否加载，取决于该命名空间的 `search.paths` 与 `permitted.paths` 覆盖。找等价库替换则违反版本一致性，接口不兼容会在运行期以更难的形式失败。唯一正规的路径有两条：改用平台的公开 API；或在自己的进程里创建自定义命名空间并正确配置 `links` 与 `shared_libs`。判断规则：看到"绕过命名空间隔离"的需求，先问一句这个库为什么不在公开清单里——多数情况下真正缺的是一个公开接口，而不是一条加载技巧。

**Q18: [learning] Bionic 的 malloc 最终走到哪个分配器？为什么不同产品行为不同？**

Bionic 的 malloc 是一层 MallocDispatch 分派表，实现按产品配置注入——多数产品默认 Scudo（加固分配器），配置 malloc_low_memory 的低内存产品走 jemalloc；应用无需链接额外库即获得对应行为。

前提：分配器是可替换组件，dispatch 层隔离替换的影响。机制：调优入口是 mallopt——M_PURGE 自 API 28 起主动归还内存，更新的 API 还有 M_PURGE_ALL/M_PURGE_FAST 变体（源文档口径）。结果：排查 native 堆问题先确认产品的分配器：两者的 RSS 行为、碎片与 purge 语义不同，跨产品直接对比 RSS 数值会误导。

**Q19: [learning] linker namespace 怎么判定"这个库能不能加载"？报 not accessible 时按什么顺序排查？**

每次 dlopen 从调用方 namespace 出发，检查目标文件路径是否落在可访问集合——按 ld_library_paths_、default_library_paths_、permitted_paths_ 线性查找，目录匹配区分 file_is_in_dir（本目录）与 file_is_under_dir（子树），都不中则拒绝。

前提：namespace 是 Android 隔离平台/vendor/应用库的基本单位，配置由 linkerconfig 生成（Android 11 起在 /linkerconfig/ld.config.txt）。机制：allowed_libs_ 提供跨 namespace 白名单补充。结果：报 "is not accessible for the namespace" 时按序排查——目标库属于哪个分区、调用方 namespace 的 permitted 路径是否覆盖、是否误用了本应经 public 库间接访问的平台私有库；直接改 LD_LIBRARY_PATH 通常破坏隔离而非修复。

**Q20: [learning] 为什么说 Bionic 的符号解析全是急切的？一次 dlopen 的耗时应该归到哪些阶段？**

Bionic 不支持 RTLD_LAZY、忽略 DT_BIND_NOW——加载即完成全部重定位，符号问题在 dlopen 时就失败，不会推迟到首次调用；一次 dlopen 的耗时由映射（PT_LOAD）、重定位（link_image，含 RELRO mprotect）与 call_constructors 三段构成，JNI_OnLoad 属于其后的 ART 加载阶段（JavaVMExt::LoadNativeLibrary），不算 dlopen 本身。

机制：do_dlopen 流程为——确定调用方 namespace → BFS 展开 DT_NEEDED 建 LoadTask → 映射 → prelink_image → 全局/局部符号分组 → link_image 完成重定位并保护 PT_GNU_RELRO → 引用计数与构造函数；重定位数据可用 DT_RELR/APS2 打包压缩，trace 上对应 `dlopen: <库名>` 与 `calling constructors: <路径>` 两类切片。结果：优化加载按段下药——减依赖宽度省映射与重定位、用打包重定位省数据量、拆分非必要构造省构造段；构造函数内崩溃会直接杀进程，且没有推迟构造执行的 API。

**Q21: [learning] VNDK 弃用后，framework 与 vendor 的原生库隔离靠什么维持？Android 17 的 16 KB 链接兼容开关有哪些取值？**

隔离的运行时基础仍是 linker namespace（linkerconfig 生成配置）；VNDK 自 Android 15 起弃用（已与 source.android.com 核对）——旧 VNDK APEX（v14 及以下）保留，原 VNDK 库改装入 vendor/product 分区，LL-NDK 因稳定 ABI 不属于 VNDK、继续存在。Android 17 的 16 KB 链接兼容属性 `bionic.linker.16kb.app_compat.enabled` 取 true/false/fatal，fatal 让不兼容库立即中止（配合 `pm.16kb.app_compat.disabled true` 全局关闭 backcompat，fatal 模式已与官方核对）。

前提："Self-contained HAL"（厂商自带全部依赖）不是新的链接器模式，只是库组织方式的变化，机制上仍走 namespace 检查。结果：判断 vendor 库加载问题先看 namespace 规则，再看 VNDK 弃用带来的库归属调整；16 KB 升级期的崩溃先查该属性取值与库对齐状态。
