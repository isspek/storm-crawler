/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.protocol.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.apache.http.HttpStatus;
import org.apache.storm.shade.org.apache.commons.io.IOUtils;
import org.slf4j.LoggerFactory;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.protocol.HttpHeaders;
import com.digitalpebble.stormcrawler.protocol.ProtocolResponse;

public class FileResponse {

    /**
     * Adopted from Apache Nutch File Protocol implementation
     * 
     * @see https://github.com/apache/nutch/blob/master/src/plugin/protocol-file
     *      /src/java/org/apache/nutch/protocol/file/FileResponse.java
     * @see https://github.com/apache/nutch/blob/master/src/plugin/protocol-file
     *      /src/java/org/apache/nutch/protocol/file/File.java
     * 
     **/

    private byte[] content;
    private int statusCode;
    private Metadata metadata;
    private final FileProtocol fileProtocol;
    static final SimpleDateFormat dateFormat = new SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
    static final org.slf4j.Logger LOG = LoggerFactory.getLogger(MethodHandles
            .lookup().lookupClass());

    public FileResponse(String u, Metadata md, FileProtocol fp)
            throws MalformedURLException, IOException {

        fileProtocol = fp;
        metadata = md;

        URL url = new URL(u);

        if (!url.getPath().equals(url.getFile())) {
            LOG.warn("url.getPath() != url.getFile(): {}.", url);
        }

        String path = "".equals(url.getPath()) ? "/" : url.getPath();

        File file = new File(
                URLDecoder.decode(path, fileProtocol.getEncoding()));

        if (!file.exists()) {
            statusCode = HttpStatus.SC_NOT_FOUND;
            return;
        }

        if (!file.canRead()) {
            statusCode = HttpStatus.SC_UNAUTHORIZED;
            return;
        }

        if (!file.equals(file.getCanonicalFile())) {
            metadata.setValue(HttpHeaders.LOCATION, file.getCanonicalFile()
                    .toURI().toURL().toString());
            statusCode = HttpStatus.SC_MULTIPLE_CHOICES;
            return;
        }

        if (file.isDirectory()) {
            getDirAsHttpResponse(file);
        } else if (file.isFile()) {
            getFileAsHttpResponse(file);
        } else {
            statusCode = HttpStatus.SC_INTERNAL_SERVER_ERROR;
            return;
        }

        if (content == null) {
            content = new byte[0];
        }
    }

    public ProtocolResponse toProtocolResponse() throws IOException {
        return new ProtocolResponse(content, statusCode, metadata);
    }

    private void getFileAsHttpResponse(File file) throws IOException {
        long size = file.length();

        if (size > Integer.MAX_VALUE) {
            statusCode = HttpStatus.SC_BAD_REQUEST;
            return;
        }

        try {
            content = IOUtils.toByteArray(new FileInputStream(file), size);

        } catch (IOException | IllegalArgumentException e) {
            LOG.error("Exception while fetching file response {} ",
                    file.getPath(), e);
            statusCode = HttpStatus.SC_METHOD_FAILURE;
            return;
        }

        metadata.setValue(HttpHeaders.CONTENT_LENGTH, new Long(size).toString());
        metadata.setValue(HttpHeaders.LAST_MODIFIED,
                formatDate(file.lastModified()));

        statusCode = HttpStatus.SC_OK;
    }

    private void getDirAsHttpResponse(File file) {
        content = generateSitemap(file);
        metadata.setValue(HttpHeaders.CONTENT_TYPE, "application/xml");
        metadata.setValue("isSitemap", "true");
        statusCode = HttpStatus.SC_OK;
    }

    private static String formatDate(long date) {

        return dateFormat.format(new Date(date));
    }

    private byte[] generateSitemap(File dir) {

        File[] files = dir.listFiles();
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        sb.append("<url>\n  <loc>").append("file:\\").append(dir.getPath())
                .append("\\</loc>\n").append("  <lastmod>")
                .append(formatDate(dir.lastModified()))
                .append("</lastmod>\n</url>\n");
        for (File file : files) {
            sb.append("<url>\n  <loc>").append("file:\\")
                    .append(file.getPath()).append("</loc>\n")
                    .append("  <lastmod>")
                    .append(formatDate(file.lastModified()))
                    .append("</lastmod>\n</url>\n");
        }
        if (fileProtocol.getCrawlParent()) {
            sb.append("<url>\n  <loc>").append("file:\\")
                    .append(dir.getParentFile().getPath()).append("\\</loc>\n")
                    .append("  <lastmod>")
                    .append(formatDate(dir.getParentFile().lastModified()))
                    .append("</lastmod>\n</url>\n");
        }
        sb.append("</urlset>");
        return new String(sb).getBytes();
    }

}