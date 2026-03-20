package baize.code.java.ai.adviser;

import baize.code.java.ai.memory.CustomizationMemory;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CustomizationMemoryAdviser implements BaseChatMemoryAdvisor {

    @Resource
    private CustomizationMemory customizationMemory;
//    @Resource
//    private PromptTemplate promptTemplate;
        private final PromptTemplate systemPromptTemplate = new PromptTemplate("{instructions}\n\nUse the conversation memory from the MEMORY section to provide accurate answers.\n\n---------------------\nMEMORY:\n{memory}\n---------------------\n\n");
    /**
     * 发送前的操作：
     * <p>
     * 此方法在发送消息前执行，主要功能包括：
     * </p>

     * <ol>
     *     <li>从聊天中提取发送的消息</li>

     *     <li>根据会话ID查询记忆</li>

     *     <li>首先从Redis中查询最近的几条记忆</li>

     *     <li>如果Redis中没有，则从MySQL中查询最近的10条记忆</li>

     *     <li>将当前记忆异步添加到Redis和MySQL中</li>

     *     <li>将查询到的记忆整合到新的prompt中</li>

     *     <li>返回增强后的prompt给chatClientRequest</li>

     * </ol>

     * @param chatClientRequest 待处理的ChatClientRequest对象
     * @param advisorChain   AdvisorChain对象，用于获取其他Advisor
     * @return ChatClientRequest对象，包含增强后的prompt
     */
    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        //获取到上下文(请求的元数据容器 大概是这种 "conversationId": "123456")
        Map<String, Object> context = chatClientRequest.context();
        //根据对象的id和会话id获取到会话id
        String conversationId = getConversationId(context, "default");
        //根据会话id获取到记忆
        List<Message> memoryMessage = customizationMemory.get(conversationId);
        //将记忆进行promte拼接设置
        String memory = memoryMessage.stream().filter((m) -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .map((m) -> {
                    // 将身份和对应的内容拼接
                    String identity = String.valueOf(m.getMessageType());
                    return identity + ":" + m.getText();
                }).collect(Collectors.joining(System.lineSeparator()));
        //获取到系统提示词
        SystemMessage systemMessage = chatClientRequest.prompt().getSystemMessage();
        //将提示词语记忆promote进行拼接，形成新的提示词
        String newSystemMessage = this.systemPromptTemplate.render(Map.of("instructions", systemMessage, "memory", memory));
        //复制一个聊天请求，并且修改提示词，进行构建
        ChatClientRequest processChatClientRequest = chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentSystemMessage(newSystemMessage))
                .build();
        //提取用户的信息用于存储
        UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
        this.customizationMemory.add(conversationId, userMessage);

        //将新的记忆进行返回
        return processChatClientRequest;
        
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        //获取会话的id
        String conversationId = getConversationId(chatClientResponse.context(),"default");
        if("default".equals(conversationId)){
            throw new RuntimeException("会话不能为空");
        }
        //获取到会话的信息
        ChatResponse chatResponse = chatClientResponse.chatResponse();
        //将会话的信息变成一个StringBuilder集合
        if(chatResponse==null){
            throw new RuntimeException("ai返回的消息为空");
        }
        //创建一个stringbuilder，将ai会话变成一个字符串
        StringBuilder content = new StringBuilder();
        for (Generation result : chatResponse.getResults()) {
            content.append(result.getOutput().getText());
        }
        AssistantMessage assistantMessage = new AssistantMessage(content.toString());
        //将会话的id与会话的信息存储到本地
        this.customizationMemory.add(conversationId, assistantMessage);
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 会话记忆流程的制定
     * @param chatClientRequest  下游模型（LLM）返回的 流式响应片段 或者为 模型实时输出的 token/文本流 
     *        aggregatedResponse  完整的一次回答（不是流片段）
     * @param streamAdvisorChain 它是一个“责任链（Chain）”，用来串联多个 Advisor（拦截器）并控制调用顺序
     * @return
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        //调用before
        ChatClientRequest processedRequest = this.before(chatClientRequest, streamAdvisorChain);
        //继续执行下游的流式调用
        Flux<ChatClientResponse> streamedResponses = streamAdvisorChain.nextStream(processedRequest);

        return new ChatClientMessageAggregator().aggregateChatClientResponse(
                streamedResponses,
                aggregatedResponse -> {
                    // 只在整个流结束、内容完整时调用一次 after()
                    this.after(aggregatedResponse, streamAdvisorChain);
                }
        );
    }
}
