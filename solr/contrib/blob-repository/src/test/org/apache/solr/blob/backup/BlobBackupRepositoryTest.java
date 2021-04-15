/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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
package org.apache.solr.blob.backup;

import com.adobe.testing.s3mock.junit4.S3MockRule;
import com.amazonaws.services.s3.AmazonS3;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import com.google.common.base.Strings;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.store.*;
import org.apache.solr.blob.client.AdobeMockS3StorageClient;
import org.apache.solr.blob.client.BlobstoreProviderType;
import org.apache.solr.cloud.api.collections.AbstractBackupRepositoryTest;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.backup.repository.BackupRepository;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.carrotsearch.randomizedtesting.RandomizedTest.$;
import static com.carrotsearch.randomizedtesting.RandomizedTest.$$;
import static org.apache.solr.blob.backup.BlobBackupRepository.BLOB_SCHEME;

public class BlobBackupRepositoryTest extends AbstractBackupRepositoryTest {

    private static final String BUCKET_NAME = BlobBackupRepositoryTest.class.getSimpleName();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @ClassRule
    public static final S3MockRule S3_MOCK_RULE = S3MockRule.builder()
            .silent()
            .withInitialBuckets(BUCKET_NAME)
            .withHttpPort(AdobeMockS3StorageClient.DEFAULT_MOCK_S3_PORT)
            .build();

    @ParametersFactory(argumentFormatting = "%1$s")
    public static Iterable<Object[]> parameters() {
        return Arrays.asList($$($(BlobstoreProviderType.LOCAL), $(BlobstoreProviderType.S3MOCK)));
    }

    private final BlobstoreProviderType type;

    public BlobBackupRepositoryTest(BlobstoreProviderType type) {
        this.type = type;
    }

    /**
     * Sent by {@link org.apache.solr.handler.ReplicationHandler}, ensure we don't choke on the bare URI.
     */
    @Test
    public void testURI() {
        try (BlobBackupRepository repo = getRepository()) {
            URI uri = repo.createURI("x");
            assertEquals(BLOB_SCHEME, uri.getScheme());
            assertEquals("/x", uri.getPath());
            assertEquals("blob:/x", uri.toString());

        }
    }

    @Test
    public void testLocalDirectoryFunctions() throws Exception {

        try (BlobBackupRepository repo = getRepository()) {

            URI path = new URI("/test");
            repo.createDirectory(path);
            assertTrue(repo.exists(path));
            assertEquals(BackupRepository.PathType.DIRECTORY, repo.getPathType(path));
            assertEquals(repo.listAll(path).length, 0);

            URI subDir = new URI("/test/dir");
            repo.createDirectory(subDir);
            assertTrue(repo.exists(subDir));
            assertEquals(BackupRepository.PathType.DIRECTORY, repo.getPathType(subDir));
            assertEquals(repo.listAll(subDir).length, 0);

            assertEquals(repo.listAll(path).length, 1);

            repo.deleteDirectory(path);
            assertFalse(repo.exists(path));
            assertFalse(repo.exists(subDir));
        }
    }

    /**
     * Check resolving paths.
     */
    @Test
    public void testResolve() throws Exception {

        BlobBackupRepository repo = new BlobBackupRepository();

        // Add single element to root
        assertEquals(new URI("blob:/root/path"),
                repo.resolve(new URI("blob:/root"), "path"));

        // Root ends with '/'
        assertEquals(new URI("blob://root/path"),
                repo.resolve(new URI("blob://root/"), "path"));
        assertEquals(new URI("blob://root/path"),
                repo.resolve(new URI("blob://root///"), "path"));

        // Add to a sub-element
        assertEquals(new URI("blob://root/path1/path2"),
                repo.resolve(new URI("blob://root/path1"), "path2"));

        // Add two elements to root
        assertEquals(new URI("blob://root/path1/path2"),
                repo.resolve(new URI("blob://root"), "path1", "path2"));

        // Add compound elements
        assertEquals(new URI("blob:/root/path1/path2/path3"),
                repo.resolve(new URI("blob:/root"), "path1/path2", "path3"));

        // Check URIs with an authority
        assertEquals(new URI("blob://auth/path"),
                repo.resolve(new URI("blob://auth"), "path"));
        assertEquals(new URI("blob://auth/path1/path2"),
                repo.resolve(new URI("blob://auth/path1"), "path2"));
    }

    /**
     * Check
     * - pushing a file to the repo (backup).
     * - pulling a file from the repo (restore).
     */
    @Test
    public void testCopyFiles() throws Exception {

        // basic test with a small file
        String content = "Test to push a backup";
        doTestCopyFileFrom(content);
        doTestCopyFileTo(content);

        // copy a 10Mb file
        content += Strings.repeat("1234567890", 1024 * 1024);
        doTestCopyFileFrom(content);
        doTestCopyFileTo(content);
    }

    /**
     * Check copying a file to the repo (backup).
     * Specified content is used for the file.
     */
    private void doTestCopyFileFrom(String content) throws Exception {

        try (BlobBackupRepository repo = getRepository()) {

            // A file on the local disk (another storage than the local blob)
            File tmp = temporaryFolder.newFolder();
            FileUtils.write(new File(tmp, "from-file"), content, StandardCharsets.UTF_8);

            Directory sourceDir = new NIOFSDirectory(tmp.toPath());
            repo.copyIndexFileFrom(sourceDir, "from-file", new URI("blob://to-folder"), "to-file");

            // Sanity check: we do have different files
            File actualSource = new File(tmp, "from-file");
            File actualDest = pullBlob("to-folder/to-file");
            assertNotEquals(actualSource, actualDest);

            // Check the copied content
            assertTrue(actualDest.isFile());
            assertTrue(FileUtils.contentEquals(actualSource, actualDest));
        }
    }

    /**
     * Check retrieving a file from the repo (restore).
     * Specified content is used for the file.
     */
    private void doTestCopyFileTo(String content) throws Exception {

        try (BlobBackupRepository repo = getRepository()) {

            // Local folder for destination
            File tmp = temporaryFolder.newFolder();
            Directory destDir = new NIOFSDirectory(tmp.toPath());

            // Directly create a file in blob storage
            pushBlob("from-file", content);

            repo.copyIndexFileTo(new URI("blob:///"), "from-file", destDir, "to-file");

            // Sanity check: we do have different files
            File actualSource = pullBlob("from-file");
            File actualDest = new File(tmp, "to-file");
            assertNotEquals(actualSource, actualDest);

            // Check the copied content
            assertTrue(actualDest.isFile());
            assertTrue(FileUtils.contentEquals(actualSource, actualDest));
        }
    }

    /**
     * Check reading input with random access stream.
     */
    @Test
    public void testRandomAccessInput() throws Exception {

        // Test with a short text that fills in the buffer
        String content = "This is the content of my blob";
        doRandomAccessTest(content, content.indexOf("content"));

        // Large text, we force to refill the buffer
        String blank = Strings.repeat(" ", 5 * BufferedIndexInput.BUFFER_SIZE);
        content = "This is a super large" + blank + "content";
        doRandomAccessTest(content, content.indexOf("content"));
    }

    /**
     * Check implementation of {@link BlobBackupRepository#openInput(URI, String, IOContext)}.
     * Open an index input and seek to an absolute position.
     *
     * <p>We use specified text. It must has the word "content" at given position.
     */
    private void doRandomAccessTest(String content, int position) throws Exception {

        try (BlobBackupRepository repo = getRepository()) {
            File tmp = temporaryFolder.newFolder();

            // Open an index input on a file
            File subdir = new File(tmp, "my-repo");
            FileUtils.write(new File(subdir, "content"), content, StandardCharsets.UTF_8);
            repo.copyIndexFileFrom(new NIOFSDirectory(tmp.getAbsoluteFile().toPath()), "my-repo/content", new URI("blob://my-repo"), "content");
            IndexInput input = repo.openInput(new URI("blob://my-repo"), "content", IOContext.DEFAULT);

            byte[] buffer = new byte[100];

            // Read 4 bytes
            input.readBytes(buffer, 0, 4);
            assertEquals("This", new String(buffer, 0, 4, StandardCharsets.UTF_8));

            // Seek to the work 'content' and read it
            input.seek(position);
            input.readBytes(buffer, 0, 7);
            assertEquals("content", new String(buffer, 0, 7, StandardCharsets.UTF_8));
        }
    }

    /**
     * Check we gracefully fail when seeking before current position of the stream.
     */
    @Test
    public void testBackwardRandomAccess() throws Exception {

        try (BlobBackupRepository repo = getRepository()) {

            // Open an index input on a file
            String blank = Strings.repeat(" ", 5 * BufferedIndexInput.BUFFER_SIZE);
            String content = "This is the file " + blank + "content";

            pushBlob("/content", content);
            IndexInput input = repo.openInput(new URI("blob:///"), "content", IOContext.DEFAULT);

            // Read twice the size of the internal buffer, so first bytes are not in the buffer anymore
            byte[] buffer = new byte[BufferedIndexInput.BUFFER_SIZE * 2];
            input.readBytes(buffer, 0, BufferedIndexInput.BUFFER_SIZE * 2);

            // Seek back to the 5th byte.
            // It is not any more in the internal buffer, so we should fail
            IOException exception = assertThrows(IOException.class, () -> input.seek(5));
            assertEquals("Cannot seek backward", exception.getMessage());
        }
    }

    /**
     * Initialize a blog repository based on local or S3 blob storage.
     */
    @Override
    protected BlobBackupRepository getRepository() {

        NamedList<Object> args = getBaseBackupRepositoryConfiguration();

        if (type == BlobstoreProviderType.LOCAL) {
            File directory = temporaryFolder.getRoot();
            args.add(BlobBackupRepositoryConfig.LOCAL_STORAGE_ROOT, directory.getAbsolutePath());
        }

        BlobBackupRepository repo = new BlobBackupRepository();
        repo.init(args);

        return repo;
    }

    @Override
    protected URI getBaseUri() throws URISyntaxException {
        return new URI("blob:/");
    }

    @Override
    protected NamedList<Object> getBaseBackupRepositoryConfiguration() {
        NamedList<Object> args = new NamedList<>();
        args.add(BlobBackupRepositoryConfig.PROVIDER_TYPE, type.name());
        args.add(BlobBackupRepositoryConfig.BUCKET_NAME, BUCKET_NAME);
        return args;
    }

    private void pushBlob(String path, String content) throws IOException {

        switch (type) {
            case LOCAL:
                FileUtils.write(new File(temporaryFolder.getRoot(), path), content, StandardCharsets.UTF_8);
                break;

            case S3MOCK:
                AmazonS3 s3 = S3_MOCK_RULE.createS3Client();
                try {
                    s3.putObject(BUCKET_NAME, path, content);
                } finally {
                    s3.shutdown();
                }
                break;

            default:
                throw new AssertionError();
        }
    }

    private File pullBlob(String path) throws IOException {

        switch (type) {
            case LOCAL:
                return new File(temporaryFolder.getRoot(), path);

            case S3MOCK:
                AmazonS3 s3 = S3_MOCK_RULE.createS3Client();
                try {
                    File file = temporaryFolder.newFile();
                    InputStream input = s3.getObject(BUCKET_NAME, path).getObjectContent();
                    FileUtils.copyInputStreamToFile(input, file);
                    return file;
                } finally {
                    s3.shutdown();
                }

            default:
                throw new AssertionError();
        }

    }
}
