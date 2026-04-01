package cn.myagent.llm.myagent.agent;

import cn.myagent.llm.myagent.common.AgentResponse;
import cn.myagent.llm.myagent.entity.AiSession;
import cn.myagent.llm.myagent.manager.AgentTaskManager;
import cn.myagent.llm.myagent.prompts.ReactAgentPrompts;
import cn.myagent.llm.myagent.service.AiSessionService;
import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
public abstract class BaseAgent {

    protected final ChatModel chatModel;

    protected final String name;

    protected String agentType;

    protected boolean enableRecommendations = true;

    protected Set<String> usedTools;

    @Setter
    @Getter
    protected ChatMemory chatMemory;

    protected AiSessionService sessionService;

    protected AgentTaskManager taskManager;

    protected long startTime;

    protected long firstResponseTime;

    protected String currentQuestion;

    protected Long currentSessionId;

    protected String currentRecommendations;

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

    protected Flux<String> checkRunningTask(String conversationId) {
        if(conversationId != null && taskManager != null && taskManager.hasRunningTask(conversationId)){
            return Flux.error(new IllegalStateException("会话正在执行，稍后再试"));
        }
        return null;
    }

    protected void initTimers() {
        startTime = System.currentTimeMillis();
        firstResponseTime = 0;
    }

    protected void clearUsedTools() {
        if(usedTools != null) {
            usedTools.clear();
        }
    }

    protected AgentTaskManager.TaskInfo registerTask(String conversationId, Sinks.Many<String> sink){
        if(conversationId != null && taskManager != null) {
            AgentTaskManager.TaskInfo taskInfo = taskManager.registerTask(conversationId, sink, agentType);
            if (taskInfo == null) {
                log.warn("任务注册失败，converstionId:{}", conversationId);
            }
            return taskInfo;
        }
        return null;
    }

    protected void loadChatHistory(String conversactionId, List<Message> messages, boolean skipSystem, boolean addLable) {
        if(conversactionId != null && chatMemory != null) {
            List<Message> history = chatMemory.get(conversactionId);
            if (history != null && !history.isEmpty()){
                if (addLable) {
                    messages.add(new UserMessage("对话历史："));
                }
                for(Message msg : history) {
                    if (skipSystem && msg instanceof SystemMessage){
                        continue;
                    }
                    messages.add(msg);
                }
            }
        }
    }

    protected String createTextResponse(String content) {
        return AgentResponse.text(content);
    }

    protected String createReferenceResponse(String content) {
        return AgentResponse.reference(content);
    }

    protected String createRecommendResponse(String content) {
        return AgentResponse.recommend(content);
    }

    protected String createThinkingResponse(String content) {
        return AgentResponse.thinking(content);
    }

    protected String generateRecommendations(String conversationId, String currentQuestion, String currentAnswer) {
        if (!enableRecommendations){
            return null;
        }
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(ReactAgentPrompts.getRecommendPrompt()));
            loadChatHistory(conversationId, messages, true, true);
            messages.add(new UserMessage("当前会话： "));
            messages.add(new UserMessage(currentQuestion));
            if (currentAnswer != null) {
                messages.add(new AssistantMessage(currentAnswer));
            }

            BeanOutputConverter<List<String>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<String>>() {});

            messages.add(new UserMessage("请根据上述对话生成三个推荐问题，输出格式为：\n" + converter.getFormat()));

            String response = ChatClient.builder(chatModel).build()
                    .prompt()
                    .messages(messages)
                    .call()
                    .content();

            if (response != null && !response.isEmpty()) {
                List<String> recommendations = converter.convert(response);
                if (recommendations != null && !recommendations.isEmpty()) {
                    String jsonStr = JSON.toJSONString(recommendations);
                    log.info("生成推荐问题成功：{}", jsonStr);
                    return jsonStr;
                }
            }
            log.warn("生成推荐问题失败，响应格式无效：{}", response);
            return null;
        } catch (Exception e){
            log.error("生成推荐问题异常", e);
            return null;
        }
    }

    protected long getTotalResponseTime() {
        if (startTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - startTime;
    }

    protected String getUsedToolsString() {
        if (usedTools == null || usedTools.isEmpty()) {
            return "";
        }
        return String.join(",", usedTools);
    }
}
