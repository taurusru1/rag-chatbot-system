package baize.code.java.utils;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 *  文档提取的工具类
 */
@Component
public class FileToDocuments {
    @Autowired
    private static ChineseTokenTextSplitter chineseTokenTextSplitter;
    
    //
    public List<Document> handle(MultipartFile file){
        //调用方法 将不同的文件调用合理的文件读取器解析为Document对象
       FileHandle fileHandle = classifier(file); //调用classfire，辨别文档的类型
       return fileHandle.run(file);
    }

    private FileHandle classifier(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if(fileName== null){
            throw new IllegalArgumentException("文件名不能为空");
        }
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return switch(extension){
            case "md", "markdown" -> new MarkdownFileHandle();
            case "pdf" -> new PDFFileHandle();
            case "txt" -> new textFilehandle();
            default -> throw new IllegalArgumentException("不支持的文件类型: " + extension);
        };
    }
    
    
    public interface FileHandle{
        List<Document> run(MultipartFile file);
    }
    
    //markdown文档的读取
    private static class MarkdownFileHandle implements FileHandle{
        @Override
        public List<Document> run(MultipartFile file) {
            //使用springAi的MarkdownDocumentReader进行
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(false) //不按照分割线创建新的document
                    .withIncludeCodeBlock(false) //不按照代码快进行创建document
                    .build();
            
            //将MultipartFile封装成Resource
            Resource resource = null;
            try {
                resource = new ByteArrayResource(file.getBytes());
            } catch (IOException e) {
                throw new RuntimeException("读取文件失败",e);
            }
            
            MarkdownDocumentReader markdownDocumentReader = new MarkdownDocumentReader(resource, config);

            List<Document> documents = markdownDocumentReader.read();

            documents = chineseTokenTextSplitter.quicklyBuilder().split(documents);
            
            return documents;
        }
    }
    
    //pdf文档的读取
    private static class PDFFileHandle implements FileHandle{
        @Override
        public List<Document> run(MultipartFile file) {

            Resource resource =null;
            try {
                 resource= new ByteArrayResource(file.getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            PagePdfDocumentReader pagePdfDocumentReader = new PagePdfDocumentReader(resource);
            List<Document> pdfDocument = pagePdfDocumentReader.read();
            pdfDocument = chineseTokenTextSplitter.quicklyBuilder().split(pdfDocument);
            return pdfDocument;
        }
    }
    private static class textFilehandle implements FileHandle{
        @Override
        public List<Document> run(MultipartFile file) {
            
            //读取文档中的每一行
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(file.getInputStream()))) {
                StringBuilder content = new StringBuilder();
                String line;
                
                while((line=  reader.readLine())!=null ){
                    content.append(line);
                }

                List<Document> documents = new ArrayList<>();
                documents.add(new Document(content.toString()));
                documents = chineseTokenTextSplitter.quicklyBuilder().split(documents);
                return documents;
            } catch (IOException e) {
                throw new RuntimeException("读取文本文件失败", e);
            }
        }
    }
}
