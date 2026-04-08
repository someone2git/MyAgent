package cn.myagent.llm.myagent.service;

import cn.myagent.llm.myagent.entity.AiFileInfo;
import cn.myagent.llm.myagent.entity.record.FileInfo;

public interface FileInfoService {

    void saveFileInfo(FileInfo fileInfo);

    void updateFileInfo(FileInfo fileInfo);

    FileInfo getFileInfoById(String fileId);

    AiFileInfo getEntityById(String fileId);

}
