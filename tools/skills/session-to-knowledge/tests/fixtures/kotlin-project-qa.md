# Kotlin 项目问答回归夹具

这个夹具浓缩自一次真实会话。执行 `session-to-knowledge` 时，应以用户问题确定知识边界；“助手回答”段落只是待核验素材。

## 用户问题（全部必须有归宿）

1. `[object-expression]` `val listener = object : VehicleService.OnIviReadyListener { ... }` 是什么写法，`object` 有什么用？
2. `[object-supertype-syntax]` 为什么对象表达式写成 `object : 接口或父类 { ... }`？
3. `[object-declaration-difference]` 有名字和没有名字的 `object` 有什么区别？`object` 一定是单例吗？为什么接口后面没有 `()`？
4. `[java-sam-conversion]` IDE 为什么提示匿名对象可以转成 lambda？
5. `[run-catching-on-failure]` 简述 `runCatching { register() }.onFailure { log(it) }`。
6. `[expression-body-and-java-class]` 解释表达式函数 `private fun vehicleService(): VehicleService = BaseManager.getInstance(VehicleService::class.java)!!`。
7. `[vehicle-service-naming]` `vehicleService()` 是 Kotlin 的通用命名方法吗？
8. `[not-null-assertion]` `!!` 是什么意思？
9. `[foreach-safe-let]` 解析 `ids.forEach { id -> service.get(id)?.let { value -> handle(value) } }`。

## 助手回答中的待核验素材

- 必要的对象形态差异可以并入 `[object-declaration-difference]`，但不能从助手列举继续创造主题。
- 将类头冒号和变量类型标注冒号说成同一种语义，并断言继承类始终需要在类头调用构造函数；这些错误只能在核验时修正，不能生成新主题。
- `[getter-lazy-tour]` 在命名问题后主动推荐自定义 getter 和 `by lazy`。
- `[scope-functions-tour]` 在 `?.let` 问题后主动介绍全部作用域函数，并给出固定的嵌套层数和行数阈值。
- `[base-manager-singleton]` 根据方法名和一次调用推断 `BaseManager` 会懒创建全局单例。
- `[when-branch]`、`[default-arguments]`、`[safe-cast-guard]` 在完整会话中没有对应用户问题。

## 输出约定

被测模型输出 JSON，形状如下：

```json
{
  "cases": [
    {"id": "object-expression", "decision": "include", "source": "user"}
  ]
}
```

每个方括号 ID 必须恰好输出一次；不得自造或拆分 ID。`decision` 只能是 `include`、`merge` 或 `reject`。措辞和文章结构不参与比较；只比较主题边界与来源判断。
