package com.hmdp.search.service.impl;

import com.hmdp.search.service.IRedundantFieldRefreshService;
import com.hmdp.search.service.ISearchIndexService;
import com.hmdp.api.client.ShopClient;
import com.hmdp.api.client.UserClient;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.ShopTypeDTO;
import com.hmdp.common.domain.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 冗余字段刷新（作者改名 / 分类改名）：用合并窗口收敛成一次 _update_by_query，失败放回待处理集合下一轮重试，不走死信表。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedundantFieldRefreshServiceImpl implements IRedundantFieldRefreshService {

    private final Set<Long> pendingUserIds = ConcurrentHashMap.newKeySet();

    private final Set<Long> pendingTypeIds = ConcurrentHashMap.newKeySet();

    private final UserClient userClient;

    private final ShopClient shopClient;

    private final ISearchIndexService indexService;

    public void scheduleUser(Long userId) {
        if (userId != null) {
            pendingUserIds.add(userId);
        }
    }

    public void scheduleShopType(Long typeId) {
        if (typeId != null) {
            pendingTypeIds.add(typeId);
        }
    }

    @Scheduled(fixedDelayString = "${hmdp.search.debounce-millis:5000}")
    public void flush() {
        flushUsers();
        flushShopTypes();
    }

    private void flushUsers() {
        Set<Long> userIds = drain(pendingUserIds);
        for (Long userId : userIds) {
            try {
                Result<UserDTO> result = userClient.queryUserById(userId);
                if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
                    log.warn("查询用户失败，稍后重试作者字段刷新，userId={}", userId);
                    pendingUserIds.add(userId);
                    continue;
                }
                UserDTO user = result.getData();
                if (user == null) {
                    log.warn("用户不存在，跳过作者字段刷新，userId={}", userId);
                    continue;
                }
                indexService.refreshBlogAuthor(userId, user.getNickName(), user.getIcon());
            } catch (Exception e) {
                log.error("刷新作者字段失败，稍后重试，userId={}", userId, e);
                pendingUserIds.add(userId);
            }
        }
    }

    private void flushShopTypes() {
        Set<Long> typeIds = drain(pendingTypeIds);
        if (typeIds.isEmpty()) {
            return;
        }
        Map<Long, String> typeNames = new HashMap<>(typeIds.size());
        try {
            Result<List<ShopTypeDTO>> result = shopClient.listShopTypes();
            if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null) {
                log.warn("查询店铺分类失败，稍后重试分类字段刷新，typeIds={}", typeIds);
                pendingTypeIds.addAll(typeIds);
                return;
            }
            for (ShopTypeDTO type : result.getData()) {
                if (type != null && type.getId() != null) {
                    typeNames.put(type.getId(), type.getName());
                }
            }
        } catch (Exception e) {
            log.error("查询店铺分类异常，稍后重试分类字段刷新，typeIds={}", typeIds, e);
            pendingTypeIds.addAll(typeIds);
            return;
        }
        for (Long typeId : typeIds) {
            String name = typeNames.get(typeId);
            if (name == null) {
                // 分类被删除：店铺文档里的旧 typeName 会一直留着，但这是展示信息，
                // 且删除分类时通常也会调整店铺归属。不为了它做额外的一致性动作。
                log.warn("分类不存在（可能已删除），跳过 typeName 刷新，typeId={}", typeId);
                continue;
            }
            try {
                indexService.refreshShopTypeName(typeId, name);
            } catch (Exception e) {
                log.error("刷新分类名字段失败，稍后重试，typeId={}", typeId, e);
                pendingTypeIds.add(typeId);
            }
        }
    }

    private Set<Long> drain(Set<Long> source) {
        Set<Long> drained = ConcurrentHashMap.newKeySet();
        drained.addAll(source);
        source.removeAll(drained);
        return drained;
    }
}
