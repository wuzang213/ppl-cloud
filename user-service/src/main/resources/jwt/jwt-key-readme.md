# JWT RS256 密钥生成说明
本项目 AccessToken 使用 **RS256(RSA‑SHA256) 非对称签名算法**。

> ⚠️ 安全警告
> - `private.jks`：**私钥密钥库，用于签发 JWT AccessToken，严禁上传公开代码仓库！**
> - 私钥一旦泄露，攻击者可以伪造任意用户的 JWT 凭证。
> - `public.jks`：公钥，用于服务端 JWT 验签，也不建议直接提交二进制 jks 文件，部署环境自行生成。

## 运行环境依赖
本地需要 JDK 的 `keytool` 工具（JDK 自带，无需额外安装）。

## 1. 生成私钥库 private.jks
执行下面命令，交互输入密钥库密码、密钥密码，记住该密码，后续配置需要使用。
```bash
keytool -genkeypair \
-alias jwt-rs256-key \
-keyalg RSA \
-keysize 2048 \
-storetype JKS \
-keystore private.jks \
-validity 3650
```
## 2. 从私钥库导出公钥 public.jks
```bash
keytool -exportcert \
-alias jwt-rs256-key \
-keystore private.jks \
-file public.jks
```

## 3. 文件放置位置
将生成好的 `private.jks`、`public.jks` 放到项目资源目录下:public.jks放到gateway模块下