package cn.myagent.llm.myagent.controller;

import cn.myagent.llm.myagent.common.BaseContent;
import cn.myagent.llm.myagent.common.BaseResult;
import cn.myagent.llm.myagent.entity.record.FileInfo;
import cn.myagent.llm.myagent.service.file.FileManageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/file")
@Slf4j
public class FileController {

    @Resource
    private FileManageService fileManageService;

    @PostMapping("/upload")
    public BaseResult<FileInfo> uploadFile(@RequestParam("file")MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return BaseResult.newError("文件不能为空");
            }
            if(file.getSize() > BaseContent.MAX_TEXT_LENGTH) {
                return BaseResult.newError("文件上传失败：超过最大文件大小5M限制");
            }
            FileInfo fileInfo = fileManageService.uploadFile(file);
            log.info("文件上传成功， fileid:{}", fileInfo.getFileId());
            return BaseResult.newSuccess(fileInfo);

        } catch (Exception e) {
            log.error("文件上传失败", e);
            return BaseResult.newError("文件上传失败：" + e.getMessage());
        }
    }
}
