/*
 * Copyright 2025-2026 Hancom Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opendataloader.pdf.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ServerApplication {

    public static void main(String[] args) {
        // Force-load the JPEG2000 ImageReader SPI before the PDF processing
        // pipeline starts. PDFBox's JPEG2000Factory looks up the reader via
        // ImageIO.getImageReadersByFormatName("JPEG2000"), which depends on
        // IIORegistry having scanned META-INF/services entries. In Spring
        // Boot's nested-jar layout (BOOT-INF/lib/*.jar) the
        // LaunchedURLClassLoader does not always surface those resources to
        // IIORegistry's own scan path, so PDFBox ends up throwing
        // "Cannot read JPEG2000 image: Java Advanced Imaging (JAI) Image I/O
        // Tools are not installed" the first time a JPXDecode image is hit
        // (reached from LineArtProcessor.renderPageToImage and
        // StreamTableProcessor). Touching the SPI class here triggers its
        // static initializer, which registers the provider explicitly.
        try {
            Class.forName("com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi");
        } catch (ClassNotFoundException missing) {
            // JAI ImageIO JPEG2000 is not on the classpath at all. PDFBox
            // will surface a clearer error later when it actually tries to
            // decode a JPEG2000 image; nothing to do here.
        }
        SpringApplication.run(ServerApplication.class, args);
    }
}
