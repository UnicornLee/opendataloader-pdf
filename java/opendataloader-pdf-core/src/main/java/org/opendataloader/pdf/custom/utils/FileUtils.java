package org.opendataloader.pdf.custom.utils;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class FileUtils {

    /**
     * 将 classpath 下的资源文件拷贝到指定目录
     *
     * @param resourcePath classpath 下的资源路径，例如 "templates/report.xlsx"
     * @param targetDir    目标目录（文件系统路径）
     * @return 拷贝后的目标文件路径
     * @throws IOException IO异常
     */
    public static Path copyResourceToDir(String resourcePath, String targetDir) throws IOException {
        // 1. 确保目标目录存在
        Path targetPath = Paths.get(targetDir);
        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
        }

        // 2. 取目标文件名
        String fileName = Paths.get(resourcePath).getFileName().toString();
        Path targetFile = targetPath.resolve(fileName);

        // 3. 通过 ClassLoader 读取 resources 下的资源
        try (InputStream in = FileUtils.class.getClassLoader()
            .getResourceAsStream(resourcePath);
             OutputStream out = Files.newOutputStream(targetFile,
                 StandardOpenOption.CREATE,
                 StandardOpenOption.TRUNCATE_EXISTING)) {

            if (in == null) {
                throw new FileNotFoundException("资源未找到: " + resourcePath);
            }

            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        }
        return targetFile;
    }

    /**
     * 按行读取 classpath 下的 UTF-8 文本资源。
     *
     * <p>用于读取打包在 jar 中 {@code src/main/resources} 下的 HTML / CSS / JS
     * 等文本模板，和 {@link #copyResourceToDir(String, String)} 使用相同的
     * ClassLoader 查找逻辑，避免出现"同一份模板一种用 classpath、一种用
     * 文件系统"这种隐患。
     *
     * @param resourcePath classpath 下的资源路径，例如 "templates/announcementAnalysis.html"
     * @return 文本资源的每一行作为列表元素（保持原有换行结构）
     * @throws IOException           读取失败时抛出
     * @throws FileNotFoundException 资源在 classpath 中找不到时抛出
     */
    public static List<String> readResourceLines(String resourcePath) throws IOException {
        InputStream in = FileUtils.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new FileNotFoundException("资源未找到: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.toList());
        }
    }

    /**
     * 将字符串内容写入指定路径的文件。
     *
     * <p>使用 UTF-8 编码；若文件已存在则覆盖，不存在则创建；
     * 父目录不存在时会自动创建。
     *
     * @param fileName 目标文件路径（绝对或相对）
     * @param content  要写入的字符串内容（允许为空字符串）
     * @return 写入后的目标文件路径
     * @throws IOException IO 异常
     */
    public static Path writeToFile(String fileName, String content) throws IOException {
        Path targetFile = Paths.get(fileName);
        Path parent = targetFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Files.writeString(targetFile, Objects.requireNonNull(content, "content"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return targetFile;
    }
}
