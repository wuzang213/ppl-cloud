package com.hmdp.blog.constants;

public final class BlogConstants {

    public static final String BLOG_NOT_FOUND = "笔记不存在";
    public static final String BLOG_SAVE_FAILED = "新增笔记失败";
    public static final String COMMENT_DELETE_FORBIDDEN = "无权删除";
    public static final String ILLEGAL_FILENAME = "非法文件名";

    public static final String NOTICE_TYPE_LIKE = "LIKE";
    public static final String NOTICE_TYPE_COMMENT = "COMMENT";
    public static final String NOTICE_TYPE_FOLLOW = "FOLLOW";

    public static final int FEED_FAN_THRESHOLD = 10_000;

    public static final String NOTICE_LIKE_CONTENT = "有人点赞了你的笔记";
    public static final String NOTICE_COMMENT_CONTENT = "有人评论了你的笔记";
    public static final String NOTICE_FOLLOW_CONTENT = "有人关注了你";

    private BlogConstants() {
    }
}
