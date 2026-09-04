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
//        List<String> nodeAddresses = clusterNodes.stream()
//                .map(node -> node.startsWith("redis://") ? node : "redis://" + node)
//                .collect(Collectors.toList());
//        config.useClusterServers()
//                .setPassword(redisPassword)
//                .addNodeAddress(nodeAddresses.toArray(new String[0]));
        return Redisson.create(config);
    }

    /**
     * 配置Lettuce的读写策略
     * - MASTER：从主节点读取
     * - MASTER_PREFERRED：优先从master节点读取，master不可用才读取replica
     * - REPLICA：从slave（replica）节点读取
     * - REPLICA _PREFERRED：优先从slave（replica）节点读取，所有的slave都不可用才读取master
     * @return
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer clientConfigurationBuilderCustomizer(){
        return clientConfigurationBuilder -> clientConfigurationBuilder.readFrom(ReadFrom.REPLICA_PREFERRED);
    }
}