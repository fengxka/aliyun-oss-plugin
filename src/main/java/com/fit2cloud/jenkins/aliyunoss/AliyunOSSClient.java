package com.fit2cloud.jenkins.aliyunoss;

import com.aliyun.oss.OSSClient;
import com.fit2cloud.AntPathMatcher;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

public class AliyunOSSClient {
    private static final String fpSeparator = ";";

    public static boolean validateAliyunAccount (final String aliyunAccessKey, final String aliyunSecretKey)
        throws AliyunOSSException {
        try {
            OSSClient client = new OSSClient(aliyunAccessKey, aliyunSecretKey);
            client.listBuckets();
        }
        catch (Exception e) {
            throw new AliyunOSSException("阿里云账号验证失败：" + e.getMessage());
        }
        return true;
    }

    public static boolean validateOSSBucket (String aliyunAccessKey, String aliyunSecretKey, String bucketName)
        throws AliyunOSSException {
        try {
            OSSClient client = new OSSClient(aliyunAccessKey, aliyunSecretKey);
            client.getBucketLocation(bucketName);
        }
        catch (Exception e) {
            throw new AliyunOSSException("验证Bucket名称失败：" + e.getMessage());
        }
        return true;
    }

    private static boolean matchAny (List<String> patterns, final String fileName) {
        boolean isMatch = false;
        if (StringUtils.isBlank(fileName) || CollectionUtils.isEmpty(patterns)) {
            return isMatch;
        }
        final AntPathMatcher pathMatcher = new AntPathMatcher();
        for (String pattern : patterns) {
            isMatch = pathMatcher.match(pattern, fileName);
            if (isMatch) {
                break;
            }
        }
        return isMatch;
    }

    private static List<File> getFileListRecursive (File root, FileFilter fileFilter) {
        List<File> resultFiles = new ArrayList<File>();
        if (root != null && root.exists()) {
            if (root.isDirectory()) {
                File[] files = root.listFiles();
                for (File file : files) {
                    if (file.isDirectory()) {
                        resultFiles.addAll(getFileListRecursive(file, fileFilter));
                    }
                    else {
                        if (fileFilter.accept(file)) {
                            resultFiles.add(file);
                        }
                    }
                }
            }
            else {
                if (fileFilter.accept(root)) {
                    resultFiles.add(root);
                }
            }
        }
        return resultFiles;
    }

    private static List<File> parseWorkspaceFiles (BuildListener listener, String filePath, String wsPath) {

        List<String> patternTempList = new ArrayList<String>();
        if (StringUtils.isNotBlank(filePath)) {
            String[] splitedStrArray = StringUtils.split(filePath, fpSeparator);
            for (String tmp : splitedStrArray) {
                patternTempList.add(StringUtils.join(wsPath, tmp));
            }
        }
        final List<String> patterns = new ArrayList<String>(patternTempList);

        listener.getLogger().println(MessageFormat.format("解析后的通配符表达式为:{0}", StringUtils.join(patterns.toArray())));
        FileFilter fileFilter = new FileFilter() {
            @Override
            public boolean accept (File file) {
                return matchAny(patterns, file.getAbsolutePath());
            }
        };
        return getFileListRecursive(new File(wsPath), fileFilter);
    }

    public static int upload (AbstractBuild<?, ?> build, BuildListener listener, final String aliyunAccessKey,
        final String aliyunSecretKey, final String aliyunEndPointSuffix, String bucketName, String filePath,
        String objectPrefix) throws AliyunOSSException {
        String endpoint = "http://" + aliyunEndPointSuffix;
        OSSClient client = new OSSClient(endpoint, aliyunAccessKey, aliyunSecretKey);
        int filesUploaded = 0;
        List<File> allMatchFiles = null;

        FilePath workspacePath = build.getWorkspace();
        if (workspacePath == null) {
            listener.getLogger().println("工作空间中没有任何文件.");
            return filesUploaded;
        }
        String wsPath = null;
        try {
            wsPath = workspacePath.toURI().toString();
            if (!StringUtils.endsWith(wsPath, "/")) {
                wsPath = wsPath.concat("/");
            }
            if (StringUtils.startsWith(wsPath, "file:")) {
                wsPath = StringUtils.substringAfter(wsPath, "file:");
            }
            listener.getLogger().println("当前工作空间路径:" + wsPath);
            if (StringUtils.isBlank(filePath)) {
                listener.getLogger().println("未指定任何规则,终止上传");
                return filesUploaded;
            }
            allMatchFiles = parseWorkspaceFiles(listener, filePath, wsPath);
        }
        catch (Exception e) {
            listener.getLogger().println("解析工作空间下文件失败:" + e.getMessage());
        }

        if (CollectionUtils.isNotEmpty(allMatchFiles)) {
            listener.getLogger().println("准备上传文件到阿里云OSS...");
            listener.getLogger().println(MessageFormat.format("预计将上传{0}个文件", allMatchFiles.size()));
            listener.getLogger().println("上传endpoint是:" + endpoint);
            for (File file : allMatchFiles) {
                listener.getLogger().println(MessageFormat.format("文件:{0} 开始上传", file.getAbsolutePath()));
                try {
                    uploadSingleFile(wsPath, bucketName, objectPrefix, file, client);
                    filesUploaded++;
                }
                catch (Exception e) {
                    listener.getLogger()
                        .println(MessageFormat.format("文件:{0} 上传失败,exception:{1}",file.getAbsolutePath(), e.getMessage()));
                }
            }
        }else {
            listener.getLogger().println("上传文件终止,未找到匹配的文件...");
        }
        return filesUploaded;
    }

    private static void uploadSingleFile (String basePath, String bucketName, String topFolderName, File file,
        OSSClient client) throws Exception {
        String key = StringUtils.substringAfter(file.getAbsolutePath(), basePath);
        if (StringUtils.isNotBlank(topFolderName)) {
            topFolderName = topFolderName.endsWith("/") ? topFolderName : topFolderName.concat("/");
            key = StringUtils.join(topFolderName, key);
        }
        client.deleteObject(bucketName,key);
        client.putObject(bucketName, key, file);
    }

    public static String getTime (long timeInMills) {
        return DurationFormatUtils.formatDuration(timeInMills, "HH:mm:ss.S") + " (HH:mm:ss.S)";
    }

    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types/Complete_list_of_MIME_types
    // support the common web file types for now
    private static final String[] COMMON_CONTENT_TYPES = { ".js", "application/js", ".json", "application/json", ".svg",
        "image/svg+xml", ".woff", "application/x-font-woff", ".woff2", "application/x-font-woff", ".ttf",
        "application/x-font-ttf"
    };

    // http://www.rgagnon.com/javadetails/java-0487.html
    // we don't use the more robust solutions (checking magic headers) here, because the file stream might be
    // loaded remotely, so that would be time consuming in checking, even hangs the Jenkins build in my test.
    private static String getContentType (FilePath filePath) {
        FileNameMap fileNameMap = URLConnection.getFileNameMap();
        String fileName = filePath.getName();
        String type = fileNameMap.getContentTypeFor(fileName);

        if (type == null) {
            for (int i = 0; i < COMMON_CONTENT_TYPES.length; i += 2) {
                String extension = COMMON_CONTENT_TYPES[i];
                int beginIndex = Math.max(0, fileName.length() - extension.length());
                if (fileName.substring(beginIndex).equals(extension)) {
                    return COMMON_CONTENT_TYPES[i + 1];
                }
            }
            type = "application/octet-stream";
        }
        return type;
    }

}
