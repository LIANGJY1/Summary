# SIR-6332 · 语音拨打已同步联系人时弹框只显示名字不显示号码

- **提交**：`ce77fd67` | 2026-08-24 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
语音说"打电话给某某"（联系人已同步）后，拨打弹框界面只显示联系人名字，不显示号码。

## 根因分析
`FloatCallWindow`（通话浮窗）里有一个跨视图缓存字段 `lastTvNumText`：在 `updateOngoingView`（通话中视图刷新）里被赋值为 `displayName`——注意这段代码里 `displayName = TextUtils.isEmpty(contactName) ? number : contactName`，即**联系人存在时存的是名字而不是号码**。下一次语音拨号弹出 `inflateOutGoingView`（去电视图）时，代码无条件用 `tvNum.setNameAndNumber(StringUtil.formateDisPlayName(lastTvNumText))` 把上一次的"名字"当成号码文本写入号码栏，覆盖了正常查询到的"名字+号码"显示，于是弹框只见名字不见号码。缺陷库根因"联系人名称更新错误"、how"移除多余的联系人名称更新"：就是这段多余的名称回写把号码栏污染了。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java（-11）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
@@ 字段删除
-    private String lastTvNumText;
@@ inflateOutGoingView() 删除跨视图回写
-        LogUtils.d(TAG,"lastTvNumText:"+lastTvNumText);
-        if(!TextUtils.isEmpty(lastTvNumText)){
-            LogUtils.d(TAG,"Display caller info as:"+lastTvNumText+","+StringUtil.formateDisPlayName(lastTvNumText));
-            tvNum.setNameAndNumber(StringUtil.formateDisPlayName(lastTvNumText));
-        }else{
-            LogUtils.e(TAG,"Caller info is empty~~~~~");
-        }
-
         if(isHandUp){
@@ updateOngoingView() 删除缓存写入
             Log.d(TAG,"updateOngoingView caller name setContactName:"+...);
-            lastTvNumText = displayName;
-            LogUtils.e(TAG, "updateOngoingView display as: " + lastTvNumText);
             ViewUtil.updateCallInfo(mContext, primaryCall, tvNum, null, null);
```

## 为什么能修复
删除 `lastTvNumText` 的写入与回放后，去电视图的号码栏只由本次通话的 `primaryCall` 经 `ViewUtil.updateCallInfo` 正常填充，名字与号码各归其位，不再被上一次通话的 displayName（名字）污染。删除式修复无新增逻辑，副作用为零；浮窗本就保留了 `mLastContactName`/`mLastPhoneNumber` 这对语义正确的缓存用于流转回显，本删除不影响。

## 复盘与经验
- 缓存字段命名与内容语义错位（`lastTvNumText` 实际存的是名字）是"张冠李戴"类显示 bug 的温床，缓存命名必须与语义一致。
- 用 A 视图的数据回填 B 视图（通话中视图 → 去电视图）属于跨场景状态泄漏，除非有明确的业务流转需求并有正确字段，否则应删除。
- `displayName = name.isEmpty() ? number : name` 这种"名字优先"变量被当作号码缓存，复核写入点时务必核对每个赋值来源的语义。
