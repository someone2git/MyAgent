package cn.myagent.llm.myagent.utils.file;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OverlapParagraphTextSplitter extends TextSplitter {

    protected final int chunkSize;

    protected final int overlap;

    public OverlapParagraphTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 不能大于等于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    protected List<String> splitText(String text) {
         if (StringUtils.isEmpty(text)) {
             return Collections.emptyList();
         }
         String[] paragraphs = text.split("\\n+");
         List<String> allChunks = new ArrayList<>();
         StringBuilder currentChunk = new StringBuilder();

         for(String paragraph : paragraphs) {
             if (StringUtils.isEmpty(paragraph)) {
                 continue;
             }
             int start = 0;
             while (start < paragraph.length()) {
                 int remainingSpace = chunkSize - currentChunk.length();
                 int end = Math.min(start + remainingSpace, paragraph.length());

                 currentChunk.append(paragraph, start, end);

                 if (currentChunk.length() >= chunkSize) {
                     allChunks.add(currentChunk.toString());

                     String overlapText = "";
                     if (overlap > 0) {
                         int overlapStart = Math.max(0, currentChunk.length() - overlap);
                         overlapText = currentChunk.substring(overlapStart);
                     }
                     currentChunk = new StringBuilder();
                     if (!overlapText.isEmpty()){
                         currentChunk.append(overlapText);
                     }
                 }
                 start = end;
             }
         }
         if (currentChunk.length() > 0) {
             allChunks.add(currentChunk.toString());
         }
         return allChunks;
    }

    public List<Document> apply(List<Document> documents) {
        if(CollectionUtils.isEmpty(documents)) {
            return Collections.emptyList();
        }
        List<Document> result = new ArrayList<>();
        for (Document document : documents) {
            List<String> chunks = splitText(document.getText());
            for(String chunk : chunks) {
                result.add(new Document(chunk));
            }
        }
        return result;
    }
}
