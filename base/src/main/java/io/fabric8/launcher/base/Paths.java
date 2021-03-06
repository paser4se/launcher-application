/**
 * Copyright 2005-2015 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.launcher.base;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Enumeration;
import java.util.Set;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import static io.fabric8.launcher.base.PosixFilePermissionSupport.toOctalFileMode;
import static io.fabric8.launcher.base.PosixFilePermissionSupport.toPosixFilePermissions;

/**
 * {@link Path} related operations
 *
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
public final class Paths {

    private Paths() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * Unzip a zip file in a directory
     *
     * @param is        the zip file contents to be unzipped
     * @param outputDir the output directory
     * @throws IOException when we could not read the file
     */
    public static void unzip(InputStream is, Path outputDir) throws IOException {
        Path tmpzip = Files.createTempFile("tmpzip",".zip");
        try {
            Files.copy(is, tmpzip, StandardCopyOption.REPLACE_EXISTING);
            unzip(tmpzip, outputDir);
        } finally {
            Files.delete(tmpzip);
        }
    }

    public static void unzip(Path zip, Path outputDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry zipEntry = entries.nextElement();
                Path entry = outputDir.resolve(zipEntry.getName()).normalize();
                if (!entry.startsWith(outputDir)) {
                    throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
                }
                if (zipEntry.isDirectory()) {
                    Files.createDirectories(entry);
                } else {
                    if (!Files.isDirectory(entry.getParent())) {
                        Files.createDirectories(entry.getParent());
                    }
                    try (InputStream zis = zipFile.getInputStream(zipEntry)) {
                        Files.copy(zis, entry);
                    }
                    int mode = zipEntry.getUnixMode();
                    if (mode != 0) {
                        Set<PosixFilePermission> permissions = toPosixFilePermissions(mode);
                        Files.setPosixFilePermissions(entry, permissions);
                    }
                }
            }
        }
    }


    /**
     * Zips an entire directory and returns as a byte[]
     *
     * @param root      the root directory to be used
     * @param directory the directory to be zipped
     * @return a byte[] representing the zipped directory
     * @throws IOException if any I/O error happens
     */
    public static byte[] zip(String root, final Path directory) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        zip(root, directory, baos);
        return baos.toByteArray();
    }

    /**
     * Zips an entire directory and stores in the provided {@link OutputStream}
     *
     * @param root      the root directory to be used
     * @param directory the directory to be zipped
     * @param os        the {@link OutputStream} which the zip operation will be written to
     * @throws IOException if any I/O error happens
     */
    public static void zip(String root, final Path directory, OutputStream os) throws IOException {
        try (final ZipArchiveOutputStream zos = new ZipArchiveOutputStream(os)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entry = root + File.separator + directory.relativize(file).toString();
                    ZipArchiveEntry archiveEntry = new ZipArchiveEntry(file.toFile(), entry);
                    archiveEntry.setUnixMode(toOctalFileMode(Files.getPosixFilePermissions(file)));
                    zos.putArchiveEntry(archiveEntry);
                    Files.copy(file, zos);
                    zos.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String entry = root + File.separator + directory.relativize(dir).toString() + File.separator;
                    zos.putArchiveEntry(new ZipArchiveEntry(entry));
                    zos.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Deletes a directory recursively
     *
     * @param directory
     * @throws IOException
     */
    public static void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try {
                        Files.delete(file);
                    } catch (NoSuchFileException ignore) {
                        // Ignore if file is already removed by other process
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Joins all the given strings, ignoring nulls so that they form a URL with / between the paths without a // if the previous path ends with / and the next path starts with / unless a path item is blank
     *
     * @param strings A list of strings which you need to concatenate.
     * @return the strings concatenated together with / while avoiding a double // between non blank strings.
     */
    public static String join(String... strings) {
        StringBuilder buffer = new StringBuilder();
        for (String string : strings) {
            if (string == null) {
                continue;
            }
            if (buffer.length() > 0) {
                boolean bufferEndsWithSeparator = buffer.toString().endsWith("/");
                boolean stringStartsWithSeparator = string.startsWith("/");
                if (bufferEndsWithSeparator) {
                    if (stringStartsWithSeparator) {
                        string = string.substring(1);
                    }
                } else {
                    if (!stringStartsWithSeparator) {
                        buffer.append("/");
                    }
                }
            }
            buffer.append(string);
        }
        return buffer.toString();
    }
}