# Launcher SDK 接⼝⽂档

# 概述

LauncherSDK⽤于与云桌⾯服务建⽴连接，实现应⽤列表查询、应⽤操作和前台应⽤监控等功能。

# 1. 对外接⼝

# 1.1 LauncherClient

主接⼝，负责连接管理和应⽤操作。

代码块

1 package com.reachauto.clouddesk.remote.launcher; 

<table><tr><td>方法</td><td>参数</td><td>返回值</td><td>说明</td></tr><tr><td>staticcreate(Context,ConnectionListener)</td><td>context:上下文, listener:连接监听</td><td>LauncherClient</td><td>创建客户端实例(默认配置)</td></tr><tr><td>staticcreate(Context,ClientOptions,ConnectionListener)</td><td>context:上下文, options:客户端配置, listener:连接监听</td><td>LauncherClient</td><td>创建客户端实例(自定义配置)</td></tr><tr><td>connect(RemoteOptions)</td><td>options:连接配置</td><td>void</td><td>建立服务连接</td></tr><tr><td>disconnect()</td><td>-</td><td>void</td><td>断开服务连接</td></tr><tr><td>isConnected()</td><td>-</td><td>boolean</td><td>是否已连接</td></tr><tr><td>registerCloudAppListener(CloudAppListener)</td><td>listener:云应用监听</td><td>void</td><td>注册云应用回调监听</td></tr><tr><td>unregisterCloudAppListener(CloudAppListener)</td><td>listener:云应用监听</td><td>void</td><td>取消注册云应用回调监听</td></tr><tr><td></td><td>listener:流媒体监听</td><td>void</td><td>注册流媒体回调监听</td></tr><tr><td>registerStreamListener(StreamListener)</td><td></td><td>//</td><td>//</td></tr><tr><td>unregisterStreamListener(StreamListener)</td><td>listener:流媒体监听</td><td>void</td><td>取消注册流媒体回调监听</td></tr><tr><td>requestAppList()</td><td>-</td><td>void</td><td>请求获取应用列表（结果通过回调返回）</td></tr><tr><td>getLastAppList()</td><td>-</td><td>List</td><td>获取最近一次同步的应用列表，未同步过返回 null</td></tr><tr><td>operateApp(String, @AppActionDef int)</td><td>packageName:包名, action:操作类型</td><td>void</td><td>操作应用</td></tr><tr><td>getForegroundApp()</td><td>-</td><td>String</td><td>获取云实例前台应用包名</td></tr><tr><td>setNightMode(boolean)</td><td>nightMode:是否黑夜模式</td><td>void</td><td>设置云实例黑夜模式</td></tr><tr><td>setDrivingMode(bool)</td><td>drivingMode:是否走行模式</td><td>void</td><td>设置云实例走行模式</td></tr><tr><td>getAppUploadLink()</td><td>-</td><td>String</td><td>获取应用上传链接（用于生成二维码）</td></tr></table>

# 1.2 ConnectionListener

连接状态监听接⼝，位于 support 模块。

代码块

1 package com.reachauto.clouddesk.remote.support; 

<table><tr><td>方法</td><td>参数</td><td>说明</td></tr><tr><td>onConnectionStateChanged(@ ConnectionStateDef int)</td><td>state: 连接状态</td><td>连接状态变化回调</td></tr></table>

连接状态值定义⻅ ConnectionState （第 3.1 节）。

# 1.3 CloudAppListener

云应⽤事件监听接⼝。

<table><tr><td>方法</td><td>参数</td><td>说明</td></tr><tr><td>onAppChanged(App, @AppActionDef int, boolean, String)</td><td>app: 应用信息, action: 操作类型, success: 是否成功, message: 错误信息</td><td>单个应用变化通知（安装、卸载、移除等）</td></tr><tr><td>onAppListUpdated(List&lt;String&gt;, boolean, String)</td><td>appList: 应用列表, success: 是否成功, message: 错误信息</td><td>应用列表更新通知</td></tr><tr><td>onForegroundAppChanged(St ring)</td><td>appPackage: 前台应用包名</td><td>云实例前台应用变化通知</td></tr></table>

# 1.4 StreamListener

流媒体事件监听接⼝。

代码块

1 package com.reachauto.clouddesk.remote.launcher.listener; 

<table><tr><td>方法</td><td>参数</td><td>说明</td></tr><tr><td>onViewStateChanged(boolean)</td><td>foreground: 页面是否在前台</td><td>流媒体页面前后台状态变更通知</td></tr></table>

# 2. 实体类

# 2.1 App

应⽤信息实体，实现 Parcelable。

代码块

1 package com.reachauto.clouddesk.remote.launcher.bean; 

<table><tr><td>字段</td><td>类型</td><td>说明</td></tr><tr><td>mAppName</td><td>String</td><td>应用名称</td></tr><tr><td>mAppPackage</td><td>String</td><td>应用包名（主键）</td></tr><tr><td>mAppStatus</td><td>int</td><td>应用状态，取值见 AppStatus</td></tr><tr><td>mAppCategory</td><td>int</td><td>应用分类，取值见 AppCategory</td></tr><tr><td>mAppFlag</td><td>int</td><td>应用标记位</td></tr><tr><td>mAppIcon</td><td>Bitmap</td><td>应用图标</td></tr><tr><td>mAppIconBase64</td><td>String</td><td>应用图标的 Base64 编码</td></tr></table>

<table><tr><td>Setter/Setter</td><td>说明</td></tr><tr><td>getAppName() / setAppName(String)</td><td>应用名称</td></tr><tr><td>getAppPackage() / setAppPackage(String)</td><td>应用包名</td></tr><tr><td>getAppStatus() / setAppStatus(int)</td><td>应用状态</td></tr><tr><td>getAppCategory() / setAppCategory(int)</td><td>应用分类</td></tr><tr><td>getAppFlag() / setAppFlag(int)</td><td>应用标记位</td></tr><tr><td>getAppIcon() / setAppIcon(Bitmap)</td><td>应用图标</td></tr><tr><td>getAppIconBase64() /setAppIconBase64(String)</td><td>应用图标的 Base64 编码</td></tr><tr><td>isUninstallSupported()</td><td>是否支持卸载（根据 flag 位判断）</td></tr></table>

# 2.2 RemoteOptions

连接配置类，位于 support 模块。

代码块

1 package com.reachauto.clouddesk.remote.support; 

<table><tr><td>方法</td><td>参数</td><td>返回值</td><td>说明</td></tr><tr><td>static create()</td><td>-</td><td>RemoteOptions</td><td>创建实例，默认不进行自动重连</td></tr><tr><td>autoReconnect(int, long)</td><td>times: 最大重连次数, interval: 重连间隔(ms)</td><td>RemoteOptions</td><td>启用自动重连（链式调用）</td></tr></table>

# 2.3 ClientOptions

客⼾端配置类，⽤于创建LauncherClient时⾃定义⾏为。

代码块

1 package com.reachauto.clouddesk.remote.launcher; 

<table><tr><td>方法</td><td>参数</td><td>返回值</td><td>说明</td></tr><tr><td>static create()</td><td>-</td><td>ClientOptions</td><td>创建实例</td></tr><tr><td>useBase64Icon()</td><td>-</td><td>ClientOptions</td><td>启用 Base64 图标，开启后 App 会通过mAppIconBase64 返回图标的 Base64 编码（链式调用）</td></tr></table>

# 3. 常量

# 3.1 ConnectionState

连接状态常量。

代码块

1 package com.reachauto.clouddesk.remote.support.constant; 

<table><tr><td>常量</td><td>值</td><td>说明</td></tr><tr><td>STATE_CONNECTED</td><td>1</td><td>已连接</td></tr><tr><td>STATE_DISCONNECTED</td><td>0</td><td>已断开</td></tr><tr><td>STATE_LOST</td><td>-1</td><td>连接丢失</td></tr></table>

# 3.2 AppAction

# 代码块

1 package com.reachauto.clouddesk.remote.launcher.constant; 

<table><tr><td>常量</td><td>值</td><td>说明</td></tr><tr><td>START_APP</td><td>1</td><td>启动应用</td></tr><tr><td>CLOSE_APP</td><td>2</td><td>关闭应用</td></tr><tr><td>INSTALL_APP</td><td>10</td><td>安装应用</td></tr><tr><td>UNINSTALL_APP</td><td>11</td><td>卸载应用</td></tr><tr><td>REMOVE_APP</td><td>12</td><td>移除应用</td></tr></table>

# 3.3 AppStatus

应⽤状态常量。

# 代码块

1 package com.reachauto.clouddesk.remote.launcher.constant; 

<table><tr><td>常量</td><td>值</td><td>说明</td></tr><tr><td>STATUSInstalling</td><td>1</td><td>安装中</td></tr><tr><td>STATUS_UNINSTALL</td><td>2</td><td>未安装</td></tr><tr><td>STATUS_INSTALLED</td><td>3</td><td>已安装</td></tr><tr><td>STATUS_UNINSTALLING</td><td>4</td><td>卸载中</td></tr></table>

# 3.4 AppCategory

应⽤分类常量。

# 代码块

1 package com.reachauto.clouddesk.remote.launcher.constant; 

<table><tr><td>常量</td><td>值</td><td>说明</td></tr><tr><td>CATEGORY_OTHER</td><td>0</td><td>其他</td></tr><tr><td>CATEGORYVideo</td><td>1</td><td>视频</td></tr><tr><td>CATEGORYGAME</td><td>2</td><td>游戏</td></tr><tr><td>CATEGORY=AUDIO</td><td>3</td><td>音频</td></tr><tr><td>CATEGORY_OFFICE</td><td>4</td><td>办公</td></tr><tr><td>CATEGORY_EDUCATION</td><td>5</td><td>教育</td></tr><tr><td>CATEGORY_NEWS</td><td>6</td><td>新闻</td></tr><tr><td>CATEGORY_NAVI</td><td>7</td><td>导航</td></tr></table>

# 4.使⽤⽰例

# 代码块

```java
1 // 1. 创建客户端（可选配置 ClientOptions）  
2 ClientOptions clientOptions = ClientOptions.create().useBase64Icon();  
3 LauncherClient client = LauncherClient.create(context, clientOptions, state -> {  
4 switch(state) {  
5 case ConnectionState.state_CONNECTED:  
6 Log.d("TAG", "连接成功");  
7 client.requestAppList();  
8 break;  
9 case ConnectionState.state_DISCONNECTED:  
10 Log.d("TAG", "连接断开");  
11 break;  
12 case ConnectionState.State_LOST:  
13 Log.d("TAG", "连接丢失");  
14 break;  
15 }  
16 });  
17 // 2. 注册云应用监听  
19 client.registerCloudAppListener(new CloudAppListener() {  
20 @Override  
21 public void onAppChanged(App app, int action, boolean success, String message) {
```

switch(action){ caseAppAction.Start_APP: Log.d("TAG","应用启动：" $^+$ app.getAppName(); break; caseAppAction.CLOSE_APP: Log.d("TAG","应用关闭：" $^+$ app.getAppName()); break; caseAppAction.INSTALL_APP: Log.d("TAG","应用安装：" $^+$ app.getAppName()); break; } } @Override public void onAppListUpdated(ListApp,boolean success,String message){ Log.d("TAG", "应用列表更新：" $^+$ appList.size() + "个应用"); for(Appapp:appList){ Log.d("TAG","应用：" $^+$ app.appAppName(） + [" $^+$ app.appAppName(） +"] + "分类=" $^+$ app.app_category(） + "可卸载=" $^+$ app.isUninstallSupported(） + "hasIconBase64=" $^+$ (app.getAppIconBase64(！ $\equiv$ null)); } } @Override public void onForegroundAppChanged(String appPackage){ Log.d("TAG","前台应用变化：" $^+$ app.Package); } ）；

```javascript
client.registerStreamListener(new streamListener() { @Override public void onViewStateChanged(boolean foreground) { Log.d("TAG", "流媒体页面：" + (foreground ? "前台" : "后台")); } });
```

```javascript
RemoteOptions options = RemoteOptions.create()
    .autoReconnect(5, 3000); // 最多重连5次，间隔3秒
client.connect(options);
```

```javascript
client.operateApp("com.example.app", AppAction START_APP); // 启动应用  
client OperateApp("com.example.app", AppAction.CLOSE_APP); // 关闭应用  
client operatApp("com.example.app", AppAction.INSTALL_APP); // 安装应用
```

```javascript
String foregroundApp = client.getForegroundApp(); 
```

```javascript
client.setNightMode(true);  
client.setDrivingMode(false); 
```

```javascript
String uploadLink = client.getAppUploadLink(); 
```