package cn.myagent.llm.myagent.service.Impl;

import cn.myagent.llm.myagent.entity.AiFileInfo;
import cn.myagent.llm.myagent.entity.record.FileInfo;
import cn.myagent.llm.myagent.mapper.AiFileInfoMapper;
import cn.myagent.llm.myagent.service.FileInfoService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@Slf4j
@Service
public class FileInfoServiceImpl extends ServiceImpl<AiFileInfoMapper, AiFileInfo>  implements FileInfoService {

    @Override
    public void saveFileInfo(FileInfo fileInfo) {
        AiFileInfo entity = convertToEntity(fileInfo);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        this.save(entity);
        log.info("文件信息已保存: fileId={}", fileInfo.getFileId());    }

    @Override
    public void updateFileInfo(FileInfo fileInfo) {
        AiFileInfo entity = convertToEntity(fileInfo);
        entity.setUpdateTime(LocalDateTime.now());
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("file_id", fileInfo.getFileId());
        this.update(entity, wrapper);
        log.info("文件信息已更新: fileId={}", fileInfo.getFileId());
    }


    @Override
    public FileInfo getFileInfoById(String fileId) {
        AiFileInfo entity = getEntityById(fileId);
        if (entity == null) {
            return null;
        }
        return convertToDto(entity);
    }

    @Override
    public AiFileInfo getEntityById(String fileId) {
        QueryWrapper<AiFileInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("file_id", fileId);
        return this.getOne(wrapper);
    }
    /**
     * 将DTO转换为实体
     */
    private AiFileInfo convertToEntity(FileInfo fileInfo) {
        AiFileInfo entity = new AiFileInfo();
        BeanUtils.copyProperties(fileInfo, entity);
        entity.setStatus(fileInfo.getStatus() != null ? fileInfo.getStatus().name() : "PENDING");
        return entity;
    }

    /**
     * 将实体转换为DTO
     */
    private FileInfo convertToDto(AiFileInfo entity) {
        FileInfo fileInfo = new FileInfo();
        BeanUtils.copyProperties(entity, fileInfo);
        // 转换状态字符串为枚举
        if (entity.getStatus() != null) {
            try {
                fileInfo.setStatus(FileInfo.FileStatus.valueOf(entity.getStatus()));
            } catch (IllegalArgumentException e) {
                log.warn("无法识别的文件状态: {}, 使用默认状态PENDING", entity.getStatus());
                fileInfo.setStatus(FileInfo.FileStatus.PENDING);
            }
        }
        return fileInfo;
    }
}
