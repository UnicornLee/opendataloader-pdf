package org.opendataloader.pdf.custom.utils;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

public class MarkDownUtils {

    public static String markdownToPlainText(String markdown) {
        // 配置Markdown解析器
        //MutableDataSet options = new MutableDataSet();
        //Parser parser = Parser.builder(options).build();
        //HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        //
        //// 1. 将Markdown转换为HTML
        //Node document = parser.parse(markdown);
        //String html = renderer.render(document);
        //
        //// 2. 使用JSoup去除HTML标签
        //return Jsoup.parse(html).text();
        return null;
    }

    /**
     * 将Markdown转换为纯文本（保留基本段落结构）
     * @param markdown Markdown格式文本
     * @return 纯文本内容
     */
    public static String markdownToPlainTextV2(String markdown) {
        if(markdown != null && !"".equals(markdown.trim())){
            return markdown;
        }
        Parser parser = Parser.builder().build();
        Node document = parser.parse(markdown);

        // 自定义文本渲染器
        TextContentRenderer renderer = TextContentRenderer.builder()
            .stripNewlines(false)  // 保留换行
            .build();

        return renderer.render(document);
    }

}
