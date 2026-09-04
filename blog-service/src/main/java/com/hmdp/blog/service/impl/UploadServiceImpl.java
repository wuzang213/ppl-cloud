package com.hmdp.blog.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.blog.constants.BlogConstants;
import com.hmdp.blog.service.UploadService;
import com.hmdp.common.domain.Result;
import com.hmdp.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Service
public class UploadServiceImpl implements UploadService {

    @Value("${hmdp.upload.dir}")
    private String uploadDir;

    @Override
    public Result uploadImage(MultipartFile image) throws IOException {
        String fileName = createNewFileName(image.getOriginalFilename());
        String dirPath = getDirPath(fileName);
        File dir = new File(uploadDir, dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        image.transferTo(new File(dir, fileName));
        return Result.ok("/imgs/" + dirPath + "/" + fileName);
    }

    @Override
    public Result deleteBlogImg(String filename) {
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new BadRequestException(BlogConstants.ILLEGAL_FILENAME);
        }
        File file = new File(uploadDir, filename);
        if (file.isDirectory()) {
            throw new BadRequestException(BlogConstants.ILLEGAL_FILENAME);
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        return UUID.randomUUID().toString() + "." + suffix;
    }

    private String getDirPath(String fileName) {
        int hash = fileName.hashCode();
        return "blogs/" + (hash & 0xF) + "/" + ((hash >> 4) & 0xF);
    }
}
