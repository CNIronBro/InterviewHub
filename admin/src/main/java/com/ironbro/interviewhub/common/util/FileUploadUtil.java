package com.ironbro.interviewhub.common.util;

import java.util.Arrays;
import java.util.List;

/**
 * 文件上传工具类
 */
public class FileUploadUtil {

    public enum FileType {
        PDF("application/pdf", Arrays.asList(".pdf"), 20 * 1024 * 1024),
        IMAGE("image/", Arrays.asList(".jpg", ".jpeg", ".png", ".gif", ".bmp"), 10 * 1024 * 1024),
        AUDIO("audio/", Arrays.asList(".mp3", ".wav", ".pcm", ".m4a"), 50 * 1024 * 1024);

        private final String mimeTypePrefix;
        private final List<String> extensions;
        private final long maxSize;

        FileType(String mimeTypePrefix, List<String> extensions, long maxSize) {
            this.mimeTypePrefix = mimeTypePrefix;
            this.extensions = extensions;
            this.maxSize = maxSize;
        }

        public String getMimeTypePrefix() { return mimeTypePrefix; }
        public List<String> getExtensions() { return extensions; }
        public long getMaxSize() { return maxSize; }
    }

    public static String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1) return "";
        return filename.substring(lastDotIndex);
    }
}