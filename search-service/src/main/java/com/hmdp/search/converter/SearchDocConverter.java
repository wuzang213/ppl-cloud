package com.hmdp.search.converter;

import com.hmdp.common.search.BlogSearchDocDTO;
import com.hmdp.common.search.ShopSearchDocDTO;
import com.hmdp.search.doc.BlogDoc;
import com.hmdp.search.doc.ShopDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 业务 DTO -> ES 文档的形态转换，只做字段重命名 / 派生字段计算 / 组装 geo_point 这些无副作用的事。
 *  数据补全（查作者、查店名、查分类名）不在这里而在各业务服务侧：全量分页与增量反查必须共用同一个组装出口，否则易出现「全量无作者、增量才有作者」这类口径分叉 bug。 */
@Slf4j
@Component
public class SearchDocConverter {

    /** 摘要截断长度 */
    private static final int SUMMARY_MAX_LENGTH = 200;

    public BlogDoc toBlogDoc(BlogSearchDocDTO dto) {
        BlogDoc doc = new BlogDoc();
        doc.setId(dto.getId());
        doc.setTitle(dto.getTitle());
        doc.setSummary(summarize(dto.getContent()));
        doc.setContent(dto.getContent());
        doc.setAuthorId(dto.getUserId());
        doc.setAuthorName(dto.getAuthorName());
        doc.setAuthorIcon(dto.getAuthorIcon());
        doc.setShopId(dto.getShopId());
        doc.setImages(dto.getImages());
        doc.setLiked(dto.getLiked());
        doc.setComments(dto.getComments());
        doc.setViewCount(dto.getViewCount());
        doc.setCreateTime(dto.getCreateTime());
        doc.setUpdateTime(dto.getUpdateTime());
        return doc;
    }

    public ShopDoc toShopDoc(ShopSearchDocDTO dto) {
        ShopDoc doc = new ShopDoc();
        doc.setId(dto.getId());
        doc.setName(dto.getName());
        doc.setTypeId(dto.getTypeId());
        doc.setTypeName(dto.getTypeName());
        doc.setArea(dto.getArea());
        doc.setAddress(dto.getAddress());
        doc.setLocation(toLocation(dto.getX(), dto.getY()));
        doc.setImages(dto.getImages());
        doc.setAvgPrice(dto.getAvgPrice());
        doc.setScore(dto.getScore());
        doc.setSold(dto.getSold());
        doc.setComments(dto.getComments());
        doc.setViewCount(dto.getViewCount());
        doc.setOpenHours(dto.getOpenHours());
        doc.setCreateTime(dto.getCreateTime());
        doc.setUpdateTime(dto.getUpdateTime());
        return doc;
    }

    /** 项目里 x = 经度、y = 纬度（见 {@code ShopServiceImpl.queryShopByType} 的 {@code GeoReference.fromCoordinate(x, y)}），所以 geo_point 是 lat=y、lon=x。
     * 缺任意一个则整体置空 —— 半截坐标会让该文档在距离查询里直接报错。 */
    private Map<String, Double> toLocation(Double x, Double y) {
        if (x == null || y == null) {
            return null;
        }
        Map<String, Double> location = new LinkedHashMap<>(2);
        location.put("lat", y);
        location.put("lon", x);
        return location;
    }

    /**
     * 由正文派生摘要：tb_blog 没有 summary 列，全量/增量都靠这里统一生成，保证同一篇笔记在任何路径得到的 summary 都一样。
     */
    static String summarize(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content
                .replaceAll("<[^>]+>", " ")      // 去 HTML 标签
                .replaceAll("\\s+", " ")          // 折叠空白
                .trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= SUMMARY_MAX_LENGTH
                ? text
                : text.substring(0, SUMMARY_MAX_LENGTH) + "…";
    }
}
