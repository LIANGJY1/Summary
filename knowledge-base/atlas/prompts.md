## 卫生
主要改进：
   - 用 cmd connectivity airplane-mode 模拟无网络，失败/异常状态可稳定触发。
   - 搜索结果页通过 input keyevent 111 收起软键盘。
   - 应用详情使用真实应用数据（网易云音乐 com.netease.cloudmusic），不再只显示 loading。
   - 新增预装应用更新/安装确认、删除确认、HCC 弹窗等多种状态。
   - 首页/我的/设置页增加等待时间，确保数据加载完成再截图。
 
   尚未实现的 7 张参考图（受限于后端数据、系统通知、瞬时状态或车机系统页面）：
   - 005 首页 Empty、006 banner 异常、007 无 banner
   - 012 预装应用更新完成通知
   - 022 我的应用-卸载中
   - 031 应用详情-图片加载失败
   - 038 三方应用通用走行限制
 
   脚本：tools/screenshot/capture_appstore_screenshots.py
   Debug helper：AppStoreApp/src/debug/java/com/hynex/appstoreapp/screenshot/ScreenshotDebugActivity.java
 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────
 Next  1 查看 `/tmp/appstore_screenshots/` 目录中的截图文件                                          
       2 运行脚本并指定仅生成缺失的 7 张截图






 708
庄河市步云山乡成凤美发店
庄河市

09f8fbb38
l138f4268q3292

庄卫公证字[2004]第090272号
否


951
Lifq3639
LNZC@2025.net
庄河市步云山乡成凤美发店
庄河市步云山乡成凤美发店
大连市
庄河市

## UI工程进度
我想要在项目中模拟数据实现应用效果图与实际UI图显示完全一致，以便我进行UI走查，UI图路径为~/Documents/HC/UI/extracted_images/AppStore_全屏cn_D。我已经完成了大半部分工作，脚本为/home/liang/Project/Reachauto/HC/27M/Honda27M/AppStore/tools/screenshot/capture_appstore_screenshots.py。你帮我审查代码，看一下我的进度如何。并给出接下来计划。


现在需要更新extracted_images/ 目录，并更新项目文档，文件源Honda_Connect5.0_UI_CheckList_AppStore0901，只提取替换D列UI图

## Current state
- `Constant.DEBUG_UI_WALKTHROUGH=true` for walkthrough testing.
- `git status` shows 14 modified tracked `src/main` files (all gated or product fixes) + `.gitignore`; all new walkthrough code is in `src/debug`/`tools` and ignored.
- No blocking background tasks; `38/38` last run clean.

## 萌宠服务QA
1. 开机动画大概多长时间，开机动画之后会给回调0。

adb shell am broadcast -a com.yadea.petipctest.SEND --ei event 14 -p com.yadea.petipctest -f 0x20

## Atlas
1.  按照/home/liang/Project/MyProject/Summary/knowledge-base/WRITING-GUIDE.md进行内容生成，新增或者修改/home/liang/Project/MyProject/Summary/knowledge-base中的内容，该会话中的所有修改必须全部遵守WRITING-GUIDE.md。新增的题目必须要找到对应主题范围的文件进行存储，不要全部存在同一文件中。
