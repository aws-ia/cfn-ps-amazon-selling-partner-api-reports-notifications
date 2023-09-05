package lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.util.StringUtils;
import com.google.common.collect.Lists;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static utils.Constants.COMPRESSION_METADATA_MAP;

public class ReportDocumentStorageHandler implements RequestHandler<Map<String, String>, String> {

    //Lambda Environment Variables
    private static final String DESTINATION_S3_BUCKET_NAME_ENV_VARIABLE = "DESTINATION_S3_BUCKET_NAME";

    //Lambda Input Parameters
    private static final String OBJECT_PRESIGNED_URL_KEY_NAME = "PresignedUrl";
    private static final String COMPRESSION_ALGORITHM_KEY_NAME = "CompressionAlgorithm";
    private static final String REPORT_TYPE_KEY_NAME = "ReportType";

    @Override
    public String handleRequest(Map<String, String> event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("ReportDocumentStorage Lambda handler started");

        validateInput(event);

        String objectPresignedUrl = event.get(OBJECT_PRESIGNED_URL_KEY_NAME);
        String compressionAlgorithm = event.get(COMPRESSION_ALGORITHM_KEY_NAME);
        String reportType = event.get(REPORT_TYPE_KEY_NAME);
        String destinationS3Bucket = System.getenv(DESTINATION_S3_BUCKET_NAME_ENV_VARIABLE);

        String fileKey = String.format("%s/%s", reportType, UUID.randomUUID());
        logger.log(String.format("File Key: %s", fileKey));

        try {
            InputStream inputStream = URI.create(objectPresignedUrl).toURL().openConnection().getInputStream();
            ObjectMetadata metadata = new ObjectMetadata();

            if (!StringUtils.isNullOrEmpty(compressionAlgorithm)) {
                if (!COMPRESSION_METADATA_MAP.containsKey(compressionAlgorithm)) {
                    throw new InternalError(String.format("Report document storage failed. Unsupported compression algorithm: %s",
                            compressionAlgorithm));
                }

                String contentType = COMPRESSION_METADATA_MAP.get(compressionAlgorithm);
                metadata.setContentType(contentType);
            }

            AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
            s3.putObject(destinationS3Bucket, fileKey, inputStream, metadata);
        } catch (Exception e) {
            throw new InternalError("Report document storage failed", e);
        }

        return fileKey;
    }

    private void validateInput(Map<String, String> event) {
        List<String> requiredParameters = Lists.newArrayList(
                OBJECT_PRESIGNED_URL_KEY_NAME,
                REPORT_TYPE_KEY_NAME);

        if (!event.keySet().containsAll(requiredParameters)) {
            String msg = String.format("The provided input must contain all the following keys: %s",
                    requiredParameters);

            throw new IllegalArgumentException(msg);
        }
    }
}
