package com.hmdp.search.listener;

import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.outbox.OutboxConsumeService;
import com.hmdp.search.dao.SearchStateDao;
import com.hmdp.search.domain.SearchFailEnvelope;
import com.hmdp.search.es.EsIndexNames;
import com.hmdp.search.service.IRedundantFieldRefreshService;
import com.hmdp.search.service.ISearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** 索引同步入口：订阅既有的 {@code cache.sync.fanout} 广播 —— 四个业务服务（blog / shop / voucher / user）本来就在发这个广播，search-service 只是多挂一个队列，业务侧一行都不用改。
 * 不自起 Canal 消费者监听 {@code tb_blog}/{@code tb_shop}：那会重新引入「一条变更产生两条消息」的双发问题，且行变化拿不到 {@code eventType}；Outbox 表显式携带 {@code aggregate_type + event_type}、与业务同事务，是更合适的信号源。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexListener {

    private final OutboxConsumeService outboxConsumeService;

    private final ISearchIndexService indexService;

    private final IRedundantFieldRefreshService redundantFieldRefreshService;

    private final SearchStateDao stateDao;

    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = CacheConstants.QUEUE_SEARCH_INDEX,
                    durable = "true",
                    arguments = {
                            @Argument(name = "x-queue-mode", value = "lazy"),
                            @Argument(name = "x-dead-letter-exchange", value = MqConstants.SEARCH_INDEX_DLX_EXCHANGE),
                            @Argument(name = "x-dead-letter-routing-key",
                                    value = MqConstants.SEARCH_INDEX_DEAD_ROUTING_KEY)
                    }),
            exchange = @Exchange(name = CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE, type = "fanout")))
    public void onIndexSync(CacheSyncMessage message, Message amqp) {
        String messageId = amqp.getMessageProperties().getMessageId();
        if (!outboxConsumeService.tryMarkConsumed(messageId)) {
            return;
        }
        String type = message.getType();
        String eventType = message.getEventType();
        Long id = message.getId();
        try {
            dispatch(message, type, eventType, id);
        } catch (Exception e) {
            // 绝不让异常逃出监听方法：容器的 requeue 行为受 Nacos 里 shared-rabbitmq.yaml 的 retry 配置影响
            // （retry + RepublishMessageRecoverer 会把异常劫到 error.direct、死信队列收不到；default-requeue-rejected=true 则无限重投）。
            // 所以这里自己处理失败：落 search_index_failed 表（重试依据）+ 显式投死信；幂等记录用 messageId，重试从表里走。
            log.error("索引同步失败，已落失败表待重试：type={}, id={}, eventType={}", type, id, eventType, e);
            recordFailure(type, id, eventType, e);
        }
    }

    private void dispatch(CacheSyncMessage message, String type, String eventType, Long id) {
        if ("BLOG".equals(type)) {
            if (!indexService.applyBlogEvent(eventType, id)) {
                log.debug("忽略与索引无关的 BLOG 事件：{}", eventType);
            }
            return;
        }
        if ("SHOP".equals(type)) {
            if (!indexService.applyShopEvent(eventType, id)) {
                log.debug("忽略与索引无关的 SHOP 事件：{}", eventType);
            }
            return;
        }
        if ("SHOP_TYPE".equals(type)) {
            // 分类变更：只影响店铺文档里的 typeName 冗余字段，走 _update_by_query 局部刷
            redundantFieldRefreshService.scheduleShopType(id);
            return;
        }
        if ("USER".equals(type)) {
            if ("USER_UPDATED".equals(eventType)) {
                // 昵称/头像变更：只影响文章文档里的 authorName/authorIcon 冗余字段
                redundantFieldRefreshService.scheduleUser(id);
            }
            return;
        }
        log.debug("search-service 忽略消息类型：{}", type);
    }

    private void recordFailure(String type, Long id, String eventType, Exception e) {
        String indexType = indexTypeOf(type);
        if (indexType == null || id == null) {
            // 连类型/主键都识别不了的消息（如 VOUCHER 之类），落表也没有重试意义，只告警
            log.error("索引同步失败但无法定位索引类型，type={}, id={}", type, id, e);
            return;
        }
        try {
            stateDao.insertFailed(indexType, id, eventType, e.getMessage());
        } catch (Exception dbError) {
            log.error("写入 search_index_failed 失败，这条消息将只有日志可查：type={}, id={}", type, id, dbError);
        }
        try {
            rabbitTemplate.convertAndSend(MqConstants.SEARCH_INDEX_DLX_EXCHANGE,
                    MqConstants.SEARCH_INDEX_DEAD_ROUTING_KEY,
                    new SearchFailEnvelope(indexType, id, eventType, e.getMessage()));
        } catch (Exception mqError) {
            log.error("投递死信失败（失败表已记录，不影响重试）：type={}, id={}", type, id, mqError);
        }
    }

    private String indexTypeOf(String type) {
        if ("BLOG".equals(type)) {
            return EsIndexNames.TYPE_BLOG;
        }
        return "SHOP".equals(type) ? EsIndexNames.TYPE_SHOP : null;
    }
}
