package cn.myagent.llm.myagent.agent.websearch;

import cn.myagent.llm.myagent.agent.BaseAgent;
import cn.myagent.llm.myagent.service.AiSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

@Slf4j
public class WebSearchReactAgent extends BaseAgent {

    private ChatClient chatClient;

    private final List<ToolCallback> toolCallbacks;

    private final String systemPrompt;

    private int maxRounds;

    private final List<Advisor> advisors;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WebSearchReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools, String systemPrompt, int maxRounds,
                               ChatMemory chatMemory, List<Advisor> advisors,
                               AiSessionService sessionService) {
        super(name, chatModel, "websearch");
        this.toolCallbacks = tools;
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.advisors = advisors;
        this.chatMemory = chatMemory;
        this.sessionService = sessionService;

        // 初始化工具记录集合
        this.usedTools = new HashSet<>();

        initChatClient();

        if (this.chatClient == null) {
            throw new IllegalStateException("ChatClient 初始化失败！");
        }
    }

    private void initChatClient() {
        ToolCallingChatOptions toolCallingChatOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .build();

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (CollectionUtils.isNotEmpty(advisors)) {
            builder.defaultAdvisors(advisors);
        }
        this.chatClient = builder.defaultOptions(toolCallingChatOptions).defaultToolCallbacks(toolCallbacks).build();
    }

    public Flux<String> stream(String query, String conversationId) {
        return streamInternal(query, conversationId);
    }

    public static Builder builder() {
        return new Builder();
    }


    public static class Builder {
        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools;
        private String systemPrompt = "";
        private int maxRounds;
        private List<Advisor> advisors;
        private ChatMemory chatMemory;
        private AiSessionService sessionService;
//        private AgentTaskManager taskManager;

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public Builder sessionService(AiSessionService sessionService) {
            this.sessionService = sessionService;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder tools(ToolCallback... tools) {
            this.tools = Arrays.asList(tools);
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        public Builder advisors(List<Advisor> advisors) {
            this.advisors = advisors;
            return this;
        }

        public Builder advisors(Advisor... advisors) {
            this.advisors = Arrays.asList(advisors);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public WebSearchReactAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空！");
            }
            return new WebSearchReactAgent(name, chatModel, tools, systemPrompt, maxRounds, chatMemory, advisors, sessionService);
        }
    }

}
