package cn.myagent.llm.myagent.agent.websearch;

import cn.myagent.llm.myagent.agent.BaseAgent;
import cn.myagent.llm.myagent.entity.AiSession;
import cn.myagent.llm.myagent.entity.record.AgentState;
import cn.myagent.llm.myagent.entity.record.RoundMode;
import cn.myagent.llm.myagent.entity.record.RoundState;
import cn.myagent.llm.myagent.entity.record.SearchResult;
import cn.myagent.llm.myagent.entity.vo.SaveQuestionRequest;
import cn.myagent.llm.myagent.entity.vo.UpdateAnswerRequest;
import cn.myagent.llm.myagent.manager.AgentTaskManager;
import cn.myagent.llm.myagent.prompts.ReactAgentPrompts;
import cn.myagent.llm.myagent.service.AiSessionService;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class WebSearchReactAgent extends BaseAgent {

    private ChatClient chatClient;

    private final List<ToolCallback> toolCallbacks;

    private final String systemPrompt;

    private int maxRounds;

    private final List<Advisor> advisors;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AtomicBoolean hasSentFinalResult;

    public WebSearchReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools, String systemPrompt, int maxRounds,
                               ChatMemory chatMemory, List<Advisor> advisors,
                               AiSessionService sessionService, AgentTaskManager agentTaskManager) {
        super(name, chatModel, "websearch");
        this.toolCallbacks = tools;
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.advisors = advisors;
        this.chatMemory = chatMemory;
        this.sessionService = sessionService;
        this.taskManager = agentTaskManager;
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

    private Flux<String> streamInternal(String question, String conversationId) {
        // 检查是否有正在执行任务
        Flux<String> checkResult = checkRunningTask(conversationId);
        if (checkResult != null) {
            return checkResult;
        }
        // 初始化计时器
        initTimers();
        // 清空已用工具记录
        clearUsedTools();
        // 注册任务到管理器
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();// 开启缓冲区，大模型输出和浏览器消费速度不一致，放到缓冲区
        AgentTaskManager.TaskInfo taskInfo = registerTask(conversationId, sink);
        if ( taskInfo == null && conversationId != null && taskManager != null) {
            return Flux.error(new IllegalStateException("会话正在进行，稍后重试"));
        }
        // 加载系统提示词
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        messages.add(new SystemMessage(ReactAgentPrompts.getWebSearchPrompt()));
        if (StringUtils.isNotBlank(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        // 判断是否加载历史记忆，加载历史记忆
        loadChatHistory(conversationId, messages, true, true);
        // 添加用户问题
        messages.add(new UserMessage("<question>" + question + "</question>"));
        currentQuestion = question;
        // 用户问题保存到数据库
        if (sessionService != null) {
            AiSession aiSession = sessionService.saveQuestion(SaveQuestionRequest.builder().sessionId(conversationId).question(question).build());
            currentSessionId = aiSession.getId();
        }
        // 请求大模型准备
        AtomicLong roundCounter = new AtomicLong(0);
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        StringBuilder finalAnswerBuffer = new StringBuilder();
        StringBuilder thinkingBuffer = new StringBuilder();
        AgentState agentState = new AgentState();
        boolean useMemory = conversationId != null && chatMemory != null;
        scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer);
        return sink.asFlux()
                .doOnNext(chunk -> {
                    recordFirstResponse();

                    try {
                        JSONObject json = JSON.parseObject(chunk);
                        String type = json.getString("type");
                        if ("text".equals(type)) {
                            finalAnswerBuffer.append(json.getString("content"));
                        } else if ("thinking".equals(type)){
                            thinkingBuffer.append(json.getString("content"));
                        }
                    } catch (Exception e) {
                        finalAnswerBuffer.append(chunk);
                    }
                })
                .doOnCancel(() -> {
                    hasSentFinalResult.set(true);
                    if (taskManager != null) {
                        taskManager.stopTask(conversationId);
                    }
                })
                .doFinally(signalType -> {
                    log.info("最终答案：{}", finalAnswerBuffer);
                    log.info("思考过程：{}", thinkingBuffer);

                    saveSessionResult(conversationId, finalAnswerBuffer, thinkingBuffer, agentState);

                    if (taskManager != null) {
                        taskManager.stopTask(conversationId);
                    }
                });

    }

    private void saveSessionResult(String conversationId, StringBuilder finalAnswerBuffer, StringBuilder thinkingBuffer, AgentState agentState) {
        if (sessionService != null && currentSessionId != null && finalAnswerBuffer.length() > 0) {
            long totalResponseTime = getTotalResponseTime();
            String toolString = getUsedToolsString();
            String referenceJson = "";
            if (!agentState.searchResults.isEmpty()){
                referenceJson = createReferenceResponse(JSON.toJSONString(agentState.searchResults));
            }
            UpdateAnswerRequest request = UpdateAnswerRequest.builder()
                    .id(currentSessionId)
                    .answer(finalAnswerBuffer.toString())
                    .thinking(thinkingBuffer.toString())
                    .tools(toolString)
                    .reference(referenceJson)
                    .recommend(currentRecommendations)
                    .firstResponseTime(firstResponseTime)
                    .totalResponseTime(totalResponseTime)
                    .build();
            sessionService.updateAnswer(request);
            log.info("结果已保存到会话: sessionId={}", conversationId);
        }
    }

    protected void recordFirstResponse() {
        if (firstResponseTime == 0 && startTime > 0) {
            firstResponseTime = System.currentTimeMillis() - startTime;
            log.debug("记录首次响应时间: {}ms", firstResponseTime);
        }
    }

    private void scheduleRound(
            List<Message> messages,
            Sinks.Many<String> sink,
            AtomicLong roundCounter,
            AtomicBoolean hasSentFinalResult,
            StringBuilder finalAnswerBuffer,
            boolean useMemory,
            String conversationId,
            AgentState agentState,
            StringBuilder thinkingBuffer) {
        roundCounter.incrementAndGet();
        RoundState state = new RoundState();

        Disposable disposable = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, sink, state))
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer))
                .doOnError(err -> {
                    if (!hasSentFinalResult.get()) {
                        hasSentFinalResult.set(true);
                        sink.tryEmitError(err);
                    }
                })
                .subscribe();
        if (conversationId != null && taskManager != null) {
            taskManager.setDisposable(conversationId, disposable);
        }


    }

    private void processChunk(ChatResponse chunk, Sinks.Many<String> sink, RoundState state) {
        if(chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }

        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> toolCalls = gen.getOutput().getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;
            for (AssistantMessage.ToolCall incoming : toolCalls) {
                mergeToolCall(state, incoming);
            }
            return;
        }

        if (text != null) {
            sink.tryEmitNext(createTextResponse(text));
            state.textBuffer.append(text);
        }

    }

    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {
        for(int i=0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);
            if (existing.id().equals(incoming.id())) {
                String mergedArgs = Objects.toString(existing.arguments(), "") + Objects.toString(incoming.arguments(), "");
                state.toolCalls.set(i, new AssistantMessage.ToolCall(existing.id(), "function", existing.name(), mergedArgs));
            }
            return;
        }
        state.toolCalls.add(incoming);
    }

    private void finishRound(
            List<Message> messages,
            Sinks.Many<String> sink,
            RoundState state,
            AtomicLong roundCounter,
            AtomicBoolean hasSentFinalResult,
            StringBuilder finalAnswerBuffer,
            boolean useMemory,
            String conversationId,
            AgentState agentState,
            StringBuilder thinkingBuffer) {
        if(state.getMode() != RoundMode.TOOL_CALL) {
            String referenceJson = "";
            String finalText = state.textBuffer.toString();
            if (!agentState.searchResults.isEmpty()){
                String reference = JSON.toJSONString(agentState.searchResults);
                referenceJson = createReferenceResponse(reference);
                sink.tryEmitNext(referenceJson);
            }

            if (enableRecommendations) {
                 String recommentations = generateRecommendations(conversationId, currentQuestion, finalText);
                 if (recommentations != null) {
                     currentRecommendations = recommentations;
                     String recommendJson = createRecommendResponse(recommentations);
                     sink.tryEmitNext(recommendJson);
                 }
            }
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);
            return;
        }

        AssistantMessage assistantMessage = AssistantMessage.builder().toolCalls(state.toolCalls).build();
        messages.add(assistantMessage);

        if(maxRounds > 0 && roundCounter.get() >= maxRounds) {
            forceFinalStream(messages, sink, hasSentFinalResult, conversationId, agentState);
            return;
        }

        executeToolCalls(sink, state.toolCalls, messages, hasSentFinalResult, state, agentState, () -> {
            if (!hasSentFinalResult.get()) {
                scheduleRound(messages, sink, roundCounter,
                        hasSentFinalResult, finalAnswerBuffer,
                        useMemory, conversationId, agentState, thinkingBuffer);
            }
        });

    }

    private void forceFinalStream(
            List<Message> messages,
            Sinks.Many<String> sink,
            AtomicBoolean hasSentFinalResult,
            String conversationId,
            AgentState agentState) {
        List<Message> newMessage = new ArrayList<>();

        newMessage.add(new SystemMessage(ReactAgentPrompts.getWebSearchPrompt()));
        if (StringUtils.isNotEmpty(systemPrompt)) {
            newMessage.add(new SystemMessage(systemPrompt));
        }

        for (Message message:messages){
            if (!(message instanceof SystemMessage)) {
                newMessage.add(message);
            }
        }

        newMessage.add(new UserMessage("""
                你已达到最大推理轮次限制。
                请基于当前已有的上下文信息，
                直接给出最终答案。
                禁止再调用任何工具。
                如果信息不完整，请合理总结和说明。
                """));

        messages.clear();
        messages.addAll(newMessage);
        StringBuilder finalTextBuffer = new StringBuilder();

        Disposable disposable = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null){
                        return;
                    }

                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !hasSentFinalResult.get()){
                        sink.tryEmitNext(createTextResponse(text));
                        finalTextBuffer.append(text);
                    }
                })
                .doOnComplete(() -> {
                    String referenceJson = "";
                    String finalText = finalTextBuffer.toString();
                    if(!agentState.searchResults.isEmpty()) {
                        String reference = JSON.toJSONString(agentState.searchResults);
                        referenceJson = createReferenceResponse(reference);
                        sink.tryEmitNext(referenceJson);
                    }
                    if (enableRecommendations) {
                        String recommendations = generateRecommendations(conversationId, currentQuestion, finalText);
                        if (recommendations != null) {
                            currentRecommendations = recommendations;
                            String recommendJson = createRecommendResponse(recommendations);
                            sink.tryEmitNext(recommendJson);
                        }
                    }

                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(err);
                }).subscribe();
        if (conversationId != null && taskManager != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
    }

    private void executeToolCalls(Sinks.Many<String> sink, List<AssistantMessage.ToolCall> toolCalls, List<Message> messages, AtomicBoolean hasSentFinalResult, RoundState state, AgentState agentState, Runnable onComplete) {
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalTollCalls = toolCalls.size();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            Schedulers.boundedElastic().schedule(() -> {
                if (hasSentFinalResult.get()) {
                    completeToolCall(completedCount, totalTollCalls, onComplete);
                    return;
                }

                String toolName = toolCall.name();
                String argsJson = toolCall.arguments();

                ToolCallback callback = findTool(toolName);
                if (callback == null) {
                    addErrorToolResponse(messages, toolCall, "工具未找到：" + toolName);
                    completeToolCall(completedCount, totalTollCalls, onComplete);
                    return;
                }
                if (toolName.contains("search")) {
                    JSONObject args = JSON.parseObject(argsJson);
                    String query = (String) args.get("query");
                    String queryThink = StringUtils.isNotBlank(query) ? "🔍 正在搜索信息: " + query + "\n" : "🔍 正在搜索相关信息\n";
                    sink.tryEmitNext(createThinkingResponse(queryThink));
                }

                try {
                    Object result = callback.call(argsJson);
                    ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, result.toString());
                    messages.add(ToolResponseMessage.builder().responses(List.of(toolResponse)).build());

                    recordUsedTool(toolName);

                    if (toolName.contains("tavily")) {
                        parseSearchResult(result.toString(), agentState);
                    }
                } catch (Exception e) {
                    addErrorToolResponse(messages, toolCall,"工具执行失败：" + e.getMessage());
                } finally {
                    completeToolCall(completedCount, totalTollCalls, onComplete);
                }
            });
        }
    }

    private void parseSearchResult(String resultJson, AgentState agentState) {
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            if (!root.isArray() || root.isEmpty()) {
                return;
            }
            JsonNode first = root.get(0);
            JsonNode textNode = first.get("text");

            if (textNode == null || textNode.isNull()){
                return;
            }

            JsonNode textJson;
            if (textNode.isTextual()) {
                textJson = MAPPER.readTree(textNode.asText());
            } else {
                textJson = textNode;
            }

            JsonNode results = textJson.get("result");
            if (results == null || !results.isArray()){
                return;
            }

            for (JsonNode item : results) {
                String url = getSafe(item, "url");
                String title = getSafe(item, "title");
                String content = getSafe(item, "content");

                if (url != null && !url.isBlank()) {
                    agentState.searchResults.add(new SearchResult(url, title, content));
                }
            }

        } catch (Exception e) {
            log.warn("解析 tavily 搜索结果失败: {}", e.getMessage());
        }
    }

    private String getSafe(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
    protected void recordUsedTool(String toolName) {
        if (usedTools != null && toolName != null) {
            usedTools.add(toolName);
        }
    }

    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                toolCall.id(),
                toolCall.name(),
                "{ \"error\": \"" + errMsg + "\" }"
        );

        messages.add(ToolResponseMessage.builder()
                .responses(List.of(toolResponse))
                .build());
    }

    private ToolCallback findTool(String name) {
        return toolCallbacks.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private void completeToolCall(AtomicInteger completedCount, int total, Runnable onComplete) {
        int current = completedCount.incrementAndGet();
        if(current >= total) {
            onComplete.run();
        }
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
        private AgentTaskManager agentTaskManager;

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

        public Builder agentTaskManager(AgentTaskManager agentTaskManager) {
            this.agentTaskManager = agentTaskManager;
            return this;
        }


        public WebSearchReactAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空！");
            }
            return new WebSearchReactAgent(name, chatModel, tools, systemPrompt, maxRounds, chatMemory, advisors, sessionService, agentTaskManager);
        }
    }

}
