package com.hmdp.blog.service;

import com.hmdp.common.domain.Result;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface UploadService {

    Result uploadImage(MultipartFile image) throws IOException;

    Result deleteBlogImg(String filename);
}
