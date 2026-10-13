package com.hmdp.voucher.payment.support;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 假 Mapper 的公共基类：把 {@link BaseMapper} 的 17 个抽象方法全部实现为「抛异常」，
 * 子类只覆写测试真正需要的那几个。
 *
 * <p>为什么用「抛异常」而不是「返回 null」：如果被测代码意外调用了没实现的通用方法
 * （例如某天有人把条件更新改回 {@code QueryWrapper}），测试会<b>立刻失败并指出方法名</b>，
 * 而不是在 null 上滑过去、留下一个假装通过的绿灯。
 */
public abstract class BaseMapperStub<T> implements BaseMapper<T> {

    protected static UnsupportedOperationException notImplemented(String method) {
        return new UnsupportedOperationException(
                "内存态假 Mapper 未实现 " + method + "()：本测试不应走到这条路径，"
                        + "若被测代码改用了通用 Wrapper 查询，请在此补上对应实现");
    }

    @Override
    public int insert(T entity) {
        throw notImplemented("insert");
    }

    @Override
    public int deleteById(Serializable id) {
        throw notImplemented("deleteById");
    }

    @Override
    public int deleteById(T entity) {
        throw notImplemented("deleteById(entity)");
    }

    @Override
    public int deleteByMap(Map<String, Object> columnMap) {
        throw notImplemented("deleteByMap");
    }

    @Override
    public int delete(Wrapper<T> queryWrapper) {
        throw notImplemented("delete(wrapper)");
    }

    @Override
    public int deleteBatchIds(Collection<?> idList) {
        throw notImplemented("deleteBatchIds");
    }

    @Override
    public int updateById(T entity) {
        throw notImplemented("updateById");
    }

    @Override
    public int update(T entity, Wrapper<T> updateWrapper) {
        throw notImplemented("update(wrapper)");
    }

    @Override
    public T selectById(Serializable id) {
        throw notImplemented("selectById");
    }

    @Override
    public List<T> selectBatchIds(Collection<? extends Serializable> idList) {
        throw notImplemented("selectBatchIds");
    }

    @Override
    public List<T> selectByMap(Map<String, Object> columnMap) {
        throw notImplemented("selectByMap");
    }

    @Override
    public Long selectCount(Wrapper<T> queryWrapper) {
        throw notImplemented("selectCount");
    }

    @Override
    public List<T> selectList(Wrapper<T> queryWrapper) {
        throw notImplemented("selectList");
    }

    @Override
    public List<Map<String, Object>> selectMaps(Wrapper<T> queryWrapper) {
        throw notImplemented("selectMaps");
    }

    @Override
    public List<Object> selectObjs(Wrapper<T> queryWrapper) {
        throw notImplemented("selectObjs");
    }

    @Override
    public <P extends IPage<T>> P selectPage(P page, Wrapper<T> queryWrapper) {
        throw notImplemented("selectPage");
    }

    @Override
    public <P extends IPage<Map<String, Object>>> P selectMapsPage(P page, Wrapper<T> queryWrapper) {
        throw notImplemented("selectMapsPage");
    }
}
