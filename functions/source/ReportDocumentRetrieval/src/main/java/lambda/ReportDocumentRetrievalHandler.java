package lambda;

import com.amazon.SellingPartnerAPIAA.AWSAuthenticationCredentials;
import com.amazon.SellingPartnerAPIAA.AWSAuthenticationCredentialsProvider;
import com.amazon.SellingPartnerAPIAA.LWAAuthorizationCredentials;
import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CommitmentPolicy;
import com.amazonaws.encryptionsdk.CryptoResult;
import com.amazonaws.encryptionsdk.kms.KmsMasterKey;
import com.amazonaws.encryptionsdk.kms.KmsMasterKeyProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.swagger.client.api.ReportsApi;
import io.swagger.client.model.ReportDocument;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import utils.AppCredentials;
import utils.IAMUserCredentials;
import utils.RegionConfig;
import utils.ReportDocumentResponse;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static utils.Constants.LWA_ENDPOINT;
import static utils.Constants.VALID_SP_API_REGION_CONFIG;

public class ReportDocumentRetrievalHandler implements RequestHandler<Map<String, String>, ReportDocumentResponse> {

    //Lambda Environment Variables
    private static final String IAM_USER_CREDENTIALS_SECRET_ARN_ENV_VARIABLE = "IAM_USER_CREDENTIALS_SECRET_ARN";
    private static final String SP_API_APP_CREDENTIALS_SECRET_ARN_ENV_VARIABLE = "SP_API_APP_CREDENTIALS_SECRET_ARN";
    private static final String ROLE_ARN_ENV_VARIABLE = "ROLE_ARN";
    private static final String ENCRYPTION_KEY_ARN_ENV_VARIABLE = "ENCRYPTION_KEY_ARN";
    private static final String SELLING_PARTNERS_TABLE_NAME_ENV_VARIABLE = "SELLING_PARTNERS_TABLE_NAME";
    private static final String REPORTS_TABLE_NAME_ENV_VARIABLE = "REPORTS_TABLE_NAME";

    //Lambda Input Parameters
    private static final String REPORT_ID_KEY_NAME = "ReportId";
    private static final String SELLER_ID_KEY_NAME = "SellerId";
    private static final String REPORT_DOCUMENT_ID_KEY_NAME = "ReportDocumentId";

    private static final String ROLE_SESSION_NAME = "report-document-retrieval-lambda-role-session";
    private static final String SELLING_PARTNERS_TABLE_HASH_KEY_NAME = "SellerId";
    private static final String SELLING_PARTNERS_TABLE_TOKEN_NAME = "RefreshToken";
    private static final String REPORTS_TABLE_HASH_KEY_NAME = "ReportId";
    private static final String REPORTS_TABLE_RANGE_KEY_NAME = "SellerId";
    private static final String REPORTS_TABLE_REGION_CODE_NAME = "RegionCode";

    @Override
    public ReportDocumentResponse handleRequest(Map<String, String> event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("ReportCreator Lambda handler started");

        validateInput(event);

        String reportId = event.get(REPORT_ID_KEY_NAME);
        String sellerId = event.get(SELLER_ID_KEY_NAME);
        String reportDocumentId = event.get(REPORT_DOCUMENT_ID_KEY_NAME);

        String regionCode = getReportRegionCode(reportId, sellerId);

        try {
            ReportDocument reportDocument = getReportDocument(regionCode, sellerId, reportDocumentId);
            logger.log("Report document retrieved");

            return ReportDocumentResponse.builder()
                    .url(reportDocument.getUrl())
                    .compressionAlgorithm(reportDocument.getCompressionAlgorithm() != null ?
                            reportDocument.getCompressionAlgorithm().getValue() : "")
                    .build();
        } catch (Exception e) {
            throw new InternalError("Report document retrieval failed", e);
        }
    }

    private ReportDocument getReportDocument(String regionCode, String sellerId, String reportDocumentId) throws Exception {
        ReportsApi reportsApi = getReportsApi(regionCode, sellerId);
        return reportsApi.getReportDocument(reportDocumentId);
    }

    private ReportsApi getReportsApi(String regionCode, String sellerId) throws Exception {
        RegionConfig regionConfig = getRegionConfig(regionCode);

        ObjectMapper mapper = new ObjectMapper();

        String iamUserCredentialsSecret = getSecretString(System.getenv(IAM_USER_CREDENTIALS_SECRET_ARN_ENV_VARIABLE));
        IAMUserCredentials iamUserCredentials = mapper.readValue(iamUserCredentialsSecret, IAMUserCredentials.class);

        AWSAuthenticationCredentials awsAuthenticationCredentials=AWSAuthenticationCredentials.builder()
                .accessKeyId(iamUserCredentials.getAccessKeyId())
                .secretKey(iamUserCredentials.getSecretKey())
                .region(regionConfig.getAwsRegion())
                .build();

        AWSAuthenticationCredentialsProvider awsAuthenticationCredentialsProvider=AWSAuthenticationCredentialsProvider.builder()
                .roleArn(System.getenv(ROLE_ARN_ENV_VARIABLE))
                .roleSessionName(ROLE_SESSION_NAME)
                .build();

        String appCredentialsSecret = getSecretString(System.getenv(SP_API_APP_CREDENTIALS_SECRET_ARN_ENV_VARIABLE));
        AppCredentials appCredentials = mapper.readValue(appCredentialsSecret, AppCredentials.class);

        String refreshToken = getRefreshToken(sellerId);

        LWAAuthorizationCredentials lwaAuthorizationCredentials = LWAAuthorizationCredentials.builder()
                .clientId(appCredentials.getClientId())
                .clientSecret(appCredentials.getClientSecret())
                .endpoint(LWA_ENDPOINT)
                .refreshToken(refreshToken)
                .build();

        return new ReportsApi.Builder()
                .awsAuthenticationCredentials(awsAuthenticationCredentials)
                .lwaAuthorizationCredentials(lwaAuthorizationCredentials)
                .awsAuthenticationCredentialsProvider(awsAuthenticationCredentialsProvider)
                .endpoint(regionConfig.getSpApiEndpoint())
                .build();
    }

    private String getRefreshToken(String sellerId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put(SELLING_PARTNERS_TABLE_HASH_KEY_NAME, new AttributeValue(sellerId));

        GetItemRequest getItemRequest = new GetItemRequest()
                .withTableName(System.getenv(SELLING_PARTNERS_TABLE_NAME_ENV_VARIABLE))
                .withKey(key);

        AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
        GetItemResult getItemResult = dynamoDB.getItem(getItemRequest);
        Map<String, AttributeValue> item = getItemResult.getItem();
        ByteBuffer encryptedRefreshToken = item.get(SELLING_PARTNERS_TABLE_TOKEN_NAME).getB();
        byte[] encryptedRefreshTokenBytes = encryptedRefreshToken.array();

        AwsCrypto crypto = AwsCrypto.builder()
                .withCommitmentPolicy(CommitmentPolicy.RequireEncryptRequireDecrypt)
                .build();

        KmsMasterKeyProvider keyProvider = KmsMasterKeyProvider.builder().buildStrict(System.getenv(ENCRYPTION_KEY_ARN_ENV_VARIABLE));

        CryptoResult<byte[], KmsMasterKey> decryptedData = crypto.decryptData(keyProvider, encryptedRefreshTokenBytes);
        return new String(decryptedData.getResult());
    }

    private String getReportRegionCode(String reportId, String sellerId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put(REPORTS_TABLE_HASH_KEY_NAME, new AttributeValue(reportId));
        key.put(REPORTS_TABLE_RANGE_KEY_NAME, new AttributeValue(sellerId));

        GetItemRequest getItemRequest = new GetItemRequest()
                .withTableName(System.getenv(REPORTS_TABLE_NAME_ENV_VARIABLE))
                .withKey(key);

        AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
        GetItemResult getItemResult = dynamoDB.getItem(getItemRequest);
        Map<String, AttributeValue> item = getItemResult.getItem();
        String regionCode = item.get(REPORTS_TABLE_REGION_CODE_NAME).getS();

        return regionCode;
    }

    private void validateInput(Map<String, String> event) {
        List<String> requiredParameters = Lists.newArrayList(
                REPORT_ID_KEY_NAME,
                SELLER_ID_KEY_NAME,
                REPORT_DOCUMENT_ID_KEY_NAME);

        if (!event.keySet().containsAll(requiredParameters)) {
            String msg = String.format("The provided input must contain all the following keys: %s",
                    requiredParameters);

            throw new IllegalArgumentException(msg);
        }
    }

    private String getSecretString(String secretId) {
        SecretsManagerClient client = SecretsManagerClient.builder().build();
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretId)
                .build();

        GetSecretValueResponse response = client.getSecretValue(request);
        return response.secretString();
    }

    private RegionConfig getRegionConfig(String regionCode) {
        if (!VALID_SP_API_REGION_CONFIG.containsKey(regionCode)) {
            String msg = String.format("Region Code %s is not valid. Value must be one of %s",
                    regionCode,
                    VALID_SP_API_REGION_CONFIG.keySet());

            throw new IllegalArgumentException(msg);
        }

        return VALID_SP_API_REGION_CONFIG.get(regionCode);
    }
}
