package cn.myagent.llm.myagent.controller;

import cn.myagent.llm.myagent.agent.websearch.WebSearchReactAgent;
import cn.myagent.llm.myagent.service.AiSessionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/agent")
@Slf4j
public class AgentController {

    @Resource
    private ChatModel chatModel;

    @Resource
    private AiSessionService aiSessionService;

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
            return webSearchReactAgent.stream(query, chatMemory);
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
                .maxRounds(5)
                .build();
    }

}
