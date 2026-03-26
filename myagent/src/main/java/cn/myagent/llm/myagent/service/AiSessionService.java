package cn.myagent.llm.myagent.service;

import cn.myagent.llm.myagent.entity.AiSession;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;


public interface AiSessionService extends IService<AiSession> {

    List<AiSession> findRecentBySessionId(String sessionId, int maxRecords);

}
