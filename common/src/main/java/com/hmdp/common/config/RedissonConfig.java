package com.hmdp.common.config;

import io.lettuce.core.ReadFrom;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class RedissonConfig {
    @Value("${spring.redis.host}")
    private String redisHost;
    @Value("${spring.redis.port}")
    private int redisPort;
    @Value("${spring.redis.password}")
    private String redisPassword;

//    @Value("${spring.redis.cluster.nodes}")
//    private List<String> clusterNodes;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setPassword(redisPassword);
//        集群模式（当前未启用）：clusterNodes 补 redis:// 前缀后用
//        config.useClusterServers().setPassword(redisPassword).addNodeAddress(nodeAddresses)
        return Redisson.create(config);
    }

    /** 配置 Lettuce 读写策略：MASTER / MASTER_PREFERRED / REPLICA / REPLICA_PREFERRED，
     * REPLICA_PREFERRED = 优先读 replica、全部不可用才回主。 */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer clientConfigurationBuilderCustomizer(){
        return clientConfigurationBuilder -> clientConfigurationBuilder.readFrom(ReadFrom.REPLICA_PREFERRED);
    }
}