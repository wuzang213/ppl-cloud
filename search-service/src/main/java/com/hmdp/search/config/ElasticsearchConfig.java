package com.hmdp.search.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ES 客户端配置：ES 侧单独用一份干净的 ObjectMapper，刻意不复用 Spring 容器里的那个。
 * 容器的 {@code JsonConfig} 注册了 {@code Long -> String} 全局序列化器（对外接口约定），沿用到 ES 文档会让 long 以字符串写入、污染索引语义（排序/聚合/range 依赖隐式 coerce）。 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ElasticsearchConfig {

    private final SearchProperties searchProperties;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient() {
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(searchProperties.getHost(), searchProperties.getPort(), "http"));
        builder.setRequestConfigCallback(config -> config
                .setConnectTimeout(searchProperties.getConnectTimeoutMs())
                .setSocketTimeout(searchProperties.getSocketTimeoutMs()));
        // 失败重试交给上层（重建 / search_index_failed 重试任务），这里不做客户端自动重试，避免放大 ES 压力。
        // ⚠️ 7.12.1 的 RestClientBuilder 没有 setMaxRetryTimeoutMillis(int) 这个重载（7.17.x 才有），
        // 所以这里不设重试超时；升级到 7.17.x 后才可补上。
        log.info("初始化 Elasticsearch 客户端: {}:{}", searchProperties.getHost(), searchProperties.getPort());
        return new RestHighLevelClient(builder);
    }

    /** ES 专用 ObjectMapper：不继承 Web 层 Long→String 约定，日期按 ISO-8601 输出（对齐 mapping 的 {@code strict_date_optional_time}）。
     * ⚠️ 不能加 {@code @Primary}，否则会顶掉 Boot 给 MVC 配的 ObjectMapper、丢掉 long→string 约定；注入须 {@code @Qualifier("esObjectMapper")}。 */
    @Bean("esObjectMapper")
    public ObjectMapper esObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        // 文档 source 里可能有 VO 不需要的字段（如店铺的 location），反序列化时忽略未知字段
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
