/*
 * Copyright 2017-2023 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.openapi.visitor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedFile;
import io.micronaut.openapi.visitor.group.OpenApiInfo;
import io.swagger.v3.oas.models.info.Info;

import static io.micronaut.openapi.visitor.ConfigUtils.getConfigProperty;
import static io.micronaut.openapi.visitor.OpenApiApplicationVisitor.replacePlaceholders;
import static io.micronaut.openapi.visitor.OpenApiConfigProperty.MICRONAUT_OPENAPI_FILENAME;
import static io.micronaut.openapi.visitor.OpenApiConfigProperty.MICRONAUT_OPENAPI_TARGET_FILE;
import static io.micronaut.openapi.visitor.OpenApiConfigProperty.MICRONAUT_OPENAPI_VIEWS_DEST_DIR;

/**
 * File utilities methods.
 *
 * @since 4.10.0
 */
@Internal
public final class FileUtils {

    public static final String EXT_ADOC = ".adoc";
    public static final String EXT_YML = ".yml";
    public static final String EXT_YAML = ".yaml";
    public static final String EXT_JSON = ".json";

    private FileUtils() {
    }

    public static Path resolve(VisitorContext context, Path path) {
        if (!path.isAbsolute() && context != null) {
            Path projectPath = ConfigUtils.getProjectPath(context);
            if (projectPath != null) {
                path = projectPath.resolve(path);
            }
        }
        return path.toAbsolutePath();
    }

    public static void createDirectories(Path f, VisitorContext context) {
        if (f.getParent() != null) {
            try {
                Files.createDirectories(f.getParent());
            } catch (IOException e) {
                context.warn("Fail to create directories for" + f + ": " + e.getMessage(), null);
            }
        }
    }

    public static boolean isYaml(String path) {
        return path.endsWith(EXT_YML) || path.endsWith(EXT_YAML);
    }

    public static Path getViewsDestDir(Path defaultSwaggerFilePath, VisitorContext context) {
        String destDir = getConfigProperty(MICRONAUT_OPENAPI_VIEWS_DEST_DIR, context);
        if (StringUtils.isNotEmpty(destDir)) {
            Path destPath = resolve(context, Paths.get(destDir));
            createDirectories(destPath, context);
            return destPath;
        }
        return defaultSwaggerFilePath.getParent().resolve("views");
    }

    public static Optional<Path> getDefaultFilePath(String fileName, VisitorContext context) {
        // default location
        Optional<GeneratedFile> generatedFile = context.visitMetaInfFile("swagger/" + fileName, Element.EMPTY_ELEMENT_ARRAY);
        if (generatedFile.isPresent()) {
            URI uri = generatedFile.get().toURI();
            // happens in tests 'mem:///CLASS_OUTPUT/META-INF/swagger/swagger.yml'
            if (uri.getScheme() != null && !uri.getScheme().equals("mem")) {
                Path specPath = Paths.get(uri);
                createDirectories(specPath, context);
                return Optional.of(specPath);
            }
        }
        context.warn("Unable to get swagger/" + fileName + " file.", null);
        return Optional.empty();
    }

    public static Optional<Path> openApiSpecFile(String fileName, VisitorContext context) {
        Optional<Path> path = userDefinedSpecFile(context);
        if (path.isPresent()) {
            return path;
        }
        return getDefaultFilePath(fileName, context);
    }

    public static Optional<Path> userDefinedSpecFile(VisitorContext context) {
        String targetFile = getConfigProperty(MICRONAUT_OPENAPI_TARGET_FILE, context);
        if (StringUtils.isEmpty(targetFile)) {
            return Optional.empty();
        }
        Path specFile = resolve(context, Paths.get(targetFile));
        createDirectories(specFile, context);
        return Optional.of(specFile);
    }

    public static Pair<String, String> calcFinalFilename(String groupFileName, OpenApiInfo openApiInfo, boolean isSingleGroup, String ext, VisitorContext context) {

        String fileName = "swagger" + ext;
        String documentTitle = "OpenAPI";

        Info info = openApiInfo.getOpenApi().getInfo();
        if (info != null) {
            documentTitle = Optional.ofNullable(info.getTitle()).orElse(Environment.DEFAULT_NAME);
            documentTitle = documentTitle.toLowerCase(Locale.US).replace(' ', '-');
            String version = info.getVersion();
            if (version != null) {
                documentTitle = documentTitle + '-' + version;
            }
            fileName = documentTitle + ext;
        }

        String versionFromInfo = info != null && info.getVersion() != null ? info.getVersion() : StringUtils.EMPTY_STRING;

        String fileNameFromConfig = getConfigProperty(MICRONAUT_OPENAPI_FILENAME, context);
        if (StringUtils.isNotEmpty(fileNameFromConfig)) {
            fileName = replacePlaceholders(fileNameFromConfig, context) + ext;
            if (fileName.contains("${version}")) {
                fileName = fileName.replaceAll("\\$\\{version}", versionFromInfo);
            }
        }

        // construct filename for group
        if (!isSingleGroup) {
            if (StringUtils.isNotEmpty(groupFileName)) {
                fileName = groupFileName;
            } else {

                // default name: swagger-<version>-<groupName>-<apiVersion>

                fileName = fileName.substring(0, fileName.length() - ext.length())
                    + (openApiInfo.getGroupName() != null ? "-" + openApiInfo.getGroupName() : StringUtils.EMPTY_STRING)
                    + (openApiInfo.getVersion() != null ? "-" + openApiInfo.getVersion() : StringUtils.EMPTY_STRING);
            }

            fileName = replacePlaceholders(fileName, context) + ext;
            if (fileName.contains("${apiVersion}")) {
                fileName = fileName.replaceAll("\\$\\{apiVersion}", openApiInfo.getVersion() != null ? openApiInfo.getVersion() : versionFromInfo);
            }
            if (fileName.contains("${version}")) {
                fileName = fileName.replaceAll("\\$\\{version}", versionFromInfo);
            }
            if (fileName.contains("${group}")) {
                fileName = fileName.replaceAll("\\$\\{group}", openApiInfo.getGroupName() != null ? openApiInfo.getGroupName() : StringUtils.EMPTY_STRING);
            }
        }
        if (fileName.contains(Utils.PLACEHOLDER_PREFIX)) {
            context.warn("Can't set some placeholders in fileName: " + fileName, null);
        }

        return Pair.of(documentTitle, fileName);
    }
}
