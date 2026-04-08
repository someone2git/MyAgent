package cn.myagent.llm.myagent.service.file;

import cn.myagent.llm.myagent.entity.record.FileInfo;
import cn.myagent.llm.myagent.service.FileInfoService;
import cn.myagent.llm.myagent.utils.file.FileUtils;
import cn.myagent.llm.myagent.utils.file.OverlapParagraphTextSplitter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class FileManageService {

    @Resource
    private FileInfoService fileInfoService;

    @Resource
    private MinioService minioService;

    @Resource
    private FileParserService fileParserService;

    @Resource
    private EmbeddingService embeddingService;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    private ChatModel multimodalChatModel;

    @PostConstruct
    public void init() {
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .temperature(0.2d)
                    .model("qwen3-vl-plus")
                    .build();
            multimodalChatModel = OpenAiChatModel.builder()
                    .openAiApi(OpenAiApi.builder()
                            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/")
                            .apiKey(new SimpleApiKey(apiKey))
                            .build())
                    .defaultOptions(options)
                    .build();
            log.info("多模态模型初始化成功");
        } catch (Exception e) {
            log.warn("多模态模型初始化失败: {}", e.getMessage());
        }
    }

    public FileInfo uploadFile(MultipartFile file) {
        String fileId = UUID.randomUUID().toString();
        String fileType = FileUtils.getFileType(file.getOriginalFilename());
        long fileSize = file.getSize();

        try {
            // 保存文件信息到数据库
            FileInfo fileInfo = FileInfo.builder()
                    .fileId(fileId)
                    .fileName(file.getOriginalFilename())
                    .fileType(fileType)
                    .fileSize(fileSize)
                    .createdAt(LocalDateTime.now())
                    .status(FileInfo.FileStatus.PROCESSING)
                    .build();
            fileInfoService.saveFileInfo(fileInfo);
            // 上传文件
            String uploadMinIOName = FileUtils.getMinIOFileName(fileId, fileType);
            String minioPath = minioService.uploadFile(file, uploadMinIOName);
            // 更新文件信息
            fileInfo.setMinioPath(minioPath);
            fileInfo.setStatus(FileInfo.FileStatus.SUCCESS);
            fileInfoService.updateFileInfo(fileInfo);
            // 对不同文件类型进行处理
            if(FileUtils.isTextFile(fileType)) {
                try {
                    String parseResult = fileParserService.parseFile(file);
                    fileInfo.setExtractedText(parseResult);
                    fileInfoService.updateFileInfo(fileInfo);

                    if (FileUtils.isLargeFile(parseResult)) {
                        log.info("检测到大文件，开始向量化处理: fileId={}, 文本长度: {}", fileId, parseResult.length());
                        try{
                            processLargeFileEmbedding(fileId, parseResult);
                            fileInfo.setEmbed(1);
                            fileInfoService.updateFileInfo(fileInfo);
                            log.info("大文件向量化完成: fileId={}", fileId);
                        } catch (Exception e){
                            log.error("大文件向量化失败：fileId:{}", fileId, e);
                        }
                    }
                } catch (Exception e) {
                    log.error("文件解析失败：fileId:{}", file, e);
                    fileInfo.setStatus(FileInfo.FileStatus.FAILED);
                    fileInfoService.updateFileInfo(fileInfo);
                    throw new RuntimeException("文件解析失败：" + e.getMessage(), e);
                }
            } else if (FileUtils.isImageFile(fileType)) {
                try {
                    String extractedText = image2Text(file);
                    fileInfo.setExtractedText(extractedText);
                    fileInfoService.updateFileInfo(fileInfo);
                    log.info("图片识别完成: fileId={}, 识别文本长度: {}", fileId, extractedText.length());
                } catch (Exception e) {
                    log.error("图片识别失败: fileId={}", fileId, e);
                    fileInfo.setStatus(FileInfo.FileStatus.FAILED);
                    fileInfoService.updateFileInfo(fileInfo);
                    throw new RuntimeException("图片识别失败: " + e.getMessage(), e);
                }
            } else {
                log.error("不支持该类型文件 filetype:{}", fileType);
                throw new IllegalArgumentException("不支持类型"+ fileType + "文件");
            }
            return fileInfo;
        } catch (Exception e) {
            log.error("文件上传失败: fileId={}", fileId, e);
            FileInfo fileInfo = fileInfoService.getFileInfoById(fileId);
            if (fileInfo != null) {
                fileInfo.setStatus(FileInfo.FileStatus.FAILED);
                fileInfoService.updateFileInfo(fileInfo);
            }
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    private void processLargeFileEmbedding(String fileId, String text) {
        log.info("开始处理大文件向量化: fileId={}, 文本长度: {}", fileId, text.length());
        Document document = new Document(text);
        List<Document> documents = List.of(document);

        OverlapParagraphTextSplitter overlapParagraphTextSplitter = new OverlapParagraphTextSplitter(500, 50);
        List<Document> chunks = overlapParagraphTextSplitter.apply(documents);
        log.info("文档切分完成: fileId={}, 切分数量: {}", fileId, chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("fileid", fileId);
            chunk.getMetadata().put("chunkId", i);
        }

        embeddingService.embedAndStore(chunks);
        log.info("大文件向量化存储完成: fileId={}, 切分数量: {}", fileId, chunks.size());
    }

    private String image2Text(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] imageBytes = IOUtils.toByteArray(inputStream);

            if (imageBytes == null || imageBytes.length == 0) {
                throw new RuntimeException("图片文件内容为空");
            }

            // 使用多模态模型识别图片
            ByteArrayResource imageResource = new ByteArrayResource(imageBytes);
            var userMessage = UserMessage.builder()
                    .text("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。")
                    .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, imageResource)))
                    .build();
            var response = multimodalChatModel.call(new Prompt(List.of(userMessage)));
            String resp = response.getResult().getOutput().getText();

            if (resp == null || resp.trim().isEmpty()) {
                return "[无法识别图片内容]";
            }
            return resp.trim();
        } catch (Exception e) {
            log.error("图片识别异常", e);
            throw new RuntimeException("图片识别失败: " + e.getMessage(), e);
        }
    }

    public FileInfo getFileInfo(String fileId) {
        FileInfo fileInfo = fileInfoService.getFileInfoById(fileId);
        if (fileInfo == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        return fileInfo;
    }

    public String getFileContent(String fileId) {
        FileInfo fileInfo = getFileInfo(fileId);

        if (fileInfo.getStatus() != FileInfo.FileStatus.SUCCESS) {
            throw new IllegalStateException("文件尚未处理完成，当前状态: " + fileInfo.getStatus());
        }

        String content = fileInfo.getExtractedText();
        if (content == null || content.trim().isEmpty()) {
            return "该文件没有可识别的内容";
        }

        return content;
    }
}
