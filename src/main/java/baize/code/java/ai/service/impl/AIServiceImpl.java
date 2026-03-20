package baize.code.java.ai.service.impl;

import baize.code.java.ai.adviser.CustomizationMemoryAdviser;
import baize.code.java.ai.service.AIService;
import baize.code.java.code.DocumentCode;
import baize.code.java.entity.Role;
import baize.code.java.entity.SessionLog;
import baize.code.java.mapper.SessionMapper;
import baize.code.java.service.RoleService;
import baize.code.java.service.SessionService;
import baize.code.java.utils.KeyUtils;
import baize.code.java.websocket.endpoint.UserServiceEndpoint;
import baize.code.java.websocket.message.ChatMessage;
import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgent;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AIServiceImpl implements AIService {

    @Autowired
    private ChatClient chatClient;
    @Autowired
    private SessionMapper sessionMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RoleService roleService;
    @Autowired
    private CustomizationMemoryAdviser customizationMemoryAdviser;
    
    private VectorStoreDocumentRetriever vectorStoreDocumentRetriever;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private DashScopeChatModel dashScopeChatModel;
    
    @Value("${session.key}")
    private String sessionKey;
    @Value("${session.expiration-duration}")
    private long sessionExpireTime;
    @Value("classpath:template/convert-to-manual-judgment-prompts.st")
    private Resource contextResource;//转人工的提示词资源
    @Value("classpath:template/customer-service-role.st")
    private Resource customerServiceRoleResource;
    @Value("${retrieval.threshold}")
    private Double threshold;
    @Value("${retrieval.number}")
    private Integer number;
    @Value("classpath:template/relevant-information-cannot-be-retrieved.st")
    private Resource relevantInformationCannotBeRetrievedResource;
    @Value("classpath:template/query-optimization.st")
    private Resource queryOptimizationResource;
    /**
     * 判断是否需要转人工
     * @param message
     * @param session1
     */
    @Override
    @Transactional
    public void turnToManualJudgment(ChatMessage message, baize.code.java.entity.Session session1) {
        //调用大模型判断是否需要转人工
        String content = chatClient.prompt()
                .system(new ClassPathResource("template/convert-to-manual-judgment-prompts.st"), StandardCharsets.UTF_8) //系统的提示词
                .user(message.getMessage())//用户的promote
                .call() //进行调用
                .content();//拿取出来
        //将大模型的输出转诚Boolean类型
        boolean needTransfer = false;
        if(content != null){
            System.out.println("AI返回的原始内容: [" + content + "]");  // 加这行
            System.out.println("内容长度: " + content.length());  // 加这行
            /*// 修改这里：只要包含转人工的关键词就认为需要转人工
            String lower = content.toLowerCase();
            needTransfer = lower.contains("已为您转接") ||
                    lower.contains("转接人工") ||
                    lower.contains("人工客服") ||
                    content.trim().equalsIgnoreCase("true");

            System.out.println("needTransfer: " + needTransfer);
            */
            needTransfer = Boolean.parseBoolean(content.trim());
        }
        //判断是否需要转人工
        if(needTransfer){
            //从数据库查询出会话
            baize.code.java.entity.Session session2 = sessionMapper.selectById(message.getSessionId());
            //修改会话的状态为人工
            session1.setConversationStatus(baize.code.java.entity.Session.ConversationStatus.HUMAN);
            //更新数据库为human
            sessionMapper.updateById(session1);
            //保存redis
            stringRedisTemplate.opsForValue().set(KeyUtils.redisKeyUtils(sessionKey,message.getSessionId()),
                    JSONUtil.toJsonStr(session1),
                    sessionExpireTime, 
                    TimeUnit.MINUTES);
        }
    }

    /**
     * ai对话
     * 创建一个“提示词模板对象（PromptTemplate）”，用于把模板文件 + 变量数据，生成最终发送给 AI 模型的 Prompt。 🤖
     * @param session
     * @param message
     * @param userServiceEndpoint
     */
    @Override
    public void chat(Session session, ChatMessage message, UserServiceEndpoint userServiceEndpoint) throws IllegalAccessException, EncodeException, IOException {
        /**
         * 构建提示词模版
         */
        //获取到role客服的信息
        Role role = roleService.getRoleById(message.getCtId());
        //创建一个promote模版对象
        PromptTemplate promptTemplate = PromptTemplate.builder()
                .renderer(StTemplateRenderer
                        .builder()
                        .startDelimiterToken('<')
                        .endDelimiterToken('>')
                        .build())
                .resource( customerServiceRoleResource)
                .build();
        //new一个hashmap存放模板
        HashMap<String, Object> pos = new HashMap<>();
        //获取role的字段
        Class<? extends Role> aClass = role.getClass();
        Field[] declaredFields = aClass.getDeclaredFields();
        //遍历每一个字段，只要string类型的
        for (Field declaredField : declaredFields) {
            if(declaredField.getType() == String.class){
                //将string类型的字段设置成可访问
                declaredField.setAccessible(true); //设置可访问
                //将这个字段和值放进pos
                pos.put(declaredField.getName(),declaredField.get(role));  //declaredField.get(role) ->从role中取出这个字段的值
            }
        }
        //发起调用进行流式输出
        Flux<ChatResponse> chatResponseFlux = chatClient.prompt()
                .system(promptTemplate.render(pos))
                .user(message.getMessage())
                // 传递会话ID作为记忆的唯一ID 作用在于获取这一次会话的记忆 便于下一次会话的使用
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, message.getSessionId()))
                .advisors(customizationMemoryAdviser,
                        RetrievalAugmentationAdvisor.builder()
                                .order(1) // 确保在记忆存储之后
                                //构建文档检索器
                                .documentRetriever(
                                        //构建向量文档检索器
                                        VectorStoreDocumentRetriever.builder()
                                                .vectorStore(vectorStore)
                                                .similarityThreshold(threshold) // 设置相似度阈值，不能过大
                                                .topK(number) // 设置最多返回的条数
                                                //设置过滤表达式
                                                .filterExpression(
                                                        new Filter.Expression(
                                                                Filter.ExpressionType.EQ,
                                                                new Filter.Key(DocumentCode.GOODS_ID),
                                                                new Filter.Value(message.getGoodsId())
                                                        )
                                                )
                                                .build() // 构建文档检索器
                                )//配置查询增强器
                                .queryAugmenter(ContextualQueryAugmenter.builder()
                                        .allowEmptyContext(false)
                                        .emptyContextPromptTemplate(promptTemplate.builder()
                                                .resource(relevantInformationCannotBeRetrievedResource)
                                                .build())
                                        .build())
                                .queryTransformers(TranslationQueryTransformer.builder()
                                        .chatClientBuilder(ChatClient.builder(dashScopeChatModel))//配置翻译所使用的ai
                                        .targetLanguage("chinese") //【配置需要的语言】
                                        .build())
                                .queryTransformers(RewriteQueryTransformer.builder()
                                        .chatClientBuilder(ChatClient.builder(dashScopeChatModel))
                                        .targetSearchSystem("商品客服小助手")
                                        .promptTemplate(promptTemplate.builder()
                                                .resource(queryOptimizationResource)
                                                .build())
                                        .build())
                                .build() // 完成 RetrievalAugmentationAdvisor 构建
                )
                .stream() // 使用流式调用
                .chatResponse(); // 返回聊天响应
        
        
        chatResponseFlux.toIterable().forEach(chatResponse -> {
            try {
                userServiceEndpoint.sendMessage(ChatMessage.builder()
                                .sessionId(message.getSessionId())
                                .type(SessionLog.Type.ASSISTANT)
                                .message(String.valueOf(chatResponse.getResult().getOutput()))
                        .build());
            } catch (EncodeException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        userServiceEndpoint.sendMessage(ChatMessage.builder().state(ChatMessage.State.END).build());
    }
}
