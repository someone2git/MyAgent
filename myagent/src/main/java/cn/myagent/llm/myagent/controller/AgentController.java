package cn.myagent.llm.myagent.controller;

import cn.myagent.llm.myagent.agent.websearch.WebSearchReactAgent;
import cn.myagent.llm.myagent.manager.AgentTaskManager;
import cn.myagent.llm.myagent.service.AiSessionService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/agent")
@Slf4j
public class AgentController implements InitializingBean {

    @Resource
    private ChatModel chatModel;

    @Resource
    private AiSessionService aiSessionService;

    @Resource
    private AgentTaskManager agentTaskManager;

    @Value("${tavily.api-key}")
    private String tavilyApiKey;

    @Value("${tavily.mcp-url}")
    private String tavilyMcpUrl;

    private ToolCallback[] webSearchToolCallbacks;

    @GetMapping(value = "/chat/stream", produces = "text/evetn-stream;charset=UTF-8")
    public Flux<String> webSearchStream(@RequestParam(required = true) String query, @RequestParam(required = true) String conversationId) {
        log.info("收到联网查询对话请求，查询内容query:{}, 对话id:{}", query, conversationId);
        if (query == null || query.trim().isEmpty()) {
            log.warn("查询参数为空或者无效");
            return Flux.error(new IllegalArgumentException("查询参数不能为空"));
        }

        try {
            //初始化联网查询reactagent
            WebSearchReactAgent webSearchReactAgent = initWebSearchAgent();
            //加载对话历史
            ChatMemory chatMemory = webSearchReactAgent.creatPersistentChatMemory(conversationId, 30);
            webSearchReactAgent.setChatMemory(chatMemory);
            //reactagent执行
            return webSearchReactAgent.stream(query, conversationId);
        } catch (Exception e) {
            log.error("联网查询对话请求处理异常：", e);
            return Flux.error(e);
        }
    }

    private WebSearchReactAgent initWebSearchAgent() {
        log.info("初始化网页搜索 Agent...");

        return WebSearchReactAgent.builder()
                .name("web react")
                .chatModel(chatModel)
                .tools(webSearchToolCallbacks)
                .sessionService(aiSessionService)
                .agentTaskManager(agentTaskManager)
                .maxRounds(5)
                .build();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("开始初始化工具toolcallback");

        // 初始化网页搜索工具回调
        initWebSearchToolCallbacks();

        log.info("工具toolcallback初始化完成");
    }

    private void initWebSearchToolCallbacks() throws Exception {
        log.info("初始化网页搜索工具回调...");

        // tavily 搜索引擎
        String authorizationHeader = "Bearer " + tavilyApiKey;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .header("Authorization", authorizationHeader);

        HttpClientStreamableHttpTransport tavTransport = HttpClientStreamableHttpTransport.builder(tavilyMcpUrl).requestBuilder(requestBuilder).build();
        McpSyncClient tavilyMcp = McpClient.sync(tavTransport)
                .requestTimeout(Duration.ofSeconds(120))
                .build();
        tavilyMcp.initialize();

        List<McpSyncClient> mcpClients = List.of(tavilyMcp);
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder().mcpClients(mcpClients).build();

        webSearchToolCallbacks = provider.getToolCallbacks();
        log.info("网页搜索工具回调初始化完成，工具数量: {}", webSearchToolCallbacks.length);
    }
}
