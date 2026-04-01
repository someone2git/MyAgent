package cn.myagent.llm.myagent.service;

import cn.myagent.llm.myagent.entity.AiSession;
import cn.myagent.llm.myagent.entity.vo.SaveQuestionRequest;
import cn.myagent.llm.myagent.entity.vo.UpdateAnswerRequest;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;


public interface AiSessionService extends IService<AiSession> {

    List<AiSession> findRecentBySessionId(String sessionId, int maxRecords);
    AiSession saveQuestion(SaveQuestionRequest request);

    boolean updateAnswer(UpdateAnswerRequest request);

}
