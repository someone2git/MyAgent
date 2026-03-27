package cn.myagent.llm.myagent.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AgentTaskManager {

    public static class TaskInfo {

        private final Sinks.Many<String> sink;

        private Disposable disposable;

        private final long createTime;

        private String agentType;

        public TaskInfo(Sinks.Many<String> sink, String agentType) {
            this.sink = sink;
            this.agentType = agentType;
            this.createTime = System.currentTimeMillis();
        }

        public Sinks.Many<String> getSink() {
            return sink;
        }

        public Disposable getDisposable() {
            return disposable;
        }

        public void setDisposable(Disposable disposable) {
            this.disposable = disposable;
        }

        public long getCreateTime() {
            return createTime;
        }

        public String getAgentType() {
            return agentType;
        }

        public void setAgentType(String agentType) {
            this.agentType = agentType;
        }
    }

    private final Map<String, TaskInfo> taskInfoMap = new ConcurrentHashMap<>();

    public TaskInfo registerTask(String coversationId, Sinks.Many<String> sink, String agentType) {
        TaskInfo existing = taskInfoMap.get(coversationId);
        if (existing != null) {
            log.warn("会话任务正在执行，会话id：{}", coversationId);
            return null;
        }
        TaskInfo taskInfo = new TaskInfo(sink, agentType);
        taskInfoMap.put(coversationId, taskInfo);
        log.info("注册任务成功，会话id：{},agenttype:{}", coversationId, agentType);
        return taskInfo;
    }

    public void setDisposable(String conversationId, Disposable disposable) {
        TaskInfo taskInfo = taskInfoMap.get(conversationId);
        if (taskInfo == null) {
           log.warn("会话id:{}任务未注册", conversationId);
           return;
        }
        taskInfo.setDisposable(disposable);
    }

    public boolean stopTask(String conversationId) {
        TaskInfo taskInfo = taskInfoMap.get(conversationId);
        if (taskInfo == null) {
            log.warn("会话id:{}没有正在执行的任务",conversationId);
            return false;
        }
        try {
            //1.中断底层调用
            Disposable disposable = taskInfo.getDisposable();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
                log.info("会话{}中断底层调用", conversationId);
            }

            // 2.中断前端响应
            Sinks.Many<String> sink = taskInfo.getSink();
            if (sink != null) {
                // 2.1 通知前段链接结束
                sink.tryEmitNext(createStopMessage());
                // 2.2 结束响应流程
                sink.tryEmitComplete();
            }
            taskInfoMap.remove(conversationId);
            return true;
        } catch (Exception e) {
            log.error("停止任务失败，会话id：{}", conversationId);
            return false;
        }
    }

    private String createStopMessage() {
        JSONObject obj = new JSONObject();
        obj.put("type", "text");
        obj.put("content", "⏹ 用户已停止生成\n");
        return JSON.toJSONString(obj);
    }

    public boolean hasRunningTask(String conversationId) {
        return taskInfoMap.containsKey(conversationId);
    }
}
