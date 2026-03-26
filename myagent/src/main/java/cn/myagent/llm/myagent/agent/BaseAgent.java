package cn.myagent.llm.myagent.agent;

import cn.myagent.llm.myagent.entity.AiSession;
import cn.myagent.llm.myagent.service.AiSessionService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Set;

@Slf4j
public abstract class BaseAgent {

    protected final ChatModel chatModel;

    protected final String name;

    protected String agentType;

    protected boolean enableRTecommendations = true;

    protected Set<String> usedTools;

    @Setter
    @Getter
    protected ChatMemory chatMemory;

    protected AiSessionService sessionService;

    public BaseAgent(String name, ChatModel chatModel, String agentType) {
        this.name = name;
        this.chatModel = chatModel;
        this.agentType = agentType;
    }

    public ChatMemory creatPersistentChatMemory(String sessionId, int maxMessages) {
        if (sessionService == null) {
            log.warn("sessionservice is null, connot load chat memory");
            return MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        }
        // 查询对话历史信息
        List<AiSession> aiSessions = sessionService.findRecentBySessionId(sessionId, maxMessages);
        // 新建chatmemory
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        // 历史对话信息加载到chatmemory
        if (CollectionUtils.isNotEmpty(aiSessions)) {
            for (AiSession aiSession : aiSessions) {
                if(StringUtils.isNotEmpty(aiSession.getQuestion())) {
                    chatMemory.add(aiSession.getQuestion(), new UserMessage(aiSession.getQuestion()));
                }
                // 这里并不会覆盖上面的message，先查询已经add的message，底层process方法会把旧message和新message合并为一个list
                if(StringUtils.isNotEmpty(aiSession.getAnswer())) {
                    chatMemory.add(aiSession.getQuestion(), new AssistantMessage(aiSession.getAnswer()));
                }
            }
        }
        return chatMemory;

    }

}
