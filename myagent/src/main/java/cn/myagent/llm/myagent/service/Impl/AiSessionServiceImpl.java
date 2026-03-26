package cn.myagent.llm.myagent.service.Impl;

import cn.myagent.llm.myagent.entity.AiSession;
import cn.myagent.llm.myagent.mapper.AiSessionMapper;
import cn.myagent.llm.myagent.service.AiSessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiSessionServiceImpl extends ServiceImpl<AiSessionMapper, AiSession>  implements AiSessionService {

    @Override
    public List<AiSession> findRecentBySessionId(String sessionId, int maxRecords) {
        LambdaQueryWrapper<AiSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiSession::getSessionId, sessionId)
                .orderByDesc(AiSession::getCreateTime)
                .last("LIMIT " + maxRecords);
        return this.list(queryWrapper);
    }
}
