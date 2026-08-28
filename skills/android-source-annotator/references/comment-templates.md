# 各语言注释模板

## TypeScript / JavaScript (JSDoc)

```typescript
/**
 * 验证用户凭据，成功后创建登录会话。
 *
 * 密码使用 bcrypt 进行比较，不会以明文形式记录或存储。
 * 登录成功后自动创建新的 Session 记录并返回。
 *
 * @param email - 用户注册邮箱
 * @param password - 明文密码，会在内部进行哈希比较
 * @returns 登录成功后的会话对象，包含 token 和过期时间
 * @throws {AuthError} 当邮箱不存在或密码不匹配时抛出
 *
 * @example
 * const session = await login('user@example.com', 'password123');
 * console.log(session.token);
 */
async function login(email: string, password: string): Promise<Session> {
  // ...
}
```

### 类模板

```typescript
/**
 * 用户数据模型，封装用户相关的业务逻辑。
 *
 * 继承自 BaseModel，提供用户认证、权限检查、资料管理等功能。
 * 实例通常通过 AuthService 创建，不建议直接 new。
 */
class User extends BaseModel {
  /**
   * 检查用户是否拥有指定权限。
   *
   * @param permission - 权限标识符，如 "admin.write"
   * @returns 拥有该权限返回 true
   */
  hasPermission(permission: string): boolean { ... }
}
```

## Python (Google-style Docstring)

```python
def login(email: str, password: str) -> Session:
    """验证用户凭据，成功后创建登录会话。

    密码使用 bcrypt 进行比较，不会以明文形式记录或存储。
    登录成功后自动创建新的 Session 记录并返回。

    Args:
        email: 用户注册邮箱。
        password: 明文密码，会在内部进行哈希比较。

    Returns:
        登录成功后的会话对象，包含 token 和过期时间。

    Raises:
        AuthError: 当邮箱不存在或密码不匹配时抛出。

    Example:
        >>> session = login('user@example.com', 'password123')
        >>> print(session.token)
    """
    ...
```

### 类模板

```python
class User(BaseModel):
    """用户数据模型，封装用户相关的业务逻辑。

    继承自 BaseModel，提供用户认证、权限检查、资料管理等功能。
    实例通常通过 AuthService 创建，不建议直接实例化。

    Attributes:
        email: 用户注册邮箱。
        role: 用户角色（admin / editor / viewer）。
    """

    def has_permission(self, permission: str) -> bool:
        """检查用户是否拥有指定权限。

        Args:
            permission: 权限标识符，如 "admin.write"。

        Returns:
            拥有该权限返回 True，否则返回 False。
        """
        ...
```

## Python (NumPy-style Docstring) —— 仅当项目已有此风格时使用

```python
def login(email, password):
    """验证用户凭据，成功后创建登录会话。

    Parameters
    ----------
    email : str
        用户注册邮箱。
    password : str
        明文密码，会在内部进行哈希比较。

    Returns
    -------
    Session
        登录成功后的会话对象，包含 token 和过期时间。

    Raises
    ------
    AuthError
        当邮箱不存在或密码不匹配时抛出。
    """
    ...
```

## Java (Javadoc)

```java
/**
 * 验证用户凭据，成功后创建登录会话。
 *
 * <p>密码使用 bcrypt 进行比较，不会以明文形式记录或存储。
 * 登录成功后自动创建新的 Session 记录并返回。</p>
 *
 * @param email   用户注册邮箱
 * @param password 明文密码
 * @return 登录成功后的会话对象
 * @throws AuthException 当邮箱不存在或密码不匹配时抛出
 */
public Session login(String email, String password) throws AuthException {
    // ...
}
```

### 类模板

```java
/**
 * 用户数据模型，封装用户相关的业务逻辑。
 *
 * <p>继承自 BaseModel，提供用户认证、权限检查、资料管理等功能。</p>
 *
 * @author Zhang San
 * @since 1.0
 */
public class User extends BaseModel {
    /**
     * 检查用户是否拥有指定权限。
     *
     * @param permission 权限标识符，如 "admin.write"
     * @return 拥有该权限返回 true
     */
    public boolean hasPermission(String permission) { ... }
}
```

## Go (Godoc)

```go
// Login 验证用户凭据，成功后创建登录会话。
//
// 密码使用 bcrypt 进行比较，不会以明文形式记录或存储。
// 登录成功后自动创建新的 Session 记录并返回。
//
// 参数 email 是用户注册邮箱，password 是明文密码。
// 返回登录成功后的会话对象和可能的错误。
func Login(email, password string) (*Session, error) {
    // ...
}
```

### 结构体模板

```go
// User 是用户数据模型，封装用户相关的业务逻辑。
//
// 继承 BaseModel，提供用户认证、权限检查、资料管理等功能。
type User struct {
    // Email 用户注册邮箱
    Email string
    // Role 用户角色
    Role  string
}

// HasPermission 检查用户是否拥有指定权限。
//
// 参数 permission 是权限标识符，如 "admin.write"。
func (u *User) HasPermission(permission string) bool { ... }
```

## Rust (Rustdoc)

```rust
/// 验证用户凭据，成功后创建登录会话。
///
/// 密码使用 bcrypt 进行比较，不会以明文形式记录或存储。
/// 登录成功后自动创建新的 Session 记录并返回。
///
/// # Arguments
///
/// * `email` - 用户注册邮箱
/// * `password` - 明文密码
///
/// # Returns
///
/// 登录成功后的会话对象
///
/// # Errors
///
/// 当邮箱不存在或密码不匹配时返回 `AuthError`
///
/// # Examples
///
/// ```
/// let session = login("user@example.com", "password123")?;
/// ```
pub async fn login(email: &str, password: &str) -> Result<Session, AuthError> {
    // ...
}
```

## C / C++ (Doxygen)

```cpp
/**
 * @brief 验证用户凭据，成功后创建登录会话。
 *
 * 密码使用 bcrypt 进行比较，不会以明文形式记录或存储。
 * 登录成功后自动创建新的 Session 记录并返回。
 *
 * @param email 用户注册邮箱
 * @param password 明文密码
 * @return 登录成功后的会话对象
 * @throws AuthError 当邮箱不存在或密码不匹配时抛出
 */
Session login(const std::string& email, const std::string& password) {
    // ...
}
```

## Kotlin (KDoc)

```kotlin
/**
 * 验证用户凭据，成功后创建登录会话。
 *
 * 密码使用 bcrypt 进行比较，不会以明文形式记录或存储。
 *
 * @param email 用户注册邮箱
 * @param password 明文密码
 * @return 登录成功后的会话对象
 * @throws AuthException 当邮箱不存在或密码不匹配时抛出
 */
fun login(email: String, password: String): Session {
    // ...
}
```

## Swift (Swift Doc)

```swift
/// 验证用户凭据，成功后创建登录会话。
///
/// 密码使用 bcrypt 进行比较，不会以明文形式记录或存储。
///
/// - Parameters:
///   - email: 用户注册邮箱
///   - password: 明文密码
/// - Returns: 登录成功后的会话对象
/// - Throws: `AuthError` 当邮箱不存在或密码不匹配时
func login(email: String, password: String) throws -> Session {
    // ...
}
```

## 通用原则

不管哪种语言格式，好的注释遵循：

- 描述「做什么」和「为什么」，不是复述代码
- 函数名已经自解释时（如 `getUserById`），注释重点放在边界条件和返回值特殊情况
- 简单 getter/setter/一行工具函数可以只写一行注释，甚至跳过
- 副作用（网络请求、文件 I/O、数据库写、全局状态变更）一定要标注
