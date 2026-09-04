# Nacos 配置中心导入说明

本目录存放本项目的 Nacos 配置中心配置文件，用于在新环境中快速还原配置。

## 目录结构与 Nacos 的对应关系

Nacos 中的配置由三要素定位：**命名空间（Namespace）→ 分组（Group）→ 配置 ID（DataId）**。

本目录按 **分组** 建文件夹，文件夹内的 **文件名即 DataId**：

```
nacos-config/
├── HMDP_CLOUD/                  # 分组：HMDP_CLOUD
│   ├── shared-jdbc.yaml         # DataId：shared-jdbc.yaml
│   ├── shared-redis.yaml
│   ├── shared-rabbitmq.yaml
│   ├── shared-canal.yaml
│   ├── shared-sentinel.yaml
│   ├── shared-swagger.yaml
│   ├── shared-log.yaml
│   └── gateway-routes.json
└── DEFAULT_GROUP/               # 分组：DEFAULT_GROUP
    └── shared-seata.yaml
```

> 命名空间请在导入时自行选择，各服务 `bootstrap.yaml` 中未显式指定 namespace 时默认使用 `public`。

## 导入方式

### 方式一：Nacos 控制台手动导入（推荐）

1. 启动 Nacos，浏览器访问 `http://<nacos地址>:8848/nacos`，默认账号密码 `nacos / nacos`
2. 左侧菜单进入 **配置管理 → 配置列表**
3. 点击右上角 **+ 新建配置**，按上表逐个填入：
   - **Data ID**：文件名，例如 `shared-jdbc.yaml`
   - **Group**：所在文件夹名，例如 `HMDP_CLOUD`
   - **配置格式**：`.yaml` 文件选 `YAML`，`gateway-routes.json` 选 `JSON`
   - **配置内容**：直接把文件内容粘贴进去
4. 点击 **发布**

### 方式二：Nacos 导入配置压缩包

1. 在 **配置管理 → 配置列表** 点击 **导入配置**
2. 上传打包好的 zip（Nacos 导出的标准格式）

## 各配置文件说明

| 文件 | 用途 |
|---|---|
| `shared-jdbc.yaml` | MySQL 数据源与 MyBatis-Plus 配置 |
| `shared-redis.yaml` | Redis 连接与连接池配置 |
| `shared-rabbitmq.yaml` | RabbitMQ 连接、生产者确认与消费者重试配置 |
| `shared-canal.yaml` | Canal 数据同步（监听 Binlog 同步缓存）配置 |
| `shared-sentinel.yaml` | Sentinel 流控控制台地址 |
| `shared-swagger.yaml` | Knife4j 接口文档配置 |
| `shared-log.yaml` | 日志级别 |
| `shared-seata.yaml` | Seata 分布式事务 TC 注册中心配置 |
| `gateway-routes.json` | 网关动态路由规则，新增服务时在此追加 |

## 需要你替换的变量

配置中的 `${变量名}` 是占位符，**部分带有默认值**（形如 `${hmdp.db.pw:root}` 表示取不到时使用 `root`）。导入后请在 Nacos 控制台按实际环境修改：

| 配置文件 | 变量 | 说明 |
|---|---|---|
| `shared-jdbc.yaml` | `hmdp.db.host` | MySQL 地址 |
| | `hmdp.db.port` | MySQL 端口，默认 `3306` |
| | `hmdp.db.database` | 数据库名 |
| | `hmdp.db.un` | 数据库用户名，**必填** |
| | `hmdp.db.pw` | 数据库密码，**必填** |
| `shared-redis.yaml` | `redis.host` | Redis 地址 |
| | `redis.pw` | Redis 密码 |
| `shared-rabbitmq.yaml` | `rabbitmq.host` | RabbitMQ 地址 |
| | `rabbitmq.virtual-host` | 虚拟主机 |
| | `rabbitmq.username` / `rabbitmq.password` | RabbitMQ 账号密码 |
| `shared-canal.yaml` | `canal.server` | Canal 服务地址 |
| | `canal.destination` | Canal 集群名，需与安装时设置一致 |
| `shared-sentinel.yaml` | `hmdp.sentinel.transport.dashboard` | Sentinel 控制台地址，默认 `localhost:8090` |
| `shared-swagger.yaml` | `hmdp.swagger.*` | 接口文档的标题、邮箱、联系人等 |
| `shared-seata.yaml` | `nacos.server-addr` | Nacos 地址 |
| | `nacos.username` / `nacos.password` | Nacos 账号密码 |

> **安全提示**：数据库账号密码（`hmdp.db.un` / `hmdp.db.pw`）**刻意不设默认值**。若未配置，服务启动时会直接报错而不是静默使用 `root` 账号，属于有意的 fail-fast 设计。其余带默认值的占位符（如 `${hmdp.db.port:3306}`）在变量未配置时会取默认值，部署前请按需确认。

## 数据库初始化

本配置对应的建表语句见仓库根目录下的 `sql/` 目录，按服务拆分为 `blog.sql`、`shop.sql`、`user.sql`、`voucher.sql`，导入 Nacos 前请先执行。
