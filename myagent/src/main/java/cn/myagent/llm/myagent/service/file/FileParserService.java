package cn.myagent.llm.myagent.service.file;

import cn.myagent.llm.myagent.utils.file.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@Slf4j
public class FileParserService {


    public String parseFile(MultipartFile multipartFile) {
        String fileType = FileUtils.getFileType(multipartFile.getOriginalFilename());
        long fileSize = multipartFile.getSize();
        log.info("开始解析文件: {} (类型: {}, 大小: {} bytes)", multipartFile.getOriginalFilename(), fileType, fileSize);

        try{
            String content;
            switch (fileType.toLowerCase()) {
                case "pdf":
                    content = parsePdf(multipartFile);
                    break;
                case "doce":
                    content = parseDoce(multipartFile);
                    break;
                case "doc":
                    throw new IllegalArgumentException("暂不支持 .doc 格式，请转换为 .docx");
                case "txt":
                    content = parseTxt(multipartFile);
                    break;
                default:
                    throw new IllegalArgumentException("不支持的文件类型：" +  fileType);
            }


            log.info("文件解析完成，内容长度: {} 字符", content.length());
            return content;
        } catch (Exception e) {
            log.error("文件解析失败: {}", multipartFile.getOriginalFilename(), e);
            throw new RuntimeException("文件解析失败: " + e.getMessage(), e);        }
    }

    private String parsePdf(MultipartFile multipartFile) throws Exception {
        try(InputStream inputStream = multipartFile.getInputStream();
            PDDocument pdDocument = PDDocument.load(inputStream)) {

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            String text = stripper.getText(pdDocument);
            log.info("PDF 解析完成，页数: {}, 文本长度: {}", pdDocument.getNumberOfPages(), text.length());
            return text.trim();
        }
    }

    private String parseDoce(MultipartFile multipartFile) throws Exception {
        try(InputStream inputStream = multipartFile.getInputStream();
            XWPFDocument xwpfDocument = new XWPFDocument(inputStream)) {
            StringBuilder text = new StringBuilder();
            List<XWPFParagraph> paragraphs = xwpfDocument.getParagraphs();

            for(XWPFParagraph xwpfParagraph : paragraphs) {
                String paraText = xwpfParagraph.getText();
                if (paraText != null && !paraText.trim().isEmpty()) {
                    text.append(paraText).append("\n");
                }
            }
            log.info("DOCX 解析完成，段落数: {}, 文本长度: {}", paragraphs.size(), text.length());
            return text.toString().trim();
        }
    }

    private String parseTxt(MultipartFile multipartFile) throws Exception {
        try(InputStream inputStream = multipartFile.getInputStream()) {
            String text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            log.info("txt 解析完成，文本长度：{}", text.length());
            return text.trim();
        }
    }
}
















