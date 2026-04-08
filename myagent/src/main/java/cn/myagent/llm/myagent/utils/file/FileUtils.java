package cn.myagent.llm.myagent.utils.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class FileUtils {

    private static final int LARGE_FILE_THRESHOLD = 5000;

    public static String getFileType(String fileName) {
        if (fileName.isEmpty()) {
            log.error("获取不到文件名称");
            throw new IllegalArgumentException("获取不到文件名");
        }
        String[] splitName = fileName.split("\\.");
        return splitName[splitName.length - 1];
    }

    public static boolean isTextFile(String fileType) {
        return ("pdf".equalsIgnoreCase(fileType) ||
                "docx".equalsIgnoreCase(fileType) ||
                "doc".equalsIgnoreCase(fileType) ||
                "txt".equalsIgnoreCase(fileType));
    }

    public static boolean isImageFile(String fileType) {
        return ("jpg".equalsIgnoreCase(fileType) ||
                "jpeg".equalsIgnoreCase(fileType) ||
                "png".equalsIgnoreCase(fileType) ||
                "gif".equalsIgnoreCase(fileType) ||
                "bmp".equalsIgnoreCase(fileType));
    }

    public static boolean isLargeFile(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return text.length() >= LARGE_FILE_THRESHOLD;
    }

    /**
     *
     * @param fileId
     * @param fileType
     * @return file-xsdfwesofkjsdf.pdf
     */
    public static String getMinIOFileName(String fileId, String fileType) {
        return "file-" + fileId.replace("-", "") + "." + fileType.toLowerCase();
    }
}
