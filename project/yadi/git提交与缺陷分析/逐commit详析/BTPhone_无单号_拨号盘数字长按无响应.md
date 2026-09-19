# 无单号 · 拨号盘数字键长按被无条件消费导致无响应

- **提交**：`240d6344` | 2026-07-22 | liujinfeng | BTPhone | bugfix
- **缺陷库**：未关联单号（提交单号写 SIR-XXXX 占位）

## 问题
拨号盘上除"0"外的数字键长按没有任何响应。

## 根因分析
`DialPadView`（自定义 ViewGroup）给每个数字按钮设置的 `OnLongClickListener` 里，无论按键是否属于需要处理长按的"0"键（index==10）、无论 `listener` 是否为空，一律 `return true` 把长按事件消费掉。返回 true 表示"事件已被处理"，系统不再分发给其他处理者，而对普通数字键消费后又什么都没做——事件被"吞"了，表现为长按无响应。提交 `[why]` 描述"长按事件被拦截了"与 diff 完全吻合。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/view/DialPadView.java`
```diff
             // 设置长按监听
             button.setOnLongClickListener(v -> {
+                LogUtils.d(TAG, "Long press number button: " + NUMBERS[index]);
                 if (listener != null && index == 10) {
                     // 按键0长显示"+"
                     listener.onNumberLongClick(LETTERS[index]);
+                    return true;
                 }
-                return true;
+                return false;
             });
```

## 为什么能修复
返回值改为与"是否真的处理了"一致：只有 `listener != null && index == 10`（长按"0"输入"+"）时返回 true；其余情况返回 false，事件不再被白白消费，交给上层/默认长按逻辑处理，长按有了响应路径。同时补了 `LogUtils.d` 日志便于验证触发。改动仅影响长按返回值语义，单击与删除键逻辑不动，无副作用。

## 复盘与经验
- `onLongClick`/`onTouch`/`onKey` 返回 true 的前提是"确实做了处理"，无条件 return true 会把事件吞成静默失败——这是触控类"无响应"问题的高频根因。
- 消费条件应与处理条件共用同一个判断（`listener != null && index == 10`），避免出现"拦截了但不处理"的中间态。
- "无响应"类问题排查第一步：在事件回调入口加日志确认事件是否到达，到达但不生效多半是返回值/拦截问题。
