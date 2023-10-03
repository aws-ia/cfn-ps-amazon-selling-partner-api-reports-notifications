package lambda;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.google.common.collect.Lists;

import java.net.URL;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class ReportPresignedUrlGeneratorHandler implements RequestHandler<Map<String, String>, String> {

    //Lambda Environment Variables
    private static final String S3_BUCKET_NAME_ENV_VARIABLE = "S3_BUCKET_NAME";

    //Lambda Input Parameters
    private static final String OBJECT_KEY_KEY_NAME = "ObjectKey";

    @Override
    public String handleRequest(Map<String, String> event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("ReportPresignedUrlGenerator Lambda handler started");

        validateInput(event);

        String s3BucketName = System.getenv(S3_BUCKET_NAME_ENV_VARIABLE);
        String objectKey = event.get(OBJECT_KEY_KEY_NAME);

        // Set the presigned URL to expire after one hour
        Instant expirationTime = Instant.now().plusMillis(1000 * 60 * 60);
        Date expirationDate = new Date(expirationTime.toEpochMilli());

        try {
            GeneratePresignedUrlRequest generatePresignedUrlRequest =
                    new GeneratePresignedUrlRequest(s3BucketName, objectKey)
                            .withMethod(HttpMethod.GET)
                            .withExpiration(expirationDate);

            AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
            URL url = s3.generatePresignedUrl(generatePresignedUrlRequest);

            logger.log("Presigned Url successfully generated");
            return url.toString();
        } catch (Exception e) {
            throw new InternalError("Report document presigned url generation failed", e);
        }
    }

    private void validateInput(Map<String, String> event) {
        List<String> requiredParameters = Lists.newArrayList(
                OBJECT_KEY_KEY_NAME);

        if (!event.keySet().containsAll(requiredParameters)) {
            String msg = String.format("The provided input must contain all the following keys: %s",
                    requiredParameters);

            throw new IllegalArgumentException(msg);
        }
    }
}
