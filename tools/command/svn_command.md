### 检出与更新

```bash
svn checkout / co <URL> [目录]                        # 检出代码
svn checkout / co <URL> --username <u> --password <p> # 附带账号密码检出
svn update / up                                       # 更新当前目录所有文件
svn update / up -r 200 file                      # 更新到指定版本号
svn commit / ci -m "提交说明"                         # 提交本地所有修改
```

### 文件操作

```bash
svn add file                                          # 添加单个文件到版本控制
svn add * --force                                     # 递归添加目录下所有新文件
svn delete / rm file                                  # 删除文件并纳入版本控制
svn revert file                                       # 撤销对特定文件的本地未提交修改
svn revert -R .                                       # 递归撤销当前目录下所有修改
svn move / mv file1 file2                             # 移动或重命名文件
```

### 信息查看

```bash
svn status / st                                       # 查看状态 [?:未追踪 M:修改 C:冲突 A:添加 D:删除 !:丢失]
svn log -l 5                                          # 查看最近5条提交日志
svn diff / di file                                    # 查看本地修改与版本库的差异
svn info                                              # 查看当前工作副本的URL、版本等详细信息
svn list / ls <URL>                                   # 仅列出版本库中的文件和目录
```

### 分支与合并

```bash
svn copy / cp <Trunk_URL> <Branch_URL> -m "注释"      # 复制目录创建分支或标签
svn merge <Branch_URL>                                # 将分支代码合并到当前所在的主干目录
```

### 冲突与维护

```bash
svn resolve --accept working file                     # 标记冲突文件已手动解决
svn lock file -m "加锁说明"                           # 锁定文件防止他人同时修改 (多用于二进制文件)
svn unlock file                                       # 解锁文件
svn cleanup                                           # 清理异常中断导致的工作副本锁定
svn propedit svn:ignore .                             # 设置当前目录需要忽略的文件或目录模式
```

