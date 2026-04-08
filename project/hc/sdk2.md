# Media SDK 接⼝⽂档
# 概述
MediaSDK⽤于与云桌⾯媒体服务建⽴连接，实现远程⾳乐播放控制和播放状态监听。
# 1. 对外接⼝
# 1.1 MediaClient
主接⼝，负责连接管理和媒体播放控制。
代码块
package com.reachauto.clouddesk.remote.media;1 
<table><tr><td>方法</td><td>参数</td><td>返回值</td><td>说明</td></tr><tr><td>staticcreate(Context,ConnectionListener)</td><td>context:上下文, listener:连接监听</td><td>MediaClient</td><td>创建客户端实例</td></tr><tr><td>connect(RemoteOptions)</td><td>options:连接配置</td><td>void</td><td>建立服务连接</td></tr><tr><td>disconnect()</td><td>-</td><td>void</td><td>断开服务连接</td></tr><tr><td>isConnected()</td><td>-</td><td>boolean</td><td>是否已连接</td></tr><tr><td>registerMediaListen er(MediaStateListen er)</td><td>listener:媒体监听</td><td>void</td><td>注册媒体状态监听</td></tr><tr><td>unregisterMediaListen er(MediaStateListen er)</td><td>listener:媒体监听</td><td>void</td><td>取消注册媒体状态监听</td></tr><tr><td>getMediaApp()</td><td>-</td><td>String</td><td>获取当前媒体应用包名</td></tr><tr><td>launchMediaApp()</td><td>-</td><td>void</td><td>启动媒体应用</td></tr><tr><td>sendMediaEvent(@Med iaEventDef int)</td><td>event:媒体事件类型</td><td>void</td><td>发送媒体控制事件</td></tr><tr><td>getMediaInfo()</td><td>-</td><td>MediaInfo</td><td>获取当前媒体信息</td></tr><tr><td>getMediaPlayer( )</td><td>-</td><td>int</td><td>获取当前播放状态，取值见 PlayState</td></tr></table>

# 1.2 ConnectionListener
连接状态监听接⼝，位于 support 模块。
代码块
1 package com.reachauto.clouddesk.remote.support; 
<table><tr><td>方法</td><td>参数</td><td>说明</td></tr><tr><td>onConnectionStateChanged(@ ConnectionStateDef int)</td><td>state: 连接状态</td><td>连接状态变化回调</td></tr></table>
连接状态值定义⻅ ConnectionState （第4.1节）。
# 1.3 MediaStateListener
媒体状态监听接⼝。
代码块
1 package com.reachauto.clouddesk.remote.media.listener; 
<table><tr><td>方法</td><td>参数</td><td>说明</td></tr><tr><td>onMediaInfoChanged(MediaInfo)</td><td>mediaInfo: 媒体信息</td><td>媒体信息变化回调（歌曲名、歌手、专辑等）</td></tr><tr><td>onMediaPlayerStateChanged(int)</td><td>state: 播放状态</td><td>播放状态变化回调（播放/暂停/停止）</td></tr><tr><td>onMediaPlayerProgressChanged(long, long)</td><td>current: 当前进度(ms), total: 总时长(ms)</td><td>播放进度更新回调
播放后重新加载</td></tr></table>
# 2. 实体类
# 2.1 MediaInfo
媒体信息实体。
代码块
1 package com.reachauto.clouddesk.remote.media.bean; 
<table><tr><td>字段</td><td>类型</td><td>说明</td></tr><tr><td>mName</td><td>String</td><td>歌曲名称</td></tr><tr><td>mArtist</td><td>String</td><td>歌手</td></tr><tr><td>mAlbum</td><td>String</td><td>专辑名</td></tr><tr><td>mMediaCover</td><td>String</td><td>封面图片地址</td></tr><tr><td>mDuration</td><td>long</td><td>歌曲时长(ms)</td></tr></table>
<table><tr><td>getter/Setter</td><td>说明</td></tr><tr><td>名称</td><td>歌曲名称</td></tr><tr><td>Artist</td><td>歌手</td></tr><tr><td>Album</td><td>专辑名</td></tr><tr><td>MediaCover</td><td>封面图片地址</td></tr><tr><td>Duration</td><td>歌曲时长(ms)</td></tr></table>
# 2.2 RemoteOptions
连接配置类，位于support模块。
代码块
package com.reachauto.clouddesk.remote.support;1 
<table><tr><td>方法</td><td>参数</td><td>返回值</td><td>说明</td></tr><tr><td>static create()</td><td>-</td><td>RemoteOptions</td><td>创建实例，默认不进行自动重连</td></tr><tr><td>autoReconnect(int, long)</td><td>times:最大重连次数, interval:重连间隔(ms)</td><td>RemoteOptions</td><td>启用自动重连（链式调用）</td></tr></table>
# 3.媒体事件常量
sendMediaEvent ⽅法中的 event 参数为 int 类型，使⽤ MediaEvent 中的常量：
<table><tr><td>常量</td><td>值</td><td>说明</td></tr><tr><td>MEDIA_EVENT_PAUSE</td><td>1</td><td>暂停</td></tr><tr><td>MEDIA_EVENT.Play</td><td>2</td><td>播放</td></tr><tr><td>MEDIA_EVENT.Play_PAUSE</td><td>3</td><td>播放/暂停切换</td></tr><tr><td>MEDIA_EVENT_PREV</td><td>4</td><td>上一曲</td></tr><tr><td>MEDIA_EVENT_NEXT</td><td>5</td><td>下一曲</td></tr></table>
# 4.枚举与注解常量
# 4.1 ConnectionState
连接状态常量。
代码块
1 package com.reachauto.clouddesk.remote.support.constant; 
<table><tr><td>常量</td><td>值</td><td>说明</td></tr><tr><td>STATE_CONNECTED</td><td>1</td><td>已连接</td></tr><tr><td>STATE_DISCONNECTED</td><td>0</td><td>已断开</td></tr><tr><td>STATE_LOST</td><td>-1</td><td>连接丢失</td></tr></table>
# 4.2 PlayState
播放状态常量。
代码块
1 package com.reachauto.clouddesk.remote.media.constant; 
<table><tr><td>常量</td><td>值</td><td>说明</td></tr><tr><td>PLAYING</td><td>3</td><td>播放中</td></tr><tr><td>PAUSED</td><td>4</td><td>已暂停</td></tr><tr><td>STOPPED</td><td>5</td><td>已停止</td></tr></table>
# 5.使⽤⽰例
代码块
```txt
1 // 1. 创建客户端  
2 MediaClient client = MediaClient.create(context, state ->  
3 switch(state) {  
4 case ConnectionState.state_CONNECTED:  
5 Log.d("TAG", "媒体服务连接成功");  
6 break;  
7 case ConnectionState.state_DISCONNECTED:  
8 Log.d("TAG", "媒体服务连接断开");  
9 break;  
10 case ConnectionState.state_LOST:  
11 Log.d("TAG", "媒体服务连接丢失");  
12 break;  
13 }  
14 });  
15  
16 // 2. 注册媒体监听  
17 client.registerMediaListener(new MediaStateListener() {  
18 @Override  
19 public void onMediaInfoChanged(MediaInfo mediaInfo) {  
20 Log.d("TAG", "正在播放: " + mediaInfo.getName())  
21 + " - " + mediaInfo.getArtist()  
22 + " [ " + mediaInfo.getAlbum() + ")" );
```
23   
24   
25   
26   
27   
28   
29   
30   
31   
32   
33   
34   
35   
36   
37   
38   
39   
40   
41   
42   
43   
44   
45   
46   
47   
48   
49   
50   
51   
52   
53   
54   
55   
56   
57   
58   
59   
60   
61   
62   
63   
64 
} @Override public void onMediaPlayStateChanged(int state) { String stateStr; switch(state){ case PlayState.PLAYER: stateStr $=$ "播放中"; break; case PlayState.PAUSED: stateStr $=$ "已暂停"; break; case PlayState.STOPPED: stateStr $=$ "已停止"; break; default: stateStr $=$ "未知"; break; } Log.d("TAG", "播放状态：" + stateStr); } @Override public void onMediaPlayProgressChanged(long current, long total) { Log.d("TAG", "播放进度：" + current + "/" + total); } };
```javascript
RemoteOptions options = RemoteOptions.create()
    .autoReconnect(5, 3000);
client.connect(options); 
```
```javascript
client.sendMediaEvent(MediaEvent.MEDIA_EVENTPlay); //播放  
client.sendMediaEvent(MediaEvent.MEDIA_EVENT_NEXT); //下一曲  
client.sendMediaEvent(MediaEvent.MEDIA_EVENTPlay_PAUSE); //播放/暂停切换
```
```javascript
MediaInfo mediaInfo = client.getMediaInfo();  
int playState = client.getMediaPlayer(); 
```
```javascript
String mediaApp = client.getMediaApp(); 
```
```javascript
clientlaunchMediaApp(); 
```
```javascript
clientdisconnect(); 
```