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
package org.apache.solr.s3;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest.KeyVersion;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.apache.commons.io.input.ClosedInputStream;
import org.apache.solr.common.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Creates a {@link AmazonS3} for communicating with AWS S3. Utilizes the default credential provider chain;
 * reference <a href="https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html">AWS SDK docs</a> for
 * details on where this client will fetch credentials from, and the order of precedence.
 */
class S3StorageClient {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static final String BLOB_FILE_PATH_DELIMITER = "/";

    // S3 has a hard limit of 1000 keys per batch delete request
    private static final int MAX_KEYS_PER_BATCH_DELETE = 1000;

    // Metadata name used to identify flag directory entries in S3
    private static final String BLOB_DIR_HEADER = "x_is_directory";

    // Error messages returned by S3 for a key not found.
    private static final Set<String> NOT_FOUND_CODES = Set.of("NoSuchKey", "404 Not Found");

    private final AmazonS3 s3Client;

    /**
     * The S3 bucket where we write all of our blobs to.
     */
    private final String bucketName;

    S3StorageClient(String bucketName, String region, String proxyHost, int proxyPort, String endpoint) {
        this(createInternalClient(region, proxyHost, proxyPort, endpoint), bucketName);
    }

    @VisibleForTesting
    S3StorageClient(AmazonS3 s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    private static AmazonS3 createInternalClient(String region, String proxyHost, int proxyPort, String endpoint) {
        ClientConfiguration clientConfig = new ClientConfiguration()
            .withProtocol(Protocol.HTTPS);

        // If configured, add proxy
        if (!StringUtils.isEmpty(proxyHost)) {
            clientConfig.setProxyHost(proxyHost);
            if (proxyPort > 0) {
                clientConfig.setProxyPort(proxyPort);
            }
        }

        /*
         * Default s3 client builder loads credentials from disk and handles token refreshes
         */
        AmazonS3ClientBuilder clientBuilder = AmazonS3ClientBuilder.standard()
            .enablePathStyleAccess()
            .withClientConfiguration(clientConfig);

        if (!StringUtils.isEmpty(endpoint)) {
            clientBuilder.setEndpointConfiguration(
                new AwsClientBuilder.EndpointConfiguration(endpoint, region)
            );
        } else {
            clientBuilder.setRegion(region);
        }

        return clientBuilder.build();
    }

    /**
     * Create Directory in S3 Blob Store.
     *
     * @param path Directory Path in Blob Store.
     */
    void createDirectory(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        if (!parentDirectoryExist(path)) {
            createDirectory(path.substring(0, path.lastIndexOf(BLOB_FILE_PATH_DELIMITER)));
            //TODO see https://issues.apache.org/jira/browse/SOLR-15359
//            throw new BlobException("Parent directory doesn't exist, path=" + path);
        }

        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.addUserMetadata(BLOB_DIR_HEADER, "true");
        objectMetadata.setContentLength(0);

        // Create empty blob object with header
        final InputStream im = ClosedInputStream.CLOSED_INPUT_STREAM;

        try {
            PutObjectRequest putRequest = new PutObjectRequest(bucketName, path, im, objectMetadata);
            s3Client.putObject(putRequest);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     /**
     * Delete files from S3 Blob Store. Deletion order is not guaranteed.
     *
     * @param paths Paths to files or blobs.
     */
    void delete(Collection<String> paths) throws S3Exception {
        Set<String> entries = new HashSet<>();
        for (String path : paths) {
            entries.add(sanitizedPath(path, true));
        }

        deleteBlobs(entries);
    }

    /**
     * Delete directory, all the files and sub-directories from S3.
     *
     * @param path Path to directory in S3.
     */
    void deleteDirectory(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        List<String> entries = new ArrayList<>();
        entries.add(path);

        // Get all the files and subdirectories
        entries.addAll(listAll(path));

        deleteObjects(entries);
    }

    /**
     * List all the files and sub-directories directly under given path.
     *
     * @param path Path to directory in S3.
     * @return Files and sub-directories in path.
     */
    String[] listDir(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        String prefix = path.equals("/") ? path : path + BLOB_FILE_PATH_DELIMITER;
        ListObjectsRequest listRequest = new ListObjectsRequest()
            .withBucketName(bucketName)
            .withPrefix(prefix)
            .withDelimiter(BLOB_FILE_PATH_DELIMITER);

        List<String> entries = new ArrayList<>();
        try {
            ObjectListing objectListing = s3Client.listObjects(listRequest);

            while (true) {
                List<String> files = objectListing.getObjectSummaries().stream()
                        .map(S3ObjectSummary::getKey)
                        // This filtering is needed only for S3mock. Real S3 does not ignore the trailing '/' in the prefix.
                        .filter(s -> s.startsWith(prefix))
                        .map(s -> s.substring(prefix.length()))
                        .collect(Collectors.toList());

                entries.addAll(files);

                if (objectListing.isTruncated()) {
                    objectListing = s3Client.listNextBatchOfObjects(objectListing);
                } else {
                    break;
                }
            }
            return entries.toArray(new String[0]);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Check if path exists.
     *
     * @param path to File/Directory in S3.
     * @return true if path exists, otherwise false?
     */
    boolean pathExists(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        // for root return true
        if (path.isEmpty() || "/".equals(path)) {
            return true;
        }

        try {
            return s3Client.doesObjectExist(bucketName, path);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Check if path is directory.
     *
     * @param path to File/Directory in S3.
     * @return true if path is directory, otherwise false.
     */
    boolean isDirectory(String path) throws S3Exception {
        path = sanitizedPath(path, false);

        try {
            ObjectMetadata objectMetadata = s3Client.getObjectMetadata(bucketName, path);
            String blobDirHeaderVal = objectMetadata.getUserMetaDataOf(BLOB_DIR_HEADER);

            return !StringUtils.isEmpty(blobDirHeaderVal) && blobDirHeaderVal.equalsIgnoreCase("true");
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Get length of file in bytes.
     *
     * @param path to file in S3.
     * @return length of file.
     */
    long length(String path) throws S3Exception {
        path = sanitizedPath(path, true);
        try {
            ObjectMetadata objectMetadata = s3Client.getObjectMetadata(bucketName, path);
            String blobDirHeaderVal = objectMetadata.getUserMetaDataOf(BLOB_DIR_HEADER);

            if (StringUtils.isEmpty(blobDirHeaderVal) || !blobDirHeaderVal.equalsIgnoreCase("true")) {
                return objectMetadata.getContentLength();
            }
            throw new S3Exception("Path is Directory");
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Open a new {@link InputStream} to file for read. Caller needs to close the stream.
     *
     * @param path to file in S3.
     * @return InputStream for file.
     */
    InputStream pullStream(String path) throws S3Exception {
        path = sanitizedPath(path, true);

        try {
            S3Object requestedObject = s3Client.getObject(bucketName, path);
            // This InputStream instance needs to be closed by the caller
            return requestedObject.getObjectContent();
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Open a new {@link OutputStream} to file for write. Caller needs to close the stream.
     *
     * @param path to file in S3.
     * @return OutputStream for file.
     */
    OutputStream pushStream(String path) throws S3Exception {
        path = sanitizedPath(path, true);

        if (!parentDirectoryExist(path)) {
            throw new S3Exception("Parent directory doesn't exist of path: " + path);
        }

        try {
            return new S3OutputStream(s3Client, path, bucketName);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Override {@link Closeable} since we throw no exception.
     */
    void close() {
        s3Client.shutdown();
    }

    /**
     * Batch delete blob files from the blob store.
     *
     * @param entries collection of blob file keys to the files to be deleted.
     **/
    private void deleteBlobs(Collection<String> entries) throws S3Exception {
        Collection<String> deletedPaths = deleteObjects(entries);

        // If we haven't deleted all requested objects, assume that's because some were missing
        if (entries.size() != deletedPaths.size()) {
            Set<String> notDeletedPaths = new HashSet<>(entries);
            entries.removeAll(deletedPaths);
            throw new S3NotFoundException(notDeletedPaths.toString());
        }
    }

    /**
     *  Any blob file path that specifies a non-existent blob file will not be treated as an error.
     */
    private Collection<String> deleteObjects(Collection<String> paths) throws S3Exception {
        try {
            /*
             * Per the S3 docs:
             * https://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/s3/model/DeleteObjectsResult.html
             * An exception is thrown if there's a client error processing the request or in
             * the Blob store itself. However there's no guarantee the delete did not happen
             * if an exception is thrown.
             */
            return deleteObjects(paths, MAX_KEYS_PER_BATCH_DELETE);
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    @VisibleForTesting
    Collection<String> deleteObjects(Collection<String> entries, int batchSize) {
        List<KeyVersion> keysToDelete = entries.stream()
            .map(KeyVersion::new)
            .collect(Collectors.toList());

        List<List<KeyVersion>> partitions = Lists.partition(keysToDelete, batchSize);
        Set<String> deletedPaths = new HashSet<>();

        for (List<KeyVersion> partition : partitions) {
            DeleteObjectsRequest request = createBatchDeleteRequest(partition);

            DeleteObjectsResult result = s3Client.deleteObjects(request);

            result.getDeletedObjects().stream()
                    .map(DeleteObjectsResult.DeletedObject::getKey)
                    .forEach(deletedPaths::add);
        }

        return deletedPaths;
    }

    private DeleteObjectsRequest createBatchDeleteRequest(List<KeyVersion> keysToDelete) {
        return new DeleteObjectsRequest(bucketName).withKeys(keysToDelete);
    }

    private List<String> listAll(String path) throws S3Exception {
        String prefix = path + BLOB_FILE_PATH_DELIMITER;
        ListObjectsRequest listRequest = new ListObjectsRequest()
            .withBucketName(bucketName)
            .withPrefix(prefix);

        List<String> entries = new ArrayList<>();
        try {
            ObjectListing objectListing = s3Client.listObjects(listRequest);

            while (true) {
                List<String> files = objectListing.getObjectSummaries().stream()
                        .map(S3ObjectSummary::getKey)
                        // This filtering is needed only for S3mock. Real S3 does not ignore the trailing '/' in the prefix.
                        .filter(s -> s.startsWith(prefix))
                        .collect(Collectors.toList());

                entries.addAll(files);

                if (objectListing.isTruncated()) {
                    objectListing = s3Client.listNextBatchOfObjects(objectListing);
                } else {
                    break;
                }
            }
            return entries;
        } catch (AmazonClientException ase) {
            throw handleAmazonException(ase);
        }
    }

    /**
     * Assumes the path does not end in a trailing slash
     */
    private boolean parentDirectoryExist(String path) throws S3Exception {
        if (!path.contains(BLOB_FILE_PATH_DELIMITER)) {
            // Should only happen in S3Mock cases; otherwise we validate that all paths start with '/'
            return true;
        }

        String parentDirectory = path.substring(0, path.lastIndexOf(BLOB_FILE_PATH_DELIMITER));

        // If we have no specific parent directory, we consider parent is root (and always exists)
        if (parentDirectory.isEmpty()) {
            return true;
        }

        return pathExists(parentDirectory);
    }

    /**
     * Ensures path adheres to some rules:
     * -Starts with a leading slash
     * -If it's a file, throw an error if it ends with a trailing slash
     * -Else, silently trim the trailing slash
     */
    String sanitizedPath(String path, boolean isFile) throws S3Exception {
        // Trim space from start and end
        String sanitizedPath = path.trim();

        // Path should start with file delimiter
        if (!sanitizedPath.startsWith(BLOB_FILE_PATH_DELIMITER)) {
            throw new S3Exception("Invalid Path. Path needs to start with '/'");
        }

        if (isFile && sanitizedPath.endsWith(BLOB_FILE_PATH_DELIMITER)) {
            throw new S3Exception("Invalid Path. Path for file can't end with '/'");
        }

        // Trim file delimiter from end
        if (sanitizedPath.length() > 1 && sanitizedPath.endsWith(BLOB_FILE_PATH_DELIMITER)) {
            sanitizedPath = sanitizedPath.substring(0, path.length() - 1);
        }

        return sanitizedPath;
    }

    /**
     * Best effort to handle Amazon exceptions as checked exceptions. Amazon exception are all subclasses
     * of {@link RuntimeException} so some may still be uncaught and propagated.
     */
    static S3Exception handleAmazonException(AmazonClientException ace) {

        if (ace instanceof AmazonServiceException) {
            AmazonServiceException ase = (AmazonServiceException)ace;
            String errMessage = String.format(Locale.ROOT, "An AmazonServiceException was thrown! [serviceName=%s] "
                            + "[awsRequestId=%s] [httpStatus=%s] [s3ErrorCode=%s] [s3ErrorType=%s] [message=%s]",
                    ase.getServiceName(), ase.getRequestId(), ase.getStatusCode(),
                    ase.getErrorCode(), ase.getErrorType(), ase.getErrorMessage());

            log.error(errMessage);

            if (ase.getStatusCode() == 404 && NOT_FOUND_CODES.contains(ase.getErrorCode())) {
                return new S3NotFoundException(errMessage, ase);
            } else {
                return new S3Exception(errMessage, ase);
            }
        }

        return new S3Exception(ace);
    }
}
