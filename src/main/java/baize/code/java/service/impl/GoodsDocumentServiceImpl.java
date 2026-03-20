package baize.code.java.service.impl;

import baize.code.java.code.ResultCode;
import baize.code.java.common.Result;
import baize.code.java.service.GoodsDocumentService;
import baize.code.java.utils.FileToDocuments;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import baize.code.java.entity.GoodsDocument;
import baize.code.java.mapper.GoodsDocumentMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;


import java.util.List;

import static baize.code.java.code.DocumentCode.FILE_ID;
import static baize.code.java.code.DocumentCode.GOODS_ID;
import static baize.code.java.code.ResultCode.ADD_SUCCESS;
import static baize.code.java.code.ResultCode.DELETE_ERROR;

@Service
@RequiredArgsConstructor
public class GoodsDocumentServiceImpl extends ServiceImpl<GoodsDocumentMapper, GoodsDocument> implements GoodsDocumentService {

    private final MilvusVectorStore vectorStore;
    
    @Resource
    private FileToDocuments fileToDocuments;
   
    

    @Override
    public List<GoodsDocument> getListByGoodsId(Integer id) {
        return lambdaQuery().eq(GoodsDocument::getGoodsId, id).list();
    }

    

    @Override
    public Result<?> upload(MultipartFile file, Integer goodsId) {
        //将文本文档变成代码中适用的document文件
        List<Document> documents = fileToDocuments.handle(file);

        System.out.println(documents);
        //将处理好的文件放到数据库中
        GoodsDocument goodsDocuments = GoodsDocument.builder()
                .goodsId(goodsId)
                .name(file.getOriginalFilename())
                .build();
        save(goodsDocuments);
        //配置文本的元数据 
        //因为在一个商品上会有多个文本文件，应该设置它对应的id
        documents.forEach(document -> {
            document.getMetadata().put(FILE_ID,goodsDocuments.getId());
            document.getMetadata().put(GOODS_ID,goodsId);
                }
        );
        //将文档变量存储到向量数据库中vectorStore存储，每次只能存储十条
        for (int i = 0; i < documents.size(); i+=10) {
            int endIndex = Math.min(i + 10, documents.size());
            List<Document> batch = documents.subList(i, endIndex);
            vectorStore.add(batch);
        }
        
        return Result.success(ADD_SUCCESS,goodsDocuments);
    }

    /**
     * 根据文档id删除相应的文档信息
     * @param id
     * @return
     */
    @Override
    public Result<?> delete(Integer id) {
        //根据id查询相应的文档信息
        GoodsDocument fileInfo = getById(id);
        //判断文档信息是否存在
        if(fileInfo == null){
            return Result.error(DELETE_ERROR);
        }
        //进行删除
        boolean b = removeById(id);
        
        //判断是否删除成功
        if(!b){
            return Result.error(DELETE_ERROR);
        }
        //删除该文档的向量数据库的信息 【需要注意此处的元数据过滤表达式写法】
        vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(FILE_ID),
                new Filter.Value(id)
        ));
        //返回
        return Result.success(ResultCode.DELETE_SUCCESS);
    }
}
